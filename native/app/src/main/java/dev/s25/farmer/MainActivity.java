package dev.s25.farmer;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

public final class MainActivity extends Activity {
    private Store store;
    private LinearLayout body;
    private static final int EXPORT = 20;
    private static final LinkedHashMap<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("dragonCount", "Dragons in your army"); LABELS.put("minimumCombined", "Minimum combined loot");
        LABELS.put("minimumGold", "Minimum gold loot"); LABELS.put("minimumElixir", "Minimum elixir loot");
        LABELS.put("reserveGold", "Gold reserve"); LABELS.put("reserveElixir", "Elixir reserve");
        LABELS.put("maxWallCost", "Maximum wall price"); LABELS.put("maxWallsPerVisit", "Walls per home visit");
        LABELS.put("targetWallLevel", "Target wall level (0 = any)"); LABELS.put("maxAttacks", "Attack limit");
        LABELS.put("maxMinutes", "Time limit in minutes"); LABELS.put("maxSkips", "Maximum skipped bases");
    }
    @Override public void onCreate(Bundle bundle) { super.onCreate(bundle); render(); }
    @Override public void onResume() { super.onResume(); render(); }
    private void render() {
        try { store = new Store(this); } catch (Exception e) { alert("Settings error", e.toString()); return; }
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(245, 244, 239));
        body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(22), dp(22), dp(22), dp(30));
        scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(scroll);
        text("DRAGON FARMER", 12, Color.rgb(42, 111, 88));
        text("Your farming controls", 28, Color.rgb(24, 44, 36));
        text("TH18 · Dragons · Gold + elixir walls", 15, Color.DKGRAY);
        text("Standalone device-test build. No internet permission; captures and settings stay on this phone. Includes bundled Google ML Kit OCR.", 13, Color.DKGRAY);
        MacroService service = MacroService.getInstance();
        text(service == null ? "Accessibility is OFF" : service.status(), 15, service == null ? Color.rgb(143, 80, 36) : Color.rgb(42, 111, 88));
        button("1   Enable Accessibility", () -> new AlertDialog.Builder(this)
            .setTitle("Allow screen reading and taps")
            .setMessage("Android grants broad screen/control access. This app only automates the Clash of Clans window. Enable Dragon Farmer in the next screen. You can disable it there at any time.")
            .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
            .setNegativeButton("Cancel", null).show());
        button("2   Capture game screens", this::calibrationMenu);
        button("3   Army & farming settings", this::settingsMenu);
        button("4   Check recognition — no taps", () -> prepare(MacroService.DIAGNOSE));
        button("5   Test one attack + wall check", () -> prepare(MacroService.ONE_ATTACK));
        button("Start farming session", () -> prepare(MacroService.FARM));
        if (service != null && service.busy()) button("STOP", () -> { service.stopWork("Stopped from app"); render(); });
        text("Keep Clash in English and landscape. Prepare your dragons and leave one builder free. Mark individual wall positions at a consistent zoom.", 13, Color.DKGRAY);
        button("Read last result / recognition report", this::showReports);
        button("Export test details", () -> {
            if (!idle()) return;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, "Dragon-Farmer-test-details.zip");
            startActivityForResult(intent, EXPORT);
        });
        button("Short setup & installation help", () -> alert("Quick setup",
            "1. Enable Dragon Farmer Accessibility.\n2. Capture the requested game screens.\n3. Set your actual dragon count.\n4. Check recognition, then test one attack.\n5. Start farming after the test works.\n\nStop: STOP or Volume Down.\n\nIf Accessibility is restricted: Android Settings → Apps → Dragon Farmer → ⋮ → Allow restricted settings. Then return to Accessibility. Menu names vary with One UI.\n\nNo root, separate macro app, screen-sharing app, or computer connection is needed."));
        button("Reset calibration", () -> { if (!idle()) return; new AlertDialog.Builder(this).setTitle("Reset saved positions?")
            .setMessage("Existing screenshots remain available for diagnostics.")
            .setPositiveButton("Reset", (d,w) -> { if (!idle()) return; try { store.reset(); render(); } catch (Exception e) { alert("Error", e.toString()); } })
            .setNegativeButton("Cancel", null).show(); });
        text("Game automation can result in a permanent account ban. This app has not yet been verified in live gameplay on your S25.", 12, Color.rgb(125, 67, 39));
    }
    private void calibrationMenu() {
        String[] names = new String[Stages.ALL.size()];
        for (int i = 0; i < names.length; i++) names[i] = (store.complete(Stages.ALL.get(i)) ? "✓ " : "○ ") + Stages.ALL.get(i).name;
        new AlertDialog.Builder(this).setTitle("Capture each screen once").setItems(names, (d, i) ->
            new AlertDialog.Builder(this).setTitle(Stages.ALL.get(i).name).setMessage(Stages.ALL.get(i).instruction + "\n\nNavigate manually, then tap the floating CAPTURE button. Mark the requested details on the frozen screenshot.")
                .setPositiveButton("Open game", (a,b) -> prepare(i)).setNegativeButton("Cancel", null).show()).show();
    }
    private void settingsMenu() {
        List<String> keys = new ArrayList<>(LABELS.keySet()), choices = new ArrayList<>();
        for (String k : keys) choices.add(LABELS.get(k) + ": " + store.setting(k));
        choices.add("Wall upgrades: " + (store.wallsEnabled() ? "ON — gold + elixir" : "OFF"));
        new AlertDialog.Builder(this).setTitle("Farming settings").setItems(choices.toArray(new String[0]), (d,i) -> {
            if (MacroService.getInstance() != null && MacroService.getInstance().busy()) { alert("Stop first", "Stop the active capture or farming session before changing settings."); return; }
            if (i == keys.size()) {
                try { store.setWallsEnabled(!store.wallsEnabled()); settingsMenu(); } catch (Exception e) { alert("Error", e.toString()); }
                return;
            }
            String key = keys.get(i); EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setText(Long.toString(store.setting(key))); input.selectAll();
            new AlertDialog.Builder(this).setTitle(LABELS.get(key)).setView(input)
                .setPositiveButton("Save", (a,b) -> {
                    if (!idle()) return;
                    try { Long value = Rules.number(input.getText().toString()); if (value == null) throw new IllegalArgumentException("Enter a whole number."); store.set(key, value); settingsMenu(); }
                    catch (Exception e) { alert("Invalid setting", e.getMessage()); }
                }).setNegativeButton("Cancel", null).show();
        }).show();
    }
    private void prepare(int mode) {
        MacroService s = MacroService.getInstance();
        if (s == null) { alert("Enable Accessibility first", "Use step 1 to enable Dragon Farmer."); return; }
        if (s.busy()) { alert("Session already open", "Finish the current capture or tap STOP before starting another."); return; }
        s.prepare(mode);
    }
    private boolean idle() {
        MacroService s = MacroService.getInstance();
        if (s == null || !s.busy()) return true;
        alert("Stop first", "Finish the current capture or tap STOP first."); return false;
    }
    private void showReports() {
        StringBuilder report = new StringBuilder();
        for (String name : List.of("last-status.txt", "last-diagnostic.txt")) {
            try { File f = new File(store.directory, name); if (f.exists()) report.append(name).append("\n").append(new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8)).append("\n\n"); }
            catch (Exception e) { report.append(e.getMessage()); }
        }
        alert("Last test results", report.length() == 0 ? "No device test has been run yet." : report.toString());
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT && result == RESULT_OK && data != null && data.getData() != null) {
            if (!idle()) return;
            Store exportStore = store;
            new Thread(() -> {
                try { OutputStream out = getContentResolver().openOutputStream(data.getData()); if (out == null) throw new IOException("Cannot write that file."); exportStore.export(out); runOnUiThread(() -> alert("Export saved", "The ZIP contains game screenshots, calibration, settings, and logs. Share it if you want help with a failed test.")); }
                catch (Exception e) { runOnUiThread(() -> alert("Export failed", e.getMessage())); }
            }, "diagnostic-export").start();
        }
    }
    private void button(String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT); button.setTextSize(15);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(7); body.addView(button, p); button.setOnClickListener(v -> action.run());
    }
    private void text(String text, int size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setPadding(0, dp(7), 0, dp(7)); body.addView(view);
    }
    private void alert(String title, String text) { new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).show(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
