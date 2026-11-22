/**
 * Paranoia Menu — backend des nametags (Cloudflare Worker + KV).
 *
 * POST /v1/profile  { name, serverId, style }  -> publie le style du joueur.
 *   L'identité est prouvée par Mojang : le mod appelle d'abord sessionserver "join"
 *   avec un serverId aléatoire, puis ce worker vérifie "hasJoined". Le token du joueur
 *   ne transite jamais par ici.
 * POST /v1/lookup   { uuids: [...] }           -> styles des joueurs qui ont le mod.
 * GET  /v1/health                                -> { ok: true }
 * /v2/*  : système social (amis, demandes, présence, messages), voir social.js
 */

import { social } from "./social.js";
import { fairplay } from "./fairplay.js";

const MODES = ["static", "scroll", "pulse", "wave"];
const HEX = /^[0-9a-fA-F]{6}$/;
const UUID = /^[0-9a-f]{32}$/;

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function cleanStyle(style) {
  if (!style || typeof style !== "object") return null;
  const colors = Array.isArray(style.colors) ? style.colors.filter((c) => typeof c === "string" && HEX.test(c)) : [];
  if (colors.length < 1 || colors.length > 4) return null;
  const mode = MODES.includes(style.mode) ? style.mode : "scroll";
  const speed = Math.min(5, Math.max(0.1, Number(style.speed) || 1));
  return { colors: colors.map((c) => c.toUpperCase()), mode, speed, logo: style.logo !== false };
}

async function publish(request, env) {
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "json invalide" }, 400);
  }
  const name = String(body.name || "");
  const serverId = String(body.serverId || "");
  if (!/^[A-Za-z0-9_]{1,16}$/.test(name) || !/^[0-9a-f]{16,64}$/.test(serverId)) {
    return json({ error: "paramètres invalides" }, 400);
  }
  const style = cleanStyle(body.style);
  if (!style) return json({ error: "style invalide" }, 400);

  const url = `https://sessionserver.mojang.com/session/minecraft/hasJoined?username=${encodeURIComponent(name)}&serverId=${serverId}`;
  const res = await fetch(url);
  if (res.status !== 200) return json({ error: "authentification Mojang refusée" }, 401);
  const profile = await res.json();
  const uuid = String(profile.id || "").toLowerCase();
  if (!UUID.test(uuid)) return json({ error: "profil invalide" }, 401);

  await env.PROFILES.put(uuid, JSON.stringify({ name: profile.name, style, updated: Date.now() }));
  return json({ ok: true, uuid });
}

async function lookup(request, env) {
  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "json invalide" }, 400);
  }
  const uuids = Array.isArray(body.uuids) ? body.uuids : [];
  const wanted = [...new Set(uuids.map((u) => String(u).toLowerCase().replace(/-/g, "")))].filter((u) => UUID.test(u)).slice(0, 100);
  const found = await Promise.all(wanted.map((u) => env.PROFILES.get(u, "json")));
  const profiles = {};
  wanted.forEach((u, i) => {
    if (found[i]) profiles[u] = found[i].style;
  });
  return json({ profiles });
}

export default {
  async fetch(request, env) {
    const { pathname } = new URL(request.url);
    try {
      if (request.method === "GET" && pathname === "/v1/health") return json({ ok: true });
      // mises à jour du mod : publiées avec tools/publish_update.sh
      if (request.method === "GET" && pathname === "/v1/update") {
        const m = await env.PROFILES.get("update:manifest", "json");
        return m ? json(m) : json({ error: "aucune version publiée" }, 404);
      }
      if (request.method === "GET" && pathname === "/v1/update/jar") {
        const jar = await env.PROFILES.get("update:jar", "arrayBuffer");
        if (!jar) return json({ error: "aucune version publiée" }, 404);
        return new Response(jar, { headers: { "content-type": "application/java-archive", "cache-control": "no-store" } });
      }
      if (request.method === "GET" && pathname === "/v1/fairplay") return await fairplay(env);
      if (request.method === "POST" && pathname === "/v1/profile") return await publish(request, env);
      if (request.method === "POST" && pathname === "/v1/lookup") return await lookup(request, env);
      if (pathname.startsWith("/v2/")) {
        const res = await social(request, env, pathname);
        if (res) return res;
      }
      return json({ error: "introuvable" }, 404);
    } catch (e) {
      return json({ error: "erreur serveur" }, 500);
    }
  },
};
