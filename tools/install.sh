#!/bin/sh
# Installe le jar dans l'instance Paranoia. Refuse si le jeu tourne : remplacer un jar ouvert par la JVM
# corrompt la lecture des ressources du mod -> Minecraft retire alors tous les packs de ressources.
D="/c/Users/RTX 3070/AppData/Roaming/.paranoia-client/instances/fef515c6-a8e6-4b81-93a1-55d5d13239e0/mods"
if tasklist 2>/dev/null | grep -qiE "^javaw?\.exe"; then
  echo "Jeu en cours : ferme Minecraft avant d'installer." >&2
  exit 1
fi
JAR="build/libs/ParanoiaMod-$(grep "^version=" gradle.properties | cut -d= -f2).jar"
rm -f "$D"/aurora-*.jar "$D"/ParanoiaMod-*.jar "$D/.aurora-update.jar"
cp "$JAR" "$D/" && ls -la "$D"/ParanoiaMod-*.jar
