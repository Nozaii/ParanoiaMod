import { json } from "./social.js";

/**
 * Community texture packs. Metadata and counters live in D1, the zip in KV (limit 25 MiB per value).
 * Every route needs a session, so each like, view and download is tied to one verified account.
 *
 * GET  /v2/packs?sort=popular|new     list
 * POST /v2/packs                      publish: zip as body, x-pack-name header
 * GET  /v2/packs/:id/file             download
 * POST /v2/packs/:id/like             toggle
 * POST /v2/packs/views                { ids: [...] }, counted once per account
 * POST /v2/packs/:id/report           hidden after REPORT_LIMIT accounts report it
 * POST /v2/packs/:id/delete           by its author
 */

const MAX_BYTES = 20 * 1024 * 1024;
const UPLOADS_PER_DAY = 3;
const REPORT_LIMIT = 3;
const DAY = 86400000;

const fail = (code, message, status = 400) => json({ error: code, message }, status);

function cleanName(raw) {
  const name = decodeURIComponent(String(raw || ""))
    .replace(/§./g, "")
    .replace(/\.zip$/i, "")
    .replace(/[\u0000-\u001f]/g, "")
    .trim();
  return name.length >= 3 && name.length <= 40 ? name : null;
}

/** A resource pack: zip magic, and pack.mcmeta in the central directory at the end of the file. */
function looksLikePack(bytes) {
  if (bytes.length < 22 || bytes[0] !== 0x50 || bytes[1] !== 0x4b || bytes[2] !== 0x03 || bytes[3] !== 0x04) return false;
  const tail = bytes.subarray(Math.max(0, bytes.length - 262144));
  return new TextDecoder("latin1").decode(tail).includes("pack.mcmeta");
}

async function list(req, env, user) {
  const sort = new URL(req.url).searchParams.get("sort") === "new" ? "created DESC" : "likes DESC, downloads DESC, created DESC";
  const { results } = await env.DB.prepare(
    `SELECT p.id, p.name, p.author, p.owner, p.size, p.created, p.likes, p.views, p.downloads,
            EXISTS(SELECT 1 FROM pack_likes l WHERE l.pack = p.id AND l.user = ?1) AS liked
     FROM packs p WHERE p.hidden = 0 ORDER BY ${sort} LIMIT 60`,
  ).bind(user.uuid).all();
  return json({
    packs: results.map((p) => ({ ...p, liked: !!p.liked, mine: p.owner === user.uuid, owner: undefined })),
  });
}

