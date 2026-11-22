package gg.aurora.cosmetic;

/**
 * Objets portés dans le dos : modèles 3D procéduraux (pavés éclairés), en diagonale sur le dos comme
 * une bandoulière. Repère de l'objet : Y = longueur (haut de l'objet en +Y), X = largeur, Z = épaisseur
 * (+Z vers l'extérieur du dos). Une quarantaine de pavés par objet au plus.
 */
final class BackItems {
    private BackItems() {}

    private static final int CHROME = 0xC9CDD4, BLACK = 0x18181C;
    private static final float[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    static void draw(Mesh m, String id) {
        switch (id) {
            case "guitare" -> guitar(m);
            case "katana" -> katana(m);
            case "ak47" -> ak47(m);
            case "masse" -> mace(m);
            default -> {
            }
        }
    }

    /** Taille dans le dos (les modèles sont en blocs). */
    static float scale(String id) {
        return switch (id) {
            case "guitare", "ak47" -> 1.35f;
            case "masse" -> 1.25f;
            default -> 1.15f;
        };
    }

    /** Inclinaison dans le dos (degrés autour de Z) et longueur, pour placer l'objet. */
    static float tilt(String id) {
        return switch (id) {
            case "katana" -> -38;
            case "ak47" -> 34;
            case "masse" -> -30;
            default -> 32;
        };
    }

    // ------------------------------------------------------------------ guitare électrique (type Strat)
    private static void guitar(Mesh m) {
        int red = 0xD3202E, redDark = 0x9C1621, white = 0xF2F0EA, maple = 0xE2B77A, rose = 0x3B2418;
        // caisse : corps, bas arrondi, deux cornes asymétriques
        m.box(-0.17f, -0.46f, -0.032f, 0.17f, -0.13f, 0.032f, red);
        m.box(-0.14f, -0.50f, -0.032f, 0.14f, -0.46f, 0.032f, red);
        m.box(-0.10f, -0.52f, -0.030f, 0.10f, -0.50f, 0.030f, redDark);
        m.box(-0.17f, -0.13f, -0.032f, -0.09f, 0.0f, 0.032f, red);
        m.box(0.08f, -0.13f, -0.032f, 0.15f, -0.05f, 0.032f, red);
        // pickguard, 3 micros, chevalet, boutons
        m.box(-0.11f, -0.41f, 0.032f, 0.09f, -0.13f, 0.037f, white);
        for (int i = 0; i < 3; i++) {
            float y = -0.37f + i * 0.075f;
            m.box(-0.05f, y, 0.037f, 0.05f, y + 0.026f, 0.046f, BLACK);
        }
        m.box(-0.06f, -0.44f, 0.032f, 0.06f, -0.415f, 0.047f, CHROME);
        m.box(0.10f, -0.40f, 0.032f, 0.125f, -0.375f, 0.05f, CHROME);
        m.box(0.10f, -0.35f, 0.032f, 0.125f, -0.325f, 0.05f, CHROME);
        // manche, touche, frettes
        m.box(-0.026f, -0.13f, -0.02f, 0.026f, 0.42f, 0.02f, maple);
        m.box(-0.028f, -0.13f, 0.02f, 0.028f, 0.42f, 0.028f, rose);
        for (int i = 0; i < 12; i++) {
            float y = -0.10f + i * 0.042f;
            m.box(-0.028f, y, 0.028f, 0.028f, y + 0.004f, 0.031f, CHROME);
        }
        // tête et mécaniques
        m.box(-0.03f, 0.42f, -0.016f, 0.05f, 0.52f, 0.016f, maple);
        for (int i = 0; i < 6; i++) {
            float y = 0.43f + i * 0.015f;
            m.box(0.05f, y, -0.006f, 0.068f, y + 0.008f, 0.006f, CHROME);
        }
        // cordes
        for (int i = 0; i < 4; i++) {
            float x = -0.018f + i * 0.012f;
            m.box(x, -0.43f, 0.032f, x + 0.0025f, 0.43f, 0.034f, 0xDDE1E6);
        }
    }

    // ------------------------------------------------------------------ katana dans son fourreau
    private static void katana(Mesh m) {
        int lacquer = 0x15131A, gold = 0xC9A23A, goldDark = 0x8F6E22, ray = 0xE8E4DC, cord = 0x5A2A8A;
        // fourreau (saya) : laqué noir, embout et col dorés, cordon violet
        m.box(-0.022f, -0.52f, -0.015f, 0.022f, 0.18f, 0.015f, lacquer);
        m.box(-0.024f, -0.56f, -0.017f, 0.024f, -0.52f, 0.017f, gold);
        m.box(-0.025f, 0.15f, -0.018f, 0.025f, 0.18f, 0.018f, gold);
        m.box(-0.026f, 0.06f, -0.019f, 0.026f, 0.085f, 0.019f, cord);
        // garde (tsuba) et habaki
        m.box(-0.055f, 0.18f, -0.042f, 0.055f, 0.196f, 0.042f, goldDark);
        m.box(-0.026f, 0.196f, -0.019f, 0.026f, 0.212f, 0.019f, gold);
        // poignée (tsuka) : peau claire et tressage noir en losanges
        m.box(-0.021f, 0.212f, -0.017f, 0.021f, 0.47f, 0.017f, ray);
        for (int k = 0; k < 6; k++) {
            float y = 0.218f + k * 0.042f;
            m.box(-0.0235f, y, -0.0195f, 0.0235f, y + 0.026f, 0.0195f, BLACK);
        }
        m.box(-0.012f, 0.32f, 0.0195f, 0.012f, 0.345f, 0.023f, gold); // menuki
        m.box(-0.024f, 0.47f, -0.019f, 0.024f, 0.49f, 0.019f, gold); // kashira
    }

    // ------------------------------------------------------------------ AK-47 (profil vers l'extérieur, dessus en +X)
    private static void ak47(Mesh m) {
        int wood = 0x8A4A1C, woodDark = 0x6A3614, steel = 0x2A2B2E, bakelite = 0x7A3A14;
        // crosse
        m.box(-0.06f, -0.48f, -0.022f, 0.035f, -0.40f, 0.022f, wood);
        m.box(-0.04f, -0.40f, -0.02f, 0.03f, -0.22f, 0.02f, wood);
        m.box(-0.062f, -0.49f, -0.023f, 0.037f, -0.48f, 0.023f, woodDark);
        // boîte de culasse, couvercle, levier
        m.box(-0.03f, -0.22f, -0.022f, 0.035f, 0.06f, 0.022f, steel);
        m.box(0.035f, -0.20f, -0.018f, 0.045f, 0.05f, 0.018f, 0x34363A);
        m.box(0.0f, -0.02f, 0.022f, 0.012f, 0.0f, 0.04f, steel);
        // poignée pistolet et pontet
        m.box(-0.11f, -0.20f, -0.017f, -0.03f, -0.155f, 0.017f, woodDark);
        m.box(-0.065f, -0.15f, -0.006f, -0.03f, -0.10f, 0.006f, steel);
        // chargeur courbé (vers l'avant en descendant)
        m.box(-0.10f, -0.10f, -0.016f, -0.03f, -0.035f, 0.016f, bakelite);
        m.box(-0.155f, -0.085f, -0.016f, -0.10f, -0.015f, 0.016f, bakelite);
        m.box(-0.205f, -0.06f, -0.016f, -0.155f, 0.012f, 0.016f, bakelite);
        // garde-main bois (bas) et tube de gaz (haut)
        m.box(-0.036f, 0.06f, -0.024f, 0.016f, 0.25f, 0.024f, wood);
        m.box(0.016f, 0.06f, -0.016f, 0.042f, 0.27f, 0.016f, wood);
        m.box(-0.036f, 0.24f, -0.025f, 0.016f, 0.255f, 0.025f, steel);
        // canon, guidon, frein de bouche, hausse
        m.box(-0.008f, 0.25f, -0.009f, 0.012f, 0.46f, 0.009f, steel);
        m.box(0.012f, 0.40f, -0.012f, 0.05f, 0.43f, 0.012f, steel);
        m.box(-0.012f, 0.45f, -0.012f, 0.016f, 0.49f, 0.012f, BLACK);
        m.box(0.035f, 0.05f, -0.012f, 0.052f, 0.085f, 0.012f, steel);
    }

    // ------------------------------------------------------------------ masse (Minecraft)
    private static void mace(Mesh m) {
        int handle = 0x3A3D44, grip = 0x5C3A21, steel = 0x8C93A3, light = 0xB7BDC9, band = 0x6B5536;
        m.box(-0.03f, -0.44f, -0.03f, 0.03f, -0.39f, 0.03f, steel); // pommeau
        m.box(-0.021f, -0.39f, -0.021f, 0.021f, 0.18f, 0.021f, handle);
        for (int k = 0; k < 5; k++) {
            float y = -0.36f + k * 0.05f;
            m.box(-0.024f, y, -0.024f, 0.024f, y + 0.028f, 0.024f, grip);
        }
        m.box(-0.05f, 0.165f, -0.05f, 0.05f, 0.19f, 0.05f, band);
        // tête : bloc lourd, pointes sur les 4 côtés, bouton
        m.box(-0.075f, 0.19f, -0.075f, 0.075f, 0.34f, 0.075f, steel);
        m.box(-0.08f, 0.24f, -0.08f, 0.08f, 0.29f, 0.08f, light);
        for (float[] d : DIRS) {
            float x = d[0] * 0.075f, z = d[1] * 0.075f;
            m.rod(Mesh.p(x, 0.265f, z), Mesh.v(x + d[0] * 0.07f, 0.265f, z + d[1] * 0.07f), 0.035f, 0.004f, light, 0xE6EAF0);
        }
        m.box(-0.03f, 0.34f, -0.03f, 0.03f, 0.38f, 0.03f, light);
    }
}
