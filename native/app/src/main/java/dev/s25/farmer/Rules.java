package dev.s25.farmer;

import java.util.*;
import java.util.regex.*;

/** Pure decision logic; Android adapters cannot authorize ambiguous numbers. */
public final class Rules {
    private Rules() {}
    // Clash separates thousands with thin spaces; OCR often loses the one right after
    // a narrow leading digit ("1574 019" for 1 574 019). Losing a space changes no digit,
    // so a 4-digit first group is allowed before space-separated groups.
    private static final Pattern NUMBER = Pattern.compile("(?:[0-9]+|[0-9]{1,3}([ ,.'\u00a0\u202f])[0-9]{3}(?:\\1[0-9]{3})*|[0-9]{4}( )[0-9]{3}(?:\\2[0-9]{3})*)");
    // "Wall (Level 15)", or "Wall (Level 15)x 8" while Upgrade More has 8 walls selected. The count's
    // blocky 1 and 0 can come back as I/l and O ("x I" for x 1).
    private static final Pattern WALL = Pattern.compile("^Wall\\s*\\(?\\s*Level\\s+(\\d{1,2})\\s*\\)?\\s*(?:[xX×]\\s*([0-9OoIl]{1,3}))?$", Pattern.CASE_INSENSITIVE);
    public static Long number(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (!NUMBER.matcher(s).matches()) return null;
        try {
            long n = Long.parseLong(s.replaceAll("[ ,.'\u00a0\u202f]", ""));
            return n <= 1_000_000_000L ? n : null;
        } catch (NumberFormatException e) { return null; }
    }
    /** True when one line of {@code text} is exactly {@code amount}; a sale price sits above the old one crossed out. */
    public static boolean shows(String text, long amount) {
        if (text == null) return false;
        for (String line : text.split("\n")) if (Long.valueOf(amount).equals(number(line))) return true;
        return false;
    }
    private static final Pattern BATCH_DIALOG = Pattern.compile("(?i)upgrade\\s+the\\s+selected\\s+walls\\s+for\\s+([0-9OoDIlSB][0-9OoDIlSB ,.'\u00a0\u202f]*[0-9OoDIlSB])\\s+(gold|elixir)\\s*\\?");
    /**
     * Total named by Upgrade More's own question, "Do you really want to upgrade the selected Walls for
     * 18480000 Elixir?", when it asks for {@code resource}; null when the text is not that question or
     * names the other resource. The question may be split over several OCR lines.
     */
    public static Long batchDialog(List<String> lines, String resource) {
        Matcher m = BATCH_DIALOG.matcher(String.join(" ", lines).replaceAll("\\s+", " "));
        if (!m.find() || !m.group(2).equalsIgnoreCase(resource)) return null;
        // The same blocky-digit misreads as on prices; the amount must still equal the walls' total.
        String amount = m.group(1).trim();
        if (!amount.matches(".*[0-9].*")) return null;
        return number(amount.replaceAll("[OoDd]", "0").replaceAll("[IiLl]", "1").replaceAll("[Ss]", "5").replaceAll("[Bb]", "8"));
    }
    public static Long stable(String a, String b) {
        Long x = number(a), y = number(b);
        return x != null && x.equals(y) ? x : null;
    }
    public static Integer troopCount(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (!s.matches("[xX×]?\\s*[0-9]{1,3}")) return null;
        int n = Integer.parseInt(s.replaceFirst("^[xX×]\\s*", "").trim());
        return n <= 100 ? n : null;
    }
    private static final Pattern BUILDERS = Pattern.compile("^(\\d{1,2})\\s*/\\s*(\\d{1,2})$");
    /** Free builders from the home counter, e.g. "1/7" → 1. */
    public static Integer freeBuilders(String text) {
        if (text == null) return null;
        Matcher m = BUILDERS.matcher(text.trim());
        if (!m.matches()) return null;
        int free = Integer.parseInt(m.group(1)), total = Integer.parseInt(m.group(2));
        return total > 0 && free <= total ? free : null;
    }
    public static boolean affordable(Long balance, Long cost, long reserve, long cap) {
        return balance != null && cost != null && reserve >= 0 && cost > 0 && cost <= cap && balance - cost >= reserve;
    }
    public static Integer wallLevel(String text) {
        if (text == null) return null;
        Matcher m = WALL.matcher(text.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : null;
    }
    /** Number of walls in an Upgrade More selection ("x 8"), only when exactly one wall title is visible. */
    public static Integer selectedWallCount(List<String> lines) {
        Integer found = null; int titles = 0;
        for (int i = 0; i < lines.size(); i++) {
            // OCR may split the title and its "x N" over two lines: prefer the joined reading.
            Matcher m = i + 1 < lines.size() ? WALL.matcher((lines.get(i) + " " + lines.get(i + 1)).trim()) : null;
            if (m != null && m.matches()) i++;
            else m = WALL.matcher(lines.get(i).trim());
            if (m.matches()) { titles++; if (m.group(2) != null) found = Integer.parseInt(m.group(2).replaceAll("(?i)o", "0").replaceAll("(?i)[il]", "1")); }
        }
        return titles == 1 ? found : null;
    }
    public static Integer selectedWall(List<String> lines) {
        Integer found = null;
        for (int i = 0; i < lines.size(); i++) {
            Integer n = wallLevel(lines.get(i));
            if (n == null && i + 1 < lines.size()) {
                n = wallLevel(lines.get(i) + " " + lines.get(i + 1));
                if (n != null) i++;
            }
            if (n != null) { if (found != null) return null; found = n; }
        }
        return found;
    }
    public static boolean payment(Long before, Long after, long cost) {
        return before != null && after != null && cost > 0 && before - after == cost;
    }
    public static boolean loot(Long gold, Long elixir, long minGold, long minElixir, long combined) {
        return gold != null && elixir != null && gold >= minGold && elixir >= minElixir && gold + elixir >= combined;
    }
    /** Siege cards are cyan: green and blue far above red, blue a little above green. Troop cards are bluer, spells violet, heroes dark. */
    static boolean siegeCyan(int argb) {
        int r = argb >> 16 & 0xff, g = argb >> 8 & 0xff, b = argb & 0xff;
        return g - r >= 60 && b - g >= 0 && b - g <= 45 && b >= 150;
    }
    /**
     * Tap point on the siege card in a troop-bar screenshot, or null. Every siege machine (Clan Castle
     * donations change it from battle to battle, at any level) sits on the same cyan card, so this looks
     * for the card, never the machine. {@code card} is the calibrated siege picture {x, y, w, h}: rows of
     * cyan about one card wide across its upper half (the plain background above the icon), and cyan
     * card sides below that. A troop card's top edge is a single row; the freeze spell's ice is cyan
     * only near its bottom; water above the bar is far wider and has the dark bar under it.
     */
    public static int[] siegeCard(int[] argb, int width, int height, int[] card) {
        if (!rect(card, width, height)) return null;
        List<int[]> runs = new ArrayList<>(); // {row, center}
        for (int y = card[1]; y < card[1] + card[3] / 2; y++) {
            for (int x = 0; x < width; ) {
                if (!siegeCyan(argb[y * width + x])) { x++; continue; }
                int start = x;
                while (x < width && siegeCyan(argb[y * width + x])) x++;
                if (x - start >= card[2] * 6 / 10 && x - start <= card[2] * 13 / 10) runs.add(new int[] {y, (start + x - 1) / 2});
            }
        }
        // Candidates: runs in consecutive rows around one center (a gap of more than 3 rows, like the
        // dark band between the battlefield and the cards, ends one), most rows first.
        List<int[]> candidates = new ArrayList<>(); // {rows, center, top, bottom}
        for (int i = 0; i < runs.size(); i++) {
            int[] seed = runs.get(i);
            int rows = 1, last = seed[0]; long sum = seed[1];
            for (int j = i + 1; j < runs.size(); j++) {
                int[] run = runs.get(j);
                if (Math.abs(run[1] - seed[1]) > card[2] / 4) continue;
                if (run[0] - last > 3) break;
                rows++; sum += run[1]; last = run[0];
            }
            candidates.add(new int[] {rows, (int) (sum / rows), seed[0], last});
        }
        candidates.sort((a, b) -> b[0] - a[0]);
        for (int[] c : candidates) {
            if (c[0] < Math.max(4, card[3] / 40)) break;
            // Below the plain top, the card's left and right margins stay cyan beside the machine.
            int center = c[1], cyan = 0, seen = 0;
            for (int x : new int[] {center - card[2] * 4 / 10, center + card[2] * 4 / 10}) {
                if (x < 0 || x >= width) continue;
                for (int y = c[3]; y < Math.min(height, c[3] + card[3] / 3); y++) { seen++; if (siegeCyan(argb[y * width + x])) cyan++; }
            }
            // Tap the machine, well above the level switch at the bottom of the card.
            if (seen > 0 && cyan * 10 >= seen * 3) return new int[] {center, Math.min(height - 1, c[2] + card[3] / 4)};
        }
        return null;
    }
    public static boolean rect(int[] r, int width, int height) {
        return r != null && r.length == 4 && r[0] >= 0 && r[1] >= 0 && r[2] >= 6 && r[3] >= 6
            && (long) r[0] + r[2] <= width && (long) r[1] + r[3] <= height;
    }
    public static boolean point(int[] p, int width, int height) {
        return p != null && p.length == 2 && p[0] >= 0 && p[1] >= 0 && p[0] < width && p[1] < height;
    }
}
