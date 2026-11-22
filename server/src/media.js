import { json } from "./social.js";

/**
 * Player images, stored in KV.
 *
 * Imported capes (seen by other players):
 *   POST /v2/cape                 PNG as body (frames stacked top to bottom, each 2:1), headers x-cape-frames, x-cape-delays
 *   POST /v2/cape/delete          removes it
 *   GET  /v2/cape/:uuid           the PNG, with x-cape-frames / x-cape-delays / x-cape-hash
 *   POST /v2/cape/face            the cape front (10:16) as a GIF, every frame at its own speed; sent after the PNG
 *   GET  /v2/cape/:uuid/face      that GIF, as sent: the player who sees the cape builds it (full frame rate)
 *   POST /v2/cape/:uuid/report    hidden after REPORT_LIMIT different accounts report it
 *
 * Pictures in private messages (kept 7 days, readable only by the sender and the friend it was sent to):
 *   POST /v2/media                PNG as body, header x-to: friend uuid  ->  { id }
 *   GET  /v2/media/:id            the PNG
 */

const CAPE_MAX = 512 * 1024;
const CAPE_FRAMES = 24;
const CAPE_UPLOADS_PER_DAY = 40; // PNG + face GIF per change
const CAPE_FACE_MAX = 1024 * 1024;
const REPORT_LIMIT = 3;
const MEDIA_MAX = 1024 * 1024;
const MEDIA_TTL = 7 * 86400;
const MEDIA_PER_HOUR = 30;
const UUID = /^[0-9a-f]{32}$/;
const ID = /^[0-9a-f]{16}$/;

const fail = (code, message, status = 400) => json({ error: code, message }, status);

/** PNG signature, and the size read from the IHDR chunk. */
function pngSize(b) {
  const sig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (b.length < 33 || sig.some((v, i) => b[i] !== v)) return null;
  if (b[12] !== 0x49 || b[13] !== 0x48 || b[14] !== 0x44 || b[15] !== 0x52) return null;
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  return { w: dv.getUint32(16), h: dv.getUint32(20) };
}

/** Taille d'un GIF (en-tête logique), ou null. */
function gifSize(b) {
  if (b.length < 13 || b[0] !== 0x47 || b[1] !== 0x49 || b[2] !== 0x46 || b[3] !== 0x38) return null;
  return { w: b[6] | (b[7] << 8), h: b[8] | (b[9] << 8) };
}

async function sha(bytes) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return [...d].slice(0, 8).map((x) => x.toString(16).padStart(2, "0")).join("");
}

/** At most `limit` actions per window for this account (KV counter, good enough against spam). */
async function allow(env, key, limit, ttl) {
  const n = parseInt((await env.PROFILES.get(key)) || "0", 10) || 0;
  if (n >= limit) return false;
  await env.PROFILES.put(key, String(n + 1), { expirationTtl: ttl });
  return true;
}

async function capeUpload(req, env, user) {
  if (Number(req.headers.get("content-length") || 0) > CAPE_MAX) return fail("too_big", "Capes are limited to 512 KB", 413);
  const frames = parseInt(req.headers.get("x-cape-frames") || "1", 10);
  if (!(frames >= 1 && frames <= CAPE_FRAMES)) return fail("bad_cape", "At most 24 frames");
  const delays = String(req.headers.get("x-cape-delays") || "")
    .split(",").map((d) => parseInt(d, 10)).filter((d) => d >= 20 && d <= 10000);
  if (frames > 1 && delays.length !== frames) return fail("bad_cape", "One delay per frame");
  const day = Math.floor(Date.now() / 86400000);
  if (!(await allow(env, `capeup:${user.uuid}:${day}`, CAPE_UPLOADS_PER_DAY, 90000))) return fail("limit", "Too many cape uploads today", 429);
  const bytes = new Uint8Array(await req.arrayBuffer());
  if (bytes.length > CAPE_MAX) return fail("too_big", "Capes are limited to 512 KB", 413);
  const size = pngSize(bytes);
  // the cape layout: each frame is 2:1, 64 to 512 wide, a multiple of 64
  if (!size || size.w < 64 || size.w > 512 || size.w % 64 !== 0 || size.h !== (size.w / 2) * frames) return fail("bad_cape", "This is not a cape image");
  const hash = await sha(bytes);
  await env.PROFILES.put(`cape:${user.uuid}`, bytes, { metadata: { frames, delays: frames > 1 ? delays : [], hash, at: Date.now() } });
  // a new cape: the face of the previous one must not stay behind it
  await env.PROFILES.delete(`capeface:${user.uuid}`);
  await env.PROFILES.delete(`capereports:${user.uuid}`);
  return json({ ok: true, hash });
}

