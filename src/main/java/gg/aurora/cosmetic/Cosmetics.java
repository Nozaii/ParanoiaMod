package gg.aurora.cosmetic;

import gg.aurora.Capes;
import gg.aurora.Settings;
import gg.aurora.nametag.NametagSync;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Catalogue des cosmétiques (ailes, auras, halos) et choix de chaque joueur. */
public final class Cosmetics {
    private Cosmetics() {}

    public enum Type { WINGS, AURA, HALO, BACK, PET, SHIELD }

    public record Item(Type type, String id, String name, Capes.Rarity rarity, int color, ParticleOptions particle) {
        public Identifier texture() {
            String prefix = switch (type) {
                case WINGS -> "wings_";
                case AURA -> "aura_";
                case HALO -> "halo_";
                case BACK -> "back_";
                case PET -> "pet_";
                case SHIELD -> "shield_";
            };
            return Identifier.fromNamespaceAndPath("aurora", "textures/cosmetic/" + prefix + id + ".png");
        }
    }

    public static final List<Item> WINGS = List.of(
            new Item(Type.WINGS, "angel", "Angel", Capes.Rarity.EPIC, 0xFFFFFF, null),
            new Item(Type.WINGS, "paranoia", "Paranoia", Capes.Rarity.LEGENDARY, 0x9D7CFF, null),
            new Item(Type.WINGS, "demon", "Demon", Capes.Rarity.LEGENDARY, 0xD1102A, null),
            new Item(Type.WINGS, "corbeau", "Raven", Capes.Rarity.RARE, 0x303038, null),
            new Item(Type.WINGS, "papillon", "Monarch", Capes.Rarity.EPIC, 0xF28C28, null),
            new Item(Type.WINGS, "maudites", "Cursed wings", Capes.Rarity.LEGENDARY, 0xE8E4FF, null));

    public static final List<Item> AURAS = List.of(
            new Item(Type.AURA, "braise", "Inferno", Capes.Rarity.LEGENDARY, 0xFF8C32, ParticleTypes.SMALL_FLAME),
            new Item(Type.AURA, "givre", "Storm", Capes.Rarity.EPIC, 0x3FB8FF, ParticleTypes.SNOWFLAKE),
            new Item(Type.AURA, "spectre", "Spectre", Capes.Rarity.LEGENDARY, 0x3C8CFF, ParticleTypes.SOUL_FIRE_FLAME),
            new Item(Type.AURA, "neant", "Void", Capes.Rarity.LEGENDARY, 0xB078FF, ParticleTypes.PORTAL),
            new Item(Type.AURA, "etoiles", "Stars", Capes.Rarity.RARE, 0xFFFFFF, ParticleTypes.END_ROD),
            new Item(Type.AURA, "coeurs", "Hearts", Capes.Rarity.RARE, 0xFF6EAA, ParticleTypes.HEART));

    public static final List<Item> HALOS = List.of(
            new Item(Type.HALO, "or", "Halo", Capes.Rarity.EPIC, 0xFFD65E, null),
            new Item(Type.HALO, "paranoia", "Paranoia crown", Capes.Rarity.LEGENDARY, 0xB08CFF, null),
            new Item(Type.HALO, "givre", "Frost crown", Capes.Rarity.EPIC, 0x8CDCFF, null),
            // chapeaux : portés à la place du halo (même emplacement « Tête », synchronisés pareil)
            new Item(Type.HALO, "hat_lunettes", "Neon glasses", Capes.Rarity.EPIC, 0xF4F2FF, null),
            new Item(Type.HALO, "hat_couronne", "Royal crown", Capes.Rarity.LEGENDARY, 0xF2C14E, null),
            new Item(Type.HALO, "hat_pharaon", "Pharaoh headdress", Capes.Rarity.LEGENDARY, 0x2E62C9, null),
            new Item(Type.HALO, "hat_haut", "Top hat", Capes.Rarity.EPIC, 0x9D7CFF, null),
            new Item(Type.HALO, "hat_sorciere", "Witch hat", Capes.Rarity.EPIC, 0x7A4DFF, null),
            new Item(Type.HALO, "hat_cornes", "Demon horns", Capes.Rarity.EPIC, 0xD1102A, null),
            new Item(Type.HALO, "hat_chat", "Cat ears", Capes.Rarity.RARE, 0xFF8FC8, null));

