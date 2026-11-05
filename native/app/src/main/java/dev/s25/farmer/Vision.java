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
    private List<Template> wallPieces;
    public Vision(Store store) { this.store = store; }
    /**
     * The wall panel's cream buttons are see-through: the village behind them shows in the button and
     * changes with the camera. Their white letters and black outlines are opaque and always the same.
     */
    private static final Set<String> SEE_THROUGH = Set.of("wall_more", "wall_gold", "wall_elixir", "batch_add1", "batch_add10", "batch_gold", "batch_elixir");
    private static final class Template {
        int w, h;
        int[] pixels;
        /** Compare only opaque white and black pixels, on a finer grid (see-through buttons). */
        boolean opaqueOnly; int fineStep;
        Template(Bitmap bitmap) {
            w = bitmap.getWidth(); h = bitmap.getHeight(); pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        }
        void seeThrough() {
            fineStep = Math.max(1, Math.min(w, h) / 40);
            int count = 0;
            for (int y = 0; y < h; y += fineStep) for (int x = 0; x < w; x += fineStep) if (opaque(pixels[y * w + x])) count++;
            opaqueOnly = count >= 50; // too few to judge: compare the whole picture as before
        }
    }
    /** White letters and the black outlines around them. */
    private static boolean opaque(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        int min = Math.min(r, Math.min(g, b)), max = Math.max(r, Math.max(g, b));
        return min >= 200 && max - min <= 40 || max <= 60;
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
            try {
                Template t = new Template(b);
                if (SEE_THROUGH.contains(key)) t.seeThrough();
                templates.put(key, t);
            } finally { b.recycle(); }
        }
        return templates.get(key);
    }
    private static int gray(int color) {
        return (Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) >> 8;
    }
    /** 8 px was tuned on a 1080p-tall S25 screen; taller panels (1440p Poco/Xiaomi) get the same physical slack. */
    private static int tolerance(Frame f) { return Math.max(8, Math.round(8f * Math.min(f.width(), f.height()) / 1080f)); }
    public int[] match(Frame f, String key) throws IOException {
        int[] r = store.rectangle("templates", key);
        if (!Rules.rect(r, f.width(), f.height())) throw new IOException("Calibrate " + key + " first.");
        Template t = template(key);
        if (t.w != r[2] || t.h != r[3]) throw new IOException("Template dimensions differ from calibration.");
        int tolerance = key.equals("home_anchor") ? 1 : tolerance(f);
        // Clash centers the troop bar, so cards slide sideways when the army has more
        // or fewer cards (Clan Castle troops, spells, sieges).
        boolean troopBar = key.equals("dragon") || key.equals("rage") || Stages.EXTRA_CARDS.contains(key);
        int minX = troopBar ? 0 : Math.max(0, r[0] - tolerance), maxX = troopBar ? f.width() - t.w : Math.min(f.width() - t.w, r[0] + tolerance);
        int minY = Math.max(0, r[1] - tolerance), maxY = Math.min(f.height() - t.h, r[1] + tolerance);
        int[] rows = steadyRows(key, t);
        int[] at = best(f, t, minX, maxX, minY, maxY, 1, rows[0], rows[1], 0.94, 22);
        if (at == null) return null;
        if (key.equals("dragon")) dragonShift = at[0] - r[0];
        return new int[] {at[0] + t.w / 2, at[1] + t.h / 2};
    }
    /**
     * Template rows {from, to} that look the same every time. The siege icon animates ("!" / "X").
     * Wall and Upgrade More buttons print their price on top, which changes with the level, a sale
     * and the number of walls; Confirm keeps only its label row above the price.
     */
    private static int[] steadyRows(String key, Template t) {
        return switch (key) {
            case "siege" -> new int[] {t.h * 65 / 100, t.h};
            case "wall_gold", "wall_elixir", "batch_gold", "batch_elixir" -> new int[] {t.h * 25 / 100, t.h};
            case "confirm_gold", "confirm_elixir" -> new int[] {0, t.h * 30 / 100};
            default -> new int[] {0, t.h};
        };
    }
    /**
     * A troop-bar card to deploy later. The siege machine changes from battle to battle (Clan Castle
     * donations, any machine, any level), so it is found by its cyan card, never by its picture. If that
     * fails, the calibrated picture is tried, then the slot just left of the first hero, where Clash
     * always puts the siege, unless that slot holds the dragon card.
     */
    public int[] card(Frame f, String key) throws IOException {
        if (!key.equals("siege")) return match(f, key);
        int[] at = Rules.siegeCard(f.pixels(), f.width(), f.height(), store.rectangle("templates", key));
        String how = "its cyan card";
        if (at == null) { at = match(f, key); how = "the calibrated picture"; }
        if (at == null) { at = besideHeroes(f); how = "the slot left of the first hero"; }
        if (at == null) store.log("card-miss", "siege: no cyan siege card, calibrated picture or card left of the heroes");
        else store.log("siege-found", "by " + how + " at " + Arrays.toString(at));
        return at;
    }
    /** The card left of the leftmost hero, spaced like the calibrated siege and first hero; null if that is the dragon card. */
    private int[] besideHeroes(Frame f) throws IOException {
        int[] siege = store.rectangle("templates", "siege"), first = store.rectangle("templates", "hero_1");
        if (!Rules.rect(siege, f.width(), f.height()) || !Rules.rect(first, f.width(), f.height())) return null;
        Integer left = null;
        for (String hero : Stages.EXTRA_CARDS) {
            if (!hero.startsWith("hero_") || !Rules.rect(store.rectangle("templates", hero), f.width(), f.height())) continue;
            int[] at = match(f, hero);
            if (at != null && (left == null || at[0] < left)) left = at[0];
        }
        if (left == null) return null;
        int x = left - (first[0] + first[2] / 2 - (siege[0] + siege[2] / 2));
        int[] dragon = match(f, "dragon");
        if (x < 0 || dragon != null && Math.abs(dragon[0] - x) < siege[2] / 2) return null;
        return new int[] {x, siege[1] + siege[3] / 2};
    }
    /** {x, y, score × 1000} of the best placement scoring at least {@code threshold}, sampling template rows [fromRow, toRow). */
    private static int[] best(Frame f, Template t, int minX, int maxX, int minY, int maxY, int step, int fromRow, int toRow, double threshold, double maxError) {
        int[] pixels = f.pixels();
        double best = threshold; int[] found = null;
        for (int y = minY; y <= maxY; y += step) for (int x = minX; x <= maxX; x += step) {
            double score = score(pixels, f.width(), t, x, y, fromRow, toRow, maxError);
            if (score >= best) { best = score; found = new int[] {x, y, (int) Math.round(score * 1000)}; }
        }
        return found;
    }
    /** Gray-level correlation of the template placed at (x, y); -1 when its colors are off by more than maxError on average (dimmed controls behind a dialog). */
    private static double score(int[] pixels, int width, Template t, int x, int y, int fromRow, int toRow, double maxError) {
        int rowStep = t.opaqueOnly ? t.fineStep : Math.max(1, (toRow - fromRow) / 12), colStep = t.opaqueOnly ? t.fineStep : Math.max(1, t.w / 12);
        double a = 0, b = 0, aa = 0, bb = 0, ab = 0, error = 0; int count = 0;
        for (int ty = fromRow; ty < toRow; ty += rowStep) {
            for (int tx = 0; tx < t.w; tx += colStep) {
                int tc = t.pixels[ty * t.w + tx];
                if (t.opaqueOnly && !opaque(tc)) continue;
                int fc = pixels[(y + ty) * width + x + tx];
                int tg = gray(tc), fg = gray(fc);
                a += tg; b += fg; aa += tg * tg; bb += fg * fg; ab += tg * fg;
                error += Math.abs(Color.red(tc) - Color.red(fc)) + Math.abs(Color.green(tc) - Color.green(fc)) + Math.abs(Color.blue(tc) - Color.blue(fc));
                count++;
            }
        }
        if (count == 0 || error / (count * 3) > maxError) return -1;
        double denominator = Math.sqrt(Math.max(0, (aa - a * a / count) * (bb - b * b / count)));
        return denominator == 0 ? -1 : (ab - a * b / count) / denominator;
    }
    /**
     * Small pictures of the walls picked in Home calibration, cut from that screenshot. Every wall of
     * one level looks the same, so they find that level's walls wherever the camera is.
     */
    private List<Template> wallPieces(Frame f) {
        if (wallPieces != null) return wallPieces;
        wallPieces = new ArrayList<>();
        Bitmap home = BitmapFactory.decodeFile(new File(store.directory, "screens/0.png").getPath());
        if (home == null) return wallPieces;
        try {
            if (home.getWidth() != f.width() || home.getHeight() != f.height()) return wallPieces;
            int half = pieceHalf(f);
            for (int[] p : store.points("walls")) {
                if (p == null || p.length != 2 || p[0] < half || p[1] < half || p[0] + half > home.getWidth() || p[1] + half > home.getHeight()) continue;
                Bitmap crop = Bitmap.createBitmap(home, p[0] - half, p[1] - half, 2 * half, 2 * half);
                try { wallPieces.add(new Template(crop)); } finally { if (crop != home) crop.recycle(); }
            }
        } finally { home.recycle(); }
        return wallPieces;
    }
    /** About half a wall piece at the 6-pinch Home zoom (18 px on a 1156 px tall screen). */
    private static int pieceHalf(Frame f) { return Math.max(8, f.height() * 18 / 1156); }
    /**
     * Centers of walls that look like the picked ones, best first. Skips the side buttons, the
     * resource bars and the "Unplaced Items"/bottom bar, where a tap would hit the interface.
     */
    public List<int[]> findWalls(Frame f) {
        List<Template> pieces = wallPieces(f);
        int[] pixels = f.pixels(); int half = pieceHalf(f);
        int minX = f.width() / 10, maxX = f.width() * 85 / 100, minY = f.height() * 5 / 100, maxY = f.height() * 62 / 100;
        List<int[]> coarse = new ArrayList<>();
        for (Template t : pieces) {
            for (int y = minY; y + t.h <= maxY; y += 3) for (int x = minX; x + t.w <= maxX; x += 3) {
                double s = score(pixels, f.width(), t, x, y, 0, t.h, 22);
                if (s >= 0.80) coarse.add(new int[] {x, y, (int) Math.round(s * 1000), pieces.indexOf(t)});
            }
        }
        coarse.sort((p, q) -> q[2] - p[2]);
        List<int[]> walls = new ArrayList<>();
        for (int[] c : coarse.subList(0, Math.min(200, coarse.size()))) {
            Template t = pieces.get(c[3]);
            int[] fine = best(f, t, Math.max(minX, c[0] - 2), Math.min(maxX - t.w, c[0] + 2), Math.max(minY, c[1] - 2), Math.min(maxY - t.h, c[1] + 2), 1, 0, t.h, 0.85, 22);
            if (fine == null) continue;
            int cx = fine[0] + t.w / 2, cy = fine[1] + t.h / 2;
            if (cx > f.width() * 72 / 100 && cy < f.height() * 30 / 100) continue; // resource bars
            boolean near = false;
            for (int[] w : walls) if (Math.abs(w[0] - cx) < half && Math.abs(w[1] - cy) < half) { near = true; break; }
            if (!near) walls.add(new int[] {cx, cy, fine[2]});
        }
        walls.sort((p, q) -> q[2] - p[2]);
        List<int[]> centers = new ArrayList<>();
        for (int[] w : walls) centers.add(new int[] {w[0], w[1]});
        return centers;
    }
    public List<String> lines(Bitmap bitmap) throws Exception {
        List<String> lines = new ArrayList<>();
        for (Text.Line line : textLines(bitmap)) lines.add(line.getText());
        return lines;
    }
    /** Center of the one line reading exactly {@code word} (ignoring case), or null when there is none or more than one. */
    public static int[] word(List<Text.Line> lines, String word) {
        int[] found = null;
        for (Text.Line line : lines) {
            if (!line.getText().trim().equalsIgnoreCase(word) || line.getBoundingBox() == null) continue;
            if (found != null) return null;
            found = new int[] {line.getBoundingBox().centerX(), line.getBoundingBox().centerY()};
        }
        return found;
    }
    public static List<String> texts(List<Text.Line> lines) {
        List<String> out = new ArrayList<>();
        for (Text.Line line : lines) out.add(line.getText());
        return out;
    }
    public List<Text.Line> textLines(Bitmap bitmap) throws Exception {
        // A timeout/STOP does not cancel ML Kit's native task. It must own an
        // independent bitmap until completion, even if the caller closes its frame.
        Bitmap owned = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        com.google.android.gms.tasks.Task<Text> task;
        try { task = recognizer.process(InputImage.fromBitmap(owned, 0)); }
        catch (Exception e) { owned.recycle(); throw e; }
        task.addOnCompleteListener(Runnable::run, ignored -> owned.recycle());
        Text result = Tasks.await(task, warmed ? 8 : 30, TimeUnit.SECONDS);
        warmed = true;
        List<Text.Line> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) lines.addAll(block.getLines());
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
        boolean loot = key.startsWith("loot_"), cost = key.endsWith("_cost"), confirm = cost && key.startsWith("confirm_");
        if (cost && r != null && r.length == 4) r = priceBox(key, r, frame.width());
        if (!Rules.rect(r, frame.width(), frame.height())) throw new IOException("Calibrate resource region " + key + ".");
        Bitmap crop = Bitmap.createBitmap(frame.bitmap, r[0], r[1], r[2], r[3]);
        Bitmap scaled = Bitmap.createScaledBitmap(crop, crop.getWidth() * 2, crop.getHeight() * 2, true);
        // Never combine separate OCR rows into a plausible grouped amount.
        try {
            // Clash draws numbers as white/cream fill with a black outline over busy
            // art, which ML Kit misreads (e.g. "2 762 711" as "27627NI"). Read only the
            // fill first; fall back to the untouched crop if that is not a valid value.
            Mask mask = cost ? digitMask(scaled, true, 0, true) : digitMask(scaled, counter, 200);
            String masked = mask == null ? "" : counter ? counterText(mask.bitmap) : String.join("\n", lines(mask.bitmap)).trim();
            if (mask != null) mask.bitmap.recycle();
            // Opponent loot decides attacks: ML Kit can silently drop a thin leading "1"
            // ("1 852 708" → "852 708"), so its digits must match the glyphs on screen.
            if (loot) masked = lootDigits(masked);
            // A confirmation is only checked against a known amount, so every amount on it is kept.
            if (cost) masked = confirm ? numberLines(priceDigits(masked)) : onlyNumberLine(priceDigits(masked));
            boolean builders = key.equals("home_builders");
            boolean valid = counter ? Rules.troopCount(masked) != null : builders ? Rules.freeBuilders(masked) != null
                    : confirm ? !masked.isEmpty() : Rules.number(masked) != null;
            if (valid && (!loot || glyphs(masked) == mask.glyphs)) return masked;
            String plain = counter ? counterText(scaled) : String.join("\n", lines(scaled)).trim();
            if (counter && Rules.troopCount(plain) == null) {
                // A depleted card is greyed: its "x0" fill is ~120 bright on a ~60 card, far below
                // the 200 used for lit digits. Only tried after both normal reads failed.
                Mask dim = digitMask(scaled, true, 105);
                String dimText = dim == null ? "" : counterText(dim.bitmap);
                if (dim != null) dim.bitmap.recycle();
                if (Rules.troopCount(dimText) != null) return dimText;
                store.log("ocr-unreadable", key + ": masked \"" + masked + "\", plain \"" + plain + "\", dim \"" + dimText + "\"");
                store.saveBitmap("unreadable-" + key + "-" + (unreadableShots++ % 3) + ".png", crop);
                return plain;
            }
            if (loot) plain = lootDigits(plain);
            if (cost) plain = confirm ? numberLines(priceDigits(plain)) : onlyNumberLine(priceDigits(plain));
            if (loot && (mask == null || Rules.number(plain) == null || glyphs(plain) != mask.glyphs)) {
                store.log("ocr-unreadable", key + ": masked \"" + masked + "\", plain \"" + plain + "\", glyphs " + (mask == null ? 0 : mask.glyphs));
                store.saveBitmap("unreadable-" + key + "-" + (unreadableShots++ % 3) + ".png", crop);
                return "";
            }
            if (cost && (confirm ? plain.isEmpty() : Rules.number(plain) == null)) {
                store.log("ocr-unreadable", key + ": masked \"" + masked + "\", plain \"" + plain + "\"");
                store.saveBitmap("unreadable-" + key + "-" + (unreadableShots++ % 3) + ".png", crop);
            }
            return plain;
        }
        finally { if (scaled != crop) scaled.recycle(); if (crop != frame.bitmap) crop.recycle(); }
    }
    /**
     * Where a price is read. It is centered over its button, and a box drawn around a shorter price cuts
     * the leading digit off a longer one, so wall and Upgrade More prices span the button's full width.
     * Upgrade More prints its total on the same line as the single-wall price, above the hammers (a box
     * over the whole button would also hold the hammers and "Upgrade"). Confirm has room on both sides.
     */
    private int[] priceBox(String key, int[] r, int width) {
        if (key.startsWith("confirm_")) {
            int grow = r[2] / 5, left = Math.max(0, r[0] - grow);
            return new int[] {left, r[1], Math.min(width - left, r[2] + 2 * grow), r[3]};
        }
        int[] line = store.rectangle("regions", "wall_" + (key.contains("gold") ? "gold" : "elixir") + "_cost");
        if (line == null || line.length != 4) line = r;
        int[] button = store.rectangle("templates", key.replace("_cost", ""));
        if (button == null || button.length != 4) return new int[] {r[0], line[1], r[2], line[3]};
        int left = Math.max(0, Math.min(r[0], button[0] - 10)), right = Math.min(width, Math.max(r[0] + r[2], button[0] + button[2] + 10));
        return new int[] {left, line[1], right - left, line[3]};
    }
    /**
     * Prices: Clash's blocky 5, 0, 1 and 8 come back as S, O/D, I/l and B ("S 040 D00" for 5 040 000). Only
     * lines made of digits and those letters are changed. A wrong guess cannot pay: the amount must still
     * equal the walls' total, the game's own confirmation and the deduction.
     */
    static String priceDigits(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();
            if (t.matches("[0-9OoDIl|SsB ,.]+") && t.matches(".*[0-9].*"))
                lines[i] = t.replaceAll("[OoD]", "0").replaceAll("[Il|]", "1").replaceAll("[Ss]", "5").replace('B', '8');
        }
        return String.join("\n", lines);
    }
    /** Every line that is an amount, e.g. a sale price and the old price crossed out below it. */
    static String numberLines(String text) {
        List<String> found = new ArrayList<>();
        for (String line : text.split("\n")) if (Rules.number(line.trim()) != null) found.add(line.trim());
        return String.join("\n", found);
    }
    /**
     * Price boxes may also cover a button's label ("1 680 000" above "Upgrade"). Keep the single line
     * that is a valid amount; with two amounts, or none, the text stays as read and is rejected later.
     */
    static String onlyNumberLine(String text) {
        String found = null;
        for (String line : text.split("\n")) {
            if (Rules.number(line.trim()) == null) continue;
            if (found != null) return text;
            found = line.trim();
        }
        return found == null ? text : found;
    }
    private static int glyphs(String text) { return text.replaceAll("[^0-9A-Za-z]", "").length(); }
    /** Loot only: Clash's blocky 0, 1 and 5 come back as O, I/l and S/s. The glyph check above still has to pass. */
    static String lootDigits(String text) {
        if (!text.matches("[0-9OoIlSs ]+")) return text;
        return text.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').replace('S', '5').replace('s', '5');
    }
    /** Troop counters: the blocky 0, 1 and 5 after the "x" come back as O, I/l and S ("XO" for x0). */
    static String counterDigits(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([xX×]?\\s*)([0-9OoIlSs]{1,3})$").matcher(text.trim());
        if (!m.matches()) return text;
        return m.group(1) + m.group(2).replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').replace('S', '5').replace('s', '5');
    }
    static final class Mask {
        final Bitmap bitmap; final int glyphs;
        Mask(Bitmap bitmap, int glyphs) { this.bitmap = bitmap; this.glyphs = glyphs; }
    }
    static Mask digitMask(Bitmap source, boolean dropEdges, int minGray) { return digitMask(source, dropEdges, minGray, false); }
    /**
     * Bright, unsaturated pixels (Clash digit fill) as black on white with a margin; specks much shorter
     * than the digits are dropped. With dropEdges, shapes touching the crop edge (a selection border) are
     * dropped too. Loot never uses it: a digit touching the edge must still count as a glyph.
     * Prices sit on cream buttons nearly as bright as their digits, so a price keeps only pure white or
     * sale-green fill, and drops wide flat shapes (a button's top highlight).
     */
    static Mask digitMask(Bitmap source, boolean dropEdges, int minGray, boolean price) {
        int w = source.getWidth(), h = source.getHeight(), pad = 16;
        int[] pixels = new int[w * h]; source.getPixels(pixels, 0, w, 0, 0, w, h);
        boolean[] on = new boolean[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i], r = Color.red(c), g = Color.green(c), b = Color.blue(c);
            int min = Math.min(r, Math.min(g, b)), max = Math.max(r, Math.max(g, b));
            on[i] = price ? min >= 215 && max - min <= 40 || g >= 200 && g - r >= 60 && g - b >= 100
                    : gray(c) >= minGray && max - min <= 70;
        }
        // 4-connected components: keep those at least half as tall as the tallest (the digits).
        int[] component = new int[w * h], stack = new int[w * h];
        List<Integer> heights = new ArrayList<>(); heights.add(0);
        int tallest = 0;
        for (int start = 0; start < on.length; start++) {
            if (!on[start] || component[start] != 0) continue;
            int id = heights.size(), top = h, bottom = -1, left = w, right = -1, size = 0;
            boolean edge = false;
            stack[size++] = start; component[start] = id;
            while (size > 0) {
                int p = stack[--size], x = p % w, y = p / w;
                top = Math.min(top, y); bottom = Math.max(bottom, y); left = Math.min(left, x); right = Math.max(right, x);
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) edge = true;
                if (x > 0 && on[p - 1] && component[p - 1] == 0) { component[p - 1] = id; stack[size++] = p - 1; }
                if (x + 1 < w && on[p + 1] && component[p + 1] == 0) { component[p + 1] = id; stack[size++] = p + 1; }
                if (y > 0 && on[p - w] && component[p - w] == 0) { component[p - w] = id; stack[size++] = p - w; }
                if (y + 1 < h && on[p + w] && component[p + w] == 0) { component[p + w] = id; stack[size++] = p + w; }
            }
            if (dropEdges && edge || price && right - left + 1 > 3 * (bottom - top + 1)) { heights.add(-1); continue; } // never kept: -1 * 2 < tallest
            heights.add(bottom - top + 1); tallest = Math.max(tallest, bottom - top + 1);
        }
        if (tallest < 6) return dropEdges ? digitMask(source, false, minGray, price) : null;
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
            return counterDigits(String.join("\n",found));
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
    public Integer wallCount(Frame frame) throws Exception { return Rules.selectedWallCount(lines(frame.bitmap)); }
    @Override public void close() { templates.clear(); recognizer.close(); }
}
