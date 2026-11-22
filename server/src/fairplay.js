/**
 * Fair-play : liste « adresse de serveur → modules Paranoia coupés là-bas », lue par le mod.
 * GET /v1/fairplay -> { servers: { "hypixel.net": ["freelook"], "*": [] } }
 * La liste est stockée dans KV (clé « fairplay ») ; à défaut, la liste par défaut ci-dessous.
 * Pour la modifier : npx wrangler kv key put --binding PROFILES fairplay '<json>' --remote
 */
const DEFAULT_FAIRPLAY = { servers: { "hypixel.net": ["freelook"], "*": [] } };

export async function fairplay(env) {
  const v = await env.PROFILES.get("fairplay", "json");
  return new Response(JSON.stringify(v || DEFAULT_FAIRPLAY), {
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "public, max-age=300" },
  });
}
