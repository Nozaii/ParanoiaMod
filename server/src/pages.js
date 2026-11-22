import { json } from "./social.js";
import { discordInfo } from "./live.js";

/**
 * Pages de joueur (profil public du mod) : bannière image / GIF, bio, pose du skin, visuel + légende,
 * onglet Discord optionnel, compteur de vues et de likes, historique des skins (enregistré par le mod).
 *
 * GET  /v2/page?user=<uuid|pseudo>        (session) -> la page ; compte une vue par compte
 * POST /v2/page   { bio, pose, caption, show_discord }
 * PUT  /v2/page/banner | /v2/page/visual   corps image/png ou image/gif (512 Ko max)
 * DELETE /v2/page/banner | /v2/page/visual
 * GET  /v2/page/banner/<uuid> | /v2/page/visual/<uuid>  -> l'image
 * POST /v2/page/like  { uuid }             -> bascule le like, { liked, likes }
 * POST /v2/page/skin  { url, model }       -> enregistre le skin Mojang actuel du joueur
 * POST /v2/page/stats { minutes, kills, deaths, streak } -> ajoute le temps de jeu et le combat (plafonnés par appel)
 * PUT  /v2/page/background                 fond de page (image ou GIF, 512 Ko)
 *
 * Apparence : fond (couleur, dégradé ou image), cadre d'avatar, titre (un badge gagné), statut, liens,
 * vitrine (cosmétiques, stats, badges, skins) et confidentialité (public ou amis seulement).
 * Les badges sont calculés ici à partir de données vérifiables, jamais envoyés par le client.
 */

const fail = (code, message, status = 400) => json({ error: code, message }, status);
const UUID = /^[0-9a-f]{32}$/;
const NAME = /^[A-Za-z0-9_]{1,16}$/;
const POSES = new Set(["stand", "walk", "sneak", "swing", "fly"]);
const IMAGE_MAX = 512 * 1024;
const SKIN_URL = /^https?:\/\/textures\.minecraft\.net\/texture\/[0-9a-f]{1,80}$/;

let ready = false;
async function ensure(env) {
  if (ready) return;
  ready = true;
  await env.DB.batch([
    env.DB.prepare(
      `CREATE TABLE IF NOT EXISTS pages (uuid TEXT PRIMARY KEY, bio TEXT NOT NULL DEFAULT '', pose TEXT NOT NULL DEFAULT 'stand',
       caption TEXT NOT NULL DEFAULT '', show_discord INTEGER NOT NULL DEFAULT 0, banner TEXT, visual TEXT,
       views INTEGER NOT NULL DEFAULT 0, likes INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL DEFAULT 0)`
    ),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS page_likes (page TEXT NOT NULL, user TEXT NOT NULL, PRIMARY KEY (page, user))"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS page_views (page TEXT NOT NULL, user TEXT NOT NULL, PRIMARY KEY (page, user))"),
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS skins (uuid TEXT NOT NULL, url TEXT NOT NULL, model TEXT NOT NULL, first INTEGER NOT NULL, seen INTEGER NOT NULL, PRIMARY KEY (uuid, url))"
    ),
  ]);
  // colonnes ajoutées après coup : ALTER échoue si elles existent déjà
  for (const col of [
    "bg_type TEXT", "bg1 INTEGER", "bg2 INTEGER", "background TEXT", "frame TEXT", "title TEXT", "status TEXT", "links TEXT",
    "private INTEGER NOT NULL DEFAULT 0", "showcase INTEGER NOT NULL DEFAULT 15",
    "accent INTEGER", "card_style INTEGER NOT NULL DEFAULT 0", "card_alpha INTEGER NOT NULL DEFAULT 55", "radius INTEGER NOT NULL DEFAULT 1",
    "playtime INTEGER NOT NULL DEFAULT 0", "kills INTEGER NOT NULL DEFAULT 0", "deaths INTEGER NOT NULL DEFAULT 0", "best_streak INTEGER NOT NULL DEFAULT 0",
  ]) {
    try {
      await env.DB.prepare(`ALTER TABLE pages ADD COLUMN ${col}`).run();
    } catch {
      // déjà là
    }
  }
}

