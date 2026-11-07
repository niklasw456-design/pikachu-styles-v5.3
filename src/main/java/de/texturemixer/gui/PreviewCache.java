package de.texturemixer.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.texturemixer.Category;
import de.texturemixer.PackScanner;
import de.texturemixer.TextureMixerClient;
import de.texturemixer.render.CrystalRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lädt Vorschaubilder aus Packs als GUI-Texturen und merkt sie sich.
 * Unterstützt .mcmeta-Animationen, fertig berechnete Bilder (Tränke) und den drehenden 3D-End-Crystal.
 */
public final class PreviewCache {
    /** Kantenlänge des Vorschaubereichs in GUI-Pixeln. */
    public static final int SIZE = 44;

    private static final Map<String, Preview> CACHE = new HashMap<>();
    private static int counter = 0;

    /** id == null bedeutet: in diesem Pack gibt es kein Vorschaubild. */
    public record Preview(Identifier id, int texW, int texH, int frameW, int frameH,
                          int dw, int dh, int[] frameIdx, int[] frameTicks, int total) {
        public int currentFrame() {
            if (frameIdx.length == 0) return 0;
            long t = (System.currentTimeMillis() / 50L) % Math.max(1, total);
            int acc = 0;
            for (int i = 0; i < frameIdx.length; i++) {
                acc += frameTicks[i];
                if (t < acc) return frameIdx[i];
            }
            return frameIdx[0];
        }
    }

    private static final Preview NONE = new Preview(null, 0, 0, 0, 0, 0, 0, new int[0], new int[0], 1);

    /**
     * Eine geladene Rüstungstextur. scale = ganzzahlige Vergrößerung beim Laden (scharfe Pixel),
     * grid() = Breite der Originaltextur in Pixeln (64 bei Vanilla, 128/256 bei HD-Packs).
     */
    public record Tex(Identifier id, int texW, int texH, int scale) {
        public int grid() {
            return texW / scale;
        }
    }

    /** Getragene Rüstung: outer = Helm/Brustplatte/Stiefel, legs = Hose (je null, falls das Pack sie nicht hat). */
    public record Body(Tex outer, Tex legs) {
        public boolean isEmpty() {
            return outer == null && legs == null;
        }
    }

    private static final Body NO_BODY = new Body(null, null);
    private static final Map<String, Body> BODY_CACHE = new HashMap<>();

    private PreviewCache() {}

    private static String key(PackScanner.PackInfo pack, Category c, Category.Variant v) {
        return pack.name() + "|" + pack.path().toFile().lastModified() + "|" + c.fullId(v);
    }

    /** Gibt das Bild zurück, falls es schon geladen wurde, sonst null. */
    public static Preview peek(PackScanner.PackInfo pack, Category c, Category.Variant v) {
        return CACHE.get(key(pack, c, v));
    }

    /** Lädt das Bild sofort (muss im Render-Thread aufgerufen werden). */
    public static Preview load(PackScanner.PackInfo pack, Category c, Category.Variant v) {
        String k = key(pack, c, v);
        Preview existing = CACHE.get(k);
        if (existing != null) return existing;

        Preview result = NONE;
        try {
            PackScanner.PreviewData data = PackScanner.readPreview(pack, v);
            if (data != null) result = create(data, v);
        } catch (Throwable e) {
            TextureMixerClient.LOGGER.warn("Vorschau für {} / {} konnte nicht geladen werden", pack.name(), c.fullId(v), e);
        }
        CACHE.put(k, result);
        return result;
    }

    private static String bodyKey(PackScanner.PackInfo pack, Category c, Category.Variant v) {
        String prefix = pack == null ? "vanilla|" : pack.name() + "|" + pack.path().toFile().lastModified() + "|";
        return prefix + c.fullId(v);
    }

    /** Gibt die Rüstung zurück, falls sie schon geladen wurde, sonst null. pack == null = Vanilla ("Standard"). */
    public static Body peekBody(PackScanner.PackInfo pack, Category c, Category.Variant v) {
        return BODY_CACHE.get(bodyKey(pack, c, v));
    }

    /** Lädt die getragene Rüstung sofort (Render-Thread). pack == null = Vanilla ("Standard"). */
    public static Body loadBody(PackScanner.PackInfo pack, Category c, Category.Variant v) {
        String k = bodyKey(pack, c, v);
        Body existing = BODY_CACHE.get(k);
        if (existing != null) return existing;

        Body result = NO_BODY;
        try {
            String material = v.material();
            PackScanner.BodyData data = pack == null
                    ? PackScanner.readVanillaBody(material)
                    : PackScanner.readBody(pack, material);
            if (data != null) {
                Tex outer = data.outer() == null ? null : registerArmor(data.outer());
                Tex legs = data.legs() == null ? null : registerArmor(data.legs());
                result = new Body(outer, legs);
            }
        } catch (Throwable e) {
            TextureMixerClient.LOGGER.warn("Rüstungs-Vorschau für {} / {} konnte nicht geladen werden",
                    pack == null ? "Standard" : pack.name(), c.fullId(v), e);
        }
        BODY_CACHE.put(k, result);
        return result;
    }

