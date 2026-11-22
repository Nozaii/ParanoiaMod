package gg.aurora;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Pack de ressources par objet : pour chaque catégorie (épées, haches, toile…) ou objet choisi, on prend
 * la définition, les modèles et les textures de cet objet dans le pack voulu. Une couche ajoutée tout en
 * haut de la pile ne sert que ces fichiers : le reste vient des packs activés normalement.
 */
public final class ItemPacks {
    private ItemPacks() {}

    /** Une case du catalogue : un ou plusieurs objets qui partagent le même choix de pack. */
    public record Category(String key, String name, List<String> items) {
        public net.minecraft.world.item.ItemStack icon() {
            return new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(items.get(0))));
        }
    }

    public record Mode(String name, String desc, String icon, List<String> tiles) {}

    public static final List<Category> CATEGORIES = List.of(
            cat("crystal", "Crystal", "end_crystal"),
            cat("obsidian", "Obsidian", "obsidian"),
            cat("anchor", "Anchor", "respawn_anchor"),
            cat("glowstone", "Glowstone", "glowstone"),
            cat("totem", "Totem", "totem_of_undying"),
            cat("netherite_armor", "Netherite armor", "netherite_chestplate", "netherite_helmet", "netherite_leggings", "netherite_boots"),
            cat("diamond_armor", "Diamond armor", "diamond_chestplate", "diamond_helmet", "diamond_leggings", "diamond_boots"),
            cat("iron_armor", "Iron armor", "iron_chestplate", "iron_helmet", "iron_leggings", "iron_boots"),
            cat("gold_armor", "Gold armor", "golden_chestplate", "golden_helmet", "golden_leggings", "golden_boots"),
            cat("copper_armor", "Copper armor", "copper_chestplate", "copper_helmet", "copper_leggings", "copper_boots"),
            cat("chain_armor", "Chainmail armor", "chainmail_chestplate", "chainmail_helmet", "chainmail_leggings", "chainmail_boots"),
            cat("leather_armor", "Leather armor", "leather_chestplate", "leather_helmet", "leather_leggings", "leather_boots"),
            cat("gapple", "Golden apple", "golden_apple", "enchanted_golden_apple"),
            cat("pearl", "Pearl", "ender_pearl"),
            cat("xp", "XP bottle", "experience_bottle"),
            cat("cobweb", "Cobweb", "cobweb"),
            cat("ender_chest", "Ender chest", "ender_chest"),
            cat("shulker", "Shulker", "shulker_box"),
            cat("swords", "Swords", "netherite_sword", "diamond_sword", "iron_sword", "golden_sword", "stone_sword", "wooden_sword", "copper_sword"),
            cat("axes", "Axes", "netherite_axe", "diamond_axe", "iron_axe", "golden_axe", "stone_axe", "wooden_axe", "copper_axe"),
            cat("mace", "Mace", "mace"),
            cat("wind_charge", "Wind charge", "wind_charge"),
            cat("elytra", "Elytra", "elytra"),
            cat("firework", "Rocket", "firework_rocket"),
            cat("shield", "Shield", "shield"),
            cat("bow", "Arc", "bow"),
            cat("crossbow", "Crossbow", "crossbow"),
            cat("arrow", "Arrows", "arrow", "spectral_arrow", "tipped_arrow"),
            cat("trident", "Trident", "trident"),
            cat("potions", "Potions", "splash_potion", "potion", "lingering_potion"),
            cat("steak", "Steak", "cooked_beef"),
            cat("carrot", "Golden carrot", "golden_carrot"),
            cat("tnt_cart", "TNT minecart", "tnt_minecart"),
            cat("rail", "Rails", "rail", "activator_rail", "powered_rail", "detector_rail"),
            cat("flint", "Flint and steel", "flint_and_steel"),
            cat("lava", "Lava bucket", "lava_bucket"),
            cat("water", "Water bucket", "water_bucket"),
            cat("tools", "Pickaxes", "netherite_pickaxe", "diamond_pickaxe", "iron_pickaxe", "golden_pickaxe", "stone_pickaxe", "wooden_pickaxe"));

    public static final List<Mode> MODES = List.of(
            new Mode("Crystal PvP", "Crystals, obsidian, anchors and totems", "end_crystal",
                    List.of("crystal", "obsidian", "anchor", "glowstone", "totem", "netherite_armor", "gapple", "pearl", "xp", "cobweb", "ender_chest", "shulker")),
            new Mode("Sword PvP", "Swords, shields and healing", "diamond_sword", List.of("swords", "shield", "gapple", "pearl", "diamond_armor", "steak", "totem")),
            new Mode("Axe PvP", "Axes against shields", "diamond_axe", List.of("axes", "shield", "swords", "diamond_armor", "pearl", "gapple", "bow", "arrow")),
            new Mode("Netherite Pot", "Potions and netherite armor", "splash_potion",
                    List.of("potions", "netherite_armor", "swords", "pearl", "steak", "xp", "totem", "gapple")),
            new Mode("Mace PvP", "Mace, wind charges, elytra", "mace", List.of("mace", "wind_charge", "elytra", "firework", "pearl", "totem", "netherite_armor", "shield")),
            new Mode("Cart PvP", "TNT minecarts, rails and bows", "tnt_minecart", List.of("tnt_cart", "rail", "bow", "crossbow", "arrow", "flint", "lava")),
            new Mode("Weapons and armor", "Everything that hits or protects", "netherite_chestplate",
                    List.of("swords", "axes", "mace", "trident", "bow", "crossbow", "arrow", "shield", "netherite_armor", "diamond_armor", "iron_armor", "gold_armor", "copper_armor", "chain_armor", "leather_armor", "elytra")),
            new Mode("Blocks", "Blocks placed in combat", "obsidian", List.of("obsidian", "anchor", "glowstone", "cobweb", "ender_chest", "shulker", "rail")),
            new Mode("Items and tools", "Consumables and tools", "totem_of_undying",
                    List.of("totem", "gapple", "pearl", "xp", "potions", "steak", "carrot", "firework", "wind_charge", "water", "lava", "flint", "tnt_cart", "tools")));

    public static Map<String, String> mapping(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            int i = part.indexOf('=');
            if (i > 0 && i < part.length() - 1) out.put(part.substring(0, i), part.substring(i + 1));
        }
        return out;
    }

    public static Category tile(String name) {
        for (Category c : CATEGORIES) if (c.key().equals("cat:" + name)) return c;
        return null;
    }

    private static Category cat(String key, String name, String... items) {
        return new Category("cat:" + key, name, List.of(items));
    }

    // ------------------------------------------------------------------ réglage : "clé=pack;clé=pack"

    public static Map<String, String> mapping() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : Settings.itemPacks.split(";")) {
            int i = part.indexOf('=');
            if (i > 0 && i < part.length() - 1) out.put(part.substring(0, i), part.substring(i + 1));
        }
        return out;
    }

    public static void set(String key, String pack) {
        Map<String, String> m = mapping();
        if (pack == null || pack.isEmpty()) m.remove(key);
        else m.put(key, pack);
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ";").append(k).append('=').append(v));
        Settings.itemPacks = sb.toString();
    }

    // ------------------------------------------------------------------ partage : code à coller à un ami

    private static final String SHARE_PREFIX = "PARANOIAPACKS1:";

    /** Copies the current per-item choices as a share code. Returns false when there is nothing to share. */
    public static boolean copyShareCode() {
        Map<String, String> m = mapping();
        if (m.isEmpty()) return false;
        JsonObject map = new JsonObject();
        m.forEach(map::addProperty);
        JsonObject root = new JsonObject();
        root.add("map", map);
        try {
            var bo = new java.io.ByteArrayOutputStream();
            try (var z = new java.util.zip.GZIPOutputStream(bo)) {
                z.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            Minecraft.getInstance().keyboardHandler.setClipboard(SHARE_PREFIX + java.util.Base64.getEncoder().encodeToString(bo.toByteArray()));
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * Reads a share code from the clipboard and replaces the current choices.
     * @return the packs the code uses that are not in the resourcepacks folder, or null if the clipboard holds no valid code
     */
    public static List<String> importShareCode() {
        return importShareCode(false);
    }

    /** Items the last merge added, and items it left alone because you had already picked a pack for them. */
    public static int lastMergeAdded, lastMergeKept;

    /**
     * Same as {@link #importShareCode()}, but with merge = true the code only fills in the items you have
     * not set yet: your own choices win on every item both setups touch.
     */
    public static List<String> importShareCode(boolean merge) {
        String code = Minecraft.getInstance().keyboardHandler.getClipboard().trim();
        if (!code.startsWith(SHARE_PREFIX)) return null;
        Map<String, String> in = new LinkedHashMap<>();
        try (var z = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(code.substring(SHARE_PREFIX.length()))))) {
            JsonObject map = JsonParser.parseString(new String(z.readNBytes(1 << 20), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("map");
            for (var e : map.entrySet()) {
                String k = e.getKey(), v = e.getValue().getAsString();
                // keys and pack names end up in the "k=v;k=v" setting and in paths: keep them clean
                if (!k.matches("(cat|item):[a-z0-9_:./-]{1,80}") || v.isEmpty() || v.length() > 200 || v.contains(";") || v.contains("=")
                        || v.contains("/") || v.contains("\\") || v.contains("..")) continue;
                if (k.startsWith("cat:") && tile(k.substring(4)) == null) continue;
                in.put(k, v);
            }
        } catch (Exception e) {
            return null;
        }
        if (in.isEmpty()) return null;
        Set<String> have = new HashSet<>(available());
        List<String> missing = new ArrayList<>();
        if (merge) {
            Map<String, String> mine = mapping();
            lastMergeAdded = 0;
            lastMergeKept = 0;
            for (var e : in.entrySet()) {
                if (mine.containsKey(e.getKey())) {
                    if (!mine.get(e.getKey()).equals(e.getValue())) lastMergeKept++;
                    continue;
                }
                mine.put(e.getKey(), e.getValue());
                lastMergeAdded++;
                String v = e.getValue();
                if (!VANILLA.equals(v) && !have.contains(v) && !missing.contains(v)) missing.add(v);
            }
            in = mine;
        }
        StringBuilder sb = new StringBuilder();
        in.forEach((k, v) -> {
            sb.append(sb.isEmpty() ? "" : ";").append(k).append('=').append(v);
            if (!merge && !VANILLA.equals(v) && !have.contains(v) && !missing.contains(v)) missing.add(v);
        });
        Settings.itemPacks = sb.toString();
        return missing;
    }

    /** Objets visés par une clé de réglage (catégorie ou objet seul). */
    public static List<Identifier> itemsOf(String key) {
        if (key.startsWith("item:")) {
            Identifier id = Identifier.tryParse(key.substring(5));
            return id == null ? List.of() : List.of(id);
        }
        for (Category c : CATEGORIES) {
            if (c.key().equals(key)) return c.items().stream().map(Identifier::withDefaultNamespace).toList();
        }
        return List.of();
    }

    // ------------------------------------------------------------------ packs disponibles

    public static Path packDir() {
        return Minecraft.getInstance().getResourcePackDirectory();
    }

    public static List<String> available() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(packDir())) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p) ? Files.exists(p.resolve("pack.mcmeta")) : n.toLowerCase().endsWith(".zip")) out.add(n);
            });
        } catch (Exception ignored) {
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    public static PackResources open(String name) {
        Path p = packDir().resolve(name);
        if (!Files.exists(p) || !p.normalize().startsWith(packDir().normalize())) return null;
        PackLocationInfo info = new PackLocationInfo("aurora_item/" + name, Component.literal(name), PackSource.DEFAULT, Optional.empty());
        try {
            return Files.isDirectory(p) ? new PathPackResources.PathResourcesSupplier(p).openPrimary(info)
                    : new FilePackResources.FileResourcesSupplier(p).openPrimary(info);
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("Pack illisible : {}", name);
            return null;
        }
    }

    /** Le pack contient-il quelque chose pour un de ces objets ? (pour ne proposer que les packs utiles) */
    public static boolean covers(PackResources pack, List<Identifier> items) {
        for (Identifier it : items) {
            for (Identifier f : entityFiles(it)) if (pack.getResource(PackType.CLIENT_RESOURCES, f) != null) return true;
            if (pack.getResource(PackType.CLIENT_RESOURCES, it.withPath("items/" + it.getPath() + ".json")) != null
                    || pack.getResource(PackType.CLIENT_RESOURCES, it.withPath("models/item/" + it.getPath() + ".json")) != null
                    || preview(pack, it) != null) {
                return true;
            }
        }
        return false;
    }

    /** Texture représentative de l'objet dans ce pack (objet, sinon bloc), ou null. */
    public static Identifier preview(PackResources pack, Identifier it) {
        String p = it.getPath();
        for (String path : new String[]{"textures/item/" + p + ".png", "textures/block/" + p + ".png", "textures/block/" + p + "_top.png",
                "textures/block/" + p + "_side.png", "textures/block/" + p + "_front.png", "textures/item/" + p + "_standby.png"}) {
            Identifier id = it.withPath(path);
            if (pack.getResource(PackType.CLIENT_RESOURCES, id) != null) return id;
        }
        return viaModel(pack, it);
    }

    /** Blocks drawn as flat sprites in the inventory (cobweb, rails): no 3D cube. */
    public static boolean flatBlock(Identifier it) {
        String p = it.getPath();
        return p.equals("cobweb") || p.contains("rail");
    }

    /** Texture named by the pack's own item definition and models (for packs with custom paths). */
    private static Identifier viaModel(PackResources pack, Identifier it) {
        ArrayDeque<Identifier> models = new ArrayDeque<>();
        IoSupplier<InputStream> d = pack.getResource(PackType.CLIENT_RESOURCES, it.withPath("items/" + it.getPath() + ".json"));
        if (d != null) {
            JsonElement j = read(d);
            if (j != null) findModels(j, models);
        }
        models.add(it.withPath("item/" + it.getPath()));
        Set<Identifier> seen = new HashSet<>();
        while (!models.isEmpty() && seen.size() < 12) {
            Identifier m = models.poll();
            if (!seen.add(m)) continue;
            IoSupplier<InputStream> s = pack.getResource(PackType.CLIENT_RESOURCES, m.withPath("models/" + m.getPath() + ".json"));
            if (s == null || !(read(s) instanceof JsonObject o)) continue;
            if (o.has("textures") && o.get("textures").isJsonObject()) {
                for (var e : o.getAsJsonObject("textures").entrySet()) {
                    if (!e.getValue().isJsonPrimitive() || e.getValue().getAsString().startsWith("#")) continue;
                    Identifier t = Identifier.tryParse(e.getValue().getAsString());
                    if (t == null) continue;
                    Identifier f = t.withPath("textures/" + t.getPath() + ".png");
                    if (pack.getResource(PackType.CLIENT_RESOURCES, f) != null) return f;
                }
            }
            if (o.has("parent") && o.get("parent").isJsonPrimitive()) {
                Identifier p = Identifier.tryParse(o.get("parent").getAsString());
                if (p != null) models.add(p);
            }
        }
        return null;
    }

    private static final Map<String, Identifier> PREVIEWS = new HashMap<>();

    /** Aperçu chargé en texture (mis en cache), ou null si le pack n'a pas d'image pour cet objet. */
    /** Pseudo pack name: the game's own textures. */
    public static final String VANILLA = "\u0000vanilla";
    /** Loaded picture: width, frame height, frame count, ticks per frame. */
    private static final Map<Identifier, int[]> INFO = new HashMap<>();

    public static int[] info(Identifier tex) {
        return INFO.get(tex);
    }

    /** Picture of this item in the pack (cached), or null. Animated strips keep all their frames. */
    public static Identifier previewTexture(String packName, PackResources pack, Identifier item) {
        Identifier src = preview(pack, item);
        return src == null ? null : load(packName, pack, src);
    }

    /** Any texture of a pack as a GUI texture (cached), or null. */
    public static Identifier load(String packName, PackResources pack, Identifier src) {
        String key = packName + "|" + src;
        if (PREVIEWS.containsKey(key)) return PREVIEWS.get(key);
        Identifier out = null;
        IoSupplier<InputStream> sup = pack.getResource(PackType.CLIENT_RESOURCES, src);
        if (sup != null) {
            try (InputStream in = sup.get()) {
                var img = com.mojang.blaze3d.platform.NativeImage.read(in);
                int w = img.getWidth(), h = img.getHeight(), frames = 1, ticks = 1;
                IoSupplier<InputStream> meta = pack.getResource(PackType.CLIENT_RESOURCES, src.withSuffix(".mcmeta"));
                if (meta != null && h > w && h % w == 0) {
                    frames = h / w;
                    h = w;
                    JsonElement j = read(meta);
                    if (j instanceof JsonObject o && o.has("animation") && o.get("animation").isJsonObject()
                            && o.getAsJsonObject("animation").has("frametime")) {
                        ticks = Math.max(1, o.getAsJsonObject("animation").get("frametime").getAsInt());
                    }
                }
                out = Identifier.fromNamespaceAndPath("aurora", "dynamic/pack_preview/" + Integer.toHexString(key.hashCode()));
                Minecraft.getInstance().getTextureManager().register(out, new net.minecraft.client.renderer.texture.DynamicTexture(() -> key, img));
                INFO.put(out, new int[]{w, h, frames, ticks});
            } catch (Exception e) {
                out = null;
            }
        }
        PREVIEWS.put(key, out);
        return out;
    }

    private static final Map<String, net.minecraft.resources.ResourceKey<net.minecraft.world.item.equipment.EquipmentAsset>> ASSETS = new HashMap<>();
    private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.item.equipment.EquipmentAsset>, net.minecraft.client.resources.model.EquipmentClientInfo> ASSET_INFO = new HashMap<>();

    /** Infos d'équipement d'un asset d'aperçu (mixin EquipmentAssetManager), ou null si ce n'est pas le nôtre. */
    public static net.minecraft.client.resources.model.EquipmentClientInfo previewAssetInfo(net.minecraft.resources.ResourceKey<net.minecraft.world.item.equipment.EquipmentAsset> key) {
        return ASSET_INFO.get(key);
    }

    /**
     * Asset d'équipement dont les textures (corps + jambières) viennent du pack : une armure portée par un
     * vrai porte-armure se dessine alors exactement comme en jeu. Null si le pack n'a pas la texture du corps.
     */
    public static net.minecraft.resources.ResourceKey<net.minecraft.world.item.equipment.EquipmentAsset> previewAsset(String packName, PackResources pack, Identifier item) {
        String key = packName + "|" + item;
        if (ASSETS.containsKey(key)) return ASSETS.get(key);
        net.minecraft.resources.ResourceKey<net.minecraft.world.item.equipment.EquipmentAsset> out = null;
        List<Identifier> files = entityFiles(item);
        if (files.size() >= 2) {
            String hash = Integer.toHexString(key.hashCode());
            Identifier texId = Identifier.fromNamespaceAndPath("aurora", "pack/" + hash);
            boolean body = equipmentTexture(pack, files.get(0), texId.withPath("textures/entity/equipment/humanoid/pack/" + hash + ".png"));
            boolean legs = equipmentTexture(pack, files.get(1), texId.withPath("textures/entity/equipment/humanoid_leggings/pack/" + hash + ".png"));
            if (body) {
                out = net.minecraft.resources.ResourceKey.create(net.minecraft.world.item.equipment.EquipmentAssets.ROOT_ID, texId);
                var layer = item.getPath().contains("leather")
                        ? net.minecraft.client.resources.model.EquipmentClientInfo.Layer.leatherDyeable(texId, false)
                        : new net.minecraft.client.resources.model.EquipmentClientInfo.Layer(texId);
                var builder = net.minecraft.client.resources.model.EquipmentClientInfo.builder()
                        .addLayers(net.minecraft.client.resources.model.EquipmentClientInfo.LayerType.HUMANOID, layer);
                if (legs) builder.addLayers(net.minecraft.client.resources.model.EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS, layer);
                ASSET_INFO.put(out, builder.build());
            }
        }
        ASSETS.put(key, out);
        return out;
    }

    /** Texture du pack enregistrée telle quelle (première image si animée) sous l'identifiant demandé. */
    private static boolean equipmentTexture(PackResources pack, Identifier src, Identifier dst) {
        IoSupplier<InputStream> sup = pack.getResource(PackType.CLIENT_RESOURCES, src);
        if (sup == null) return false;
        try (InputStream in = sup.get()) {
            var img = com.mojang.blaze3d.platform.NativeImage.read(in);
            int w = img.getWidth(), h = img.getHeight();
            if (h > w / 2 && h % (w / 2) == 0 && pack.getResource(PackType.CLIENT_RESOURCES, src.withSuffix(".mcmeta")) != null) {
                var first = new com.mojang.blaze3d.platform.NativeImage(w, w / 2, true);
                img.copyRect(first, 0, 0, 0, 0, w, w / 2, false, false);
                img.close();
                img = first;
            }
            Minecraft.getInstance().getTextureManager().register(dst, new net.minecraft.client.renderer.texture.DynamicTexture(() -> dst.toString(), img));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Entity-side textures of an item: worn armor, crystal, elytra, shield, trident. */
    public static List<Identifier> entityFiles(Identifier it) {
        String p = it.getPath();
        List<Identifier> out = new ArrayList<>();
        for (String piece : new String[]{"_helmet", "_chestplate", "_leggings", "_boots"}) {
            if (p.endsWith(piece)) {
                String m = p.substring(0, p.length() - piece.length());
                if (m.equals("golden")) m = "gold";
                out.add(it.withPath("textures/entity/equipment/humanoid/" + m + ".png"));
                out.add(it.withPath("textures/entity/equipment/humanoid_leggings/" + m + ".png"));
            }
        }
        switch (p) {
            case "end_crystal" -> {
                out.add(it.withPath("textures/entity/end_crystal/end_crystal.png"));
                out.add(it.withPath("textures/entity/end_crystal/end_crystal_beam.png"));
            }
            case "elytra" -> out.add(it.withPath("textures/entity/equipment/wings/elytra.png"));
            case "shield" -> {
                out.add(it.withPath("textures/entity/shield/base.png"));
                out.add(it.withPath("textures/entity/shield/base_nopattern.png"));
            }
            case "trident" -> out.add(it.withPath("textures/entity/trident/trident.png"));
            default -> {
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ couche de ressources

    /** Couche à ajouter en haut de la pile, ou null si aucun objet n'a de pack choisi. */
    public static PackResources layer() {
        Map<String, String> m = mapping();
        if (m.isEmpty()) return null;
        Map<String, PackResources> opened = new HashMap<>();
        Map<Identifier, IoSupplier<InputStream>> files = new HashMap<>();
        m.forEach((key, packName) -> {
            PackResources src = opened.computeIfAbsent(packName, ItemPacks::open);
            if (src == null) return;
            for (Identifier item : itemsOf(key)) collect(src, item, files);
        });
        opened.values().removeIf(java.util.Objects::isNull);
        if (files.isEmpty()) {
            opened.values().forEach(PackResources::close);
            return null;
        }
        AuroraClient.LOGGER.info("Packs par objet : {} fichiers depuis {} pack(s)", files.size(), opened.size());
        return new Layer(files, new ArrayList<>(opened.values()));
    }

    /** Définition de l'objet, ses modèles (et parents) et leurs textures, s'ils sont dans le pack. */
    private static void collect(PackResources src, Identifier item, Map<Identifier, IoSupplier<InputStream>> files) {
        ArrayDeque<Identifier> models = new ArrayDeque<>();
        Identifier def = item.withPath("items/" + item.getPath() + ".json");
        IoSupplier<InputStream> d = src.getResource(PackType.CLIENT_RESOURCES, def);
        if (d != null) {
            files.put(def, d);
            JsonElement j = read(d);
            if (j != null) findModels(j, models);
        }
        models.add(item.withPath("item/" + item.getPath()));
        for (Identifier f : entityFiles(item)) {
            for (Identifier x : new Identifier[]{f, f.withSuffix(".mcmeta")}) {
                IoSupplier<InputStream> sup = src.getResource(PackType.CLIENT_RESOURCES, x);
                if (sup != null) files.put(x, sup);
            }
        }
        // bloc posé : le blockstate et tous ses modèles (ancre chargée 0-4, faces…), sinon seule la face du modèle d'item suivait le pack
        Identifier bs = item.withPath("blockstates/" + item.getPath() + ".json");
        IoSupplier<InputStream> b = src.getResource(PackType.CLIENT_RESOURCES, bs);
        if (b != null) {
            files.put(bs, b);
            JsonElement j = read(b);
            if (j != null) findModels(j, models);
        }
        // textures et modèles nommés d'après l'objet (épée seule, arc_pulling_0…), même si le pack garde le modèle vanilla
        String base = item.getPath();
        for (String dir : new String[]{"textures/item", "models/item", "textures/block", "models/block"}) {
            src.listResources(PackType.CLIENT_RESOURCES, item.getNamespace(), dir, (id, sup) -> {
                String name = id.getPath().substring(dir.length() + 1);
                int dot = name.indexOf('.');
                String stem = dot < 0 ? name : name.substring(0, dot);
                if (!stem.equals(base) && !stem.startsWith(base + "_")) return;
                files.put(id, sup);
                if (dir.equals("models/item")) models.add(item.withPath("item/" + stem));
                if (dir.equals("models/block")) models.add(item.withPath("block/" + stem));
            });
        }
        Set<Identifier> seen = new HashSet<>();
        while (!models.isEmpty()) {
            Identifier model = models.poll();
            if (!seen.add(model) || model.getPath().startsWith("builtin/")) continue;
            Identifier file = model.withPath("models/" + model.getPath() + ".json");
            IoSupplier<InputStream> s = src.getResource(PackType.CLIENT_RESOURCES, file);
            if (s == null) continue;
            files.put(file, s);
            JsonElement j = read(s);
            if (!(j instanceof JsonObject o)) continue;
            if (o.has("parent") && o.get("parent").isJsonPrimitive()) {
                Identifier p = Identifier.tryParse(o.get("parent").getAsString());
                if (p != null) models.add(p);
            }
            if (o.has("textures") && o.get("textures").isJsonObject()) {
                for (var e : o.getAsJsonObject("textures").entrySet()) {
                    if (!e.getValue().isJsonPrimitive()) continue;
                    String t = e.getValue().getAsString();
                    if (t.startsWith("#")) continue;
                    Identifier tex = Identifier.tryParse(t);
                    if (tex == null) continue;
                    for (String ext : new String[]{".png", ".png.mcmeta"}) {
                        Identifier tf = tex.withPath("textures/" + tex.getPath() + ext);
                        IoSupplier<InputStream> ts = src.getResource(PackType.CLIENT_RESOURCES, tf);
                        if (ts != null) files.put(tf, ts);
                    }
                }
            }
        }
    }

    private static void findModels(JsonElement j, ArrayDeque<Identifier> out) {
        if (j instanceof JsonObject o) {
            for (var e : o.entrySet()) {
                if (e.getKey().equals("model") && e.getValue().isJsonPrimitive()) {
                    Identifier id = Identifier.tryParse(e.getValue().getAsString());
                    if (id != null) out.add(id);
                } else {
                    findModels(e.getValue(), out);
                }
            }
        } else if (j != null && j.isJsonArray()) {
            for (JsonElement e : j.getAsJsonArray()) findModels(e, out);
        }
    }

    private static JsonElement read(IoSupplier<InputStream> s) {
        try (InputStream in = s.get()) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Layer implements PackResources {
        private final Map<Identifier, IoSupplier<InputStream>> files;
        private final List<PackResources> sources;
        private final PackLocationInfo info = new PackLocationInfo("aurora_item_packs", Component.literal("Paranoia: per-item packs"),
                PackSource.BUILT_IN, Optional.empty());

        Layer(Map<Identifier, IoSupplier<InputStream>> files, List<PackResources> sources) {
            this.files = files;
            this.sources = sources;
        }

        @Override
        public IoSupplier<InputStream> getRootResource(String... path) {
            return null;
        }

        @Override
        public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
            return type == PackType.CLIENT_RESOURCES ? files.get(id) : null;
        }

        @Override
        public void listResources(PackType type, String namespace, String path, ResourceOutput out) {
            if (type != PackType.CLIENT_RESOURCES) return;
            String prefix = path.endsWith("/") ? path : path + "/";
            files.forEach((id, s) -> {
                if (id.getNamespace().equals(namespace) && id.getPath().startsWith(prefix)) out.accept(id, s);
            });
        }

        @Override
        public Set<String> getNamespaces(PackType type) {
            Set<String> ns = new HashSet<>();
            if (type == PackType.CLIENT_RESOURCES) files.keySet().forEach(id -> ns.add(id.getNamespace()));
            return ns;
        }

        @Override
        public <T> T getMetadataSection(MetadataSectionType<T> type) {
            return null;
        }

        @Override
        public PackLocationInfo location() {
            return info;
        }

        @Override
        public void close() {
            for (PackResources p : sources) {
                try {
                    p.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Nom affiché d'un objet seul. */
    public static String itemName(String id) {
        Identifier rid = Identifier.tryParse(id);
        if (rid == null) return id;
        return BuiltInRegistries.ITEM.getOptional(rid).map(i -> new net.minecraft.world.item.ItemStack(i).getHoverName().getString()).orElse(id);
    }
}
