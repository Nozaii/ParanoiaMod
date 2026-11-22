/**
 * État « en direct » des joueurs du mod : en train d'écrire un message privé, emote en cours.
 * Et liaison du compte à Discord (OAuth2, scope identify).
 *
 * POST /v2/live        { typing: bool, emote: "wave" | "", ping?: {...} }  (session)  -> met à jour son état
 *                      ping: { id, kind, x, y, z, dim, eid?, item? } — kind "none" efface le ping
 * POST /v2/live/who    { uuids: [...] }                       -> états récents des joueurs demandés
 * POST /v2/discord/start                                    (session)  -> { url } à ouvrir dans le navigateur
 * GET  /v2/discord/callback?code&state                                 -> page « compte lié »
 * POST /v2/discord/unlink                                   (session)
 *
 * Les tables sont créées au premier appel (pas de migration manuelle).
 * Discord : définir les secrets DISCORD_CLIENT_ID et DISCORD_CLIENT_SECRET (npx wrangler secret put …).
 */

import { json } from "./social.js";

const UUID = /^[0-9a-f]{32}$/;
const EMOTES = ["wave", "dance", "point", "clap", "salute", "facepalm"];
const PING_KINDS = ["go", "danger", "enemy", "loot", "defend", "help", "item", "none"];
const PING_MS = 20000;
let ready = false;

export async function ensureTables(env) {
  if (ready) return;
  await env.DB.batch([
    env.DB.prepare("CREATE TABLE IF NOT EXISTS live (uuid TEXT PRIMARY KEY, typing INTEGER NOT NULL DEFAULT 0, emote TEXT, emote_at INTEGER NOT NULL DEFAULT 0)"),
    env.DB.prepare("CREATE TABLE IF NOT EXISTS discord_states (state TEXT PRIMARY KEY, uuid TEXT NOT NULL, expires INTEGER NOT NULL)"),
    env.DB.prepare(
      "CREATE TABLE IF NOT EXISTS discord_links (uuid TEXT PRIMARY KEY, discord_id TEXT NOT NULL, username TEXT NOT NULL, global_name TEXT, avatar TEXT, linked INTEGER NOT NULL)"
    ),
  ]);
  // colonnes ajoutées après coup : ALTER échoue si elles existent déjà
  for (const col of ["ping TEXT", "ping_at INTEGER NOT NULL DEFAULT 0"]) {
    try {
      await env.DB.prepare(`ALTER TABLE live ADD COLUMN ${col}`).run();
    } catch {}
  }
  ready = true;
}

async function body(request) {
  try {
    return await request.json();
  } catch {
    return {};
  }
}

function cleanPing(p) {
  if (!p || typeof p !== "object") return null;
  const id = String(p.id || "").slice(0, 16);
  const kind = PING_KINDS.includes(p.kind) ? p.kind : null;
  if (!id || !kind) return null;
  if (kind === "none") return { id, kind };
  const num = (v) => (Number.isFinite(v) && Math.abs(v) < 3e7 ? Math.round(v * 10) / 10 : null);
  const x = num(p.x), y = num(p.y), z = num(p.z);
  if (x === null || y === null || z === null) return null;
  const out = { id, kind, x, y, z, dim: String(p.dim || "minecraft:overworld").slice(0, 64) };
  if (Number.isInteger(p.eid)) out.eid = p.eid;
  if (typeof p.item === "string" && /^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(p.item)) out.item = p.item.slice(0, 80);
  return out;
}

export async function live(req, env, user) {
  await ensureTables(env);
  const b = await body(req);
  const now = Date.now();
  const ping = cleanPing(b.ping);
  if (ping) {
    await env.DB.prepare(
      `INSERT INTO live (uuid, typing, ping, ping_at) VALUES (?1, 0, ?2, ?3)
       ON CONFLICT(uuid) DO UPDATE SET ping = ?2, ping_at = ?3`
    ).bind(user.uuid, JSON.stringify(ping), now).run();
    return json({ ok: true, now });
  }
  const typing = b.typing === true ? now : 0;
  const emote = EMOTES.includes(b.emote) ? b.emote : null;
  if (emote) {
    await env.DB.prepare(
      `INSERT INTO live (uuid, typing, emote, emote_at) VALUES (?1, ?2, ?3, ?4)
       ON CONFLICT(uuid) DO UPDATE SET typing = ?2, emote = ?3, emote_at = ?4`
    ).bind(user.uuid, typing, emote, now).run();
  } else {
    await env.DB.prepare(
      `INSERT INTO live (uuid, typing) VALUES (?1, ?2) ON CONFLICT(uuid) DO UPDATE SET typing = ?2`
    ).bind(user.uuid, typing).run();
  }
  return json({ ok: true, now });
}

