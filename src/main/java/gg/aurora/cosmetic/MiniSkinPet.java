package gg.aurora.cosmetic;

import com.mojang.authlib.GameProfile;
import gg.aurora.Capes;
import gg.aurora.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Familier « Mini skin » : une petite version du skin d'un pseudo au choix, qui marche à côté du joueur.
 * C'est un vrai modèle de joueur vanilla (entité Mannequin jamais ajoutée au monde : ni collision, ni clic,
 * invisible pour les autres mods), donc armure, objets en main, cape et cosmétiques sont dessinés par le
 * rendu normal du jeu. Tête agrandie par {@code MiniHeadMixin}.
 */
public final class MiniSkinPet {
    private MiniSkinPet() {}

    /** Les ids des minis sont tous sous cette valeur (les entités du jeu ont des ids positifs). */
    public static final int ID_BASE = -7000;
    private static final float HEAD_OTHERS = 1.3f, SIZE_OTHERS = 0.4f;

    private static final Object PENDING = new Object(), MISSING = new Object();
    /** pseudo (minuscules) -> PlayerSkin, PENDING ou MISSING. */
    private static final Map<String, Object> SKINS = new ConcurrentHashMap<>();
    /** id du joueur -> son mini. */
    private static final Map<Integer, Mini> MINIS = new HashMap<>();
    /** id de l'entité mini -> mini (lu par la couche cosmétiques et le modèle). */
    private static final Map<Integer, Mini> BY_ID = new HashMap<>();
    private static int nextId = ID_BASE;
    private static long last = System.nanoTime(), frame;
    private static Mini preview;

    public static final class Mini {
        final Body body;
        Player owner;
        boolean mine;
        /** x, y, z, vx, vz, orientation (degrés), phase de marche. */
        float[] s;
        long seen;
        PlayerSkin skin = DefaultPlayerSkin.getDefaultSkin(), skinBase;
        String capeKey, equipKey;
        float head = HEAD_OTHERS;

        Mini(Level level) {
            body = new Body(level, this);
            body.setId(--nextId);
            BY_ID.put(body.getId(), this);
        }

        public Player owner() {
            return owner;
        }

        public ClientMannequin entity() {
            return body;
        }

        /** Réglages publiés par le propriétaire (autres joueurs du mod), null pour le mien ou si inconnu. */
        gg.aurora.nametag.NametagSync.Mini remote;

        /** Cosmétique porté par ce mini : mes réglages pour le mien, ceux publiés par son propriétaire sinon. */
        public Cosmetics.Item cosmetic(Cosmetics.Type t) {
            if (!Cosmetics.shown(t)) return null;
            String id;
            if (mine) id = switch (t) {
                case WINGS -> Settings.miniWings;
                case HALO -> Settings.miniHalo;
                case BACK -> Settings.miniBack;
                default -> "";
            };
            else if (remote != null) id = switch (t) {
                case WINGS -> remote.wings();
                case HALO -> remote.halo();
                case BACK -> remote.back();
                default -> "";
            };
            else return null;
            return id == null || id.isEmpty() ? null : Cosmetics.find(t, id);
        }
    }

    /** Modèle joueur vanilla, hors du monde. */
    static final class Body extends ClientMannequin {
        private final Mini mini;

        Body(Level level, Mini mini) {
            super(level, Minecraft.getInstance().playerSkinRenderCache());
            this.mini = mini;
        }

        @Override
        public PlayerSkin getSkin() {
            return mini.skin;
        }

        @Override
        public Component belowNameDisplay() {
            return null;
        }

        @Override
        public boolean isPickable() {
            return false;
        }
    }

    public static Mini byId(int id) {
        return id < ID_BASE ? BY_ID.get(id) : null;
    }

    /** Agrandissement de la tête pour cet état de rendu (1 = joueur normal). */
    public static float headScale(int id) {
        if (id >= ID_BASE) return 1f;
        Mini m = BY_ID.get(id);
        return m == null ? 1f : m.head;
    }