    /** Pseudo affiché par le familier « Mini skin » de ce joueur (vide = son propre skin). */
    public static String miniSkinName(Player p) {
        if (p == Minecraft.getInstance().player) return Settings.petSkin.isBlank() ? p.getGameProfile().name() : Settings.petSkin.trim();
        NametagSync.Profile prof = NametagSync.profile(p.getUUID());
        return prof != null && prof.pet() != null && prof.pet().startsWith("skin_") ? prof.pet().substring(5) : p.getGameProfile().name();
    }

    /** Les chapeaux partagent l'emplacement des halos. */
    public static boolean isHat(String id) {
        return id.startsWith("hat_");
    }

    /** Objets portés dans le dos (modèles 3D procéduraux, voir BackItems). */
    public static final List<Item> BACKS = List.of(
            new Item(Type.BACK, "guitare", "Electric guitar", Capes.Rarity.LEGENDARY, 0xD3202E, null),
            new Item(Type.BACK, "katana", "Katana", Capes.Rarity.LEGENDARY, 0xC9A23A, null),
            new Item(Type.BACK, "ak47", "AK-47", Capes.Rarity.EPIC, 0x9A5A2A, null),
            new Item(Type.BACK, "masse", "Mace", Capes.Rarity.EPIC, 0x8C93A3, null));

    /** Familiers : méduses translucides qui flottent près de l'épaule. */
    public static final List<Item> PETS = List.of(
            new Item(Type.PET, "meduse", "Lilac jellyfish", Capes.Rarity.LEGENDARY, 0xC9A8FF, null),
            new Item(Type.PET, "abysse", "Abyssal jellyfish", Capes.Rarity.LEGENDARY, 0x5FE1E8, null),
            new Item(Type.PET, "rose", "Pink jellyfish", Capes.Rarity.EPIC, 0xFF8FC8, null),
            new Item(Type.PET, "spectre", "Spectral jellyfish", Capes.Rarity.EPIC, 0xF2F0FF, null),
            new Item(Type.PET, "ovni", "UFO", Capes.Rarity.LEGENDARY, 0x7CFFB2, null),
            new Item(Type.PET, "ovni_mars", "UFO Mars", Capes.Rarity.LEGENDARY, 0xFF6A4A, null),
            new Item(Type.PET, "verity", "Verity", Capes.Rarity.EPIC, 0xF2CB05, null),
            new Item(Type.PET, "falsity", "Falsity", Capes.Rarity.EPIC, 0xE23B3B, null),
            new Item(Type.PET, "lovity", "Lovity", Capes.Rarity.EPIC, 0xFF7AB8, null),
            new Item(Type.PET, "negity", "Negity", Capes.Rarity.EPIC, 0x2A1E3A, null),
            new Item(Type.PET, "mini_skin", "Mini skin", Capes.Rarity.LEGENDARY, 0x6FB7FF, null),
            new Item(Type.PET, "dragon", "Shenron", Capes.Rarity.LEGENDARY, 0x3FAE4F, null));

    /** Pets with the same look: one card in the wardrobe, variants scrolled with the arrows. */
    private static final List<List<String>> PET_FAMILIES = List.of(
            List.of("meduse", "abysse", "rose", "spectre"), List.of("ovni", "ovni_mars"), List.of("verity", "falsity", "lovity", "negity"),
            List.of("mini_skin"), List.of("dragon"));
    private static final java.util.Map<Integer, Integer> SHOWN_VARIANT = new java.util.HashMap<>();

    public static boolean isVerity(String id) {
        return PET_FAMILIES.get(2).contains(id);
    }

    public static boolean isUfo(String id) {
        return PET_FAMILIES.get(1).contains(id);
    }

    /** Variants of this pet (itself alone if it has none). */
    public static List<String> family(String id) {
        for (List<String> f : PET_FAMILIES) if (f.contains(id)) return f;
        return List.of(id);
    }

