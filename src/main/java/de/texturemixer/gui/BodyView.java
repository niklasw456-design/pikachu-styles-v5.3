package de.texturemixer.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * Zeichnet den eigenen Spieler-Skin (Vorderansicht) mit angezogener Rüstung aus einem Pack.
 * Alles läuft über einzelne Texturausschnitte (kein Pixel-Zugriff auf den Skin nötig):
 * zuerst Skin und zweite Skin-Ebene, darüber die Hose, ganz oben Helm, Brustplatte und Stiefel.
 *
 * Maße in Skin-Pixeln: Figur 16 x 32 (Kopf 8x8, Körper 8x12, Arme 4x12, Beine 4x12).
 * Z = GUI-Pixel pro Skin-Pixel.
 */
final class BodyView {
    static final int Z = 2;
    static final int W = 16 * Z;
    static final int H = 32 * Z;

    private static final Identifier STEVE = Identifier.of("minecraft", "textures/entity/player/wide/steve.png");

    private BodyView() {}

    /** Textur des eigenen Skins, ohne Welt (z. B. im Titelbildschirm) Steve. */
    private static Identifier skin() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                return mc.player.getSkin().body().texturePath();
            }
        } catch (Throwable ignored) {
            // fällt auf Steve zurück
        }
        return STEVE;
    }

    /** x, y = linke obere Ecke der Figur (W x H GUI-Pixel). */
    static void draw(DrawContext ctx, int x, int y, PreviewCache.Body body) {
        Identifier skin = skin();

        int headX = x + 4 * Z;
        int armY = y + 8 * Z;
        int legY = y + 20 * Z;
        int rightArmX = x;                // rechter Arm des Spielers = links im Bild
        int leftArmX = x + 12 * Z;
        int rightLegX = x + 4 * Z;
        int leftLegX = x + 8 * Z;

        // Skin + zweite Skin-Ebene (Hut, Jacke, Ärmel, Hosenbeine)
        skinPart(ctx, skin, 8, 8, 8, 8, headX, y);
        skinPart(ctx, skin, 40, 8, 8, 8, headX, y);
        skinPart(ctx, skin, 20, 20, 8, 12, headX, armY);
        skinPart(ctx, skin, 20, 36, 8, 12, headX, armY);
        skinPart(ctx, skin, 44, 20, 4, 12, rightArmX, armY);
        skinPart(ctx, skin, 44, 36, 4, 12, rightArmX, armY);
        skinPart(ctx, skin, 36, 52, 4, 12, leftArmX, armY);
        skinPart(ctx, skin, 52, 52, 4, 12, leftArmX, armY);
        skinPart(ctx, skin, 4, 20, 4, 12, rightLegX, legY);
        skinPart(ctx, skin, 4, 36, 4, 12, rightLegX, legY);
        skinPart(ctx, skin, 20, 52, 4, 12, leftLegX, legY);
        skinPart(ctx, skin, 4, 52, 4, 12, leftLegX, legY);

        // Hose (Layer 2): Bund am Körper + beide Beine
        PreviewCache.Tex legs = body.legs();
        if (legs != null) {
            armorPart(ctx, legs, 20, 20, 8, 12, headX, armY, false);
            armorPart(ctx, legs, 4, 20, 4, 12, rightLegX, legY, false);
            armorPart(ctx, legs, 4, 20, 4, 12, leftLegX, legY, true);
        }

        // Helm, Brustplatte (mit Ärmeln) und Stiefel (Layer 1); linke Gliedmaßen sind gespiegelt
        PreviewCache.Tex outer = body.outer();
        if (outer != null) {
            armorPart(ctx, outer, 4, 20, 4, 12, rightLegX, legY, false);
            armorPart(ctx, outer, 4, 20, 4, 12, leftLegX, legY, true);
            armorPart(ctx, outer, 20, 20, 8, 12, headX, armY, false);
            armorPart(ctx, outer, 44, 20, 4, 12, rightArmX, armY, false);
            armorPart(ctx, outer, 44, 20, 4, 12, leftArmX, armY, true);
            armorPart(ctx, outer, 8, 8, 8, 8, headX, y, false);
        }
    }

    /** Ausschnitt aus dem 64x64-Skin, Z-fach vergrößert. */
    private static void skinPart(DrawContext ctx, Identifier skin, int u, int v, int w, int h, int dx, int dy) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, skin, dx, dy,
                (float) u, (float) v, w * Z, h * Z, w, h, 64, 64);
    }

    /**
     * Ausschnitt aus einer Rüstungstextur. u, v, w, h gelten im 64x32-Raster; HD-Packs (128, 256 ...)
     * werden über den Faktor m umgerechnet. mirror = waagerecht gespiegelt (linker Arm/Fuß).
     */
    private static void armorPart(DrawContext ctx, PreviewCache.Tex t, int u, int v, int w, int h,
                                  int dx, int dy, boolean mirror) {
        int m = Math.max(1, t.grid() / 64) * t.scale();
        if (!mirror) {
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, t.id(), dx, dy,
                    (float) (u * m), (float) (v * m), w * Z, h * Z, w * m, h * m, t.texW(), t.texH());
            return;
        }
        // Spiegeln Spalte für Spalte (jede Spalte = ein Pixel des Rasters)
        for (int c = 0; c < w; c++) {
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, t.id(), dx + c * Z, dy,
                    (float) ((u + w - 1 - c) * m), (float) (v * m), Z, h * Z, m, h * m, t.texW(), t.texH());
        }
    }
}