    static PlayerSkin skin(Player owner, String name) {
        if (owner instanceof AbstractClientPlayer acp && name.equalsIgnoreCase(owner.getGameProfile().name())) return acp.getSkin();
        String key = name.toLowerCase(Locale.ROOT);
        Object v = SKINS.get(key);
        if (v instanceof PlayerSkin s) return s;
        if (v == null && SKINS.size() < 64) {
            SKINS.put(key, PENDING);
            fetch(key);
        }
        return DefaultPlayerSkin.getDefaultSkin();
    }

    /** Pseudo -> profil Mojang -> textures -> skin, hors du thread de rendu. Une seule tentative par pseudo. */
    private static void fetch(String name) {
        CompletableFuture.runAsync(() -> {
            Minecraft mc = Minecraft.getInstance();
            try {
                var found = mc.services().profileResolver().fetchByName(name);
                if (found.isEmpty()) {
                    gg.aurora.AuroraClient.LOGGER.info("Mini skin : pseudo introuvable « {} »", name);
                    SKINS.put(name, MISSING);
                    return;
                }
                var full = mc.services().sessionService().fetchProfile(found.get().id(), false);
                GameProfile gp = full != null ? full.profile() : found.get();
                mc.getSkinManager().get(gp).whenComplete((o, err) -> {
                    boolean ok = err == null && o != null && o.isPresent();
                    if (!ok) gg.aurora.AuroraClient.LOGGER.warn("Mini skin : pas de skin pour « {} » ({})", name, err == null ? "vide" : err.toString());
                    else gg.aurora.AuroraClient.LOGGER.info("Mini skin : skin de « {} » chargé", name);
                    SKINS.put(name, ok ? o.get() : MISSING);
                });
            } catch (Exception e) {
                gg.aurora.AuroraClient.LOGGER.warn("Mini skin : skin de « {} » indisponible ({})", name, e.toString());
                SKINS.put(name, MISSING);
            }
        }, net.minecraft.util.Util.backgroundExecutor());
    }

    /** Dev : lance la recherche du skin à l'avance. */
    public static void prefetch(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (SKINS.putIfAbsent(key, PENDING) == null) fetch(key);
    }

    /** Le pseudo demandé n'existe pas (ou son skin est indisponible). */
    public static boolean missing(String name) {
        return SKINS.get(name.toLowerCase(Locale.ROOT)) == MISSING;
    }