const BG_TYPES = new Set(["color", "gradient", "image"]);
const FRAMES = new Set(["none", "ring", "double", "gold", "neon", "frost", "flame", "royal"]);
const LINK_SITES = {
  youtube: /^@?[A-Za-z0-9_.-]{3,30}$/,
  twitch: /^[A-Za-z0-9_]{3,25}$/,
  tiktok: /^@?[A-Za-z0-9_.]{2,24}$/,
  x: /^@?[A-Za-z0-9_]{1,15}$/,
  instagram: /^@?[A-Za-z0-9_.]{1,30}$/,
  github: /^[A-Za-z0-9-]{1,39}$/,
};
const DAY = 86400000;
const clean = (s, n) => String(s || "").replace(/[\u0000-\u001f\u007f§]/g, "").trim().slice(0, n);
const color = (v, def) => (Number.isInteger(v) && v >= 0 && v <= 0xffffff ? v : def);

/**
 * Badges gagnés, du plus rare au plus courant. Raretés : 0 commun, 1 rare, 2 épique, 3 légendaire.
 * Seulement des faits connus du serveur (ancienneté, amis, vues, likes, combat, packs publiés, Discord).
 */
async function badges(env, uuid, row, created) {
  const out = [];
  const age = created ? Date.now() - created : 0;
  const friends = (await env.DB.prepare("SELECT COUNT(*) AS n FROM friendships WHERE a = ?1").bind(uuid).first())?.n || 0;
  let packs = 0;
  try {
    packs = (await env.DB.prepare("SELECT COUNT(*) AS n FROM packs WHERE owner = ?1 AND hidden = 0").bind(uuid).first())?.n || 0;
  } catch {
    // table absente
  }
  let discord = false;
  try {
    discord = !!(await env.DB.prepare("SELECT 1 FROM discord_links WHERE uuid = ?1").bind(uuid).first());
  } catch {
    // table absente
  }
  const add = (id, name, rarity, desc) => out.push({ id, name, rarity, desc });
  if (created && created < Date.UTC(2026, 11, 1)) add("pioneer", "Pioneer", 3, "Joined Paranoia in its first months");
  if (age >= 365 * DAY) add("veteran", "Veteran", 2, "One year on Paranoia");
  else if (age >= 90 * DAY) add("regular", "Regular", 1, "Three months on Paranoia");
  if ((row.kills || 0) >= 1000) add("slayer", "Slayer", 3, "1,000 player kills");
  else if ((row.kills || 0) >= 100) add("fighter", "Fighter", 2, "100 player kills");
  else if ((row.kills || 0) >= 10) add("first_blood", "First blood", 0, "10 player kills");
  if ((row.best_streak || 0) >= 10) add("unstoppable", "Unstoppable", 2, "10 kills without dying");
  if ((row.playtime || 0) >= 500 * 60) add("no_life", "Dedicated", 2, "500 hours played with the mod");
  else if ((row.playtime || 0) >= 24 * 60) add("marathon", "Marathon", 1, "24 hours played with the mod");
  if ((row.likes || 0) >= 100) add("star", "Star", 3, "100 likes on this page");
  else if ((row.likes || 0) >= 10) add("liked", "Liked", 1, "10 likes on this page");
  if ((row.views || 0) >= 500) add("famous", "Famous", 2, "500 page views");
  else if ((row.views || 0) >= 50) add("noticed", "Noticed", 0, "50 page views");
  if (friends >= 25) add("social", "Social", 2, "25 friends");
  else if (friends >= 5) add("friendly", "Friendly", 0, "5 friends");
  if (packs > 0) add("creator", "Creator", 1, "Published a community texture pack");
  if (discord) add("linked", "Linked", 0, "Discord account linked");
  out.sort((a, b) => b.rarity - a.rarity);
  return out;
}

async function body(req) {
  try {
    return await req.json();
  } catch {
    return {};
  }
}

async function resolve(env, key) {
  const k = String(key || "").toLowerCase().replace(/-/g, "");
  if (UUID.test(k)) return await env.DB.prepare("SELECT uuid, name FROM users WHERE uuid = ?1").bind(k).first();
  if (NAME.test(k)) return await env.DB.prepare("SELECT uuid, name FROM users WHERE name_lower = ?1").bind(k).first();
  return null;
}

