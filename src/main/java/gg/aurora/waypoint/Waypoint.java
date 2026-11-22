package gg.aurora.waypoint;

import java.util.UUID;

/** Un waypoint : position, apparence de l'étiquette, affichage du bloc. Sérialisé tel quel en JSON. */
public class Waypoint {
    /** Styles d'étiquette (comme les préréglages de wWaypoints). */
    public static final String[] PRESETS = {"Text", "Icon", "Colored icon", "Colored text", "Simple"};
    public static final String[] ICON_TYPES = {"None", "Block / item", "Letter"};

    public String id = UUID.randomUUID().toString().substring(0, 8);
    public String name = "Waypoint";
    public int x, y, z;
    public String dim = "minecraft:overworld";
    public int color = 0x4DA3FF;
    public String folder = "";
    public boolean visible = true;
    public int preset = 0;
    /** 0 = aucune, 1 = bloc / objet (icon = id d'objet), 2 = lettre (initiale du nom). */
    public int iconType = 1;
    public String icon = "minecraft:grass_block";
    public boolean block = true;
    public boolean walls = false;
    public float fill = 0.10f;
    public float outline = 1f;
    /** Waypoint temporaire (mort) : supprimé quand on l'atteint. */
    public boolean temp;
    /** Waypoint de zone : boîte de (x, y, z) à (x2, y2, z2) inclus. */
    public boolean area;
    public int x2, y2, z2;

    public int minX() { return Math.min(x, area ? x2 : x); }
    public int minY() { return Math.min(y, area ? y2 : y); }
    public int minZ() { return Math.min(z, area ? z2 : z); }
    public int maxX() { return Math.max(x, area ? x2 : x); }
    public int maxY() { return Math.max(y, area ? y2 : y); }
    public int maxZ() { return Math.max(z, area ? z2 : z); }

    /** Taille de la zone en blocs, ex. « 12 × 4 × 9 ». */
    public String sizeText() {
        return (maxX() - minX() + 1) + " × " + (maxY() - minY() + 1) + " × " + (maxZ() - minZ() + 1);
    }

    public Waypoint copy() {
        Waypoint w = new Waypoint();
        w.name = name;
        w.x = x;
        w.y = y;
        w.z = z;
        w.dim = dim;
        w.color = color;
        w.folder = folder;
        w.visible = visible;
        w.preset = preset;
        w.iconType = iconType;
        w.icon = icon;
        w.block = block;
        w.walls = walls;
        w.fill = fill;
        w.outline = outline;
        w.area = area;
        w.x2 = x2;
        w.y2 = y2;
        w.z2 = z2;
        return w;
    }

    public static class Folder {
        public String id = UUID.randomUUID().toString().substring(0, 8);
        public String name = "Folder";
        public int color = 0xFFB43A;
        public boolean visible = true;
        public boolean locked;
        public boolean collapsed;
    }
}
