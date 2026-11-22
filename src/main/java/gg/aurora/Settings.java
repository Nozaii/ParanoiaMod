package gg.aurora;

/** Paramètres globaux du menu et du HUD (sauvegardés dans config/aurora.json, section "settings"). */
public final class Settings {
    private Settings() {}

    public enum HudStyle {
        GLASS("Glass"), SOLID("Full"), MINIMAL("Minimal"), CLEAN("Clean");
        public final String label;

        HudStyle(String label) {
            this.label = label;
        }
    }

    // Interface
    public static boolean animations = true;
    public static boolean customTitle = true;
    public static boolean blur = true;
    public static float animSpeed = 1f;      // 0.5 .. 2
    public static boolean stars = true;
    public static boolean shootingStars = true;
    public static boolean sounds = true;
    /** Télécharge les nouvelles versions du mod (installées à la fermeture du jeu). */
    public static boolean autoUpdate = true;
    /** Style d'animation des menus : 0 = aucune, 1 = minimaliste, 2 = par défaut, 3 = cinématique. */
    public static int menuAnim = 2;
    public static final String[] MENU_ANIMS = {"None", "Minimal", "Default", "Cinema"};

    /** Les animations des menus sont-elles actives ? */
    public static boolean anim() {
        return animations && menuAnim != 0;
    }

    /** Multiplicateur de vitesse selon le style (minimaliste = plus vif, cinématique = plus posé). */
    public static float animFactor() {
        return animSpeed * (menuAnim == 1 ? 1.5f : menuAnim == 3 ? 0.8f : 1f);
    }

    // Thème
    public static int accent = 0;            // index dans Theme.ACCENTS

    // HUD
    public static HudStyle hudStyle = HudStyle.CLEAN;
    /** Version du style HUD par défaut déjà appliquée (passage au style épuré). */
    public static int hudStyleVersion = 0;
    /** Dernière migration de config appliquée. */
    public static int migration = 0;
    public static float hudOpacity = 0.7f;   // 0.2 .. 1
    public static boolean hudShadow = true;
    public static boolean hudAccentIcons = true;

    // Wardrobe
    public static String cape = "";          // "" = aucune
    public static String wings = "";
    public static String aura = "";
    public static String halo = "";
    /** Familier (méduse…), visible pour toi. */
    public static String pet = "";
    /** Design de bouclier (id), "" = vanilla. */
    public static String shieldDesign = "";
    public static boolean auraParticles = true;
    /** Qualité des cosmétiques : 0 = haute, 1 = équilibrée, 2 = performance. */
    public static int cosmeticQuality = 1;
    /** Modules déjà coupés automatiquement pour doublon avec un autre mod (ids séparés par des virgules). */
    public static String autoDisabled = "";
    /** Modules favoris du menu (ids séparés par des virgules). */
    public static String favorites = "";
    /** Packs par objet : "cat:swords=Pack.zip;item:minecraft:cobweb=Autre.zip". */
    public static String itemPacks = "";
    /** Le bouton « Packs de ressources » ouvre l'écran par objet. */
    public static boolean itemPackMenu = true;
    /** Options › Key Binds opens the visual keyboard. */
    public static boolean visualKeyboard = true;
    /** Elytra use the cape pattern (vanilla behaviour); off = normal elytra texture. */
    public static boolean elytraCape = false;
    /** Last one-time config repair applied. */
    public static int migrated = 0;
    /** Auto GG: kill messages switched off (csv of translation keys). */
    public static String autoGgOff = "";
    /** Custom Sky: file name of the sky image in config/aurora/skies. */
    public static String skyImage = "";
    /** Auto GG : messages et déclencheurs. */
    public static String autoGg1 = "gg", autoGg2 = "", autoGg3 = "";
    public static String autoGgTriggers = gg.aurora.module.AutoGG.DEFAULT_TRIGGERS;
    /** Menu, garde-robe et amis affichés sur un panneau fixé dans le monde (sinon écran classique). */
    public static boolean worldMenu = false;
    public static double worldMenuScale = 1.0;
    /** Objet porté dans le dos (guitare, masse, katana, AK-47), vide = aucun. */
    public static String back = "";
    /** Réglages graphiques : végétation dessinée ou non (les blocs restent là). */
    public static boolean showGrass = true, showFlowers = true, showLitter = true;
    /** Galerie Pictures : favoris (noms de fichiers séparés par |) et taille des miniatures (0 petit, 1 moyen, 2 grand). */
    public static String shotFavorites = "";
    public static int shotThumb = 1;
    /** Item packs : choisir un pack pour une armure l'applique à toutes les armures. */
    public static boolean armorPacksLinked = true;
    /** Cosmétiques affichés, par type (les miens et ceux des autres). */
    public static boolean showCapes = true, showWings = true, showAuras = true, showHead = true, showBack = true, showPets = true,
            showShields = true, showTrails = true;
    /** Ma cape importée est envoyée au serveur pour que les autres la voient. */
    public static boolean shareImportedCape = true;
    /** Capes importées des autres joueurs : 0 tous, 1 amis seulement, 2 personne. */
    public static int importedCapes = 0;
    /** Mini skin (familier) : pseudo Minecraft dont on affiche le skin. */
    public static String petSkin = "";
    /** Mini skin : tête agrandie, taille, inventaire (ids d'objets) et cosmétiques du mini. */
    public static double miniHead = 1.3, miniSize = 0.4;
    public static String miniHelmet = "", miniChest = "", miniLegs = "", miniBoots = "", miniMain = "", miniOff = "";
    public static String miniCape = "", miniWings = "", miniHalo = "", miniBack = "";