async function get(req, env, user) {
  const target = await resolve(env, new URL(req.url).searchParams.get("user") || user.uuid);
  if (!target) return fail("not_found", "This player doesn't use the mod", 404);
  const own = target.uuid === user.uuid;
  const priv = await env.DB.prepare("SELECT private FROM pages WHERE uuid = ?1").bind(target.uuid).first();
  if (!own && priv && priv.private) {
    const friend = await env.DB.prepare("SELECT 1 FROM friendships WHERE a = ?1 AND b = ?2").bind(target.uuid, user.uuid).first();
    if (!friend) return json({ uuid: target.uuid, name: target.name, private: true });
  }
  if (!own) {
    const seen = await env.DB.prepare("INSERT OR IGNORE INTO page_views (page, user) VALUES (?1, ?2)").bind(target.uuid, user.uuid).run();
    if (seen.meta.changes) {
      await env.DB.prepare("INSERT INTO pages (uuid, views, updated) VALUES (?1, 1, ?2) ON CONFLICT(uuid) DO UPDATE SET views = views + 1").bind(target.uuid, Date.now()).run();
    }
  }
  const row = (await env.DB.prepare("SELECT * FROM pages WHERE uuid = ?1").bind(target.uuid).first()) || {};
  const liked = own ? false : !!(await env.DB.prepare("SELECT 1 FROM page_likes WHERE page = ?1 AND user = ?2").bind(target.uuid, user.uuid).first());
  const skins = (await env.DB.prepare("SELECT url, model, first, seen FROM skins WHERE uuid = ?1 ORDER BY seen DESC LIMIT 16").bind(target.uuid).all()).results || [];
  let discord = null;
  if (own || row.show_discord) {
    const info = await discordInfo(env, [target.uuid]).catch(() => ({}));
    discord = info[target.uuid] || null;
  }
  const u = await env.DB.prepare("SELECT created, last_seen, server FROM users WHERE uuid = ?1").bind(target.uuid).first();
  const prof = await env.DB.prepare("SELECT data FROM profiles WHERE uuid = ?1").bind(target.uuid).first();
  let cosmetics = {};
  try {
    const d = prof ? JSON.parse(prof.data) : {};
    for (const k of ["cape", "wings", "aura", "halo", "pet", "back", "shield", "trail"]) if (d[k]) cosmetics[k] = d[k];
  } catch {
    cosmetics = {};
  }
  let links = {};
  try {
    links = row.links ? JSON.parse(row.links) : {};
  } catch {
    links = {};
  }
  const earned = await badges(env, target.uuid, row, u ? u.created : 0);
  return json({
    private: !!row.private,
    bg_type: row.bg_type || "gradient",
    bg1: row.bg1 == null ? null : row.bg1,
    bg2: row.bg2 == null ? null : row.bg2,
    background: row.background || null,
    frame: row.frame || "ring",
    title: row.title || null,
    status: row.status || "",
    links,
    showcase: row.showcase == null ? 15 : row.showcase,
    accent: row.accent == null ? null : row.accent,
    card_style: row.card_style == null ? 0 : row.card_style,
    card_alpha: row.card_alpha == null ? 55 : row.card_alpha,
    radius: row.radius == null ? 1 : row.radius,
    stats: { playtime: row.playtime || 0, kills: row.kills || 0, deaths: row.deaths || 0, best_streak: row.best_streak || 0 },
    badges: earned,
    created: u ? u.created : 0,
    online: !!(u && Date.now() - u.last_seen < 180000),
    last_seen: u ? u.last_seen : 0,
    cosmetics,
    uuid: target.uuid,
    name: target.name,
    bio: row.bio || "",
    pose: row.pose || "stand",
    caption: row.caption || "",
    show_discord: !!row.show_discord,
    banner: row.banner || null,
    visual: row.visual || null,
    views: row.views || 0,
    likes: row.likes || 0,
    liked,
    discord,
    skins,
    updated: row.updated || 0,
  });
}

