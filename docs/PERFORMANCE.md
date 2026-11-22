# Performances de Paranoia : mesures et choix d'architecture

## Outils de mesure

| Outil | Commande | Ce qu'il mesure |
|---|---|---|
| Bench en jeu | `/paranoia bench [10-120]` | FPS, 1 % / 0,1 % low, pics, GC, **temps CPU par système Paranoia** (`Prof`), puis un enregistrement JFR (échantillons CPU 2 ms, allocations). Rapport dans `paranoia-bench/`. |
| Scène fixe (dev) | `sh tools/perf_run.sh user\|off <nom>` | Bench sur une scène reproductible (`DevWorld`, `-Daurora.devprof`) + analyse JFR (`tools/perf_report.py`) : part Paranoia du thread de rendu, points d'entrée, allocations par méthode. |
| A/B même session (dev) | `sh tools/ab_run.sh <test>` | Alterne deux implémentations toutes les 120 images (`AbTest`) : moyenne, médiane, 1 % pire, intervalle de confiance. C'est la seule mesure fiable pour des écarts de quelques dizaines de µs : d'un run à l'autre, les FPS varient de ±15 % sur cette machine. Bruit mesuré (A contre A) : 4 µs sur la médiane. |

Règle : un changement ne reste que si l'A/B montre un gain, ou au moins pas de perte, **et** que l'image est identique (captures avant/après comparées pixel par pixel).

## Résultats (scène « user », 1600×900, RTX 3070)