/** Front of the cape as a GIF (portrait, about 10:16), up to 1 MB. Requires the PNG cape to exist. */
async function capeFaceUpload(req, env, user) {
  if (Number(req.headers.get("content-length") || 0) > CAPE_FACE_MAX) return fail("too_big", "Cape GIFs are limited to 1 MB", 413);
  const day = Math.floor(Date.now() / 86400000);
  if (!(await allow(env, `capeup:${user.uuid}:${day}`, CAPE_UPLOADS_PER_DAY, 90000))) return fail("limit", "Too many cape uploads today", 429);
  const bytes = new Uint8Array(await req.arrayBuffer());
  if (bytes.length > CAPE_FACE_MAX) return fail("too_big", "Cape GIFs are limited to 1 MB", 413);
  const size = gifSize(bytes);
  if (!size || size.w < 10 || size.w > 320 || Math.abs(size.h - size.w * 1.6) > Math.max(2, size.w * 0.05)) return fail("bad_cape", "This is not a cape front");
  const cape = await env.PROFILES.getWithMetadata(`cape:${user.uuid}`, "arrayBuffer");
  if (!cape.metadata) return fail("no_cape", "Send the cape first", 409);
  const hash = await sha(bytes);
  await env.PROFILES.put(`capeface:${user.uuid}`, bytes, { metadata: { hash, at: Date.now() } });
  return json({ ok: true, hash });
}

async function capeFaceGet(env, uuid) {
  if (!UUID.test(uuid)) return fail("bad_request", "Invalid player");
  const reports = await env.PROFILES.get(`capereports:${uuid}`, "json");
  if (Array.isArray(reports) && reports.length >= REPORT_LIMIT) return fail("hidden", "This cape was reported", 404);
  const { value, metadata } = await env.PROFILES.getWithMetadata(`capeface:${uuid}`, "arrayBuffer");
  if (!value || !metadata) return fail("not_found", "No animated cape", 404);
  return new Response(value, { headers: { "content-type": "image/gif", "cache-control": "no-store", "x-cape-hash": metadata.hash || "" } });
}

async function capeGet(env, uuid) {
  if (!UUID.test(uuid)) return fail("bad_request", "Invalid player");
  const reports = await env.PROFILES.get(`capereports:${uuid}`, "json");
  if (Array.isArray(reports) && reports.length >= REPORT_LIMIT) return fail("hidden", "This cape was reported", 404);
  const { value, metadata } = await env.PROFILES.getWithMetadata(`cape:${uuid}`, "arrayBuffer");
  if (!value || !metadata) return fail("not_found", "No cape", 404);
  return new Response(value, {
    headers: {
      "content-type": "image/png",
      "cache-control": "no-store",
      "x-cape-frames": String(metadata.frames || 1),
      "x-cape-delays": (metadata.delays || []).join(","),
      "x-cape-hash": metadata.hash || "",
    },
  });
}

async function capeReport(env, user, uuid) {
  if (!UUID.test(uuid) || uuid === user.uuid) return fail("bad_request", "Invalid player");
  const key = `capereports:${uuid}`;
  const list = (await env.PROFILES.get(key, "json")) || [];
  if (!list.includes(user.uuid)) {
    list.push(user.uuid);
    await env.PROFILES.put(key, JSON.stringify(list.slice(0, 20)));
  }
  return json({ ok: true });
}