async function save(req, env, user) {
  const b = await body(req);
  const bio = String(b.bio || "").replace(/[\u0000-\u001f\u007f§]/g, "").trim().slice(0, 160);
  const caption = String(b.caption || "").replace(/[\u0000-\u001f\u007f§]/g, "").trim().slice(0, 60);
  const pose = POSES.has(b.pose) ? b.pose : "stand";
  const show = b.show_discord ? 1 : 0;
  const bgType = BG_TYPES.has(b.bg_type) ? b.bg_type : "gradient";
  const bg1 = color(b.bg1, null), bg2 = color(b.bg2, null);
  const frame = FRAMES.has(b.frame) ? b.frame : "ring";
  const status = clean(b.status, 48);
  const priv = b.private ? 1 : 0;
  const showcase = Number.isInteger(b.showcase) ? b.showcase & 31 : 15;
  const accent = color(b.accent, null);
  const cardStyle = Number.isInteger(b.card_style) && b.card_style >= 0 && b.card_style <= 3 ? b.card_style : 0;
  const cardAlpha = Number.isInteger(b.card_alpha) ? Math.max(0, Math.min(100, b.card_alpha)) : 55;
  const radius = Number.isInteger(b.radius) && b.radius >= 0 && b.radius <= 2 ? b.radius : 1;
  const links = {};
  if (b.links && typeof b.links === "object") {
    for (const [site, re] of Object.entries(LINK_SITES)) {
      const v = clean(b.links[site], 40);
      if (v && re.test(v)) links[site] = v.replace(/^@/, "");
    }
  }
  // le titre doit être un badge réellement gagné
  let title = null;
  if (typeof b.title === "string" && b.title) {
    const row = (await env.DB.prepare("SELECT * FROM pages WHERE uuid = ?1").bind(user.uuid).first()) || {};
    const u = await env.DB.prepare("SELECT created FROM users WHERE uuid = ?1").bind(user.uuid).first();
    const earned = await badges(env, user.uuid, row, u ? u.created : 0);
    if (earned.some((x) => x.id === b.title)) title = b.title;
  }
  await env.DB.prepare(
    `INSERT INTO pages (uuid, bio, pose, caption, show_discord, updated, bg_type, bg1, bg2, frame, title, status, links, private, showcase,
       accent, card_style, card_alpha, radius)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19)
     ON CONFLICT(uuid) DO UPDATE SET bio = ?2, pose = ?3, caption = ?4, show_discord = ?5, updated = ?6, bg_type = ?7, bg1 = ?8,
       bg2 = ?9, frame = ?10, title = ?11, status = ?12, links = ?13, private = ?14, showcase = ?15,
       accent = ?16, card_style = ?17, card_alpha = ?18, radius = ?19`
  ).bind(user.uuid, bio, pose, caption, show, Date.now(), bgType, bg1, bg2, frame, title, status, JSON.stringify(links), priv, showcase,
    accent, cardStyle, cardAlpha, radius).run();
  return json({ ok: true });
}

/** Temps de jeu et combat envoyés par le mod : au plus un envoi par minute, valeurs plafonnées par envoi. */
async function stats(req, env, user) {
  const minute = Math.floor(Date.now() / 60000);
  const key = `pstats:${user.uuid}:${minute}`;
  if (await env.PROFILES.get(key)) return fail("limit", "Too soon", 429);
  await env.PROFILES.put(key, "1", { expirationTtl: 120 });
  const b = await body(req);
  const n = (v, max) => (Number.isInteger(v) && v > 0 ? Math.min(v, max) : 0);
  const minutes = n(b.minutes, 15), kills = n(b.kills, 60), deaths = n(b.deaths, 60), streak = n(b.streak, 200);
  await env.DB.prepare(
    `INSERT INTO pages (uuid, playtime, kills, deaths, best_streak, updated) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
     ON CONFLICT(uuid) DO UPDATE SET playtime = playtime + ?2, kills = kills + ?3, deaths = deaths + ?4,
       best_streak = MAX(best_streak, ?5)`
  ).bind(user.uuid, minutes, kills, deaths, streak, Date.now()).run();
  return json({ ok: true });
}

function imageType(req) {
  const t = (req.headers.get("content-type") || "").toLowerCase();
  return t.startsWith("image/gif") ? "gif" : t.startsWith("image/png") ? "png" : null;
}

