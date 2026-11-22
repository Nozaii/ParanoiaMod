package gg.aurora.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Saisie de texte pour le menu dans le monde : écran invisible qui envoie le clavier au panneau
 * (recherche, champs texte). Entrée, Échap ou un clic rendent la main au jeu.
 */
public class WorldTypingScreen extends Screen {
    public WorldTypingScreen() {
        super(Component.literal("Typing"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        Draw.centered(g, "Typing in the menu · Enter to confirm", width / 2f, height - 40, 0.8f, Draw.withAlpha(0xFFFFFF, 0.8f));
    }

    private static void forward(Runnable r) {
        if (WorldPanel.hosted == null) return;
        boolean was = WorldPanel.inHostedCall;
        WorldPanel.inHostedCall = true;
        try {
            r.run();
        } finally {
            WorldPanel.inHostedCall = was;
        }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        forward(() -> WorldPanel.hosted.charTyped(event));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int k = event.key();
        if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER || k == GLFW.GLFW_KEY_ESCAPE) {
            if (WorldPanel.hosted instanceof ClickGuiScreen c) c.endTyping();
            minecraft.setScreen(null);
            return true;
        }
        forward(() -> WorldPanel.hosted.keyPressed(event));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (WorldPanel.hosted instanceof ClickGuiScreen c) c.endTyping();
        minecraft.setScreen(null);
        return true;
    }
}
