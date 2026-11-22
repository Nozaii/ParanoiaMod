package gg.aurora.ui;

import gg.aurora.Settings;

/** Palette "violet nuit". La couleur d'accent est réglable dans les paramètres. */
public final class Theme {
    private Theme() {}

    public record Accent(String name, int color) {}

    public static final Accent[] ACCENTS = {
            new Accent("Violet", 0x9D7CFF),
            new Accent("Pink", 0xF27BD1),
            new Accent("Cyan", 0x5FD3F3),
            new Accent("Red", 0xF2545B),
            new Accent("Mint", 0x5EE6A8),
            new Accent("Amber", 0xF5B54A),
            new Accent("White", 0xE8E6F2),
    };

    public static final String NAME = "Paranoia";

    public static final int BG = 0x09071A;
    public static final int PANEL = 0x110E22;
    public static final int TILE = 0x15122A;
    public static final int ROW = 0x1A1630;
    public static final int ROW_HOVER = 0x28224A;
    public static final int TEXT = 0xF3F1FB;
    public static final int TEXT_DIM = 0x8D87AA;
    public static final int TEXT_MUTED = 0x5E5978;
    public static final int MOUNTAIN_BACK = 0x1B1636;
    public static final int MOUNTAIN_MID = 0x130F28;
    public static final int MOUNTAIN_FRONT = 0x0A0818;

    public static int accent() {
        int i = Settings.accent;
        return ACCENTS[i >= 0 && i < ACCENTS.length ? i : 0].color();
    }

    /** Couleur de survol des tuiles : accent assombri. */
    public static int tileHover() {
        return Draw.lerpColor(TILE, accent(), 0.32f);
    }

    /** Teinte de l'aurore : accent éclairci. */
    public static int aurora() {
        return Draw.lerpColor(accent(), 0xFFFFFF, 0.25f);
    }
}
