# Paranoia Client — description complète (version 1.9.7)

Mod Fabric pour Minecraft 1.21.11 (id `aurora`, package `gg.aurora`). Un client « tout-en-un » orienté PvP et performance : menu de modules, HUD personnalisable, cosmétiques visibles par les autres joueurs du mod, amis et messages, packs de textures par objet, waypoints, et un serveur Cloudflare qui porte la partie sociale et les mises à jour.

Chiffres : 178 fichiers Java (~31 800 lignes), 81 mixins, 110 modules, 43 cosmétiques + 8 capes officielles, 36 écrans et composants d'interface, 5 fichiers serveur.

---

## 1. Installation et mise à jour

- Un seul jar (`ParanoiaMod-<version>.jar`) dans le dossier `mods` d'une instance Fabric 1.21.11 avec Fabric API.
- **Mises à jour automatiques** : le client interroge `/v1/update` au lancement ; le nouveau jar est téléchargé en `.aurora-update.jar` et installé à la fermeture du jeu (réglage « Automatic updates », bouton « Check now » dans Settings › About).
- Configuration dans `config/aurora.json` avec sauvegarde de secours `aurora.json.bak` ; chaque module est chargé séparément (un réglage corrompu n'efface pas les autres). Migrations de version intégrées.

## 2. Navigation

- **RShift** ouvre le menu (touche modifiable). Le menu arrive en 3D (perspective animée) puis se pose à plat.
- **Menu fixé dans le monde** (option) : le menu est posé devant le joueur comme une dalle 3D opaque ; on marche autour, on vise pour cliquer, clic droit pour le déplacer, poignée de redimensionnement, collision avec les blocs, fondu quand on est trop près.
- Coque unique : barre latérale (Combat, Visuals, HUD, Performance, Utility · Wardrobe, Friends, Pictures, Settings), contenu au centre, pied de page (compteur de modules, raccourci). Wardrobe, Friends et Pictures sont des vues de cette coque (plus d'écrans séparés).
- Modules en cartes compactes sur 3 colonnes, groupés par section ; filtres segmentés (All / Enabled / Favorites / Heavy) ; recherche en tapant n'importe où ; favoris (étoile) ; un module ouvert fait glisser un tiroir de réglages depuis la droite.
- Préréglage de performance dans l'en-tête : Quality / Balanced / High FPS (Boost + cosmétiques légers).
- Échap remonte d'un niveau (menu de preset, recherche, tiroir, vue réglages, puis ferme).
- Écran titre « Paranoia » qui remplace le menu principal de Minecraft (désactivable), bouton « Paranoia Client » vanilla dans la grille du menu pause (à la place de « Give Feedback »), toasts de notification.
- Modules Visuals : un aperçu capturé et animé de l'effet (27 bandes de 6 images, 320×180) en haut du tiroir de réglages ; fixe par rapport aux réglages du module.

## 3. Modules (110)

Chaque module a un interrupteur, souvent des réglages, un raccourci clavier optionnel et un bouton Reset. Les modules en double avec d'autres mods installés sont signalés (« Duplicate »).

### Combat
Combat sounds, Combat Hitbox, Hit Color, Hit Effects (traits de coup), Target Info, Target HUD, Target ESP, Low HP Warning (seuil, couleur, pulsation), Armor Alert, Totem Pop Counter, Hitmarker, Damage Indicator, Reach Display, Combo Counter, Keystrokes, CPS, Shield Fix, Shield Status, Shield Size, Toggle Sprint / Toggle Sneak, Auto Respawn, Auto GG (message de fin de partie automatique sur les titres de victoire), Drop Protection (empêche de jeter certains objets).

### Optimiseurs PvP
Crystal Optimizer, Gap Optimizer, Anchor Optimizer, Projectile Optimizer, Consumable Optimizer : allègent les rendus et calculs des objets de combat les plus fréquents.

### Visuals
Custom Sky (ciel cubique équirectangulaire, aurore, compatible packs de shaders), Weather Control, Fog Controls, Clear Water, Block Outline, Draw Crosshair (éditeur de réticule), Custom FOV, Zoom (C), Freelook, No Hurt Cam, No View Bobbing, Fullbright, No Night Vision, Low Fire, Time Changer, Graphic filters, Motion Blur, Rescale Resolution, Mirror floor (reflet des joueurs au sol), Hologram Skin, Friend Glow, Hand Aura (instanciée GPU), Hand Pose, Swing Animation, Animations 1.7, Custom Glint, Totem animation / No Totem Animation, Item Size (ground), Item Physic, Item flames, Shockwaves, Jump Circles, Trail (traînée colorée visible par les autres), Ambient particles, Halloween Bats, Emotes (roue d'emotes, touche N), Screenshot Tools (galerie), Overlay Editor, Light (niveau de lumière), Saturation (contours dorés clignotants sur les cuisses de la barre de faim quand on peut manger).

### HUD (widgets déplaçables)
FPS, CPS, Ping, Ping Graph, Frametime Graph, Memory, Coordinates, Direction (boussole), Biome, Clock, Day Counter, Session, Stopwatch, Speed, Sprint, Server Address, Watermark, Held Item Info, Item Counter, Item Cooldowns, Item Tooltips+, Arrows, Totem Counter, Totem Pops, Potions, Armor, Now Playing (musique : lecture, piste suivante et précédente au clavier), Death Coords, Keystrokes, Target HUD, Hide Boss Bar, Hide Scoreboard, Stream mode, Sound Mixer, Shulker Viewer (contenu des shulkers en infobulle).

### Performance
HUD cache (HUD redessiné à 30 Hz dans une texture), Optimizer +++, Smart culling, Adaptive Detail, Adaptive mode, Menu FPS Cap, Screen limiter, Smooth Scroll, Scoreboard cache.

### Utility
Better chat (Chat+), Waypoints, Death Coords, Smooth Scroll, Screenshot Tools, Stream mode, Sound Mixer, Time Changer, Fly Speed, Toggle Sneak / Sprint.

## 4. HUD

- Éditeur plein écran : le jeu reste visible et chaque widget est dessiné exactement comme en partie ; glisser, aimantation aux bords / au centre / aux autres widgets avec guides (Maj = libre), poignées d'angle et molette pour la taille, flèches pour le pixel près (Maj = 10 px), Ctrl+Z / Ctrl+Y, barre d'outils (Done, Undo, Redo, Snap, liste des widgets, Reset all), tiroir de réglages du widget dans le style du menu.
- Fonds des widgets sans barre ni anneau accent (styles Clean et Solid).
- Armor HUD : seuil d'alerte, son et notification (ex-module Armor Alert, fusionné).
- Quatre styles globaux (Glass, Full, Minimal, Clean), opacité, ombre, icônes colorées, couleur par widget.
- Export et import de disposition par code court (`PHUD1:…`).
- Potions et Armor avec plusieurs styles d'affichage.

## 5. Cosmétiques (Wardrobe)

Aperçu 3D du joueur, catégories à gauche, cartes à droite. Tout ce qui est équipé est publié sur le serveur et affiché chez les autres joueurs du mod.

- **Capes** : 8 officielles (Paranoia, Nocturne, Aurora, Cobweb, Ember, Glitch, Hypergamy, John Pork) + capes importées : fichier PNG / GIF / JPEG, ou lien collé (Ctrl+V) depuis Tenor, Klipy ou un lien direct (15 Mo max) ; recadrage ; GIF animés (24 images, 512 Ko partagés). La cape importée est envoyée au serveur pour que les autres la voient (réglage « tous / amis / personne » côté spectateur, signalement, masquée à 3 signalements). Élytres avec le motif de la cape (silhouette vanilla, option).
- **Nametag** : dégradé animé sur le pseudo (Fixed, Scroll, Pulse, Wave), éditeur de couleurs, logo Paranoia devant le nom, visible par les autres joueurs du mod.
- **Wings** (6) : Angel, Paranoia, Demon, Raven, Monarch (papillon), Cursed wings.
- **Auras** (6) avec particules : Inferno, Storm, Spectre, Void, Stars, Hearts.
- **Head** (10) : halos Halo, Paranoia crown, Frost crown ; chapeaux Neon glasses, Royal crown, Pharaoh headdress, Top hat, Witch hat, Demon horns, Cat ears.
- **Back** (4, modèles 3D) : Electric guitar, Katana, AK-47, Mace.
- **Pets** (12) : 4 méduses, UFO et UFO Mars, Verity / Falsity / Lovity / Negity (roulent au sol), Shenron (dragon serpent de 44 segments), **Mini skin**.
- **Shields** (5) : Obsidian, Paranoia, Royal, Frost, Blood.
- Qualité des cosmétiques (High / Balanced / Performance), interrupteurs d'affichage par type (capes, ailes, auras, tête, dos, pets, boucliers, traînées).

### Mini skin
Petite copie (taille réglable 25–70 %, tête agrandie 100–180 %) du skin d'un pseudo au choix qui marche à côté du joueur. C'est un vrai modèle joueur vanilla : on lui équipe casque, plastron, jambières, bottes, main principale et secondaire (sélecteur d'objets), une cape, des ailes, un accessoire de tête et de dos. Écran dédié avec aperçu 3D à faire tourner. Les autres joueurs voient le skin du mini ; son équipement reste local.

## 6. Social

- Connexion automatique au serveur Paranoia par la session Minecraft (challenge signé, jeton de session) ; le mot de passe n'est jamais lu.
- **Amis** : recherche par pseudo, demandes, acceptation ou refus, liste en ligne / hors ligne, présence « joue sur … » (serveur actuel, option de confidentialité), bouton rejoindre.
- **Messages privés** : pas de notification quand la conversation est déjà ouverte ; GIF animés (fichier, lien, recherche KLIPY intégrée via le serveur, clé `KLIPY_API_KEY`) ; texte, images (capture collée Ctrl+V, fichier, lien Tenor / Klipy / direct) conservées 7 jours, historique.
- Commandes : `/paranoia friends`, `/paranoia add <pseudo>`, `/paranoia accept`, `/paranoia ignore`, `/paranoia msg <pseudo> <texte>`.
- Lien Discord du compte (OAuth dans le navigateur) : statut affiché dans Settings › Account.
- **Pages de joueur** (style Steam : fond couleur / dégradé / image, cadre d'avatar (8 styles), titre choisi parmi ses badges, statut, liens YouTube/Twitch/TikTok/X/Instagram/GitHub, vitrine réglable, statistiques comptées par le mod (temps de jeu, kills, morts, K/D, meilleure série), badges calculés par le serveur avec rareté, mode public / amis, édition dans un tiroir avec aperçu en direct, images filtrées en linéaire ; `/paranoia profile [joueur]`, bouton Profile dans une conversation, carte profil de la Wardrobe) : bannière image ou GIF (512 Ko, 24 images), skin 3D avec 5 poses, bio, visuel + légende, compteur de vues et likes, historique des skins (enregistré par le serveur à partir de la première connexion avec cette version), onglet Discord optionnel (nom, avatar, décoration d'avatar, badges publics, bannière, couleur d'accent ; le statut et la bio Discord ne sont pas accessibles par OAuth). Édition sur place de sa propre page.
- **Pictures** : galerie des captures du dossier `screenshots` groupées par date (ce mois-ci, mois de l'année, mois des années passées), taille des miniatures et filtre favoris dans un rail à droite, visionneuse (copier dans le presse-papiers, partager à un ami en MP réduit à 1024 px, favori, télécharger, supprimer), éditeur (dessin 8 couleurs / 4 tailles, recadrage, annuler / rétablir, enregistrement d'une copie `_edit.png`).
- Notifications en jeu (ami en ligne, demande, message).

## 7. Packs de textures par objet

- Écran « Item Packs » (remplace Options › Resource Packs, option) : catalogue de textures par objet (épées, haches, toile d'araignée…), aperçu 3D, choix par objet et non par pack entier. Les armures sont prévisualisées sur un vrai porte-armure portant les textures du pack ; option « All armors » pour appliquer le même pack à toutes les armures (ou choix manuel par armure). Les blocs (ancres…) reprennent le blockstate et tous les modèles du pack, pas seulement la face du modèle d'objet.
- Codes de partage de sa sélection, import avec fusion (« Merge »), packs communautaires sur le serveur (publication, vues, likes, signalement).

## 8. Waypoints

- Touche J (menu), K (nouveau), U (zone), affichage / masquage.
- Labels dans le monde, blocs surlignés, zones, waypoint de mort automatique, distance, taille selon la distance, point simple au loin, visibilité à travers les murs, 5 styles (Text, Icon, Colored icon, Colored text, Simple), partage par code.
- Commande `/paranoia wp`.

## 9. Réglages (Settings)

Sections : Interface (menu titre, packs par objet, élytre-cape, menu dans le monde et sa taille, animations et leur vitesse, flou, étoiles, sons), Rendering (profil de performance Quality / Balanced / Max perf, Boost Light / Strong / Extreme, Boost automatique sous un seuil de FPS, herbes / fleurs / débris au sol masqués, carte graphique, upscaling FSR 1.0 avec qualité et netteté, statut DLSS), Cosmetics (affichage par type, qualité, particules), Waypoints, Theme (7 couleurs d'accent avec aperçu en direct), HUD, Profiles (jeux de réglages), Account (Discord), Social (nametags des autres, capes importées des autres, partage de sa cape, logo, serveur), Keys (clavier visuel, raccourcis du client et de chaque module), About (version, mise à jour).

## 10. Performance

- **Boost** : coupe en un clic les effets coûteux, trois niveaux, automatique quand les FPS restent sous un seuil pendant 8 s.
- **Bench** : `/paranoia bench [10-120]` ou bouton dans l'onglet Performance : FPS, 1 % et 0,1 % low, pics, GC, temps CPU par système du mod, enregistrement JFR, rapport dans `paranoia-bench/`.
- Architecture mesurée en A/B (voir `docs/PERFORMANCE.md`) : fonctionnalité coupée = coût nul, pas d'allocation par image, moins d'appels de dessin, instanciation GPU (Hand Aura), HUD en cache. Gains mesurés : −15 % de temps d'image sur la scène de référence, allocations du mod 302 → 14 Mo/s.
- Compatibilités : Sodium, Iris (packs de shaders : ciel, panneau, HUD, cosmétiques adaptés), VulkanMod (shaders transcrits, chemins de repli).

## 11. Serveur (Cloudflare Worker `paranoia-nametags`)

KV `PROFILES` + base D1 `paranoia-social`.
- `v1` : santé, profils publics (nametag, cosmétiques), mise à jour du mod (`/v1/update`, `/v1/update/jar`).
- `v2` : authentification par challenge signé Mojang, profil et synchro, recherche de joueurs, amis (demande, réponse, retrait), messages et historique, boîte de réception, présence en direct (`/v2/live`, `/v2/live/who`), Discord (start, callback, unlink), capes importées (envoi 512 Ko / 24 images, lecture, suppression, signalement), images de messages (PNG ≤ 1 Mo, 7 jours, expéditeur et destinataire seulement, limite horaire), packs communautaires (liste, fichier, like, vues, signalement, suppression).
- `v2/page` : pages de joueur (lecture par UUID ou pseudo avec comptage des vues, sauvegarde bio / pose / légende / Discord, bannière et visuel PNG ou GIF ≤ 512 Ko, like, historique des skins Mojang signalés par le mod) ; la liaison Discord enregistre aussi la bannière et la couleur d'accent.
- Tests locaux : `server/test/media_test.mjs` (32 cas) ; routes `page` vérifiées avec `wrangler dev` et deux comptes de dev.

## 12. Outils de développement

`DevWorld` (scénarios de captures automatiques par drapeau `-Daurora.dev…`), `AbTest`, `Prof`, `SelfTest` (ouverture de chaque écran), `tools/perf_run.sh`, `tools/ab_run.sh`, `tools/perf_report.py`, générateurs d'assets (`gen_icons.py`, `gen_capes.py`, `gen_cosmetics.py`, `gen_sky_equirect.py`…), `tools/install.sh`, `tools/publish_update.sh`.

## 13. Limites connues

- Mini skin : taille, tête, équipement et cosmétiques publiés dans le profil et vus par les autres joueurs du mod (les capes importées restent locales).
- Pas de navigation clavier (Tab) dans les menus.
- Import Klipy vérifié hors jeu seulement ; cape partagée et images en MP non testées de bout en bout entre deux comptes.
- WebP et vidéos refusés à l'import de cape.
- Sons de combat « Paranoia » (4 coups aléatoires) et sons de chute (petite / grosse) : fichiers `.ogg` embarqués ; la grosse chute est déduite de la hauteur de chute côté client (> 7 blocs).
- Pages de joueur : l'historique des skins ne remonte pas avant la première connexion avec 1.9.5 ; les routes serveur `v2/page` ne sont pas encore déployées en production (déploiement sur accord).
