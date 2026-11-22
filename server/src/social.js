import { MOJANG_PROFILE_KEYS } from "./mojang-keys.js";
import { live, liveWho, discordStart, discordCallback, discordUnlink, discordInfo } from "./live.js";
import { packs } from "./packs.js";
import { media } from "./media.js";
import { pages } from "./pages.js";

/**
 * API sociale Paranoia (Cloudflare D1) : comptes vérifiés par Mojang, demandes d'ami,
 * présence en ligne, messages privés entre amis.
 *
 * Authentification (Mojang bloque les appels venant de Cloudflare, donc pas de "hasJoined") :
 *   1. GET  /v2/challenge            -> { nonce } à usage unique (2 min)
 *   2. POST /v2/auth/key             -> le mod envoie sa clé de profil Mojang (celle qui signe le chat),
 *      la signature de cette clé par Mojang, et sa propre signature du défi. On vérifie les deux
 *      signatures localement puis on rend un jeton de session (30 jours).
 * Les autres routes attendent l'en-tête  Authorization: Bearer <jeton>.
 */

const DAY = 86400000;
const HISTORY_DAYS = 30; // conservation des messages privés
const ONLINE_MS = 180000; // en ligne si vu il y a moins de 3 min
const NAME = /^[A-Za-z0-9_]{1,16}$/;
const UUID = /^[0-9a-f]{32}$/;

export function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

const fail = (code, message, status = 400) => json({ error: code, message }, status);

