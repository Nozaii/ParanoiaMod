// Test local du module media.js avec un faux KV et une fausse base (aucun déploiement) :
//   node server/test/media_test.mjs
import { media } from "../src/media.js";
import zlib from "node:zlib";

const store = new Map();
const kv = {
  async get(k, type) {
    const e = store.get(k);
    if (!e) return null;
    if (type === "json") return JSON.parse(e.v);
    if (type === "arrayBuffer") return e.v;
    return typeof e.v === "string" ? e.v : null;
  },
  async getWithMetadata(k) {
    const e = store.get(k);
    return e ? { value: e.v, metadata: e.m } : { value: null, metadata: null };
  },
  async put(k, v, o = {}) {
    store.set(k, { v, m: o.metadata || null, ttl: o.expirationTtl });
  },
  async delete(k) {
    store.delete(k);
  },
};
const friends = new Set();
const db = { prepare: () => ({ bind: (x, y) => ({ first: async () => (friends.has(x + "|" + y) ? { ok: 1 } : null) }) }) };
const env = { PROFILES: kv, DB: db };
const U = (c) => c.repeat(32);
const A = { uuid: U("a") }, B = { uuid: U("b") }, C = { uuid: U("c") }, D = { uuid: U("d") };
friends.add(A.uuid + "|" + B.uuid);
friends.add(B.uuid + "|" + A.uuid);

