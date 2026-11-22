# Serveur des nametags Paranoia (Cloudflare, gratuit)

Le mod y publie ton dégradé de nametag et y récupère celui des autres joueurs du mod.
L'identité est vérifiée par Mojang (`hasJoined`) : ton token Minecraft n'est jamais envoyé ici.

## Déploiement (une seule fois, ~5 min)

1. Crée un compte gratuit sur https://dash.cloudflare.com
2. Dans ce dossier `server/` :

```bash
npx wrangler login
```

```bash
npx wrangler kv namespace create PROFILES
```

3. Copie l'`id` affiché dans `wrangler.toml` (à la place de `REMPLACE_PAR_TON_ID_KV`).
4. Déploie :

```bash
npx wrangler deploy
```

5. Le serveur officiel est déployé sur `https://paranoia-nametags.paranoiaclient-fr.workers.dev`.
   Cette adresse est intégrée au mod (`NametagSync.DEFAULT_API`) : rien à configurer pour les joueurs.
   Pour redéployer après une modification de `src/index.js` : `npx wrangler deploy`.

## Limites du plan gratuit

100 000 requêtes et 1 000 écritures par jour. Le mod ne publie qu'au démarrage et quand
tu modifies ton nametag, et ne cherche les autres joueurs qu'une fois toutes les 3 minutes.