    // Waypoints
    public static boolean wpEnabled = true;
    public static float wpLabelScale = 1f;      // 0.5 .. 2
    public static boolean wpShowDistance = true;
    /** L'étiquette rapetisse avec la distance ; au-delà de wpDotDistance (0 = jamais) elle devient un point. */
    public static boolean wpDistScale = true;
    public static int wpDotDistance = 150;
    public static boolean wpHideNear = true;     // masque l'étiquette à moins de 4 blocs
    public static int wpMaxDistance = 0;         // 0 = illimité
    public static boolean wpDeath = true;        // waypoint temporaire à la mort
    public static int wpDefaultPreset = 0;       // index dans Waypoint.PRESETS
    public static float wpFill = 0.10f;
    public static float wpOutline = 1f;
    public static boolean wpThroughWalls = false;
    /** Noms des waypoints : 0 toujours, 1 seulement quand on le vise, 2 jamais. */
    public static int wpNames = 0;
    /** Off-screen waypoints stay pinned to the screen edge with an arrow. */
    public static boolean wpEdge = false;

    // Pings
    public static boolean pingEnabled = true;
    /** Pings from other Paranoia players: 0 everyone nearby, 1 friends only, 2 nobody. */
    public static int pingFrom = 0;
    /** Share my pings with the Paranoia players around me. */
    public static boolean pingShare = true;
    public static boolean pingSound = true;
    public static int pingSeconds = 8;

    // Rendu : upscaling
    public static int perfProfile = 1;
    /** Boost : niveau (0 léger, 1 fort, 2 extrême), activation auto sous un seuil de FPS. */
    public static int boostLevel = 1;
    public static boolean boostAuto = false;
    public static int boostThreshold = 60;       // index dans PerfProfiles.NAMES
    public static int upscaler = 0;          // 0 = désactivé, 1 = FSR 1.0
    public static int fsrQuality = 1;        // index dans Upscaler.QUALITIES
    public static float fsrSharpness = 0.8f; // 0 .. 1
    /** Windows : préférence « GPU hautes performances » pour le Java du jeu (voir JvmTuning). */
    public static boolean dedicatedGpu = false;

    // Nametag
    public static boolean nametagEnabled = true;
    public static String nametagColors = "1C1C1C,FFFFFF";   // 1 à 4 couleurs hex
    public static String nametagMode = "scroll";            // static / scroll / pulse / wave
    public static float nametagSpeed = 1f;                  // 0.1 .. 5
    public static boolean nametagLogo = true;

    // Social
    public static boolean showOthers = true;
    public static boolean showLogos = true;
    public static String apiUrl = "";

    // Amis
    public static boolean friendNotify = true;
    public static boolean friendStar = true;
    public static boolean shareServer = true;
    public static boolean notifyRequests = true;
    public static boolean notifyMessages = true;
    /** Jeton de session du serveur social (obtenu après vérification Mojang). */
    public static String socialToken = "";
    /** Dernier message privé déjà vu (pour annoncer ceux reçus hors ligne). */
    public static String socialLastMessage = "0";
}
