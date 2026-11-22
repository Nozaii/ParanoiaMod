package gg.aurora;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gg.aurora.mixin.KeyBindsAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.EntrypointMetadata;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.NestedJarEntry;
import net.fabricmc.loader.impl.metadata.VersionOverrides;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads Fabric mods into the running game, without a restart, when that can be done safely.
 * <p>
 * A jar is accepted only if everything it changes is still untouched:
 * <ul>
 *   <li>its mixins and access wideners only target classes the game has not loaded yet
 *       (a loaded class can no longer be changed);</li>
 *   <li>it does not add registry content (blocks, items, entities…): registries are frozen after startup;</li>
 *   <li>its dependencies are loaded, or come in the same batch.</li>
 * </ul>
 * Then: jar on the class path, mod registered with Fabric Loader (so {@code isModLoaded}, mod containers and
 * resource packs see it), access wideners and mixins registered, entrypoints run, new keys added to Controls
 * and resources reloaded. Anything refused is left for the next launch.
 * <p>
 * A loaded mod cannot be unloaded: the JVM keeps its classes. Updating or removing one still takes a restart.
 */
public final class HotLoader {
    private HotLoader() {}

    private static final Gson GSON = new Gson();
    /** Registry.register / registerForHolder, in the runtime (intermediary) names. */
    private static final String REGISTRY = "net/minecraft/class_2378";
    private static final Set<String> REGISTER = Set.of("method_10230", "method_10226", "method_39197", "method_47985", "method_47984");

    /** Why a jar cannot be loaded live. */
    public static final class Refused extends Exception {
        Refused(String reason) {
            super(reason, null, false, false);
        }
    }

    /** Outcome of a load: null warnings = clean. */
    public record Result(List<String> loadedNames, List<String> warnings) {
        public boolean clean() {
            return warnings.isEmpty();
        }
    }

    private static final class Unit {
        final Path path;
        final LoaderModMetadata meta;
        final boolean hasAssets;
        ModContainerImpl container;

        Unit(Path path, LoaderModMetadata meta, boolean hasAssets) {
            this.path = path;
            this.meta = meta;
            this.hasAssets = hasAssets;
        }
    }

    /** Mods loaded live during this session. */
    public static final Set<String> LIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // =========================================================================== Entry point

    /** Analyses then loads the jars. Call off the game thread; the loading itself runs on it. */
    public static Result load(List<Path> jars) throws Exception {
        if (UNAVAILABLE != null) throw new Refused(UNAVAILABLE);
        List<Unit> units = prepare(jars);
        if (units.isEmpty()) return new Result(List.of(), List.of());
        if (WIDENERS_UNAVAILABLE != null) {
            // decided before anything changes in Fabric's state
            for (Unit u : units) if (u.meta.getClassTweaker() != null) throw new Refused(u.meta.getName() + ": " + WIDENERS_UNAVAILABLE);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return apply(units);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, Minecraft.getInstance()).join();
    }

    // =========================================================================== Analysis