async function mediaUpload(req, env, user) {
  const to = String(req.headers.get("x-to") || "").toLowerCase().replace(/-/g, "");
  if (!UUID.test(to)) return fail("bad_request", "Invalid friend");
  const friend = await env.DB.prepare("SELECT 1 FROM friendships WHERE a = ?1 AND b = ?2").bind(user.uuid, to).first();
  if (!friend) return fail("not_friend", "You are not friends", 403);
  if (Number(req.headers.get("content-length") || 0) > MEDIA_MAX) return fail("too_big", "Pictures are limited to 1 MB", 413);
  const hour = Math.floor(Date.now() / 3600000);
  if (!(await allow(env, `mediaup:${user.uuid}:${hour}`, MEDIA_PER_HOUR, 3700))) return fail("limit", "Too many pictures, wait a little", 429);
  const bytes = new Uint8Array(await req.arrayBuffer());
  if (bytes.length > MEDIA_MAX) return fail("too_big", "Pictures are limited to 1 MB", 413);
  const png = pngSize(bytes);
  const size = png || gifSize(bytes);
  if (!size || size.w < 1 || size.h < 1 || size.w > 1024 || size.h > 1024) return fail("bad_image", "This is not a picture");
  const type = png ? "png" : "gif";
  const b = new Uint8Array(8);
  crypto.getRandomValues(b);
  const id = [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
  await env.PROFILES.put(`img:${id}`, bytes, { expirationTtl: MEDIA_TTL, metadata: { from: user.uuid, to, type } });
  return json({ id });
}

async function mediaGet(env, user, id) {
  if (!ID.test(id)) return fail("bad_request", "Invalid picture");
  const { value, metadata } = await env.PROFILES.getWithMetadata(`img:${id}`, "arrayBuffer");
  if (!value || !metadata) return fail("not_found", "This picture expired", 404);
  // only the two people of the conversation
  if (metadata.from !== user.uuid && metadata.to !== user.uuid) return fail("forbidden", "Not your conversation", 403);
  const ct = metadata.type === "gif" ? "image/gif" : "image/png";
  return new Response(value, { headers: { "content-type": ct, "cache-control": "no-store" } });
}

/**
 * Recherche de GIF (Klipy) pour le chat : la clé reste sur le serveur (secret KLIPY_API_KEY).
 * GET /v2/gif/search?q=&page=  -> { results: [{ id, preview, url, w, h }], next }
 * Sans q : tendances.
 */
async function gifSearch(req, env, user) {
  if (!env.KLIPY_API_KEY) return fail("not_configured", "GIF search is not set up on the server yet", 503);
  const minute = Math.floor(Date.now() / 60000);
  if (!(await allow(env, `gif:${user.uuid}:${minute}`, 40, 120))) return fail("limit", "Too many searches, wait a little", 429);
  const u = new URL(req.url);
  const q = String(u.searchParams.get("q") || "").trim().slice(0, 60);
  const page = Math.max(1, Math.min(20, Number(u.searchParams.get("page") || 1) || 1));
  const params = new URLSearchParams({ page: String(page), per_page: "24", customer_id: user.uuid, content_filter: "medium" });
  if (q) params.set("q", q);
  const endpoint = q ? "search" : "trending";
  const r = await fetch(`https://api.klipy.com/api/v1/${encodeURIComponent(env.KLIPY_API_KEY)}/gifs/${endpoint}?${params}`);
  if (!r.ok) return fail("upstream", "GIF search is unavailable right now", 502);
  const body = await r.json().catch(() => null);
  const list = body && body.data && Array.isArray(body.data.data) ? body.data.data : body && Array.isArray(body.data) ? body.data : [];
  const pick = (f, sizes) => {
    for (const s of sizes) {
      const v = f && f[s] && f[s].gif;
      if (v && typeof v.url === "string" && v.url.startsWith("https://")) return v;
    }
    return null;
  };
  const results = [];
  for (const it of list) {
    if (!it || it.type === "ad") continue;
    const preview = pick(it.file, ["xs", "sm", "md"]);
    // envoyé : la taille « sm » tient presque toujours sous 1 Mo (le mod ré-encode sinon)
    const full = pick(it.file, ["sm", "md", "hd"]);
    if (!preview || !full) continue;
    results.push({ id: String(it.id || it.slug || ""), preview: preview.url, url: full.url, w: Number(full.width) || 0, h: Number(full.height) || 0 });
  }
  const next = body && body.data && body.data.has_next ? page + 1 : null;
  return json({ results, next });
}

/** Routes of this module, or null. */
export async function media(request, env, user, pathname) {
  const m = request.method;
  if (m === "POST" && pathname === "/v2/cape") return capeUpload(request, env, user);
  if (m === "POST" && pathname === "/v2/cape/face") return capeFaceUpload(request, env, user);
  if (m === "POST" && pathname === "/v2/cape/delete") {
    await env.PROFILES.delete(`cape:${user.uuid}`);
    await env.PROFILES.delete(`capeface:${user.uuid}`);
    return json({ ok: true });
  }
  if (m === "POST" && pathname === "/v2/media") return mediaUpload(request, env, user);
  if (m === "GET" && pathname === "/v2/gif/search") return gifSearch(request, env, user);
  let p = pathname.match(/^\/v2\/cape\/([0-9a-f]{32})(\/report|\/face)?$/);
  if (p) {
    if (m === "GET" && !p[2]) return capeGet(env, p[1]);
    if (m === "GET" && p[2] === "/face") return capeFaceGet(env, p[1]);
    if (m === "POST" && p[2] === "/report") return capeReport(env, user, p[1]);
  }
  p = pathname.match(/^\/v2\/media\/([0-9a-f]{16})$/);
  if (p && m === "GET") return mediaGet(env, user, p[1]);
  return null;
}
