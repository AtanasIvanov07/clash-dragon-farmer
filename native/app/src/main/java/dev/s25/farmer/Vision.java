package dev.s25.farmer;

import android.graphics.*;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Local-only image matching and bundled OCR. The manifest denies networking. */
public final class Vision implements AutoCloseable {
    private final Store store;
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final Map<String, Template> templates = new HashMap<>();
    private boolean warmed;
    private int dragonShift, unreadableShots;
    public Vision(Store store) { this.store = store; }
    private static final class Template {
        int w, h;
        int[] pixels;
        Template(Bitmap bitmap) {
            w = bitmap.getWidth(); h = bitmap.getHeight(); pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        }
    }
    public static void validateTemplate(Bitmap b) throws IOException {
        long sum = 0, sq = 0; int count = 0;
        for (int y = 0; y < b.getHeight(); y += Math.max(1, b.getHeight() / 12)) {
            for (int x = 0; x < b.getWidth(); x += Math.max(1, b.getWidth() / 12)) {
                int c = gray(b.getPixel(x, y)); sum += c; sq += c * c; count++;
            }
        }
        double variance = (double) sq / count - Math.pow((double) sum / count, 2);
        if (variance < 36) throw new IOException("That crop is almost one color. Include a distinctive label or icon.");
    }
    private Template template(String key) throws IOException {
        if (!templates.containsKey(key)) {
            Bitmap b = BitmapFactory.decodeFile(new File(store.directory, "templates/" + key + ".png").getPath());
            if (b == null) throw new IOException("Missing template: " + key);
            try { templates.put(key, new Template(b)); } finally { b.recycle(); }
        }
        return templates.get(key);
    }
    private static int gray(int color) {
        return (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) >> 8;
    }
    public int[] match(Frame f, String key) throws IOException {
        int[] r = store.rectangle("templates", key);
        if (!Rules.rect(r, f.width(), f.height())) throw new IOException("Calibrate " + key + " first.");
        Template t = template(key);
        if (t.w != r[2] || t.h != r[3]) throw new IOException("Template dimensions differ from calibration.");
        int[] pixels = f.pixels();
        // 8 px was tuned on a 1080p-tall S25 screen. Keep the same physical slack on
        // taller panels (1440p Poco/Xiaomi) by scaling with the short screen side.
        int tolerance = key.equals("home_anchor") ? 1 : Math.max(8, Math.round(8f * Math.min(f.width(), f.height()) / 1080f));
        // Clash centers the troop bar, so the dragon card slides sideways when the
        // army has more or fewer cards (Clan Castle troops, spells, sieges).
        boolean troopBar = key.equals("dragon") || Stages.EXTRA_CARDS.contains(key);
        int minX = troopBar ? 0 : Math.max(0, r[0] - tolerance), maxX = troopBar ? f.width() - t.w : Math.min(f.width() - t.w, r[0] + tolerance);
        int minY = Math.max(0, r[1] - tolerance), maxY = Math.min(f.height() - t.h, r[1] + tolerance);
        double best = 0.94; int[] found = null;
        for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
            double a = 0, b = 0, aa = 0, bb = 0, ab = 0, error = 0; int count = 0;
            for (int ty = 0; ty < t.h; ty += Math.max(1, t.h / 12)) {
                for (int tx = 0; tx < t.w; tx += Math.max(1, t.w / 12)) {
                    int tc = t.pixels[ty * t.w + tx], fc = pixels[(y + ty) * f.width() + x + tx];
                    int tg = gray(tc), fg = gray(fc);
                    a += tg; b += fg; aa += tg * tg; bb += fg * fg; ab += tg * fg;
                    error += Math.abs(Color.red(tc) - Color.red(fc)) + Math.abs(Color.green(tc) - Color.green(fc)) + Math.abs(Color.blue(tc) - Color.blue(fc));
                    count++;
                }
            }
            if (error / (count * 3) > 22) continue; // reject dimmed controls behind a dialog
            double denominator = Math.sqrt(Math.max(0, (aa - a * a / count) * (bb - b * b / count)));
            if (denominator == 0) continue;
            double score = (ab - a * b / count) / denominator;
            if (score >= best) { best = score; found = new int[] {x + t.w / 2, y + t.h / 2}; }
        }
        if (key.equals("dragon") && found != null) dragonShift = found[0] - t.w / 2 - r[0];
        return found;
    }
    public List<String> lines(Bitmap bitmap) throws Exception {
        // A timeout/STOP does not cancel ML Kit's native task. It must own an
        // independent bitmap until completion, even if the caller closes its frame.
        Bitmap owned = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        com.google.android.gms.tasks.Task<Text> task;
        try { task = recognizer.process(InputImage.fromBitmap(owned, 0)); }
        catch (Exception e) { owned.recycle(); throw e; }
        task.addOnCompleteListener(Runnable::run, ignored -> owned.recycle());
        Text result = Tasks.await(task, warmed ? 8 : 30, TimeUnit.SECONDS);
        warmed = true;
        List<String> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) for (Text.Line line : block.getLines()) lines.add(line.getText());
        return lines;
    }
    public String read(Frame frame, String key) throws Exception {
        int[] r = store.rectangle("regions", key);
        boolean counter = key.equals("dragon_count");
        if (counter && r != null && r.length == 4) {
            // The counter moves with its card. A selected card is lifted and enlarged and
            // a depleted one is dimmed; neither matches, so keep the last seen position.
            // The read area grows a little to cover the lift; the card's white selection
            // border touches the area's edge and is dropped by the mask.
            match(frame, "dragon");
            int gx = r[2] / 8, top = Math.max(0, r[1] - r[3] / 4), left = Math.max(0, r[0] + dragonShift - gx);
            r = new int[] {left, top, Math.min(frame.width() - left, r[2] + 2 * gx), Math.min(frame.height() - top, r[1] + r[3] + r[3] / 8 - top)};
        }
        if (!Rules.rect(r, frame.width(), frame.height())) throw new IOException("Calibrate resource region " + key + ".");
        Bitmap crop = Bitmap.createBitmap(frame.bitmap, r[0], r[1], r[2], r[3]);
        Bitmap scaled = Bitmap.createScaledBitmap(crop, crop.getWidth() * 2, crop.getHeight() * 2, true);
        // Never combine separate OCR rows into a plausible grouped amount.
        try {
            // Clash draws numbers as white/cream fill with a black outline over busy
            // art, which ML Kit misreads (e.g. "2 762 711" as "27627NI"). Read only the
            // fill first; fall back to the untouched crop if that is not a valid value.
            Mask mask = digitMask(scaled, counter);
            String masked = mask == null ? "" : counter ? counterText(mask.bitmap) : String.join("\n", lines(mask.bitmap)).trim();
            if (mask != null) mask.bitmap.recycle();
            // Opponent loot decides attacks: ML Kit can silently drop a thin leading "1"
            // ("1 852 708" → "852 708"), so its digits must match the glyphs on screen.
            boolean loot = key.startsWith("loot_");
            if (loot) masked = lootDigits(masked);
            if ((counter ? Rules.troopCount(masked) != null : Rules.number(masked) != null) && (!loot || glyphs(masked) == mask.glyphs)) return masked;
            String plain = counter ? counterText(scaled) : String.join("\n", lines(scaled)).trim();
            if (loot) plain = lootDigits(plain);
            if (loot && (mask == null || Rules.number(plain) == null || glyphs(plain) != mask.glyphs)) {
                store.log("ocr-unreadable", key + ": masked \"" + masked + "\", plain \"" + plain + "\", glyphs " + (mask == null ? 0 : mask.glyphs));
                store.saveBitmap("unreadable-" + key + "-" + (unreadableShots++ % 3) + ".png", crop);
                return "";
            }
            return plain;
        }
        finally { if (scaled != crop) scaled.recycle(); if (crop != frame.bitmap) crop.recycle(); }
    }
    private static int glyphs(String text) { return text.replaceAll("[^0-9A-Za-z]", "").length(); }
    /** Loot only: Clash's blocky 0, 1 and 5 come back as O, I/l and S/s. The glyph check above still has to pass. */
    static String lootDigits(String text) {
        if (!text.matches("[0-9OoIlSs ]+")) return text;
        return text.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').replace('S', '5').replace('s', '5');
    }
    static final class Mask {
        final Bitmap bitmap; final int glyphs;
        Mask(Bitmap bitmap, int glyphs) { this.bitmap = bitmap; this.glyphs = glyphs; }
    }
    /**
     * Bright, unsaturated pixels (Clash digit fill) as black on white with a margin; specks much shorter
     * than the digits are dropped. With dropEdges, shapes touching the crop edge (a selection border) are
     * dropped too. Loot never uses it: a digit touching the edge must still count as a glyph.
     */
    static Mask digitMask(Bitmap source, boolean dropEdges) {
        int w = source.getWidth(), h = source.getHeight(), pad = 16;
        int[] pixels = new int[w * h]; source.getPixels(pixels, 0, w, 0, 0, w, h);
        boolean[] on = new boolean[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i], r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            on[i] = gray(c) >= 200 && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 70;
        }
        // 4-connected components: keep those at least half as tall as the tallest (the digits).
        int[] component = new int[w * h], stack = new int[w * h];
        List<Integer> heights = new ArrayList<>(); heights.add(0);
        int tallest = 0;
        for (int start = 0; start < on.length; start++) {
            if (!on[start] || component[start] != 0) continue;
            int id = heights.size(), top = h, bottom = -1, size = 0;
            boolean edge = false;
            stack[size++] = start; component[start] = id;
            while (size > 0) {
                int p = stack[--size], x = p % w, y = p / w;
                top = Math.min(top, y); bottom = Math.max(bottom, y);
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) edge = true;
                if (x > 0 && on[p - 1] && component[p - 1] == 0) { component[p - 1] = id; stack[size++] = p - 1; }
                if (x + 1 < w && on[p + 1] && component[p + 1] == 0) { component[p + 1] = id; stack[size++] = p + 1; }
                if (y > 0 && on[p - w] && component[p - w] == 0) { component[p - w] = id; stack[size++] = p - w; }
                if (y + 1 < h && on[p + w] && component[p + w] == 0) { component[p + w] = id; stack[size++] = p + w; }
            }
            if (dropEdges && edge) { heights.add(-1); continue; } // never kept: -1 * 2 < tallest
            heights.add(bottom - top + 1); tallest = Math.max(tallest, bottom - top + 1);
        }
        if (tallest < 6) return dropEdges ? digitMask(source, false) : null;
        int glyphs = 0;
        for (int i = 1; i < heights.size(); i++) if (heights.get(i) * 2 >= tallest) glyphs++;
        int ow = w + 2 * pad, oh = h + 2 * pad;
        int[] out = new int[ow * oh]; Arrays.fill(out, Color.WHITE);
        for (int i = 0; i < component.length; i++) {
            if (component[i] != 0 && heights.get(component[i]) * 2 >= tallest) out[(i / w + pad) * ow + i % w + pad] = Color.BLACK;
        }
        return new Mask(Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888), glyphs);
    }
    public String counterText(Bitmap crop) throws Exception {
        // Sparse x1 crops can be interpreted upside down as "LX" by ML Kit.
        // An upright label outside the untouched crop anchors text orientation;
        // remove only that label, never substitute guessed digits.
        Bitmap context = Bitmap.createBitmap(Math.max(600, crop.getWidth() + 40), crop.getHeight() + 120, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(context); canvas.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK); p.setTextSize(44); p.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText("DRAGONS",20,52,p); canvas.drawBitmap(crop,20,90,null);
            List<String> found = new ArrayList<>();
            for (String line : lines(context)) {
                String value = line.replaceFirst("(?i)^DRAGONS\\s*", "").trim();
                if (!value.isEmpty()) found.add(value);
            }
            return String.join("\n",found);
        } finally { context.recycle(); }
    }
    public boolean sameRegion(Frame before, Frame after, String key) throws IOException {
        int[] r = store.rectangle("regions", key);
        if (!Rules.rect(r, before.width(), before.height()) || before.width() != after.width() || before.height() != after.height()) throw new IOException("Invalid payment region.");
        int[] a = before.pixels(), b = after.pixels();
        for (int y = r[1]; y < r[1] + r[3]; y++) for (int x = r[0]; x < r[0] + r[2]; x++) {
            if (a[y * before.width() + x] != b[y * after.width() + x]) return false;
        }
        return true;
    }
    public Integer wall(Frame frame) throws Exception { return Rules.selectedWall(lines(frame.bitmap)); }
    @Override public void close() { templates.clear(); recognizer.close(); }
}
