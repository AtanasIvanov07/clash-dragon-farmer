package dev.s25.farmer;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;

public final class Store {
    public final File directory;
    private JSONObject profile, settings;
    public static final LinkedHashMap<String, Long> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("dragonCount", 18L); DEFAULTS.put("minimumCombined", 1_500_000L);
        DEFAULTS.put("minimumGold", 0L); DEFAULTS.put("minimumElixir", 0L);
        DEFAULTS.put("reserveGold", 1_000_000L); DEFAULTS.put("reserveElixir", 1_000_000L);
        DEFAULTS.put("maxWallCost", 10_000_000L); DEFAULTS.put("maxWallsPerVisit", 2L);
        DEFAULTS.put("targetWallLevel", 0L); DEFAULTS.put("maxAttacks", 30L);
        DEFAULTS.put("maxMinutes", 90L); DEFAULTS.put("maxSkips", 40L);
    }
    public Store(Context context) throws Exception {
        directory = context.getFilesDir();
        new File(directory, "templates").mkdirs();
        new File(directory, "screens").mkdirs();
        profile = read("profile.json"); settings = read("settings.json");
        for (String kind : List.of("templates", "regions", "points")) {
            if (!profile.has(kind)) profile.put(kind, new JSONObject());
            if (profile.optJSONObject(kind) == null) throw new IOException("Saved calibration is invalid: " + kind);
        }
    }
    private JSONObject read(String name) throws Exception {
        File f = new File(directory, name);
        return f.exists() ? new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
    }
    private void write(String name, JSONObject value) throws Exception {
        File file = new File(directory, name), temp = new File(directory, name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.toString(2).getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    public synchronized int width() { return profile.optInt("width"); }
    public synchronized int height() { return profile.optInt("height"); }
    public synchronized int left() { return profile.optInt("left"); }
    public synchronized int top() { return profile.optInt("top"); }
    public synchronized void dimensions(Frame f) throws Exception {
        if (width() > 0 && (width() != f.width() || height() != f.height() || left() != f.left || top() != f.top)) {
            throw new IOException("Display dimensions changed. Restore your calibrated view or reset calibration.");
        }
        profile.put("width", f.width()).put("height", f.height()).put("left", f.left).put("top", f.top);
        write("profile.json", profile);
    }
    public synchronized long setting(String key) { return settings.optLong(key, DEFAULTS.getOrDefault(key, 0L)); }
    public synchronized boolean wallsEnabled() { return settings.optBoolean("wallsEnabled", true); }
    public synchronized void setWallsEnabled(boolean value) throws Exception { settings.put("wallsEnabled", value); write("settings.json", settings); }
    public synchronized void set(String key, long value) throws Exception {
        long max = switch (key) {
            case "dragonCount", "targetWallLevel" -> 100;
            case "maxMinutes" -> 1440;
            case "maxAttacks" -> 10000;
            case "maxSkips" -> 1000;
            case "maxWallsPerVisit" -> 50;
            default -> 100_000_000;
        };
        boolean zeroAllowed = key.startsWith("minimum") || key.startsWith("reserve") || key.equals("targetWallLevel");
        if (!DEFAULTS.containsKey(key) || value < (zeroAllowed ? 0 : 1) || value > max) {
            throw new IllegalArgumentException("Enter a whole number from " + (zeroAllowed ? 0 : 1) + " to " + max + ".");
        }
        settings.put(key, value); write("settings.json", settings);
    }
    public synchronized int[] rectangle(String kind, String key) {
        return array(profile.optJSONObject(kind).optJSONArray(key));
    }
    public synchronized List<int[]> points(String key) {
        List<int[]> result = new ArrayList<>();
        JSONArray list = profile.optJSONObject("points").optJSONArray(key);
        if (list != null) for (int i = 0; i < list.length(); i++) result.add(array(list.optJSONArray(i)));
        return result;
    }
    private int[] array(JSONArray a) {
        if (a == null) return null;
        int[] result = new int[a.length()];
        for (int i = 0; i < result.length; i++) result[i] = a.optInt(i, -1);
        return result;
    }
    public synchronized void selection(Stages.Field field, int[] rect, List<int[]> points, Bitmap frame) throws Exception {
        if (field.kind.equals("point") || field.kind.equals("points")) {
            JSONArray list = new JSONArray();
            for (int[] p : points) {
                if (!Rules.point(p, width(), height())) throw new IOException("Point is outside the game window.");
                list.put(new JSONArray(p));
            }
            if (list.length() == 0) throw new IOException("Select at least one position.");
            profile.getJSONObject("points").put(field.key, list);
        } else {
            if (!Rules.rect(rect, width(), height())) throw new IOException("Select a larger rectangle inside the image.");
            String kind = field.kind.equals("template") ? "templates" : "regions";
            if (field.kind.equals("template")) {
                Bitmap crop = Bitmap.createBitmap(frame, rect[0], rect[1], rect[2], rect[3]);
                try {
                    Vision.validateTemplate(crop);
                    saveBitmap("templates/" + field.key + ".png", crop);
                } finally { if (crop != frame) crop.recycle(); }
            }
            profile.getJSONObject(kind).put(field.key, new JSONArray(rect));
        }
        write("profile.json", profile);
    }
    public synchronized boolean complete(Stages.Stage stage) {
        for (Stages.Field f : stage.fields) {
            if (!wallsEnabled() && List.of("home_anchor", "home_gold", "home_elixir", "home_blank", "walls").contains(f.key)) continue;
            if (f.kind.startsWith("point")) {
                List<int[]> points = points(f.key);
                if (points.isEmpty()) return false;
                for (int[] p : points) if (!Rules.point(p, width(), height())) return false;
            } else {
                int[] r = rectangle(f.kind.equals("template") ? "templates" : "regions", f.key);
                if (!Rules.rect(r, width(), height())) return false;
                if (f.kind.equals("template") && !new File(directory, "templates/" + f.key + ".png").isFile()) return false;
            }
        }
        return true;
    }
    public synchronized List<String> missing() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < Stages.ALL.size(); i++) {
            if (!wallsEnabled() && i >= 5) continue;
            if (!complete(Stages.ALL.get(i))) list.add(Stages.ALL.get(i).name);
        }
        return list;
    }
    public synchronized void reset() throws Exception {
        profile = new JSONObject();
        for (String kind : List.of("templates", "regions", "points")) profile.put(kind, new JSONObject());
        write("profile.json", profile);
    }
    public synchronized void beginStage(Stages.Stage stage) throws Exception {
        for (Stages.Field f : stage.fields) {
            String kind = f.kind.startsWith("point") ? "points" : f.kind.equals("template") ? "templates" : "regions";
            profile.getJSONObject(kind).remove(f.key);
        }
        write("profile.json", profile);
    }
    public synchronized void saveBitmap(String name, Bitmap bitmap) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name))) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Screenshot could not be saved.");
        }
    }
    public synchronized void log(String event, String detail) {
        try {
            JSONObject row = new JSONObject().put("time", new Date().toString()).put("event", event).put("detail", detail);
            File f = new File(directory, "session.jsonl");
            if (f.length() > 2_000_000) Files.move(f.toPath(), new File(directory, "previous-session.jsonl").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (FileOutputStream out = new FileOutputStream(f, true)) { out.write((row + "\n").getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) { /* A full disk must not trigger more taps. */ }
    }
    public synchronized void report(String name, String text) throws IOException { Files.write(new File(directory, name).toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    public synchronized void export(OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) { add(zip, directory, ""); }
    }
    private void add(ZipOutputStream z, File dir, String prefix) throws IOException {
        File[] entries = dir.listFiles(); if (entries == null) return;
        for (File f : entries) {
            String name = prefix + f.getName();
            if (f.isDirectory() && (name.equals("templates") || name.equals("screens"))) add(z, f, name + "/");
            else if (f.isFile() && (name.endsWith(".png") || name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".txt"))) {
                z.putNextEntry(new ZipEntry(name)); Files.copy(f.toPath(), z); z.closeEntry();
            }
        }
    }
}
