package dev.s25.farmer;

import java.util.*;
import java.util.regex.*;

/** Pure decision logic; Android adapters cannot authorize ambiguous numbers. */
public final class Rules {
    private Rules() {}
    private static final Pattern NUMBER = Pattern.compile("(?:[0-9]+|[0-9]{1,3}([ ,.'\u00a0\u202f])[0-9]{3}(?:\\1[0-9]{3})*)");
    private static final Pattern WALL = Pattern.compile("^Wall\\s*\\(?\\s*Level\\s+(\\d{1,2})\\s*\\)?$", Pattern.CASE_INSENSITIVE);
    public static Long number(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (!NUMBER.matcher(s).matches()) return null;
        try {
            long n = Long.parseLong(s.replaceAll("[ ,.'\u00a0\u202f]", ""));
            return n <= 1_000_000_000L ? n : null;
        } catch (NumberFormatException e) { return null; }
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
    public static boolean affordable(Long balance, Long cost, long reserve, long cap) {
        return balance != null && cost != null && reserve >= 0 && cost > 0 && cost <= cap && balance - cost >= reserve;
    }
    public static Integer wallLevel(String text) {
        if (text == null) return null;
        Matcher m = WALL.matcher(text.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : null;
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
    public static boolean rect(int[] r, int width, int height) {
        return r != null && r.length == 4 && r[0] >= 0 && r[1] >= 0 && r[2] >= 6 && r[3] >= 6
            && (long) r[0] + r[2] <= width && (long) r[1] + r[3] <= height;
    }
    public static boolean point(int[] p, int width, int height) {
        return p != null && p.length == 2 && p[0] >= 0 && p[1] >= 0 && p[0] < width && p[1] < height;
    }
}