    /** One item per family: the equipped variant, else the last one shown. */
    public static List<Item> petCards() {
        List<Item> out = new java.util.ArrayList<>();
        for (int i = 0; i < PET_FAMILIES.size(); i++) {
            List<String> f = PET_FAMILIES.get(i);
            String id = f.contains(Settings.pet) ? Settings.pet : f.get(SHOWN_VARIANT.getOrDefault(i, 0));
            for (Item it : PETS) if (it.id().equals(id)) out.add(it);
        }
        return out;
    }

    /** Next / previous variant; if the family is equipped the new variant is worn straight away. */
    public static String cycleVariant(String id, int dir) {
        List<String> f = family(id);
        int fam = PET_FAMILIES.indexOf(f);
        int next = Math.floorMod(f.indexOf(id) + dir, f.size());
        if (fam >= 0) SHOWN_VARIANT.put(fam, next);
        if (f.contains(Settings.pet)) Settings.pet = f.get(next);
        return f.get(next);
    }

    /** Boucliers exclusifs (texture du modèle de bouclier). */
    public static final List<Item> SHIELDS = List.of(
            new Item(Type.SHIELD, "obsidienne", "Obsidian", Capes.Rarity.LEGENDARY, 0x6A3CC8, null),
            new Item(Type.SHIELD, "paranoia", "Paranoia", Capes.Rarity.LEGENDARY, 0x9D7CFF, null),
            new Item(Type.SHIELD, "royal", "Royal", Capes.Rarity.EPIC, 0xE8B931, null),
            new Item(Type.SHIELD, "givre", "Frost", Capes.Rarity.EPIC, 0x9CDCFF, null),
            new Item(Type.SHIELD, "sang", "Blood", Capes.Rarity.RARE, 0xB0141F, null));

    public static List<Item> of(Type t) {
        return switch (t) {
            case WINGS -> WINGS;
            case AURA -> AURAS;
            case HALO -> HALOS;
            case BACK -> BACKS;
            case PET -> PETS;
            case SHIELD -> SHIELDS;
        };
    }