async function upload(req, env, user, kind) {
  const type = imageType(req);
  if (!type) return fail("bad_image", "PNG or GIF only");
  if (Number(req.headers.get("content-length") || 0) > IMAGE_MAX) return fail("too_big", "Pictures are limited to 512 KB", 413);
  const bytes = new Uint8Array(await req.arrayBuffer());
  if (bytes.length > IMAGE_MAX || bytes.length < 16) return fail("too_big", "Pictures are limited to 512 KB", 413);
  const magicPng = bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
  const magicGif = bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46;
  if ((type === "png" && !magicPng) || (type === "gif" && !magicGif)) return fail("bad_image", "This is not a picture");
  const id = `${Date.now().toString(36)}.${type}`;
  await env.PROFILES.put(`page${kind}:${user.uuid}`, bytes, { metadata: { type } });
  await env.DB.prepare(
    `INSERT INTO pages (uuid, ${kind}, updated) VALUES (?1, ?2, ?3) ON CONFLICT(uuid) DO UPDATE SET ${kind} = ?2, updated = ?3`
  ).bind(user.uuid, id, Date.now()).run();
  return json({ ok: true, id });
}

async function removeImage(env, user, kind) {
  await env.PROFILES.delete(`page${kind}:${user.uuid}`);
  await env.DB.prepare(`UPDATE pages SET ${kind} = NULL, updated = ?2 WHERE uuid = ?1`).bind(user.uuid, Date.now()).run();
  return json({ ok: true });
}

async function image(env, kind, uuid) {
  if (!UUID.test(uuid)) return fail("bad_request", "Invalid player");
  const { value, metadata } = await env.PROFILES.getWithMetadata(`page${kind}:${uuid}`, "arrayBuffer");
  if (!value) return fail("not_found", "No picture", 404);
  const type = metadata && metadata.type === "gif" ? "image/gif" : "image/png";
  return new Response(value, { headers: { "content-type": type, "cache-control": "private, max-age=300" } });
}

async function like(req, env, user) {
  const b = await body(req);
  const id = String(b.uuid || "").toLowerCase().replace(/-/g, "");
  if (!UUID.test(id)) return fail("bad_request", "Invalid player");
  if (id === user.uuid) return fail("bad_request", "You can't like your own page");
  const added = await env.DB.prepare("INSERT OR IGNORE INTO page_likes (page, user) VALUES (?1, ?2)").bind(id, user.uuid).run();
  if (added.meta.changes) {
    await env.DB.prepare("INSERT INTO pages (uuid, likes, updated) VALUES (?1, 1, ?2) ON CONFLICT(uuid) DO UPDATE SET likes = likes + 1").bind(id, Date.now()).run();
  } else {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM page_likes WHERE page = ?1 AND user = ?2").bind(id, user.uuid),
      env.DB.prepare("UPDATE pages SET likes = MAX(0, likes - 1) WHERE uuid = ?1").bind(id),
    ]);
  }
  const row = await env.DB.prepare("SELECT likes FROM pages WHERE uuid = ?1").bind(id).first();
  return json({ liked: !!added.meta.changes, likes: row ? row.likes : 0 });
}

async function skin(req, env, user) {
  const b = await body(req);
  const url = String(b.url || "");
  const model = b.model === "slim" ? "slim" : "wide";
  if (!SKIN_URL.test(url)) return fail("bad_request", "Invalid skin");
  const now = Date.now();
  await env.DB.prepare(
    "INSERT INTO skins (uuid, url, model, first, seen) VALUES (?1, ?2, ?3, ?4, ?4) ON CONFLICT(uuid, url) DO UPDATE SET seen = ?4, model = ?3"
  ).bind(user.uuid, url, model, now).run();
  return json({ ok: true });
}

export async function pages(req, env, user, pathname) {
  await ensure(env);
  const m = req.method;
  if (m === "GET" && pathname === "/v2/page") return get(req, env, user);
  if (m === "POST" && pathname === "/v2/page") return save(req, env, user);
  if (m === "POST" && pathname === "/v2/page/like") return like(req, env, user);
  if (m === "POST" && pathname === "/v2/page/skin") return skin(req, env, user);
  if (m === "POST" && pathname === "/v2/page/stats") return stats(req, env, user);
  for (const kind of ["banner", "visual", "background"]) {
    if (pathname === `/v2/page/${kind}`) {
      if (m === "PUT" || m === "POST") return upload(req, env, user, kind);
      if (m === "DELETE") return removeImage(env, user, kind);
    }
    if (m === "GET" && pathname.startsWith(`/v2/page/${kind}/`)) return image(env, kind, pathname.slice(`/v2/page/${kind}/`.length));
  }
  return null;
}
