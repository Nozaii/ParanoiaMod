-- Base D1 du système social Paranoia (amis, demandes, présence, messages).
-- Appliquer : npx wrangler d1 execute paranoia-social --remote --file schema.sql

CREATE TABLE IF NOT EXISTS users (
  uuid TEXT PRIMARY KEY,           -- UUID Mojang sans tirets
  name TEXT NOT NULL,
  name_lower TEXT NOT NULL,
  created INTEGER NOT NULL,
  last_seen INTEGER NOT NULL DEFAULT 0,
  server TEXT                      -- serveur actuel, seulement si le joueur accepte de le partager
);
CREATE INDEX IF NOT EXISTS users_name ON users(name_lower);

CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  uuid TEXT NOT NULL,
  expires INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_uuid ON sessions(uuid);

-- amitié stockée dans les deux sens (a -> b et b -> a)
CREATE TABLE IF NOT EXISTS friendships (
  a TEXT NOT NULL,
  b TEXT NOT NULL,
  since INTEGER NOT NULL,
  PRIMARY KEY (a, b)
);

CREATE TABLE IF NOT EXISTS requests (
  from_uuid TEXT NOT NULL,
  to_uuid TEXT NOT NULL,
  created INTEGER NOT NULL,
  PRIMARY KEY (from_uuid, to_uuid)
);
CREATE INDEX IF NOT EXISTS requests_to ON requests(to_uuid);

CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  from_uuid TEXT NOT NULL,
  to_uuid TEXT NOT NULL,
  text TEXT NOT NULL,
  created INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS messages_to ON messages(to_uuid, id);
CREATE INDEX IF NOT EXISTS messages_from ON messages(from_uuid, created);

-- défis à usage unique pour l'authentification par clé de profil Mojang
CREATE TABLE IF NOT EXISTS challenges (
  nonce TEXT PRIMARY KEY,
  expires INTEGER NOT NULL
);

-- profil cosmétique public de chaque joueur du mod (nametag, cape, ailes, aura, halo)
CREATE TABLE IF NOT EXISTS profiles (
  uuid TEXT PRIMARY KEY,
  data TEXT NOT NULL,
  updated INTEGER NOT NULL
);

-- community texture packs: the zip is in KV under pack:<id>
CREATE TABLE IF NOT EXISTS packs (
  id TEXT PRIMARY KEY,
  owner TEXT NOT NULL,
  author TEXT NOT NULL,
  name TEXT NOT NULL,
  size INTEGER NOT NULL,
  created INTEGER NOT NULL,
  likes INTEGER NOT NULL DEFAULT 0,
  views INTEGER NOT NULL DEFAULT 0,
  downloads INTEGER NOT NULL DEFAULT 0,
  reports INTEGER NOT NULL DEFAULT 0,
  hidden INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS packs_owner ON packs (owner, created);
CREATE TABLE IF NOT EXISTS pack_likes (pack TEXT NOT NULL, user TEXT NOT NULL, PRIMARY KEY (pack, user));
CREATE TABLE IF NOT EXISTS pack_views (pack TEXT NOT NULL, user TEXT NOT NULL, PRIMARY KEY (pack, user));
CREATE TABLE IF NOT EXISTS pack_downloads (pack TEXT NOT NULL, user TEXT NOT NULL, PRIMARY KEY (pack, user));
CREATE TABLE IF NOT EXISTS pack_reports (pack TEXT NOT NULL, user TEXT NOT NULL, PRIMARY KEY (pack, user));
