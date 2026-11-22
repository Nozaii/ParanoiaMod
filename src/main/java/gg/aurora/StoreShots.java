package gg.aurora;

import gg.aurora.ui.ModIcons;
import gg.aurora.ui.ModsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Outil de développement : avec -Daurora.storeshots=true et un monde ouvert, le Store contre le vrai Modrinth —
 * pages suivantes au défilement, vérification « sans redémarrage » de chaque mod, filtre, page d'un projet (images,
 * description, versions), barre de progression d'un téléchargement, jar déposé sur l'écran (« Add ») démarré sans
 * redémarrage. Rapport : screenshots/store-report.txt, captures store_*.png. Inactif en jeu normal.
 */
final class StoreShots {
    private StoreShots() {}

    static final boolean ENABLED = Boolean.getBoolean("aurora.storeshots");
    private static int step = -1, failed, ticks;
    private static long since;
    private static ModsScreen screen;
    private static ModManager.Hit page, pack;
    private static float maxProgress = -1;
    private static boolean midShot;
    private static Path packFile, dropped;
    private static final java.util.Set<Path> before = new java.util.HashSet<>();
    private static final List<String> out = new ArrayList<>();

    static void tick(Minecraft mc) {
        if (!ENABLED) return;
        if (step < 0) {
            if (mc.player == null || mc.level == null || mc.screen != null) return;
            step = 0;
            since = System.currentTimeMillis();
        }
        ticks++;
        mc.getFramerateLimitTracker().onInputReceived();
        try {
            run(mc);
        } catch (Throwable t) {
            check("no exception", false, t.toString());
            AuroraClient.LOGGER.warn("[store] failed", t);
            finish(mc);
        }
    }

    /** Waits for {@code cond}; true once it holds. After {@code maxS} seconds, records a failure and moves on. */
    private static boolean await(String what, BooleanSupplier cond, int maxS) {
        if (cond.getAsBoolean()) return true;
        if (System.currentTimeMillis() - since > maxS * 1000L) {
            check(what + " (within " + maxS + " s)", false, "timed out");
            return true;
        }
        return false;
    }

    private static void next() {
        step++;
        since = System.currentTimeMillis();
        ticks = 0;
    }

    private static int count(ModManager.Verdict v) {
        int n = 0;
        for (ModManager.Hit h : ModManager.results) if (h.verdict == v) n++;
        return n;
    }