    private static List<Unit> prepare(List<Path> jars) throws Exception {
        // published mods use intermediary names; a dev run uses Mojang names and would need remapping
        if (!"intermediary".equals(FabricLauncherBase.getLauncher().getMappingConfiguration().getRuntimeNamespace())) {
            throw new Refused("live loading only works in the real game, not in a dev run");
        }
        List<Unit> units = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Path jar : jars) collect(jar, false, units, ids);
        for (Unit u : units) check(u, ids);
        return units;
    }

    private static void collect(Path jar, boolean nested, List<Unit> units, Set<String> ids) throws Exception {
        LoaderModMetadata meta;
        boolean assets;
        List<Path> inner = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e == null) {
                if (nested) return; // plain library jar inside a mod: never seen by Fabric either
                throw new Refused("not a Fabric mod");
            }
            try (InputStream in = z.getInputStream(e)) {
                meta = ModMetadataParser.parseMetadata(in, jar.toString(), List.of(), new VersionOverrides(),
                        new DependencyOverrides(FabricLoader.getInstance().getConfigDir()), false);
            }
            if (!meta.loadsInEnvironment(EnvType.CLIENT)) return;
            String id = meta.getId();
            if (FabricLoader.getInstance().isModLoaded(id) || ids.contains(id)) {
                if (nested) return; // the game already has this library
                throw new Refused(FabricLoader.getInstance().isModLoaded(id) ? "an older version is running" : "duplicate mod");
            }
            ids.add(id);
            ids.addAll(meta.getProvides());
            assets = z.getEntry("assets/") != null || z.stream().anyMatch(x -> x.getName().startsWith("assets/"));
            for (NestedJarEntry n : meta.getJars()) {
                ZipEntry je = z.getEntry(n.getFile());
                if (je == null) continue;
                byte[] data;
                try (InputStream in = z.getInputStream(je)) {
                    data = in.readAllBytes();
                }
                String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(data)).substring(0, 16);
                Path out = FabricLoader.getInstance().getGameDir().resolve("mods").resolve(".aurora-mods").resolve("live").resolve(hash)
                        .resolve(Path.of(n.getFile()).getFileName().toString());
                if (!Files.exists(out)) {
                    Files.createDirectories(out.getParent());
                    Files.write(out, data);
                }
                inner.add(out);
            }
        }
        for (Path p : inner) collect(p, true, units, ids); // libraries before the mod that bundles them
        units.add(new Unit(jar, meta, assets));
    }

    private static void check(Unit u, Set<String> batch) throws Exception {
        String name = u.meta.getName();
        for (ModDependency d : u.meta.getDependencies()) {
            Optional<ModContainer> c = FabricLoader.getInstance().getModContainer(d.getModId());
            switch (d.getKind()) {
                case DEPENDS -> {
                    if (batch.contains(d.getModId())) continue;
                    if (c.isEmpty()) throw new Refused(name + " needs " + d.getModId());
                    if (!d.matches(c.get().getMetadata().getVersion())) throw new Refused(name + " needs another version of " + d.getModId());
                }
                case BREAKS -> {
                    if (c.isPresent() && d.matches(c.get().getMetadata().getVersion())) throw new Refused(name + " is incompatible with " + d.getModId());
                }
                default -> { }
            }
        }

        FabricLauncher launcher = FabricLauncherBase.getLauncher();
        try (ZipFile z = new ZipFile(u.path.toFile())) {
            String tweaker = u.meta.getClassTweaker();
            if (tweaker != null) {
                ZipEntry e = z.getEntry(tweaker);
                if (e == null) throw new Refused(name + ": missing access widener");
                for (String owner : tweakerTargets(new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8))) {
                    if (launcher.isClassLoaded(owner.replace('/', '.'))) throw new Refused(name + " opens up game code that is already in use");
                }
            }
            for (String config : u.meta.getMixinConfigs(EnvType.CLIENT)) {
                ZipEntry e = z.getEntry(config);
                if (e == null) throw new Refused(name + ": missing mixin config " + config);
                JsonObject o = GSON.fromJson(new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8), JsonObject.class);
                String pkg = o.has("package") ? o.get("package").getAsString() : "";
                List<String> mixins = new ArrayList<>();
                for (String key : new String[]{"mixins", "client"}) {
                    if (o.get(key) instanceof JsonArray a) for (JsonElement m : a) mixins.add(m.getAsString());
                }
                for (String m : mixins) {
                    String file = (pkg.isEmpty() ? m : pkg + "." + m).replace('.', '/') + ".class";
                    ZipEntry ce = z.getEntry(file);
                    if (ce == null) continue; // listed but absent: Mixin reports it, nothing to check
                    for (String target : mixinTargets(z.getInputStream(ce).readAllBytes())) {
                        if (launcher.isClassLoaded(target)) throw new Refused(name + " changes game code that is already running");
                    }
                }
            }
            Enumeration<? extends ZipEntry> all = z.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                if (registers(z.getInputStream(e).readAllBytes())) throw new Refused(name + " adds blocks or items");
            }
        }
    }

    /** Classes an access widener / class tweaker file touches. */
    static List<String> tweakerTargets(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.replaceAll("#.*", "").trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            if (p[0].startsWith("accessWidener") || p[0].startsWith("classTweaker")) continue;
            if (p.length >= 3 && (p[1].equals("class") || p[1].equals("method") || p[1].equals("field"))) out.add(p[2]);
            else if (p.length >= 2) out.add(p[1]); // inject-interface and other entries: target first
        }
        return out;
    }

    /** Targets of a {@code @Mixin} class, as binary names. */
    private static Set<String> mixinTargets(byte[] bytes) {
        Set<String> out = new LinkedHashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(desc)) return null;
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String n, Object value) {
                                if (value instanceof Type t) out.add(t.getClassName());
                                else if (value instanceof String s) out.add(s.replace('/', '.'));
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out;
    }

    private static boolean registers(byte[] bytes) {
        boolean[] hit = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        if (REGISTRY.equals(owner) && REGISTER.contains(n)) hit[0] = true;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return hit[0];
    }

    // =========================================================================== Loading (game thread)

    @SuppressWarnings("unchecked")
    private static Result apply(List<Unit> units) throws Exception {
        FabricLauncher launcher = FabricLauncherBase.getLauncher();
        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        List<String> warnings = new ArrayList<>(), names = new ArrayList<>();

        // 1. class path and mod list
        Field modsF = FabricLoaderImpl.class.getDeclaredField("mods"), mapF = FabricLoaderImpl.class.getDeclaredField("modMap"),
                adaptersF = FabricLoaderImpl.class.getDeclaredField("adapterMap");
        modsF.setAccessible(true);
        mapF.setAccessible(true);
        adaptersF.setAccessible(true);
        List<ModContainerImpl> mods = (List<ModContainerImpl>) modsF.get(loader);
        Map<String, ModContainerImpl> modMap = (Map<String, ModContainerImpl>) mapF.get(loader);
        Map<String, LanguageAdapter> adapters = (Map<String, LanguageAdapter>) adaptersF.get(loader);
        Method createPlain = ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class, boolean.class, Collection.class);
        createPlain.setAccessible(true);

        for (Unit u : units) {
            launcher.addToClassPath(u.path);
            ModCandidateImpl c = (ModCandidateImpl) createPlain.invoke(null, List.of(u.path), u.meta, false, List.of());
            u.container = new ModContainerImpl(c);
            mods.add(u.container);
            modMap.put(u.meta.getId(), u.container);
            for (String p : u.meta.getProvides()) modMap.putIfAbsent(p, u.container);
            names.add(u.meta.getName());
            LIVE.add(u.meta.getId());
        }

        // 2. language adapters (Kotlin, Scala… libraries loaded in the same batch)
        for (Unit u : units) {
            for (Map.Entry<String, String> e : u.meta.getLanguageAdapterDefinitions().entrySet()) {
                if (adapters.containsKey(e.getKey())) continue;
                adapters.put(e.getKey(), (LanguageAdapter) Class.forName(e.getValue(), true, launcher.getTargetClassLoader()).getDeclaredConstructor().newInstance());
            }
        }

        // 3. access wideners: applied by Fabric's transformer when the target class loads
        for (Unit u : units) {
            String t = u.meta.getClassTweaker();
            if (t == null) continue;
            try (BufferedReader r = Files.newBufferedReader(u.container.findPath(t).orElseThrow())) {
                // class-tweaker is only on the runtime class path: reached by reflection
                Object tweaker = FabricLoaderImpl.class.getMethod("getClassTweaker").invoke(loader);
                Class<?> readerType = Class.forName("net.fabricmc.classtweaker.api.ClassTweakerReader", true, FabricLoaderImpl.class.getClassLoader());
                Method create = null;
                for (Method m : readerType.getMethods()) if (m.getName().equals("create") && m.getParameterCount() == 1) create = m;
                Object reader = create.invoke(null, tweaker);
                readerType.getMethod("read", BufferedReader.class, String.class).invoke(reader, r, launcher.getMappingConfiguration().getRuntimeNamespace());
            }
        }

        // 4. mixins: Mixin only reads configs added late if asked to select again
        boolean mixins = false;
        for (Unit u : units) {
            for (String config : u.meta.getMixinConfigs(EnvType.CLIENT)) {
                Mixins.addConfiguration(config);
                for (org.spongepowered.asm.mixin.transformer.Config c : Mixins.getConfigs()) {
                    if (!c.getName().equals(config)) continue;
                    IMixinConfig mc = c.getConfig();
                    try {
                        mc.decorate(FabricUtil.KEY_MOD_ID, u.meta.getId());
                        mc.decorate(FabricUtil.KEY_COMPATIBILITY, FabricUtil.COMPATIBILITY_LATEST);
                    } catch (IllegalArgumentException ignored) {
                        // already decorated
                    }
                }
                mixins = true;
            }
        }
        if (mixins) {
            try {
                selectMixins();
            } catch (Exception e) {
                AuroraClient.LOGGER.warn("Hot load: mixins not selected", e);
                warnings.add("its game changes apply after a restart");
                return new Result(names, warnings); // no entrypoints without their mixins
            }
        }

        // 5. entrypoints, in Fabric's order
        Set<KeyMapping> keysBefore = new HashSet<>(Arrays.asList(Minecraft.getInstance().options.keyMappings));
        run(units, adapters, "preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch, warnings);
        run(units, adapters, "main", ModInitializer.class, ModInitializer::onInitialize, warnings);
        run(units, adapters, "client", ClientModInitializer.class, ClientModInitializer::onInitializeClient, warnings);

        // 6. keys created by the new mods: listed in Controls and working
        List<KeyMapping> extra = new ArrayList<>();
        for (KeyMapping k : KeyBindsAccessor.All.aurora$all().values()) if (!keysBefore.contains(k)) extra.add(k);
        if (!extra.isEmpty()) {
            var o = Minecraft.getInstance().options;
            KeyMapping[] all = Arrays.copyOf(o.keyMappings, o.keyMappings.length + extra.size());
            for (int i = 0; i < extra.size(); i++) all[o.keyMappings.length + i] = extra.get(i);
            ((KeyBindsAccessor.Keys) o).aurora$setKeyMappings(all);
            KeyMapping.resetMapping();
        }

        // 7. textures, models, sounds, translations
        if (units.stream().anyMatch(u -> u.hasAssets)) Minecraft.getInstance().reloadResourcePacks();
        return new Result(names, warnings);
    }

    private interface Call<T> {
        void call(T t) throws Exception;
    }

    private static <T> void run(List<Unit> units, Map<String, LanguageAdapter> adapters, String key, Class<T> type, Call<T> call, List<String> warnings) {
        for (Unit u : units) {
            for (EntrypointMetadata e : u.meta.getEntrypoints(key)) {
                try {
                    LanguageAdapter a = adapters.get(e.getAdapter());
                    if (a == null) throw new IllegalStateException("no language adapter " + e.getAdapter());
                    call.call(a.create(u.container, e.getValue(), type));
                } catch (Throwable t) {
                    AuroraClient.LOGGER.warn("Hot load: {} entrypoint of {} failed", key, u.meta.getId(), t);
                    warnings.add(u.meta.getName() + " did not fully start");
                }
            }
        }
    }

    /**
     * Null when the Fabric Loader and Mixin internals this class relies on look as expected, else what differs. Read
     * only (nothing is changed): checked before any live load, so a changed loader version makes live loading say no
     * instead of failing half-way through changing Fabric's state.
     */
    public static final String UNAVAILABLE = checkInternals();

    /** Null when access wideners of a live-loaded mod can be read; else why (such mods then wait for a restart). */
    static final String WIDENERS_UNAVAILABLE = checkWideners();

    private static String checkWideners() {
        try {
            Class.forName("net.fabricmc.classtweaker.api.ClassTweakerReader", false, FabricLoaderImpl.class.getClassLoader());
            return null;
        } catch (ClassNotFoundException | LinkageError e) {
            return "access wideners cannot be read live here";
        }
    }

    private static String checkInternals() {
        try {
            for (String f : List.of("mods", "modMap", "adapterMap")) FabricLoaderImpl.class.getDeclaredField(f);
            ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class, boolean.class, Collection.class);
            FabricLoaderImpl.class.getMethod("getClassTweaker");
            Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            if (transformer == null) return "no active Mixin transformer";
            Field pf = transformer.getClass().getDeclaredField("processor");
            pf.setAccessible(true);
            Object processor = pf.get(transformer);
            if (processor == null) return "no Mixin processor";
            processor.getClass().getDeclaredMethod("select", MixinEnvironment.class);
            return null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return "this Fabric Loader / Mixin version is not supported for live loading (" + e + ")";
        }
    }

    /** Asks Mixin to read the configurations added since startup (it otherwise only does so before the first class). */
    private static void selectMixins() throws Exception {
        MixinEnvironment env = MixinEnvironment.getCurrentEnvironment();
        Object transformer = env.getActiveTransformer();
        Field pf = transformer.getClass().getDeclaredField("processor");
        pf.setAccessible(true);
        Object processor = pf.get(transformer);
        Method select = processor.getClass().getDeclaredMethod("select", MixinEnvironment.class);
        select.setAccessible(true);
        Object lock = processor;
        try {
            Field lf = processor.getClass().getDeclaredField("lock");
            lf.setAccessible(true);
            lock = lf.get(processor);
        } catch (NoSuchFieldException ignored) {
            // older Mixin: no lock object
        }
        synchronized (lock) {
            select.invoke(processor, env);
        }
    }
}