function crc32(buf) {
  let crc = 0xffffffff;
  for (const b of buf) {
    let c = (crc ^ b) & 255;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function png(w, h) {
  const chunk = (t, d) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(d.length);
    const td = Buffer.concat([Buffer.from(t), d]);
    const c = Buffer.alloc(4);
    c.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, c]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  const raw = Buffer.alloc((w * 4 + 1) * h);
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}

const req = (method, path, body, headers = {}) => new Request("https://x" + path, { method, body, headers });
let ok = 0, bad = 0;
async function expect(label, user, method, path, body, headers, status) {
  const r = await media(req(method, path, body, headers), env, user, path);
  const s = r ? r.status : "null";
  if (s === status) ok++;
  else {
    bad++;
    console.log("ECHEC", label, ": attendu", status, "obtenu", s, r ? await r.text() : "");
  }
  return r;
}
function check(label, cond, detail) {
  if (cond) ok++;
  else {
    bad++;
    console.log("ECHEC", label, detail);
  }
}

// ---- capes
await expect("cape fixe valide", A, "POST", "/v2/cape", png(256, 128), { "x-cape-frames": "1" }, 200);
await expect("cape animée valide", A, "POST", "/v2/cape", png(128, 64 * 3), { "x-cape-frames": "3", "x-cape-delays": "100,100,100" }, 200);
await expect("mauvaise taille", A, "POST", "/v2/cape", png(100, 50), { "x-cape-frames": "1" }, 400);
await expect("trop d'images", A, "POST", "/v2/cape", png(64, 32 * 25), { "x-cape-frames": "25" }, 400);
await expect("délais manquants", A, "POST", "/v2/cape", png(64, 64), { "x-cape-frames": "2", "x-cape-delays": "100" }, 400);
await expect("pas un PNG", A, "POST", "/v2/cape", Buffer.from("GIF89a" + "x".repeat(100)), { "x-cape-frames": "1" }, 400);
await expect("trop gros", A, "POST", "/v2/cape", Buffer.alloc(600 * 1024), { "x-cape-frames": "1" }, 413);
const g = await expect("lecture cape", C, "GET", "/v2/cape/" + A.uuid, undefined, {}, 200);
check("en-têtes de la cape", g.headers.get("x-cape-frames") === "3" && g.headers.get("x-cape-delays") === "100,100,100", [...g.headers]);
await expect("cape absente", C, "GET", "/v2/cape/" + B.uuid, undefined, {}, 404);
await expect("se signaler soi-même", A, "POST", "/v2/cape/" + A.uuid + "/report", undefined, {}, 400);
for (const u of [B, C]) await expect("signalement", u, "POST", "/v2/cape/" + A.uuid + "/report", undefined, {}, 200);
await expect("double signalement compte une fois", B, "POST", "/v2/cape/" + A.uuid + "/report", undefined, {}, 200);
await expect("encore visible à 2 signalements", C, "GET", "/v2/cape/" + A.uuid, undefined, {}, 200);
await expect("3e signalement", D, "POST", "/v2/cape/" + A.uuid + "/report", undefined, {}, 200);
await expect("masquée après 3 signalements", C, "GET", "/v2/cape/" + A.uuid, undefined, {}, 404);
await expect("nouvel envoi", A, "POST", "/v2/cape", png(64, 32), { "x-cape-frames": "1" }, 200);
await expect("visible après nouvel envoi", C, "GET", "/v2/cape/" + A.uuid, undefined, {}, 200);
// ---- face de cape en GIF (le joueur qui la voit la reconstruit)
const gif = (w, h, n = 200) => { const b = Buffer.alloc(n); b.write("GIF89a"); b.writeUInt16LE(w, 6); b.writeUInt16LE(h, 8); return b; };
await expect("face sans cape", B, "POST", "/v2/cape/face", gif(160, 256), {}, 409);
await expect("face valide", A, "POST", "/v2/cape/face", gif(160, 256), {}, 200);
await expect("face mauvais format", A, "POST", "/v2/cape/face", gif(256, 128), {}, 400);
await expect("face pas un GIF", A, "POST", "/v2/cape/face", png(160, 256), {}, 400);
await expect("face trop grosse", A, "POST", "/v2/cape/face", gif(160, 256, 1100 * 1024), {}, 413);
const fg = await expect("lecture face", C, "GET", "/v2/cape/" + A.uuid + "/face", undefined, {}, 200);
check("face servie en GIF", fg.headers.get("content-type") === "image/gif", [...fg.headers]);
await expect("face absente", C, "GET", "/v2/cape/" + B.uuid + "/face", undefined, {}, 404);
await expect("nouvelle cape PNG", A, "POST", "/v2/cape", png(64, 32), { "x-cape-frames": "1" }, 200);
await expect("ancienne face retirée", C, "GET", "/v2/cape/" + A.uuid + "/face", undefined, {}, 404);
await expect("face renvoyée", A, "POST", "/v2/cape/face", gif(100, 160), {}, 200);
await expect("suppression", A, "POST", "/v2/cape/delete", undefined, {}, 200);
await expect("absente après suppression", C, "GET", "/v2/cape/" + A.uuid, undefined, {}, 404);
await expect("face absente après suppression", C, "GET", "/v2/cape/" + A.uuid + "/face", undefined, {}, 404);

// ---- images de messages privés
const up = await expect("image à un ami", A, "POST", "/v2/media", png(300, 200), { "x-to": B.uuid }, 200);
const id = (await up.json()).id;
await expect("image à un non-ami", A, "POST", "/v2/media", png(10, 10), { "x-to": C.uuid }, 403);
await expect("image trop grande", A, "POST", "/v2/media", png(2000, 10), { "x-to": B.uuid }, 400);
await expect("image pas PNG", A, "POST", "/v2/media", Buffer.from("x".repeat(200)), { "x-to": B.uuid }, 400);
await expect("lecture par le destinataire", B, "GET", "/v2/media/" + id, undefined, {}, 200);
await expect("lecture par l'expéditeur", A, "GET", "/v2/media/" + id, undefined, {}, 200);
await expect("lecture par un tiers", C, "GET", "/v2/media/" + id, undefined, {}, 403);
await expect("image inconnue", B, "GET", "/v2/media/" + "0".repeat(16), undefined, {}, 404);
check("expiration à 7 jours", store.get("img:" + id).ttl === 7 * 86400, store.get("img:" + id).ttl);

// ---- limite d'envoi par heure (30) : déjà 2 comptés (les refus avant le compteur ne comptent pas)
let limited = 0, sent = 0;
for (let i = 0; i < 40; i++) {
  const r = await media(req("POST", "/v2/media", png(4, 4), { "x-to": B.uuid }), env, A, "/v2/media");
  if (r.status === 429) limited++;
  else if (r.status === 200) sent++;
}
check("limite horaire", limited > 0 && sent <= 30, { limited, sent });
check("route inconnue -> null", (await media(req("GET", "/v2/cape"), env, A, "/v2/cape")) === null, "");

console.log(`${ok} réussis, ${bad} échecs`);
process.exit(bad ? 1 : 0);