    /** Lädt eine Rüstungstextur als GUI-Textur, ganzzahlig vergrößert (Vanilla 64 px -> Faktor 2). */
    private static Tex registerArmor(byte[] png) throws Exception {
        NativeImage src = NativeImage.read(new ByteArrayInputStream(png));
        int w = src.getWidth();
        int h = src.getHeight();
        int k = Math.max(1, w / 64);
        int s = Math.max(1, BodyView.Z / k);
        NativeImage dst = new NativeImage(NativeImage.Format.RGBA, w * s, h * s, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = src.getColorArgb(x, y);
                for (int dy = 0; dy < s; dy++) {
                    for (int dx = 0; dx < s; dx++) {
                        dst.setColorArgb(x * s + dx, y * s + dy, c);
                    }
                }
            }
        }
        src.close();

        NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "texturemixer_armor", dst);
        Identifier id = Identifier.of(TextureMixerClient.MOD_ID, "preview/" + (counter++));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
        return new Tex(id, w * s, h * s, s);
    }

    private static Preview create(PackScanner.PreviewData data, Category.Variant variant) throws Exception {
        int[] argb;
        int w;
        int h;
        if (data.argb() != null) {
            argb = data.argb();
            w = data.w();
            h = data.h();
        } else {
            NativeImage src = NativeImage.read(new ByteArrayInputStream(data.png()));
            w = src.getWidth();
            h = src.getHeight();
            argb = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) argb[y * w + x] = src.getColorArgb(x, y);
            }
            src.close();
        }

        // End Crystal: drehendes 3D-Modell aus der Entity-Textur berechnen
        if ("crystal".equals(variant.render())) {
            int[] strip = CrystalRenderer.render(argb, w, h);
            List<int[]> frames = new ArrayList<>();
            for (int i = 0; i < CrystalRenderer.FRAMES; i++) frames.add(new int[]{i, 3});
            return build(strip, CrystalRenderer.FRAME, CrystalRenderer.FRAME * CrystalRenderer.FRAMES,
                    CrystalRenderer.FRAME, CrystalRenderer.FRAME, frames);
        }

        int fw = w;
        int fh = h;
        int frametime = 1;
        boolean animated = false;
        List<int[]> frames = new ArrayList<>();

        if (data.mcmeta() != null) {
            try {
                JsonObject root = JsonParser.parseString(new String(data.mcmeta(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.has("animation") && root.get("animation").isJsonObject()) {
                    JsonObject a = root.getAsJsonObject("animation");
                    animated = true;
                    fw = a.has("width") ? a.get("width").getAsInt() : w;
                    fh = a.has("height") ? a.get("height").getAsInt() : fw;
                    if (fw <= 0 || fh <= 0 || fw > w || fh > h) {
                        fw = w;
                        fh = Math.min(h, w);
                    }
                    if (a.has("frametime")) frametime = Math.max(1, a.get("frametime").getAsInt());
                    if (a.has("frames") && a.get("frames").isJsonArray()) {
                        for (JsonElement e : a.getAsJsonArray("frames")) {
                            int idx;
                            int time = frametime;
                            if (e.isJsonPrimitive()) {
                                idx = e.getAsInt();
                            } else if (e.isJsonObject()) {
                                JsonObject o = e.getAsJsonObject();
                                idx = o.has("index") ? o.get("index").getAsInt() : 0;
                                if (o.has("time")) time = Math.max(1, o.get("time").getAsInt());
                            } else {
                                continue;
                            }
                            frames.add(new int[]{idx, time});
                        }
                    }
                }
            } catch (Exception ignored) {
                animated = false;
            }
        }

        if (!animated) {
            fw = w;
            fh = h;
            frames.clear();
        }

        int count = Math.max(1, h / fh);
        final int cnt = count;
        frames.removeIf(f -> f[0] < 0 || f[0] >= cnt);
        if (frames.isEmpty()) {
            if (animated) {
                for (int i = 0; i < count; i++) frames.add(new int[]{i, frametime});
            } else {
                frames.add(new int[]{0, 1});
            }
        }
        return build(argb, w, h, fw, fh, frames);
    }

    /** Vergrößert das Bild ganzzahlig (scharfe Pixel), lädt es als Textur und legt die Animation fest. */
    private static Preview build(int[] argb, int w, int h, int fw, int fh, List<int[]> frames) {
        int s = (int) Math.ceil((double) SIZE / Math.max(fw, fh));
        s = Math.max(1, Math.min(8, s));
        int nw = w * s;
        int nh = h * s;
        NativeImage dst = new NativeImage(NativeImage.Format.RGBA, nw, nh, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                for (int dy = 0; dy < s; dy++) {
                    for (int dx = 0; dx < s; dx++) {
                        dst.setColorArgb(x * s + dx, y * s + dy, c);
                    }
                }
            }
        }

        NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "texturemixer_preview", dst);
        Identifier id = Identifier.of(TextureMixerClient.MOD_ID, "preview/" + (counter++));
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);

        int sfw = fw * s;
        int sfh = fh * s;
        float f = Math.min((float) SIZE / sfw, (float) SIZE / sfh);
        int dw = Math.max(1, Math.round(sfw * f));
        int dh = Math.max(1, Math.round(sfh * f));

        int[] idx = new int[frames.size()];
        int[] ticks = new int[frames.size()];
        int total = 0;
        for (int i = 0; i < frames.size(); i++) {
            idx[i] = frames.get(i)[0];
            ticks[i] = frames.get(i)[1];
            total += ticks[i];
        }
        return new Preview(id, nw, nh, sfw, sfh, dw, dh, idx, ticks, Math.max(1, total));
    }
}