function token() {
  const b = new Uint8Array(32);
  crypto.getRandomValues(b);
  return [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

async function body(request) {
  try {
    return await request.json();
  } catch {
    return {};
  }
}

async function openSession(env, uuid, name) {
  const now = Date.now();
  const t = token();
  await env.DB.batch([
    // un pseudo Minecraft n'appartient qu'à un joueur à la fois : on le retire aux anciens comptes
    env.DB.prepare("UPDATE users SET name_lower = '' WHERE name_lower = ?1 AND uuid != ?2").bind(name.toLowerCase(), uuid),
    env.DB.prepare(
      `INSERT INTO users (uuid, name, name_lower, created, last_seen) VALUES (?1, ?2, ?3, ?4, ?4)
       ON CONFLICT(uuid) DO UPDATE SET name = ?2, name_lower = ?3, last_seen = ?4`
    ).bind(uuid, name, name.toLowerCase(), now),
    env.DB.prepare("DELETE FROM sessions WHERE uuid = ?1 AND expires < ?2").bind(uuid, now),
    env.DB.prepare("INSERT INTO sessions (token, uuid, expires) VALUES (?1, ?2, ?3)").bind(t, uuid, now + 30 * DAY),
  ]);
  return json({ token: t, uuid, name });
}

async function auth(request, env) {
  const b = await body(request);
  const name = String(b.name || "");
  const serverId = String(b.serverId || "");
  if (!NAME.test(name) || !/^[0-9a-f]{16,64}$/.test(serverId)) return fail("bad_request", "Paramètres invalides");
  const res = await fetch(
    `https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${serverId}`
  );
  if (res.status !== 200) return fail("auth", "Authentification Mojang refusée", 401);
  const p = await res.json();
  const uuid = String(p.id || "").toLowerCase();
  if (!UUID.test(uuid)) return fail("auth", "Profil invalide", 401);
  return openSession(env, uuid, String(p.name));
}

// ------------------------------------------------------------- clés de profil Mojang

function b64(s) {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

let mojangKeys = null;

async function mojangVerifiers(env) {
  if (!mojangKeys) {
    // MOJANG_TEST_KEY : clé de test, définie uniquement en local (.dev.vars), jamais en production
    const all = env.MOJANG_TEST_KEY ? [...MOJANG_PROFILE_KEYS, env.MOJANG_TEST_KEY] : MOJANG_PROFILE_KEYS;
    mojangKeys = await Promise.all(all.map((k) =>
      crypto.subtle.importKey("spki", b64(k), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-1" }, false, ["verify"])));
  }
  return mojangKeys;
}

/** Données signées par Mojang : UUID (16 octets) + expiration en ms (8 octets) + clé publique (DER). */
function signedPayload(uuid, expiresAt, keyDer) {
  const out = new Uint8Array(24 + keyDer.length);
  for (let i = 0; i < 16; i++) out[i] = parseInt(uuid.slice(i * 2, i * 2 + 2), 16);
  const view = new DataView(out.buffer);
  view.setBigInt64(16, BigInt(expiresAt));
  out.set(keyDer, 24);
  return out;
}

async function challenge(env) {
  const nonce = token();
  const now = Date.now();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM challenges WHERE expires < ?1").bind(now),
    env.DB.prepare("INSERT INTO challenges (nonce, expires) VALUES (?1, ?2)").bind(nonce, now + 120000),
  ]);
  return json({ nonce });
}

/** Pseudo officiel d'un UUID (Mojang étant injoignable depuis Cloudflare, on passe par playerdb.co). */
async function officialName(uuid) {
  try {
    const r = await fetch(`https://playerdb.co/api/player/minecraft/${uuid}`, { headers: { "user-agent": "ParanoiaMenu/1.0" } });
    if (r.status !== 200) return null;
    const d = await r.json();
    return d?.data?.player?.username || null;
  } catch {
    return null;
  }
}

async function keyAuth(request, env) {
  const b = await body(request);
  const uuid = String(b.uuid || "").toLowerCase().replace(/-/g, "");
  const nonce = String(b.nonce || "");
  const expiresAt = Number(b.expiresAt);
  if (!UUID.test(uuid) || !/^[0-9a-f]{64}$/.test(nonce) || !Number.isFinite(expiresAt)) return fail("bad_request", "Paramètres invalides");
  const now = Date.now();
  const ch = await env.DB.prepare("DELETE FROM challenges WHERE nonce = ?1 AND expires > ?2 RETURNING nonce").bind(nonce, now).first();
  if (!ch) return fail("auth", "Défi expiré, réessaie", 401);
  if (expiresAt < now) return fail("auth", "Clé de profil expirée : relance le jeu", 401);

  let keyDer, keySig, sig;
  try {
    keyDer = b64(String(b.publicKey));
    keySig = b64(String(b.keySignature));
    sig = b64(String(b.signature));
  } catch {
    return fail("bad_request", "Encodage invalide");
  }
  // 1) la clé du joueur a bien été signée par Mojang pour cet UUID
  const payload = signedPayload(uuid, expiresAt, keyDer);
  let mojangOk = false;
  for (const k of await mojangVerifiers(env)) {
    if (await crypto.subtle.verify("RSASSA-PKCS1-v1_5", k, keySig, payload)) {
      mojangOk = true;
      break;
    }
  }
  if (!mojangOk) return fail("auth", "Clé de profil non signée par Mojang", 401);
  // 2) le joueur possède la clé privée : il a signé notre défi
  let playerKey;
  try {
    playerKey = await crypto.subtle.importKey("spki", keyDer, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  } catch {
    return fail("auth", "Clé de profil illisible", 401);
  }
  const msg = new TextEncoder().encode(`paranoia:${nonce}:${uuid}`);
  if (!(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", playerKey, sig, msg))) return fail("auth", "Signature invalide", 401);

  // pseudo : celui de l'annuaire si disponible, sinon celui annoncé (s'il n'est pas déjà pris)
  const claimed = String(b.name || "");
  let name = await officialName(uuid);
  if (!name) {
    if (!NAME.test(claimed)) return fail("bad_request", "Pseudo invalide");
    const taken = await env.DB.prepare("SELECT uuid FROM users WHERE name_lower = ?1 AND uuid != ?2").bind(claimed.toLowerCase(), uuid).first();
    if (taken) return fail("auth", "Pseudo non vérifiable pour le moment, réessaie plus tard", 503);
    name = claimed;
  }
  return openSession(env, uuid, name);
}

const COSMETIC_ID = /^[a-z0-9_]{1,24}$/;
const cosmetic = (v) => (typeof v === "string" && COSMETIC_ID.test(v) ? v : "");
const ITEM_ID = /^[a-z0-9_:]{0,48}$/;
const item = (v) => (typeof v === "string" && ITEM_ID.test(v) ? v : "");
const num = (v, lo, hi, def) => (typeof v === "number" && isFinite(v) ? Math.min(hi, Math.max(lo, v)) : def);
/** Réglages du familier « mini skin » (taille, tête, équipement, cosmétiques), visibles par les autres. */
function mini(m) {
  if (!m || typeof m !== "object") return null;
  return {
    head: num(m.head, 1, 1.8, 1.3), size: num(m.size, 0.25, 0.7, 0.4),
    helmet: item(m.helmet), chest: item(m.chest), legs: item(m.legs), boots: item(m.boots), main: item(m.main), off: item(m.off),
    cape: cosmetic(m.cape), wings: cosmetic(m.wings), halo: cosmetic(m.halo), back: cosmetic(m.back),
  };
}

/** Publication du profil cosmétique complet (nametag + cape + ailes + aura + halo). */
async function profile(req, env, user) {
  const b = await body(req);
  const style = cleanStyle(b.style);
  if (!style) return fail("bad_request", "Style invalide");
  const data = {
    style,
    cape: cosmetic(b.cape),
    wings: cosmetic(b.wings),
    aura: cosmetic(b.aura),
    halo: cosmetic(b.halo),
    pet: cosmetic(b.pet),
    back: cosmetic(b.back),
    elytra: cosmetic(b.elytra),
    shield: cosmetic(b.shield),
    trail: cosmetic(b.trail),
    mini: mini(b.mini),
  };
  await env.DB.prepare(
    `INSERT INTO profiles (uuid, data, updated) VALUES (?1, ?2, ?3)
     ON CONFLICT(uuid) DO UPDATE SET data = ?2, updated = ?3`
  ).bind(user.uuid, JSON.stringify(data), Date.now()).run();
  return json({ ok: true });
}

/** Profils publics des joueurs demandés qui ont le mod (sans session). */
export async function lookupProfiles(req, env) {
  const b = await body(req);
  const list = Array.isArray(b.uuids) ? b.uuids : [];
  const wanted = [...new Set(list.map((u) => String(u).toLowerCase().replace(/-/g, "")))].filter((u) => UUID.test(u)).slice(0, 100);
  if (!wanted.length) return json({ profiles: {} });
  const marks = wanted.map((_, i) => `?${i + 1}`).join(",");
  const rows = await env.DB.prepare(`SELECT uuid, data, updated FROM profiles WHERE uuid IN (${marks})`).bind(...wanted).all();
  const profiles = {};
  const discord = await discordInfo(env, wanted).catch(() => ({}));
  for (const r of rows.results) {
    try {
      profiles[r.uuid] = { ...JSON.parse(r.data), updated: r.updated, discord: discord[r.uuid] || null };
    } catch {}
  }
  return json({ profiles });
}

const MODES = ["static", "scroll", "pulse", "wave"];

function cleanStyle(style) {
  if (!style || typeof style !== "object") return null;
  const colors = Array.isArray(style.colors) ? style.colors.filter((c) => typeof c === "string" && /^[0-9a-fA-F]{6}$/.test(c)) : [];
  if (colors.length < 1 || colors.length > 4) return null;
  const mode = MODES.includes(style.mode) ? style.mode : "scroll";
  const speed = Math.min(5, Math.max(0.1, Number(style.speed) || 1));
  return { colors: colors.map((c) => c.toUpperCase()), mode, speed, logo: style.logo !== false };
}

/** Connexion de test, active seulement si la variable DEV_AUTH_KEY est définie (jamais en production). */
async function devAuth(request, env) {
  if (!env.DEV_AUTH_KEY) return fail("not_found", "Introuvable", 404);
  const b = await body(request);
  if (b.key !== env.DEV_AUTH_KEY) return fail("auth", "Clé invalide", 401);
  const uuid = String(b.uuid || "").toLowerCase().replace(/-/g, "");
  const name = String(b.name || "");
  if (!UUID.test(uuid) || !NAME.test(name)) return fail("bad_request", "Paramètres invalides");
  return openSession(env, uuid, name);
}

async function me(request, env) {
  const h = request.headers.get("authorization") || "";
  const t = h.startsWith("Bearer ") ? h.slice(7) : "";
  if (!/^[0-9a-f]{64}$/.test(t)) return null;
  const row = await env.DB.prepare(
    "SELECT s.uuid, u.name FROM sessions s JOIN users u ON u.uuid = s.uuid WHERE s.token = ?1 AND s.expires > ?2"
  ).bind(t, Date.now()).first();
  return row || null;
}

async function areFriends(env, a, b) {
  return !!(await env.DB.prepare("SELECT 1 FROM friendships WHERE a = ?1 AND b = ?2").bind(a, b).first());
}

async function sync(request, env, user) {
  const url = new URL(request.url);
  const now = Date.now();
  if (url.searchParams.get("presence") === "1") {
    const server = (url.searchParams.get("server") || "").slice(0, 80) || null;
    await env.DB.prepare("UPDATE users SET last_seen = ?1, server = ?2 WHERE uuid = ?3").bind(now, server, user.uuid).run();
  }
  const since = Math.max(0, parseInt(url.searchParams.get("since") || "0", 10) || 0);
  const [friends, incoming, outgoing, messages] = await env.DB.batch([
    env.DB.prepare(
      `SELECT u.uuid, u.name, u.last_seen, u.server, f.since FROM friendships f JOIN users u ON u.uuid = f.b
       WHERE f.a = ?1 ORDER BY u.name_lower`
    ).bind(user.uuid),
    env.DB.prepare(
      "SELECT u.uuid, u.name, r.created FROM requests r JOIN users u ON u.uuid = r.from_uuid WHERE r.to_uuid = ?1 ORDER BY r.created DESC"
    ).bind(user.uuid),
    env.DB.prepare(
      "SELECT u.uuid, u.name, r.created FROM requests r JOIN users u ON u.uuid = r.to_uuid WHERE r.from_uuid = ?1 ORDER BY r.created DESC"
    ).bind(user.uuid),
    env.DB.prepare(
      `SELECT id, from_uuid, text, created FROM messages WHERE to_uuid = ?1 AND id > ?2 AND created > ?3
       ORDER BY id LIMIT 100`
    ).bind(user.uuid, since, now - HISTORY_DAYS * DAY),
  ]);
  const ids = [user.uuid, ...friends.results.map((f) => f.uuid)];
  const discord = await discordInfo(env, ids).catch(() => ({}));
  return json({
    me: { uuid: user.uuid, name: user.name, discord: discord[user.uuid] || null },
    now,
    friends: friends.results.map((f) => {
      const online = now - f.last_seen < ONLINE_MS;
      return {
        uuid: f.uuid, name: f.name, online, server: online ? f.server : null, lastSeen: f.last_seen, since: f.since,
        discord: discord[f.uuid] || null,
      };
    }),
    incoming: incoming.results.map((r) => ({ uuid: r.uuid, name: r.name, created: r.created })),
    outgoing: outgoing.results.map((r) => ({ uuid: r.uuid, name: r.name, created: r.created })),
    messages: messages.results.map((m) => ({ id: m.id, from: m.from_uuid, text: m.text, created: m.created })),
  });
}

async function request(req, env, user) {
  const b = await body(req);
  const name = String(b.name || "").trim();
  if (!NAME.test(name)) return fail("bad_request", "Pseudo invalide");
  const target = await env.DB.prepare("SELECT uuid, name FROM users WHERE name_lower = ?1").bind(name.toLowerCase()).first();
  if (!target) return fail("no_mod", `${name} n'a pas encore le mod Paranoia`, 404);
  if (target.uuid === user.uuid) return fail("self", "Tu ne peux pas t'ajouter toi-même");
  if (await areFriends(env, user.uuid, target.uuid)) return fail("already", `${target.name} est déjà ton ami`);
  const now = Date.now();
  const reverse = await env.DB.prepare("SELECT 1 FROM requests WHERE from_uuid = ?1 AND to_uuid = ?2").bind(target.uuid, user.uuid).first();
  if (reverse) {
    await env.DB.batch([
      env.DB.prepare("DELETE FROM requests WHERE (from_uuid = ?1 AND to_uuid = ?2) OR (from_uuid = ?2 AND to_uuid = ?1)").bind(user.uuid, target.uuid),
      env.DB.prepare("INSERT OR IGNORE INTO friendships (a, b, since) VALUES (?1, ?2, ?3), (?2, ?1, ?3)").bind(user.uuid, target.uuid, now),
    ]);
    return json({ status: "accepted", uuid: target.uuid, name: target.name });
  }
  const pending = await env.DB.prepare("SELECT COUNT(*) AS n FROM requests WHERE from_uuid = ?1").bind(user.uuid).first();
  if (pending.n >= 50) return fail("limit", "Trop de demandes en attente");
  await env.DB.prepare("INSERT OR IGNORE INTO requests (from_uuid, to_uuid, created) VALUES (?1, ?2, ?3)").bind(user.uuid, target.uuid, now).run();
  return json({ status: "sent", uuid: target.uuid, name: target.name });
}

async function respond(req, env, user) {
  const b = await body(req);
  const from = String(b.uuid || "").toLowerCase().replace(/-/g, "");
  if (!UUID.test(from)) return fail("bad_request", "Paramètres invalides");
  const r = await env.DB.prepare("SELECT 1 FROM requests WHERE from_uuid = ?1 AND to_uuid = ?2").bind(from, user.uuid).first();
  if (!r) return fail("not_found", "Demande introuvable", 404);
  const stmts = [env.DB.prepare("DELETE FROM requests WHERE from_uuid = ?1 AND to_uuid = ?2").bind(from, user.uuid)];
  if (b.accept === true) {
    stmts.push(env.DB.prepare("INSERT OR IGNORE INTO friendships (a, b, since) VALUES (?1, ?2, ?3), (?2, ?1, ?3)").bind(user.uuid, from, Date.now()));
  }
  await env.DB.batch(stmts);
  return json({ ok: true, accepted: b.accept === true });
}

async function remove(req, env, user) {
  const b = await body(req);
  const other = String(b.uuid || "").toLowerCase().replace(/-/g, "");
  if (!UUID.test(other)) return fail("bad_request", "Paramètres invalides");
  await env.DB.batch([
    env.DB.prepare("DELETE FROM friendships WHERE (a = ?1 AND b = ?2) OR (a = ?2 AND b = ?1)").bind(user.uuid, other),
    env.DB.prepare("DELETE FROM requests WHERE (from_uuid = ?1 AND to_uuid = ?2) OR (from_uuid = ?2 AND to_uuid = ?1)").bind(user.uuid, other),
  ]);
  return json({ ok: true });
}

async function message(req, env, user) {
  const b = await body(req);
  const to = String(b.to || "").toLowerCase().replace(/-/g, "");
  const text = String(b.text || "").replace(/[\u0000-\u001f\u007f§]/g, "").trim().slice(0, 256);
  if (!UUID.test(to) || !text) return fail("bad_request", "Message vide");
  if (!(await areFriends(env, user.uuid, to))) return fail("not_friend", "Vous n'êtes pas amis", 403);
  const now = Date.now();
  const recent = await env.DB.prepare("SELECT COUNT(*) AS n FROM messages WHERE from_uuid = ?1 AND created > ?2").bind(user.uuid, now - 10000).first();
  if (recent.n >= 5) return fail("rate", "Doucement, attends quelques secondes", 429);
  const res = await env.DB.prepare("INSERT INTO messages (from_uuid, to_uuid, text, created) VALUES (?1, ?2, ?3, ?4)").bind(user.uuid, to, text, now).run();
  if (Math.random() < 0.02) {
    await env.DB.prepare("DELETE FROM messages WHERE created < ?1").bind(now - HISTORY_DAYS * DAY).run();
  }
  return json({ ok: true, id: res.meta.last_row_id, created: now });
}

/** Nouveaux messages seuls : une requête légère, appelée souvent. */
async function inbox(req, env, user) {
  const since = Math.max(0, parseInt(new URL(req.url).searchParams.get("since") || "0", 10) || 0);
  const { results } = await env.DB.prepare(
    "SELECT id, from_uuid, text, created FROM messages WHERE to_uuid = ?1 AND id > ?2 ORDER BY id LIMIT 100"
  ).bind(user.uuid, since).all();
  return json({ messages: results.map((m) => ({ id: m.id, from: m.from_uuid, text: m.text, created: m.created })) });
}

/** Conversation avec un ami, dans les deux sens. */
async function history(req, env, user) {
  const other = String(new URL(req.url).searchParams.get("with") || "").toLowerCase().replace(/-/g, "");
  if (!UUID.test(other)) return fail("bad_request", "Joueur invalide");
  if (!(await areFriends(env, user.uuid, other))) return fail("not_friend", "Vous n'êtes pas amis", 403);
  const { results } = await env.DB.prepare(
    `SELECT from_uuid, text, created FROM messages
     WHERE (from_uuid = ?1 AND to_uuid = ?2) OR (from_uuid = ?2 AND to_uuid = ?1)
     ORDER BY id DESC LIMIT 200`
  ).bind(user.uuid, other).all();
  return json({ messages: results.reverse().map((m) => ({ mine: m.from_uuid === user.uuid, text: m.text, created: m.created })) });
}

/** Routes /v2/*, ou null si la route n'existe pas. */
export async function social(request, env, pathname) {
  if (!env.DB) return fail("unavailable", "Base sociale non configurée", 503);
  if (request.method === "POST" && pathname === "/v2/auth") return auth(request, env);
  if (request.method === "POST" && pathname === "/v2/auth/dev") return devAuth(request, env);
  if (request.method === "GET" && pathname === "/v2/challenge") return challenge(env);
  if (request.method === "POST" && pathname === "/v2/auth/key") return keyAuth(request, env);
  if (request.method === "POST" && pathname === "/v2/lookup") return lookupProfiles(request, env);
  if (request.method === "POST" && pathname === "/v2/live/who") return liveWho(request, env);
  if (request.method === "GET" && pathname === "/v2/discord/callback") return discordCallback(request, env);
  const user = await me(request, env);
  if (!user) return fail("session", "Session expirée", 401);
  if (pathname.startsWith("/v2/packs")) return packs(request, env, user, pathname);
  if (pathname.startsWith("/v2/page")) {
    const res = await pages(request, env, user, pathname);
    if (res) return res;
  }
  if (pathname.startsWith("/v2/cape") || pathname.startsWith("/v2/media") || pathname.startsWith("/v2/gif")) {
    const res = await media(request, env, user, pathname);
    if (res) return res;
  }
  if (request.method === "GET" && pathname === "/v2/inbox") return inbox(request, env, user);
  if (request.method === "GET" && pathname === "/v2/messages/history") return history(request, env, user);
  if (request.method === "GET" && pathname === "/v2/sync") return sync(request, env, user);
  if (request.method === "POST" && pathname === "/v2/friends/request") return request_(request, env, user);
  if (request.method === "POST" && pathname === "/v2/friends/respond") return respond(request, env, user);
  if (request.method === "POST" && pathname === "/v2/friends/remove") return remove(request, env, user);
  if (request.method === "POST" && pathname === "/v2/messages") return message(request, env, user);
  if (request.method === "POST" && (pathname === "/v2/profile" || pathname === "/v2/nametag")) return profile(request, env, user);
  if (request.method === "POST" && pathname === "/v2/live") return live(request, env, user);
  if (request.method === "POST" && pathname === "/v2/discord/start") return discordStart(request, env, user);
  if (request.method === "POST" && pathname === "/v2/discord/unlink") return discordUnlink(request, env, user);
  return null;
}

const request_ = request;