export async function liveWho(req, env) {
  await ensureTables(env);
  const b = await body(req);
  const list = Array.isArray(b.uuids) ? b.uuids : [];
  const wanted = [...new Set(list.map((u) => String(u).toLowerCase().replace(/-/g, "")))].filter((u) => UUID.test(u)).slice(0, 60);
  if (!wanted.length) return json({ now: Date.now(), live: {} });
  const now = Date.now();
  const marks = wanted.map((_, i) => `?${i + 1}`).join(",");
  const rows = await env.DB.prepare(`SELECT uuid, typing, emote, emote_at, ping, ping_at FROM live WHERE uuid IN (${marks})`).bind(...wanted).all();
  const out = {};
  for (const r of rows.results) {
    const typing = now - r.typing < 4000;
    const emote = r.emote && now - r.emote_at < 8000 ? { id: r.emote, at: r.emote_at } : null;
    let ping = null;
    if (r.ping && now - r.ping_at < PING_MS) {
      try {
        ping = { ...JSON.parse(r.ping), at: r.ping_at };
      } catch {}
    }
    if (typing || emote || ping) out[r.uuid] = ping ? { typing, emote, ping } : { typing, emote };
  }
  return json({ now, live: out });
}

// ------------------------------------------------------------------ Discord

function hex(n) {
  const b = new Uint8Array(n);
  crypto.getRandomValues(b);
  return [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

function redirectUri(req) {
  const u = new URL(req.url);
  return `${u.origin}/v2/discord/callback`;
}

export async function discordStart(req, env, user) {
  if (!env.DISCORD_CLIENT_ID || !env.DISCORD_CLIENT_SECRET) return json({ error: "not_configured", message: "Liaison Discord pas encore activée sur le serveur" }, 503);
  await ensureTables(env);
  const state = hex(24);
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM discord_states WHERE expires < ?1").bind(now),
    env.DB.prepare("INSERT INTO discord_states (state, uuid, expires) VALUES (?1, ?2, ?3)").bind(state, user.uuid, now + 10 * 60000),
  ]);
  const params = new URLSearchParams({
    client_id: env.DISCORD_CLIENT_ID,
    response_type: "code",
    redirect_uri: redirectUri(req),
    scope: "identify",
    state,
    prompt: "consent",
  });
  return json({ url: `https://discord.com/oauth2/authorize?${params}` });
}

function page(title, text, ok) {
  const color = ok ? "#9d7cff" : "#ff5a6a";
  const html = `<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title><style>body{margin:0;min-height:100vh;display:grid;place-items:center;background:#0d0b16;color:#f3f1fb;font:16px system-ui,sans-serif}
main{max-width:420px;padding:32px;border-radius:16px;background:#17142a;border:1px solid #2c2645;text-align:center}
h1{margin:0 0 8px;font-size:22px;color:${color}}p{margin:0;color:#b8b2d6;line-height:1.5}</style></head>
<body><main><h1>${title}</h1><p>${text}</p></main></body></html>`;
  return new Response(html, { status: ok ? 200 : 400, headers: { "content-type": "text/html; charset=utf-8" } });
}