async function publish(req, env, user) {
  const name = cleanName(req.headers.get("x-pack-name"));
  if (!name) return fail("bad_name", "The name must be 3 to 40 characters");
  if (Number(req.headers.get("content-length") || 0) > MAX_BYTES) return fail("too_big", "Packs are limited to 20 MB", 413);
  const today = await env.DB.prepare("SELECT COUNT(*) AS n FROM packs WHERE owner = ?1 AND created > ?2").bind(user.uuid, Date.now() - DAY).first();
  if (today.n >= UPLOADS_PER_DAY) return fail("limit", "You can publish 3 packs per day", 429);
  const file = await req.arrayBuffer();
  if (file.byteLength > MAX_BYTES) return fail("too_big", "Packs are limited to 20 MB", 413);
  if (!looksLikePack(new Uint8Array(file))) return fail("bad_pack", "This file is not a resource pack");
  const id = crypto.randomUUID().replace(/-/g, "").slice(0, 16);
  await env.PROFILES.put(`pack:${id}`, file);
  await env.DB.prepare("INSERT INTO packs (id, owner, author, name, size, created) VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
    .bind(id, user.uuid, user.name, name, file.byteLength, Date.now()).run();
  return json({ id });
}

async function download(env, user, id) {
  const pack = await env.DB.prepare("SELECT name FROM packs WHERE id = ?1 AND hidden = 0").bind(id).first();
  if (!pack) return fail("not_found", "This pack is gone", 404);
  const file = await env.PROFILES.get(`pack:${id}`, "arrayBuffer");
  if (!file) return fail("not_found", "This pack is gone", 404);
  const first = await env.DB.prepare("INSERT OR IGNORE INTO pack_downloads (pack, user) VALUES (?1, ?2)").bind(id, user.uuid).run();
  if (first.meta.changes) await env.DB.prepare("UPDATE packs SET downloads = downloads + 1 WHERE id = ?1").bind(id).run();
  return new Response(file, { headers: { "content-type": "application/zip", "cache-control": "no-store" } });
}

async function like(env, user, id) {
  const added = await env.DB.prepare("INSERT OR IGNORE INTO pack_likes (pack, user) VALUES (?1, ?2)").bind(id, user.uuid).run();
  if (added.meta.changes) {
    await env.DB.prepare("UPDATE packs SET likes = likes + 1 WHERE id = ?1").bind(id).run();
    return json({ liked: true });
  }
  await env.DB.batch([
    env.DB.prepare("DELETE FROM pack_likes WHERE pack = ?1 AND user = ?2").bind(id, user.uuid),
    env.DB.prepare("UPDATE packs SET likes = MAX(0, likes - 1) WHERE id = ?1").bind(id),
  ]);
  return json({ liked: false });
}

async function views(req, env, user) {
  let ids = [];
  try {
    ids = (await req.json()).ids;
  } catch {
    return fail("bad_request", "Invalid body");
  }
  if (!Array.isArray(ids)) return fail("bad_request", "Invalid body");
  for (const id of ids.slice(0, 30).map(String)) {
    const seen = await env.DB.prepare("INSERT OR IGNORE INTO pack_views (pack, user) VALUES (?1, ?2)").bind(id, user.uuid).run();
    if (seen.meta.changes) await env.DB.prepare("UPDATE packs SET views = views + 1 WHERE id = ?1").bind(id).run();
  }
  return json({ ok: true });
}

async function report(env, user, id) {
  const first = await env.DB.prepare("INSERT OR IGNORE INTO pack_reports (pack, user) VALUES (?1, ?2)").bind(id, user.uuid).run();
  if (first.meta.changes) {
    await env.DB.prepare("UPDATE packs SET reports = reports + 1, hidden = CASE WHEN reports + 1 >= ?2 THEN 1 ELSE hidden END WHERE id = ?1")
      .bind(id, REPORT_LIMIT).run();
  }
  return json({ ok: true });
}

async function remove(env, user, id) {
  const done = await env.DB.prepare("DELETE FROM packs WHERE id = ?1 AND owner = ?2").bind(id, user.uuid).run();
  if (!done.meta.changes) return fail("forbidden", "Only its author can delete a pack", 403);
  await env.PROFILES.delete(`pack:${id}`);
  await env.DB.batch(["pack_likes", "pack_views", "pack_downloads", "pack_reports"].map((t) => env.DB.prepare(`DELETE FROM ${t} WHERE pack = ?1`).bind(id)));
  return json({ ok: true });
}

export async function packs(req, env, user, pathname) {
  if (pathname === "/v2/packs") {
    if (req.method === "GET") return list(req, env, user);
    if (req.method === "POST") return publish(req, env, user);
  }
  if (req.method === "POST" && pathname === "/v2/packs/views") return views(req, env, user);
  const m = pathname.match(/^\/v2\/packs\/([0-9a-f]{16})\/(file|like|report|delete)$/);
  if (!m) return null;
  if (m[2] === "file" && req.method === "GET") return download(env, user, m[1]);
  if (req.method !== "POST") return null;
  if (m[2] === "like") return like(env, user, m[1]);
  if (m[2] === "report") return report(env, user, m[1]);
  if (m[2] === "delete") return remove(env, user, m[1]);
  return null;
}