    private static void run(Minecraft mc) throws Exception {
        switch (step) {
            case 0 -> {
                screen = new ModsScreen(new PauseScreen(true));
                mc.setScreen(screen);
                screen.debugPage(1, 0);
                next();
            }
            case 1 -> {
                if (!await("first page of Modrinth mods", () -> !ModManager.searching && !ModManager.results.isEmpty(), 30)) return;
                check("the store lists Modrinth's Fabric mods for this game, first page of 40, many more to come",
                        ModManager.results.size() == 40 && ModManager.totalHits > 1000 && ModManager.more(),
                        ModManager.results.size() + " of " + ModManager.totalHits);
                next();
            }
            case 2 -> {
                if (ticks == 40) shot(mc, "store_list");
                if (ticks < 50) return;
                screen.debugScrollEnd();
                if (!await("the next page comes when the list is scrolled to its end", () -> ModManager.results.size() >= 80, 30)) return;
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (ModManager.Hit h : ModManager.results) ids.add(h.projectId);
                check("scrolling down loads the next pages, no project twice", ModManager.results.size() >= 80 && ids.size() == ModManager.results.size(),
                        ModManager.results.size() + " listed, " + ids.size() + " distinct");
                next();
            }
            case 3 -> {
                if (ticks == 1) screen.debugScrollTop();
                if (ticks < 120) return; // the top cards draw, so their icons are asked for
                if (!await("most of the first 40 mods checked", () -> count(ModManager.Verdict.CHECKING) == 0
                        && count(ModManager.Verdict.HOT) + count(ModManager.Verdict.RESTART) + count(ModManager.Verdict.NONE) >= 30, 240)) return;
                int hot = count(ModManager.Verdict.HOT), restart = count(ModManager.Verdict.RESTART), none = count(ModManager.Verdict.NONE),
                        unknown = count(ModManager.Verdict.UNKNOWN);
                check("each mod is read before install and sorted: starts now, or only after a restart", hot + restart >= 30,
                        hot + " no restart, " + restart + " restart, " + none + " no version, " + unknown + " not checked, of " + ModManager.results.size());
                ModManager.Hit why = null;
                for (ModManager.Hit h : ModManager.results) if (h.verdict == ModManager.Verdict.RESTART) { why = h; break; }
                check("a mod that needs a restart says why", why == null || why.verdictWhy != null && !why.verdictWhy.isBlank(),
                        why == null ? "" : why.title + ": " + cut(why.verdictWhy));
                int icons = 0, webp = 0, webpLoaded = 0;
                // icons are fetched when their card is drawn: the four cards on screen, then any WebP one met so far
                List<ModManager.Hit> top = screen.debugShownHits().subList(0, 4);
                for (ModManager.Hit h : ModManager.results) if (h.iconUrl != null && h.iconUrl.toLowerCase().endsWith(".webp") && ModIcons.loaded(h.iconUrl)) webpLoaded++;
                for (ModManager.Hit h : top) {
                    if (h.iconUrl == null) continue;
                    boolean ok = ModIcons.loaded(h.iconUrl);
                    if (ok) icons++;
                    if (h.iconUrl.toLowerCase().endsWith(".webp")) webp++;
                }
                check("the mods' icons are shown, WebP ones included", icons == top.stream().filter(h -> h.iconUrl != null).count() && webpLoaded >= 3, icons + "/4 on screen (" + webp + " WebP), WebP icons decoded: " + webpLoaded);
                shot(mc, "store_badges");
                next();
            }
            case 4 -> {
                screen.debugRestartFilter(1);
                if (ticks < 30) return;
                List<ModManager.Hit> shown = screen.debugShownHits();
                boolean only = shown.stream().allMatch(h -> h.verdict == ModManager.Verdict.HOT);
                check("filter « No restart » shows only mods that start without a restart", only && shown.size() == count(ModManager.Verdict.HOT), shown.size() + " shown");
                shot(mc, "store_filter_norestart");
                next();
            }
            case 5 -> {
                screen.debugRestartFilter(2);
                if (ticks < 30) return;
                List<ModManager.Hit> shown = screen.debugShownHits();
                boolean only = shown.stream().allMatch(h -> h.verdict == ModManager.Verdict.RESTART);
                check("filter « Restart » shows only mods that need one", only && shown.size() == count(ModManager.Verdict.RESTART), shown.size() + " shown");
                shot(mc, "store_filter_restart");
                screen.debugRestartFilter(0);
                next();
            }
            case 6 -> {
                for (ModManager.Hit h : ModManager.results) {
                    if (!h.gallery.isEmpty()) {
                        page = h;
                        break;
                    }
                }
                if (page == null) page = ModManager.results.get(0);
                screen.debugDetail(page);
                next();
            }
            case 7 -> {
                ModManager.Details d = ModManager.details(page);
                if (ticks < 20) return;
                if (!await("project page loaded (description, versions, first picture)", () -> d.loaded && !d.versions.isEmpty()
                        && (d.gallery.isEmpty() || ModIcons.loaded(d.gallery.get(0))), 40)) return;
                check("the project page shows its description, pictures and versions in the game",
                        d.body.length() > 100 && !d.versions.isEmpty() && d.versions.stream().anyMatch(v -> v.fits) && !d.body.contains("](") && !d.body.contains("<"),
                        page.title + ": " + d.body.length() + " chars, " + d.gallery.size() + " pictures, " + d.versions.size() + " versions");
                shot(mc, "store_detail");
                screen.debugScrollDetail(2000);
                next();
            }
            case 8 -> {
                if (ticks == 30) shot(mc, "store_detail_versions");
                if (ticks < 35) return;
                screen.debugCloseDetail();
                screen.debugPage(1, 1); // resource packs: a real download, nothing loaded into the game
                next();
            }
            case 9 -> {
                if (!await("resource packs listed", () -> !ModManager.searching && ModManager.resultsKind == ModManager.Kind.RESOURCE_PACK
                        && !ModManager.results.isEmpty(), 30)) return;
                for (ModManager.Hit h : ModManager.results) {
                    if (!h.installed()) {
                        pack = h;
                        break;
                    }
                }
                Path rp = Files.createDirectories(mc.gameDirectory.toPath().resolve("resourcepacks"));
                try (var l = Files.list(rp)) {
                    l.forEach(before::add);
                }
                ModManager.install(pack);
                next();
            }
            case 10 -> {
                if (pack.progress >= 0) {
                    maxProgress = Math.max(maxProgress, pack.progress);
                    if (!midShot && pack.progress > 0.15f && pack.progress < 0.85f) {
                        midShot = true;
                        shot(mc, "store_progress");
                    }
                }
                if (!await("the pack download ends", () -> "Installed".equals(pack.state), 90)) return;
                check("a download is streamed with its progress shown, then « Installed »", pack.progressSteps > 1 && "Installed".equals(pack.state),
                        pack.progressSteps + " progress updates" + (maxProgress > 0 ? ", bar seen at " + Math.round(maxProgress * 100) + "%" : "") + (midShot ? ", captured mid-way" : ""));
                try (var l = Files.list(mc.gameDirectory.toPath().resolve("resourcepacks"))) {
                    packFile = l.filter(p -> !before.contains(p)).findFirst().orElse(null);
                }
                next();
            }
            case 11 -> {
                // « Add »: a jar dropped onto the screen goes to mods/ and starts without a restart
                screen.debugPage(0, 0);
                dropped = mc.gameDirectory.toPath().resolve("mods/example-ticks-1.0.0.jar");
                Files.deleteIfExists(dropped);
                screen.onFilesDrop(List.of(Path.of(System.getProperty("aurora.hotjar"))));
                next();
            }
            case 12 -> {
                if (!await("dropped jar running", () -> live("example_ticks"), 30)) return;
                check("a jar dropped on the Mods screen is copied in and started without a restart",
                        Files.exists(dropped) && "ACTIVE".equals(HotBridge.state("example_ticks")) && !ModManager.restartNeeded(), String.valueOf(HotBridge.state("example_ticks")));
                shot(mc, "store_dropped");
                for (ModManager.Installed i : ModManager.installed) if ("example_ticks".equals(i.modId)) ModManager.remove(i);
                next();
            }
            case 13 -> {
                if (!await("dropped jar removed", () -> HotBridge.state("example_ticks") == null && !Files.exists(dropped), 30)) return;
                check("and removed again without a restart", HotBridge.state("example_ticks") == null && !Files.exists(dropped), "");
                finish(mc);
            }
            default -> {}
        }
    }

    private static boolean live(String id) {
        for (ModManager.Installed i : ModManager.installed) if (id.equals(i.modId) && i.live) return true;
        return false;
    }

    private static String cut(String s) {
        return s == null ? "" : s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) failed++;
        String s = (ok ? "PASS " : "FAIL ") + name + (detail.isEmpty() ? "" : "  [" + detail + "]");
        AuroraClient.LOGGER.info("[store] {}", s);
        out.add(s);
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1, msg -> {});
    }

    private static void finish(Minecraft mc) {
        out.add(failed == 0 ? "ALL PASSED (" + out.size() + " checks)" : failed + " FAILED");
        try {
            if (packFile != null) Files.deleteIfExists(packFile);
            Path dir = Files.createDirectories(mc.gameDirectory.toPath().resolve("screenshots"));
            Files.write(dir.resolve("store-report.txt"), out);
        } catch (Exception e) {
            AuroraClient.LOGGER.warn("store report not written", e);
        }
        mc.stop();
    }
}