export async function discordCallback(req, env) {
  if (!env.DISCORD_CLIENT_ID || !env.DISCORD_CLIENT_SECRET) return page("Indisponible", "La liaison Discord n'est pas activée.", false);
  await ensureTables(env);
  const url = new URL(req.url);
  const code = url.searchParams.get("code") || "";
  const state = url.searchParams.get("state") || "";
  if (!/^[0-9a-f]{48}$/.test(state) || !code) return page("Lien invalide", "Relance la liaison depuis le jeu.", false);
  const row = await env.DB.prepare("SELECT uuid FROM discord_states WHERE state = ?1 AND expires > ?2").bind(state, Date.now()).first();
  await env.DB.prepare("DELETE FROM discord_states WHERE state = ?1").bind(state).run();
  if (!row) return page("Lien expiré", "Relance la liaison depuis le jeu (le lien est valable 10 minutes).", false);
  const tokenRes = await fetch("https://discord.com/api/oauth2/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: env.DISCORD_CLIENT_ID,
      client_secret: env.DISCORD_CLIENT_SECRET,
      grant_type: "authorization_code",
      code,
      redirect_uri: redirectUri(req),
    }),
  });
  if (!tokenRes.ok) return page("Échec", "Discord a refusé la liaison. Réessaie depuis le jeu.", false);
  const tok = await tokenRes.json();
  const meRes = await fetch("https://discord.com/api/users/@me", { headers: { authorization: `Bearer ${tok.access_token}` } });
  if (!meRes.ok) return page("Échec", "Impossible de lire ton profil Discord.", false);
  const d = await meRes.json();
  await env.DB.prepare(
    `INSERT INTO discord_links (uuid, discord_id, username, global_name, avatar, linked) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
     ON CONFLICT(uuid) DO UPDATE SET discord_id = ?2, username = ?3, global_name = ?4, avatar = ?5, linked = ?6`
  ).bind(row.uuid, String(d.id), String(d.username || ""), d.global_name ? String(d.global_name) : null, d.avatar ? String(d.avatar) : null, Date.now()).run();
  // bannière et couleur d'accent du profil Discord (scope identify), pour la page du joueur
  await bannerColumn(env);
  await env.DB.prepare("UPDATE discord_links SET banner = ?2, accent = ?3, decoration = ?4, flags = ?5 WHERE uuid = ?1")
    .bind(row.uuid, d.banner ? String(d.banner) : null, d.accent_color == null ? null : Number(d.accent_color),
      d.avatar_decoration_data && d.avatar_decoration_data.asset ? String(d.avatar_decoration_data.asset) : null,
      Number(d.public_flags || 0) | (d.premium_type ? 1 << 30 : 0)).run();
  const shown = d.global_name || d.username;
  return page("Compte Discord lié ✓", `Bonjour ${escapeHtml(shown)} ! Tu peux fermer cette page et revenir dans Minecraft.`, true);
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

export async function discordUnlink(req, env, user) {
  await ensureTables(env);
  await env.DB.prepare("DELETE FROM discord_links WHERE uuid = ?1").bind(user.uuid).run();
  return json({ ok: true });
}

/** Noms des badges publics Discord (public_flags), plus Nitro quand l'utilisateur en a (premium_type, bit 30 ajouté par nous). */
function badges(flags) {
  const table = [
    [1 << 0, "Staff"], [1 << 1, "Partner"], [1 << 2, "HypeSquad Events"], [1 << 3, "Bug Hunter"], [1 << 6, "HypeSquad Bravery"],
    [1 << 7, "HypeSquad Brilliance"], [1 << 8, "HypeSquad Balance"], [1 << 9, "Early Supporter"], [1 << 14, "Bug Hunter Gold"],
    [1 << 17, "Verified Bot Developer"], [1 << 18, "Moderator Alumni"], [1 << 22, "Active Developer"], [1 << 30, "Nitro"],
  ];
  return table.filter(([bit]) => flags & bit).map(([, name]) => name);
}

let bannerReady = false;
/** Colonnes ajoutées après coup (bannière, couleur d'accent) : ALTER TABLE échoue si elles existent déjà. */
async function bannerColumn(env) {
  if (bannerReady) return;
  bannerReady = true;
  for (const col of ["banner TEXT", "accent INTEGER", "decoration TEXT", "flags INTEGER"]) {
    try {
      await env.DB.prepare(`ALTER TABLE discord_links ADD COLUMN ${col}`).run();
    } catch {
      // déjà présente
    }
  }
}

/** Infos Discord publiques (nom affiché, avatar, bannière) de plusieurs joueurs. */
export async function discordInfo(env, uuids) {
  if (!uuids.length) return {};
  await ensureTables(env);
  await bannerColumn(env);
  const marks = uuids.map((_, i) => `?${i + 1}`).join(",");
  const rows = await env.DB.prepare(`SELECT uuid, discord_id, username, global_name, avatar, banner, accent, decoration, flags FROM discord_links WHERE uuid IN (${marks})`)
    .bind(...uuids).all();
  const out = {};
  for (const r of rows.results) {
    const animated = r.banner && String(r.banner).startsWith("a_");
    out[r.uuid] = {
      name: r.global_name || r.username,
      username: r.username,
      avatar: r.avatar ? `https://cdn.discordapp.com/avatars/${r.discord_id}/${r.avatar}.png?size=128` : null,
      banner: r.banner ? `https://cdn.discordapp.com/banners/${r.discord_id}/${r.banner}.${animated ? "gif" : "png"}?size=1024` : null,
      accent: r.accent == null ? null : r.accent,
      decoration: r.decoration ? `https://cdn.discordapp.com/avatar-decoration-presets/${r.decoration}.png?size=160&passthrough=false` : null,
      badges: badges(r.flags || 0),
    };
  }
  return out;
}