| Changement | Mesure | Résultat |
|---|---|---|
| Hand Aura instanciée (1 silhouette, 36 instances GPU au lieu de 36 copies CPU) | A/B | **−234 µs/image** (médiane 1 751 → 1 517 µs), image identique (écart moyen 0,00/255) |
| Familier : rubans face caméra (1 quad au lieu de 2 croisés, même largeur et luminosité apparentes) | A/B | −37 µs (médiane) ; luminosité de la zone : 0,3/255 d'écart |
| Ensemble des anciens chemins remplacés (familier, Hand Aura, ciel) | A/B | **−235 µs/image** (médiane 1 576 → 1 341 µs, ≈ −15 % de temps d'image) |
| Hand Aura : itérateur alloué 36 fois par objet et par image | JFR | allocations Paranoia **302 → 14 Mo/s (−95 %)** |
| Ciel : cube (24 sommets) au lieu d'une sphère (2 048) | A/B | pas de gain mesurable (le coût du ciel est l'appel de dessin et le shader) ; image identique. Conservé (moins de travail, aucune perte). |
| Uniforms des passes plein écran : buffer persistant / anneau mappé / recréé | A/B | recréé = le plus rapide ici (persistant +60 µs, anneau +144 µs : le pilote attend le GPU). On garde « recréé », avec un tampon CPU réutilisé. |
| Traînée : texte formaté et découpé à chaque image | code | remplacé par une lecture directe des options + cache borné des codes des autres joueurs |
| Familier, aura, traînée quand personne ne les porte | `Prof` (scène off) | sortie immédiate (drapeau « porté par quelqu'un » recalculé 20 fois/s) |
| Lumière additive de tous les effets du monde (aura, familier, traînée, particules d'ambiance, flammes, effets de coup, cercles, bulles) regroupée en un lot par texture, vidé une fois en fin de `AFTER_ENTITIES` (`FxBatch`) | A/B ×2 | médiane **−12 µs**, moyenne −6 µs (scène « user » + particules/flammes). Gain proportionnel au nombre de systèmes actifs. Image : écart identique au témoin (0,34 contre 0,32/255), sauf la lumière située derrière la cloche du familier, désormais cachée par elle. |
| Méduse : chaque point transformé une seule fois (grille de la cloche partagée par 4 quads ; extrémités de tentacule partagées entre segments ; coins de ruban = extrémité ± largeur × côté tourné) au lieu de chaque coin de quad (`PetFx.legacyVerts`) | `Prof` + A/B | familier **127 → 82 µs** (lumière 63 → 36, cloche 33 → 16, envoi GPU 25 inchangé). Temps d'image : médiane −10,7 µs, moyenne −4,3 µs (±22) : le thread de rendu attend aussi le GPU. Image : écart 0,070/255 contre 0,081/255 pour le témoin ancien/ancien (`-Daurora.devjelly=true`). |
| Sommets lumineux en un seul appel `addVertex(...)` au lieu de 6 appels chaînés | A/B | aucun gain (−5,6 µs en faveur de l'ancien, dans le bruit). Non conservé. |

## Coût restant par fonctionnalité (A/B : tout actif contre sans elle)

- Familier méduse : ≈80 µs CPU (Prof) : lumière 36, cloche 16, envoi GPU 25 (tri de la cloche translucide) ; ≈2 000 sommets.
- Hand Aura : ≈75 µs (seconde passe vanilla de la main pour produire la silhouette).
- Sol miroir : ≈50-60 µs CPU (préparation 22, passe 25, copie 24) + GPU. La copie est obligatoire : le shader lit l'image qu'il modifie.
- Chaque `RenderType.draw` ouvre une passe de rendu (15 à 40 µs de CPU) : regrouper les géométries par type de rendu est le levier principal.

## Architecture : règles qui en découlent

1. **Une fonctionnalité coupée ne coûte rien** : tester l'activation en premier, avant toute recherche (joueurs, maps, matrices).
2. **Pas de chaîne de caractères ni d'allocation dans une boucle par image** : relire les options directement, réutiliser les vecteurs et matrices de travail.
3. **Moins d'appels de dessin plutôt que moins de sommets** : fusionner les géométries qui partagent un type de rendu (lumière + halo du familier : un seul type additif, texture `glow` à centre blanc pur).
4. **Copies identiques d'une géométrie → instanciation** (Hand Aura). Uniquement en OpenGL sans pack Iris : sous VulkanMod (tableaux d'uniforms non supportés par son convertisseur) et avec un pack de shaders, l'ancien chemin est utilisé.
5. **Toujours mesurer en A/B** : plusieurs « optimisations » évidentes (anneau de buffers, cube du ciel) n'ont rien gagné ou ont coûté.

## Instruments laissés dans le code (coût nul hors mesure)

- `Prof.start()/stop()` sur chaque système : un test de booléen hors bench.
- Interrupteurs de comparaison dev : `PetFx.legacyRibbons`, `HandGlow.legacy`, `SkyFx.legacySphere`, `UniformRing.mode`, `FxBatch.legacy`, `PetFx.legacyVerts`, utilisés par `AbTest` (`sh tools/ab_run.sh fxbatch`, capture figée : `-Daurora.devfxcmp=true`).

## JVM et carte graphique (repris de OneLauncher)

Source : launcher [Polyfrost/OneLauncher](https://github.com/Polyfrost/OneLauncher) (`oneclient_mc/src/arguments.rs`, `oneclient_common/src/memory.rs`, `oneclient_java/src/platform.rs`). Ses optimisations portent sur le lancement de Java, pas sur le rendu. Portage : `JvmTuning`, réglages *Rendering*.

| Méthode OneLauncher | Dans Paranoia |
|---|---|
| G1 + `ParallelRefProcEnabled` ; ZGC générationnel si Java ≥ 21, 64 bits et tas ≥ 8 Go ; `UseCompactObjectHeaders` dès Java 24 ; rien si un ramasse-miettes est déjà choisi | « Restart with optimized JVM » : redémarre (via `ModSwap`) avec les options manquantes ajoutées devant celles du launcher. « Copy optimized JVM arguments » : ligne à coller dans le launcher (permanent). |
| `-Xms512M`, tas max par défaut selon la RAM (2 / 3 / 4 Go sous 7 / 11 Go / au-delà) | idem, seulement si le launcher ne donne pas de `-Xmx` / `-Xms` |
| `--add-modules=jdk.incubator.vector` (Java 21+) | idem |
| Windows : `GpuPreference=2;` pour java.exe et javaw.exe dans `HKCU\Software\Microsoft\DirectX\UserGpuPreferences` | toggle « Dedicated GPU (Windows) », désactivé par défaut ; le retirer ne supprime que la valeur « GPU dédié » |
| Linux : variables PRIME offload | non repris (le choix se fait avant le lancement) |

Une JVM lancée ne change pas de ramasse-miettes : rien ne s'applique à chaud. Test dev : `-Daurora.devjvm=true` (1er lancement relance optimisé, le 2e écrit `run/devjvm.txt`). En dev, le `ModSwap` détaché est tué avec l'arbre de processus de Gradle ; lancé à la main sur le même plan, la relance fonctionne (`missing []`).