    private static ItemStack stack(String id) {
        if (id == null || id.isBlank()) return ItemStack.EMPTY;
        Identifier key = Identifier.tryParse(id);
        return key == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.getOptional(key).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /** Skin, cape, taille, tête et équipement : recalculés seulement quand un réglage change. */
    private static void sync(Mini m, Player owner) {
        Minecraft mc = Minecraft.getInstance();
        m.owner = owner;
        m.mine = owner == mc.player;
        PlayerSkin base = skin(owner, Cosmetics.miniSkinName(owner));
        var pr = m.mine ? null : gg.aurora.nametag.NametagSync.profile(owner.getUUID());
        String capeKey = !Settings.showCapes ? "" : m.mine ? Settings.miniCape : pr != null && pr.mini() != null && pr.mini().cape() != null ? pr.mini().cape() : "";
        Capes.Cape cape = capeKey.isEmpty() ? null : Capes.byId(capeKey);
        if (cape != null && !cape.ready()) capeKey = "";
        if (base != m.skinBase || !capeKey.equals(m.capeKey)) {
            m.skinBase = base;
            m.capeKey = capeKey;
            m.skin = new PlayerSkin(base.body(), capeKey.isEmpty() ? null : cape.asset(), null, base.model(), base.secure());
        }
        // autres joueurs : réglages publiés dans leur profil (taille, tête, équipement), sinon valeurs par défaut
        var prof = m.mine ? null : gg.aurora.nametag.NametagSync.profile(owner.getUUID());
        m.remote = prof == null ? null : prof.mini();
        var r = m.remote;
        m.head = m.mine ? (float) Settings.miniHead : r != null ? r.head() : HEAD_OTHERS;
        float size = m.mine ? (float) Settings.miniSize : r != null ? r.size() : SIZE_OTHERS;
        String helmet = m.mine ? Settings.miniHelmet : r != null ? r.helmet() : "", chest = m.mine ? Settings.miniChest : r != null ? r.chest() : "",
                legs = m.mine ? Settings.miniLegs : r != null ? r.legs() : "", boots = m.mine ? Settings.miniBoots : r != null ? r.boots() : "",
                main = m.mine ? Settings.miniMain : r != null ? r.main() : "", off = m.mine ? Settings.miniOff : r != null ? r.off() : "";
        String key = size + "|" + helmet + "|" + chest + "|" + legs + "|" + boots + "|" + main + "|" + off;
        if (key.equals(m.equipKey)) return;
        m.equipKey = key;
        var scale = m.body.getAttribute(Attributes.SCALE);
        if (scale != null) scale.setBaseValue(size);
        m.body.setItemSlot(EquipmentSlot.HEAD, stack(helmet));
        m.body.setItemSlot(EquipmentSlot.CHEST, stack(chest));
        m.body.setItemSlot(EquipmentSlot.LEGS, stack(legs));
        m.body.setItemSlot(EquipmentSlot.FEET, stack(boots));
        m.body.setItemSlot(EquipmentSlot.MAINHAND, stack(main));
        m.body.setItemSlot(EquipmentSlot.OFFHAND, stack(off));
    }

    private static void drop(Mini m) {
        BY_ID.remove(m.body.getId());
    }

    /** Chaque tick : physique de la cape des minis. */
    public static void tick(Minecraft mc) {
        if (mc.level == null) {
            if (!MINIS.isEmpty() || preview != null) {
                MINIS.clear();
                BY_ID.clear();
                preview = null;
            }
            return;
        }
        for (Mini m : MINIS.values()) {
            m.body.tickCount++;
            m.body.avatarState().tick(m.body.position(), new Vec3(m.s[3] / 20, 0, m.s[4] / 20));
        }
        if (preview != null) {
            preview.body.tickCount++;
            preview.body.avatarState().tick(preview.body.position(), Vec3.ZERO);
        }
    }

    /** Appelé à la fin de l'extraction des entités : ajoute les minis visibles à la liste que le jeu va dessiner. */
    public static void extract(LevelRenderState out, EntityRenderDispatcher dispatcher, Vec3 cam, float pt) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - last) / 1e9f);
        last = now;
        if (mc.level == null || mc.player == null) return;
        if (!Cosmetics.anyWorn(Cosmetics.Type.PET)) {
            if (!MINIS.isEmpty()) {
                MINIS.values().forEach(MiniSkinPet::drop);
                MINIS.clear();
            }
            return;
        }
        frame++;
        float time = PetFx.freezeTime > 0 ? PetFx.freezeTime : (float) ((System.currentTimeMillis() % 3_600_000L) / 1000.0);
        for (Player p : mc.level.players()) {
            Cosmetics.Item pet = Cosmetics.worn(p, Cosmetics.Type.PET);
            if (pet == null || !pet.id().equals("mini_skin") || p.isInvisible() || p.distanceToSqr(cam) > 64 * 64) continue;
            Mini m = MINIS.get(p.getId());
            if (m == null || m.body.level() != mc.level) {
                if (m != null) drop(m);
                m = new Mini(mc.level);
                MINIS.put(p.getId(), m);
            }
            m.seen = frame;
            sync(m, p);
            float yaw = (float) Math.toRadians(net.minecraft.util.Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot));
            double px = net.minecraft.util.Mth.lerp(pt, p.xo, p.getX()), py = net.minecraft.util.Mth.lerp(pt, p.yo, p.getY()),
                    pz = net.minecraft.util.Mth.lerp(pt, p.zo, p.getZ());
            float amp = move(mc, m, px, py, pz, yaw, dt);
            float[] s = m.s;
            place(m.body, s[0], s[1], s[2], s[5]);
            EntityRenderState st = dispatcher.extractEntity(m.body, pt);
            if (st instanceof AvatarRenderState as) {
                // vanilla : jambes = cos(pos * 0,6662) * 1,4 * vitesse
                as.walkAnimationPos = s[6] / 0.6662f;
                as.walkAnimationSpeed = amp * 0.8f;
                as.ageInTicks = time * 20;
                as.bodyRot = s[5];
                as.yRot = 0;
                as.xRot = Mesh.sin(time * 0.9f) * 3f;
                as.nameTag = null;
                as.scoreText = null;
                as.outlineColor = 0;
            }
            out.entityRenderStates.add(st);
        }
        for (Iterator<Mini> it = MINIS.values().iterator(); it.hasNext(); ) {
            Mini m = it.next();
            if (m.seen != frame) {
                drop(m);
                it.remove();
            }
        }
    }

    private static void place(ClientMannequin e, double x, double y, double z, float yaw) {
        e.setPos(x, y, z);
        e.setYRot(yaw);
        e.setXRot(0);
        e.yBodyRot = e.yBodyRotO = yaw;
        e.yHeadRot = e.yHeadRotO = yaw;
        e.setOldPosAndRot();
    }

    /** Suit le joueur (ressort amorti), se tourne dans le sens de la marche. Retourne l'amplitude de marche 0..1. */
    private static float move(Minecraft mc, Mini m, double px, double py, double pz, float yawRad, float dt) {
        float fx = -Mesh.sin(yawRad), fz = Mesh.cos(yawRad);
        float rx = -Mesh.cos(yawRad), rz = -Mesh.sin(yawRad);
        // à droite du joueur, un peu en retrait
        float tx = (float) px + rx * 0.85f - fx * 0.45f, tz = (float) pz + rz * 0.85f - fz * 0.45f;
        float playerYaw = (float) Math.toDegrees(yawRad);
        if (m.s == null) m.s = new float[]{tx, (float) py, tz, 0, 0, playerYaw, 0};
        float[] s = m.s;
        if (Math.abs(s[0] - tx) > 10 || Math.abs(s[2] - tz) > 10 || Math.abs(s[1] - py) > 6) {
            s[0] = tx;
            s[1] = (float) py;
            s[2] = tz;
            s[3] = s[4] = 0;
        }
        // ressort amorti : démarre et s'arrête en douceur
        float w = 6f;
        s[3] += (w * w * (tx - s[0]) - 2 * w * s[3]) * dt;
        s[4] += (w * w * (tz - s[2]) - 2 * w * s[4]) * dt;
        float sp = (float) Math.sqrt(s[3] * s[3] + s[4] * s[4]);
        if (sp > 7) {
            s[3] *= 7 / sp;
            s[4] *= 7 / sp;
            sp = 7;
        }
        s[0] += s[3] * dt;
        s[2] += s[4] * dt;
        float ground = ground(mc, s[0], py, s[2]);
        s[1] += (ground - s[1]) * (1 - (float) Math.exp(-dt * 12));
        // orientation : sens de la marche, sinon celle du joueur
        float want = sp > 0.35f ? (float) Math.toDegrees(Math.atan2(-s[3], s[4])) : playerYaw;
        float diff = ((want - s[5]) % 360 + 540) % 360 - 180;
        s[5] += diff * (1 - (float) Math.exp(-dt * 8));
        s[6] += dt * sp * 4.2f;
        return Math.min(1, sp / 2.5f);
    }

    /** Mini de l'écran de réglages : mêmes skin, tête, équipement et cosmétiques que le mien, immobile. */
    public static Mini preview() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        if (preview == null || preview.body.level() != mc.level) {
            if (preview != null) drop(preview);
            preview = new Mini(mc.level);
        }
        sync(preview, mc.player);
        place(preview.body, mc.player.getX(), mc.player.getY(), mc.player.getZ(), 0);
        return preview;
    }

    private static float ground(Minecraft mc, float x, double py, float z) {
        if (mc.level == null) return (float) py;
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        for (int dy = 1; dy >= -3; dy--) {
            bp.set(Math.floor(x), Math.floor(py) + dy - 1, Math.floor(z));
            var shape = mc.level.getBlockState(bp).getCollisionShape(mc.level, bp);
            if (!shape.isEmpty()) return (float) (bp.getY() + shape.max(net.minecraft.core.Direction.Axis.Y));
        }
        return (float) py;
    }
}
