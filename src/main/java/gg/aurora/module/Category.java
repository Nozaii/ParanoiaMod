package gg.aurora.module;

import gg.aurora.ui.Icons;

public enum Category {
    PVP("PvP", Icons.SHIELD),
    HUD("HUD", Icons.HUD),
    VISUALS("Visuals", Icons.WAND),
    PLAYER("Player", Icons.CHEVRONS),
    MISC("Misc", Icons.SLIDERS);

    public final String label;
    public final String icon;

    Category(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }
}
