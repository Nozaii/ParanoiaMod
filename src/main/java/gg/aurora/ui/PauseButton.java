package gg.aurora.ui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Boutons du menu Échap : « Paranoia Client » et « Mods ».
 * <p>
 * « Paranoia Client » remplace « Give Feedback » (ou « Feedback... » / « Report Bugs ») ; s'il n'existe plus
 * (déjà remplacé par un autre mod), une ligne pleine largeur est insérée au-dessus de « Save and Quit ».
 * <p>
 * « Mods » ouvre le gestionnaire de mods de Paranoia. Il prend la place du bouton de Mod Menu quand ce mod
 * est installé (son bouton, quel que soit son style, disparaît), sinon celle de « Report Bugs ».
 */
public final class PauseButton {
    private PauseButton() {}

    private static final Set<String> REPLACE = Set.of("menu.sendFeedback", "menu.feedback", "menu.reportBugs");
    private static final String MOD_MENU = "com.terraformersmc.modmenu";

    public static void attach(Screen screen) {
        List<AbstractWidget> buttons = Screens.getButtons(screen);
        // Mod Menu a déjà refait la ligne Feedback / Bugs en un bouton pleine largeur : on la partage
        // « Paranoia Client | Mods » au lieu d'ajouter une ligne
        boolean modMenu = false;
        for (AbstractWidget b : buttons) modMenu |= modMenu(b);
        if (modMenu) {
            replaceModMenu(buttons, true);
            ScreenEvents.beforeTick(screen).register(s -> replaceModMenu(Screens.getButtons(s), true));
            return;
        }
        AbstractWidget victim = null;
        // un bouton caché (Mod Menu cache « Report Bugs ») ne compte pas
        for (AbstractWidget b : buttons) if (b instanceof Button && b.visible && REPLACE.contains(key(b))) {
            victim = b;
            break;
        }
        Button mine;
        if (victim != null) {
            mine = build(victim.getX(), victim.getY(), victim.getWidth(), victim.getHeight());
            buttons.remove(victim);
        } else {
            // ligne supplémentaire, alignée sur la grille vanilla (largeur de « Back to Game ») : les boutons
            // ajoutés par un serveur ou un autre mod sur les côtés ne comptent pas
            AbstractWidget ref = null;
            for (AbstractWidget b : buttons) if (b instanceof Button && "menu.returnToGame".equals(key(b))) ref = b;
            if (ref == null) {
                for (AbstractWidget b : buttons) if (b instanceof Button && (ref == null || b.getWidth() > ref.getWidth())) ref = b;
            }
            if (ref == null) return;
            int x0 = ref.getX(), x1 = ref.getX() + ref.getWidth();
            AbstractWidget bottom = null;
            for (AbstractWidget b : buttons) {
                if (!(b instanceof Button) || b.getX() < x0 - 2 || b.getX() + b.getWidth() > x1 + 2) continue;
                if (bottom == null || b.getY() > bottom.getY()) bottom = b;
            }
            if (bottom == null) bottom = ref;
            mine = build(x0, bottom.getY(), x1 - x0, bottom.getHeight());
            bottom.setY(bottom.getY() + 24);
        }
        buttons.add(mine);

        if (!replaceModMenu(buttons, true)) {
            AbstractWidget bugs = null;
            for (AbstractWidget b : buttons) if (b instanceof Button && "menu.reportBugs".equals(key(b))) bugs = b;
            if (bugs != null) {
                buttons.remove(bugs);
                buttons.add(mods(bugs.getX(), bugs.getY(), bugs.getWidth(), bugs.getHeight()));
            }
        }
        // Mod Menu réorganise la ligne Feedback / Bugs après nous (et retire notre bouton au passage) :
        // on repasse au premier tick de l'écran
        ScreenEvents.beforeTick(screen).register(s -> replaceModMenu(Screens.getButtons(s), true));
    }

    /** Écran titre vanilla (titre Paranoia désactivé) : seul le bouton de Mod Menu est remplacé. */
    public static void takeOverModMenu(Screen screen) {
        replaceModMenu(Screens.getButtons(screen), false);
        ScreenEvents.beforeTick(screen).register(s -> replaceModMenu(Screens.getButtons(s), false));
    }

    /**
     * Retire les boutons de Mod Menu et met « Mods » à la place du premier. Vrai s'il y en avait.
     * Menu Échap : si « Paranoia Client » a disparu (Mod Menu a refait la ligne), la place est partagée en deux.
     */
    private static boolean replaceModMenu(List<AbstractWidget> buttons, boolean pause) {
        List<AbstractWidget> theirs = new ArrayList<>();
        for (AbstractWidget b : buttons) if (modMenu(b)) theirs.add(b);
        if (theirs.isEmpty()) return false;
        AbstractWidget first = theirs.get(0);
        buttons.removeAll(theirs);
        int x = first.getX(), y = first.getY(), w = first.getWidth(), h = first.getHeight();
        // « Paranoia Client » posé dans la même ligne que le bouton pleine largeur de Mod Menu : on refait la ligne
        AbstractWidget sameRow = null, elsewhere = null;
        for (AbstractWidget b : buttons) {
            if (!PARANOIA.equals(b.getMessage().getString())) continue;
            if (b.getY() == y) sameRow = b;
            else elsewhere = b;
        }
        if (sameRow != null) buttons.remove(sameRow);
        if (pause && elsewhere == null && w >= 120) {
            int half = (w - 4) / 2;
            buttons.add(build(x, y, half, h));
            buttons.add(mods(x + w - half, y, half, h));
        } else {
            buttons.add(mods(x, y, w, h));
        }
        return true;
    }

    private static final String PARANOIA = "Paranoia Client";
    /** Nos boutons « Mods » : même texte que celui de Mod Menu, on ne doit pas les prendre pour le sien. */
    private static final Set<AbstractWidget> OURS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Bouton de Mod Menu : ses propres classes (style icône), ou un bouton vanilla « modmenu.title » (style texte). */
    private static boolean modMenu(AbstractWidget b) {
        if (OURS.contains(b)) return false;
        if (b.getClass().getName().startsWith(MOD_MENU)) return true;
        if (key(b).startsWith("modmenu")) return true;
        for (Component part : b.getMessage().toFlatList()) {
            if (part.getContents() instanceof TranslatableContents t && t.getKey().startsWith("modmenu")) return true;
        }
        return false;
    }

    private static Button build(int x, int y, int w, int h) {
        return Button.builder(Component.literal(PARANOIA), b -> Minecraft.getInstance().setScreen(new ClickGuiScreen()))
                .bounds(x, y, w, h).build();
    }

    private static Button mods(int x, int y, int w, int h) {
        Button b = modsButton(x, y, w, h);
        OURS.add(b);
        return b;
    }

    private static Button modsButton(int x, int y, int w, int h) {
        return Button.builder(Component.literal(w < 40 ? "M" : "Mods"), b -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.setScreen(new ModsScreen(mc.screen)); // Back revient au menu Échap (ou à l'écran titre)
                })
                .bounds(x, y, w, h).build();
    }

    private static String key(AbstractWidget b) {
        return b.getMessage().getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }
}
