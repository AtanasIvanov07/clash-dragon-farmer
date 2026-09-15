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
        int tolerance = key.equals("home_anchor") ? 1 : 8;
        int minX = Math.max(0, r[0] - tolerance), maxX = Math.min(f.width() - t.w, r[0] + tolerance);
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
        if (!Rules.rect(r, frame.width(), frame.height())) throw new IOException("Calibrate resource region " + key + ".");
        Bitmap crop = Bitmap.createBitmap(frame.bitmap, r[0], r[1], r[2], r[3]);
        Bitmap scaled = Bitmap.createScaledBitmap(crop, crop.getWidth() * 2, crop.getHeight() * 2, true);
        // Never combine separate OCR rows into a plausible grouped amount.
        try { return key.equals("dragon_count") ? counterText(scaled) : String.join("\n", lines(scaled)).trim(); }
        finally { if (scaled != crop) scaled.recycle(); if (crop != frame.bitmap) crop.recycle(); }
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
