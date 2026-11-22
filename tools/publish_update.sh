#!/bin/sh
# Publie le jar compilé comme mise à jour automatique (serveur Paranoia, Cloudflare KV).
# Usage : sh tools/publish_update.sh   (après ./gradlew build ; nécessite « npx wrangler login »)
set -e
cd "$(dirname "$0")/.."
JAR="build/libs/aurora-$(grep "^version=" gradle.properties | cut -d= -f2).jar"
VERSION=$(grep '^version=' gradle.properties | cut -d= -f2)
SHA=$(sha256sum "$JAR" | cut -d' ' -f1)
SIZE=$(wc -c < "$JAR" | tr -d ' ')
printf '{"version":"%s","sha256":"%s","size":%s}' "$VERSION" "$SHA" "$SIZE" > build/update-manifest.json
cd server
npx --yes wrangler kv key put --binding PROFILES --remote "update:jar" --path "../$JAR"
npx --yes wrangler kv key put --binding PROFILES --remote "update:manifest" --path ../build/update-manifest.json
echo "Publié : $VERSION ($SIZE octets, sha256 $SHA)"