    public static Item find(Type t, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Item i : of(t)) if (i.id().equals(id)) return i;
        return null;
    }

    public static String selected(Type t) {
        return switch (t) {
            case WINGS -> Settings.wings;
            case AURA -> Settings.aura;
            case HALO -> Settings.halo;
            case BACK -> Settings.back;
            case PET -> Settings.pet;
            case SHIELD -> Settings.shieldDesign;
        };
    }

    public static void select(Type t, String id) {
        switch (t) {
            case WINGS -> Settings.wings = id;
            case AURA -> Settings.aura = id;
            case HALO -> Settings.halo = id;
            case BACK -> Settings.back = id;
            case PET -> Settings.pet = id;
            case SHIELD -> Settings.shieldDesign = id;
        }
    }

    /** Cosmétique porté par un joueur : le nôtre (paramètres) ou celui de son profil partagé. */
    /** Réglage « cosmétiques affichés » pour ce type. */
    public static boolean shown(Type t) {
        return switch (t) {
            case WINGS -> Settings.showWings;
            case AURA -> Settings.showAuras;
            case HALO -> Settings.showHead;
            case BACK -> Settings.showBack;
            case PET -> Settings.showPets;
            case SHIELD -> Settings.showShields;
        };
    }

    public static Item worn(Player p, Type t) {
        if (!shown(t)) return null;
        UUID id = p.getUUID();
        if (p == Minecraft.getInstance().player || id.equals(Minecraft.getInstance().getUser().getProfileId())) {
            return find(t, selected(t));
        }
        if (!Settings.showOthers) return null;
        // budget : seuls les joueurs les plus proches gardent leurs cosmétiques 3D (la cape reste, elle est vanilla)
        if (!detailed(p.getId())) return null;
        NametagSync.Profile prof = NametagSync.profile(id);
        if (t == Type.PET && prof != null && prof.pet() != null && prof.pet().startsWith("skin_")) return find(Type.PET, "mini_skin");
        if (prof == null) return null;
        return find(t, switch (t) {
            case WINGS -> prof.wings();
            case AURA -> prof.aura();
            case HALO -> prof.halo();
            case PET -> prof.pet();
            case SHIELD -> prof.shield();
            case BACK -> prof.back();
        });
    }

    /** Joueurs (ids d'entité) qui gardent leurs cosmétiques 3D, recalculés à chaque tick ; null = tous. */
    private static volatile java.util.Set<Integer> detailedIds;

    /** Nombre d'autres joueurs avec cosmétiques 3D selon la qualité : High illimité, Balanced 6, Performance 3. */
    public static int budget() {
        return switch (Settings.cosmeticQuality) {
            case 0 -> Integer.MAX_VALUE;
            case 2 -> 3;
            default -> 6;
        };
    }

    static boolean detailed(int entityId) {
        java.util.Set<Integer> s = detailedIds;
        return s == null || s.contains(entityId);
    }

    /** Les N autres joueurs les plus proches (et visibles) parmi ceux du mod. */
    private static void refreshBudget(Minecraft mc) {
        int n = budget();
        if (n == Integer.MAX_VALUE || mc.level == null || mc.player == null) {
            detailedIds = null;
            return;
        }
        List<Player> near = new ArrayList<>();
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isInvisible()) continue;
            near.add(p);
        }
        if (near.size() <= n) {
            detailedIds = null;
            return;
        }
        near.sort(java.util.Comparator.comparingDouble(p -> p.distanceToSqr(mc.player)));
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) out.add(near.get(i).getId());
        detailedIds = out;
    }

    /** Particules des auras autour des joueurs proches. */
    /** Quelqu'un (moi ou un joueur visible) porte-t-il ce type ? Recalculé à chaque tick (20/s). */
    private static final boolean[] ANY = new boolean[Type.values().length];
    private static boolean anyTrail;

    /** Faux = le rendu de ce type peut sortir tout de suite (coût quasi nul quand personne ne le porte). */
    public static boolean anyWorn(Type t) {
        return ANY[t.ordinal()];
    }

    public static boolean anyTrail() {
        return anyTrail;
    }

    private static void refreshWorn(Minecraft mc) {
        java.util.Arrays.fill(ANY, false);
        anyTrail = false;
        if (mc.level == null) return;
        for (Player p : mc.level.players()) {
            for (Type t : TYPES) if (!ANY[t.ordinal()] && worn(p, t) != null) ANY[t.ordinal()] = true;
            if (!anyTrail) {
                if (!Settings.showTrails) continue;
                if (p == mc.player) anyTrail = gg.aurora.module.ClientModules.TRAIL.enabled;
                else if (Settings.showOthers) {
                    var prof = gg.aurora.nametag.NametagSync.profile(p.getUUID());
                    anyTrail = prof != null && prof.trail() != null && !prof.trail().isEmpty();
                }
            }
        }
    }

    private static final Type[] TYPES = {Type.WINGS, Type.AURA, Type.HALO, Type.BACK, Type.PET, Type.SHIELD};

    public static void tick(Minecraft mc) {
        refreshBudget(mc);
        refreshWorn(mc);
        if (mc.level == null || mc.player == null || !Settings.auraParticles || !ANY[Type.AURA.ordinal()]) return;
        long t = mc.level.getGameTime();
        for (Player p : mc.level.players()) {
            if (p.isInvisible() || p.distanceToSqr(mc.player) > 48 * 48) continue;
            Item aura = worn(p, Type.AURA);
            if (aura == null || aura.particle() == null) continue;
            int every = (aura.particle() == ParticleTypes.HEART ? 10 : 3) * (Settings.cosmeticQuality == 2 ? 2 : 1);
            if ((t + p.getId()) % every != 0) continue;
            double a = mc.level.random.nextDouble() * Math.PI * 2, r = 0.45 + mc.level.random.nextDouble() * 0.3;
            double x = p.getX() + Mesh.cos(a) * r, z = p.getZ() + Mesh.sin(a) * r;
            double y = p.getY() + 0.05 + mc.level.random.nextDouble() * (aura.particle() == ParticleTypes.PORTAL ? 1.6 : 0.4);
            double up = aura.particle() == ParticleTypes.PORTAL ? 0 : 0.02 + mc.level.random.nextDouble() * 0.03;
            mc.level.addParticle(aura.particle(), x, y, z, 0, up, 0);
        }
    }
}
