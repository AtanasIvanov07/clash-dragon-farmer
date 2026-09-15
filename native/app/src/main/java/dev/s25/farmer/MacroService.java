package dev.s25.farmer;

import android.accessibilityservice.*;
import android.content.*;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class MacroService extends AccessibilityService {
    private static volatile java.lang.ref.WeakReference<MacroService> current = new java.lang.ref.WeakReference<>(null);
    public static MacroService getInstance() { return current.get(); }
    public static final String GAME = "com.supercell.clashofclans";
    private static final String TEST_SCENE = "dev.s25.testscene";
    public static final int DIAGNOSE = -1, ONE_ATTACK = -2, FARM = -3;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger epoch = new AtomicInteger();
    private final ThreadLocal<Integer> workerToken = new ThreadLocal<>();
    private volatile boolean workerRunning;
    private Future<?> job;
    private WindowManager windows;
    private View overlay;
    private TextView statusView;
    private Store store;
    private volatile boolean stopped, busy;
    private boolean volumeDownHeld;
    private volatile long deadline, lastCapture;
    private volatile String status = "Ready";
    private Bitmap latest;

    @Override protected void onServiceConnected() {
        current = new java.lang.ref.WeakReference<>(this); windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        try { store = new Store(this); } catch (Exception e) { status = "Cannot open settings: " + e.getMessage(); }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() { if (busy()) stopWork("Accessibility interrupted"); }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if ((busy() || volumeDownHeld) && event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && !volumeDownHeld) { volumeDownHeld = true; stopWork("Stopped with Volume Down"); }
            if (event.getAction() == KeyEvent.ACTION_UP) volumeDownHeld = false;
            return true;
        }
        return super.onKeyEvent(event);
    }
    @Override public void onDestroy() {
        if (busy()) stopWork("Service disabled"); else removeOverlay();
        current.clear(); worker.shutdownNow();
        synchronized (this) { if (latest != null) { latest.recycle(); latest = null; } }
        super.onDestroy();
    }
    public boolean busy() { return busy || workerRunning; }
    public String status() { return status; }
    private void update(String text) {
        Integer active = workerToken.get(); int token = active == null ? epoch.get() : active;
        Runnable change = () -> { if (token == epoch.get()) { status = text; if (statusView != null) statusView.setText(text); } };
        if (Looper.myLooper() == Looper.getMainLooper()) change.run(); else main.post(change);
    }
    private void checkToken(int token) throws InterruptedException {
        if (token != epoch.get()) throw new InterruptedException("Previous session stopped");
        check();
    }
    private void check() throws InterruptedException {
        Integer token = workerToken.get();
        if (token != null && token != epoch.get()) throw new InterruptedException("Previous session stopped");
        if (stopped || Thread.currentThread().isInterrupted()) throw new InterruptedException("Stopped");
        if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) throw new InterruptedException("Session time limit reached");
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (!power.isInteractive()) throw new InterruptedException("Phone screen switched off");
    }
    private void pause(long millis) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + millis;
        do { check(); Thread.sleep(Math.min(100, Math.max(1, end - SystemClock.elapsedRealtime()))); }
        while (SystemClock.elapsedRealtime() < end);
    }
    private static final class Target {
        int id; Rect bounds;
        Target(int id, Rect bounds) { this.id = id; this.bounds = bounds; }
    }
    private Target target() throws Exception {
        check();
        AccessibilityNodeInfo active = getRootInActiveWindow();
        String activePackage = active == null || active.getPackageName() == null ? "" : active.getPackageName().toString();
        if (!GAME.equals(activePackage) && !(BuildConfig.DEBUG && TEST_SCENE.equals(activePackage))) {
            throw new IOException("Clash of Clans must be the foreground app.");
        }
        int id = active.getWindowId();
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getId() == id && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                if (window.getDisplayId() != Display.DEFAULT_DISPLAY) throw new IOException("Run Clash on the phone display, outside DeX or casting.");
                if (getMagnificationController().getScale() != 1f) throw new IOException("Turn off screen magnification before running the macro.");
                if (bounds.width() <= bounds.height()) throw new IOException("Keep Clash in landscape.");
                return new Target(id, bounds);
            }
        }
        throw new IOException("Cannot locate the active game window.");
    }
    private Target confirmWindow(Target expected) throws Exception {
        // Root-node lookup can block on another app. It already ran on the
        // worker; on the main thread validate the same focused window instead.
        check();
        for (AccessibilityWindowInfo window : getWindows()) {
            if (window.getId() == expected.id && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                    && window.getDisplayId() == Display.DEFAULT_DISPLAY && window.isFocused()) {
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                if (bounds.equals(expected.bounds)) return new Target(expected.id, bounds);
            }
        }
        throw new IOException("Game window changed before Android action.");
    }
    private Frame capture(boolean calibrated) throws Exception {
        try { return captureOnce(calibrated); }
        catch (IOException e) {
            if (!(e.getCause() instanceof TimeoutException)) throw e;
            check(); store.log("capture-retry", "Android did not return a screenshot; requesting one fresh frame before any action.");
            pause(500);
            return captureOnce(calibrated);
        }
    }
    private Frame captureOnce(boolean calibrated) throws Exception {
        check(); long wait = 420 - (SystemClock.elapsedRealtime() - lastCapture); if (wait > 0) pause(wait);
        Target t = target();
        int token = epoch.get();
        CompletableFuture<Frame> result = new CompletableFuture<>();
        main.post(() -> { try {
            if (result.isDone()) return;
            checkToken(token);
            Target now = confirmWindow(t);
            if (now.id != t.id || !now.bounds.equals(t.bounds)) throw new IOException("Game window changed before capture.");
            checkToken(token); if (result.isDone()) return;
            lastCapture = SystemClock.elapsedRealtime();
            takeScreenshotOfWindow(t.id, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult screenshot) {
                HardwareBuffer buffer = screenshot.getHardwareBuffer(); Bitmap hardware = null, copy = null;
                try {
                    checkToken(token);
                    hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                    if (hardware == null) throw new IOException("Android returned an empty screenshot.");
                    copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    // Window-only capture excludes our accessibility overlay.
                    // Match its pixel coordinates to the reported window bounds.
                    if (copy.getWidth() != t.bounds.width() || copy.getHeight() != t.bounds.height()) {
                        throw new IOException("Game screenshot size does not match its window. Fullscreen mode is required.");
                    }
                    Frame frame = new Frame(copy, t.bounds.left, t.bounds.top); copy = null;
                    if (!result.complete(frame)) frame.close();
                } catch (Exception e) { result.completeExceptionally(e); }
                finally { if (copy != null) copy.recycle(); if (hardware != null) hardware.recycle(); buffer.close(); }
            }
            @Override public void onFailure(int code) { result.completeExceptionally(new IOException("Android screenshot failed (code " + code + ").")); }
        });
        } catch (Exception e) { result.completeExceptionally(e); } });
        Frame frame;
        try { frame = result.get(5, TimeUnit.SECONDS); }
        catch (TimeoutException e) { result.cancel(false); throw new IOException("Android screenshot callback timed out.", e); }
        catch (Exception e) { result.cancel(false); throw e; }
        try {
            check(); Target after = target();
            if (after.id != t.id || !after.bounds.equals(t.bounds)) throw new IOException("Game window changed during capture.");
            if (calibrated && (frame.width() != store.width() || frame.height() != store.height() || frame.left != store.left() || frame.top != store.top())) {
                throw new IOException("Display or game window size changed. Restore the calibrated screen settings.");
            }
            synchronized (this) {
                if (latest != null) latest.recycle();
                latest = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
            return frame;
        } catch (Exception e) { frame.close(); throw e; }
    }
    private void gesture(GestureDescription gesture) throws Exception {
        check(); Target t = target();
        int token = epoch.get();
        if (t.bounds.left != store.left() || t.bounds.top != store.top() || t.bounds.width() != store.width() || t.bounds.height() != store.height()) {
            throw new IOException("Game coordinates changed before gesture.");
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        main.post(() -> {
            if (result.isDone()) return;
            try {
                checkToken(token);
                Target now = confirmWindow(t);
                if (now.id != t.id || !now.bounds.equals(t.bounds)) throw new IOException("Game window changed before gesture.");
                checkObstructions(gesture, now);
                checkToken(token); if (result.isDone()) return;
                boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) { result.complete(true); }
                    @Override public void onCancelled(GestureDescription g) { result.complete(false); }
                }, main);
                if (!accepted) result.complete(false);
            } catch (Exception e) { result.completeExceptionally(e); }
        });
        try {
            if (!result.get(3, TimeUnit.SECONDS)) throw new IOException("Android cancelled or rejected a gesture.");
        } catch (TimeoutException e) { result.cancel(false); throw new IOException("Android gesture callback timed out.", e); }
        catch (Exception e) { result.cancel(false); throw e; }
        check();
    }
    private void checkObstructions(GestureDescription gesture, Target target) throws IOException {
        int gameLayer = Integer.MIN_VALUE;
        List<AccessibilityWindowInfo> visible = getWindows();
        for (AccessibilityWindowInfo w : visible) if (w.getId() == target.id) gameLayer = w.getLayer();
        if (gameLayer == Integer.MIN_VALUE) throw new IOException("Game window disappeared before tap.");
        for (int i = 0; i < gesture.getStrokeCount(); i++) {
            RectF path = new RectF(); gesture.getStroke(i).getPath().computeBounds(path, true);
            path.inset(-2, -2); // A stationary tap otherwise has zero-sized bounds.
            for (AccessibilityWindowInfo w : visible) {
                if (w.getDisplayId() != Display.DEFAULT_DISPLAY || w.getId() == target.id || w.getLayer() <= gameLayer) continue;
                Rect bounds = new Rect(); w.getBoundsInScreen(bounds);
                if (RectF.intersects(path, new RectF(bounds))) throw new IOException("A floating control or system window covers the next tap. Move it away and restart.");
            }
        }
    }
    private void press(int[] point) throws Exception {
        if (!Rules.point(point, store.width(), store.height())) throw new IOException("Tap is outside the game window.");
        Path path = new Path(); path.moveTo(point[0] + store.left(), point[1] + store.top());
        gesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build());
    }
    public void prepare(int mode) {
        if (busy || store == null) return;
        if (workerRunning) { toast("Previous work is stopping. Try again in a moment."); return; }
        if (mode < FARM || mode >= Stages.ALL.size()) { toast("Unknown operation."); return; }
        try { store = new Store(this); } catch (Exception e) { toast("Cannot load saved settings: " + e.getMessage()); return; }
        if (mode == FARM || mode == ONE_ATTACK) {
            List<String> missing = store.missing();
            if (!missing.isEmpty()) { toast("Calibrate first: " + String.join(", ", missing)); return; }
        }
        stopped = false; deadline = 0; busy = true;
        int token = epoch.incrementAndGet();
        String text = mode >= 0 ? Stages.ALL.get(mode).instruction : mode == DIAGNOSE ? "Show the game screen you want to check." : "Open Home with dragons ready and one builder free.";
        update(text);
        showControl(mode >= 0 || mode == DIAGNOSE ? "CAPTURE" : "START", () -> {
            if (token != epoch.get()) return;
            showControl(null, null);
            job = worker.submit(() -> execute(mode, token));
        });
        Intent game = getPackageManager().getLaunchIntentForPackage(GAME);
        if (game == null && BuildConfig.DEBUG) game = new Intent().setClassName(TEST_SCENE, TEST_SCENE + ".SceneActivity");
        if (game == null) { stopWork("Clash of Clans is not installed in this Android profile."); return; }
        try { game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(game); }
        catch (Exception e) { stopWork("Open Clash manually: " + e.getMessage()); }
    }
    private void execute(int mode, int token) {
        workerRunning = true; workerToken.set(token);
        try {
            checkToken(token);
            if (mode >= 0) {
                Frame snapshot = capture(false);
                try {
                    store.dimensions(snapshot);
                    store.saveBitmap("screens/" + mode + ".png", snapshot.bitmap);
                    store.beginStage(Stages.ALL.get(mode));
                } catch (Exception e) { snapshot.close(); throw e; }
                main.post(() -> {
                    if (token != epoch.get()) { snapshot.close(); return; }
                    try { showPicker(snapshot, mode, token); }
                    catch (Exception e) { snapshot.close(); complete(token, "Cannot show calibration: " + e.getMessage(), true); }
                });
                return;
            }
            try (Vision vision = new Vision(store)) {
                if (mode == DIAGNOSE) {
                    StringBuilder report = new StringBuilder("Recognition only — no taps sent\n\n");
                    try (Frame f = capture(false)) {
                        if (f.width() != store.width() || f.height() != store.height() || f.left != store.left() || f.top != store.top()) throw new IOException("Capture calibration screens first at the same position and resolution.");
                        for (Stages.Stage stage : Stages.ALL) for (Stages.Field field : stage.fields) {
                            if (field.kind.equals("template") && store.rectangle("templates", field.key) != null) {
                                report.append(field.key).append(vision.match(f, field.key) == null ? ": not visible\n" : ": MATCH\n");
                            } else if (field.kind.equals("region") && store.rectangle("regions", field.key) != null) {
                                String value = vision.read(f, field.key);
                                report.append(field.key).append(": ").append(value).append(" → ").append(Rules.number(value)).append('\n');
                            }
                        }
                        report.append("Selected wall level: ").append(vision.wall(f));
                        store.saveBitmap("last-diagnostic.png", f.bitmap);
                    }
                    store.report("last-diagnostic.txt", report.toString());
                    complete(token, "Recognition report saved. Open Dragon Farmer to read it.", false);
                } else {
                    Farmer.Plan plan = plan(); if (mode == ONE_ATTACK) plan.maxAttacks = 1;
                    deadline = SystemClock.elapsedRealtime() + plan.maxMinutes * 60_000L;
                    store.log("session-start", "native APK; " + plan.maxAttacks + " attacks; " + plan.dragons + " dragons");
                    String summary = new Farmer(port(vision), plan).run();
                    complete(token, summary, false);
                }
            }
        } catch (Exception e) {
            try { StringWriter trace = new StringWriter(); e.printStackTrace(new PrintWriter(trace)); store.report("last-error.txt", trace.toString()); } catch (IOException ignored) {}
            Throwable cause = e; while (cause instanceof ExecutionException && cause.getCause() != null) cause = cause.getCause();
            complete(token, cause.getMessage() == null ? cause.toString() : cause.getMessage(), true);
        } finally { workerToken.remove(); workerRunning = false; }
    }
    private Farmer.Plan plan() {
        Farmer.Plan p = new Farmer.Plan();
        p.dragons = (int) store.setting("dragonCount"); p.maxAttacks = (int) store.setting("maxAttacks"); p.maxMinutes = (int) store.setting("maxMinutes");
        p.maxSkips = (int) store.setting("maxSkips"); p.minGold = store.setting("minimumGold"); p.minElixir = store.setting("minimumElixir"); p.minCombined = store.setting("minimumCombined");
        p.reserveGold = store.setting("reserveGold"); p.reserveElixir = store.setting("reserveElixir"); p.maxWallCost = store.setting("maxWallCost");
        p.wallsPerVisit = (int) store.setting("maxWallsPerVisit"); p.targetWallLevel = (int) store.setting("targetWallLevel"); p.wallsEnabled = store.wallsEnabled();
        p.deployments = store.points("deploy"); p.walls = store.points("walls");
        List<int[]> blank = store.points("home_blank"); if (!blank.isEmpty()) p.blank = blank.get(0);
        return p;
    }
    private Farmer.Port port(Vision vision) {
        return new Farmer.Port() {
            @Override public long now() { return SystemClock.elapsedRealtime(); }
            @Override public Farmer.Screen capture() throws Exception { return MacroService.this.capture(true); }
            @Override public boolean has(Farmer.Screen f, String key) throws Exception { return vision.match((Frame) f, key) != null; }
            @Override public String read(Farmer.Screen f, String key) throws Exception { return vision.read((Frame) f, key); }
            @Override public Integer wall(Farmer.Screen f) throws Exception { return vision.wall((Frame) f); }
            @Override public void tap(String key, String... guards) throws Exception {
                try (Frame f = MacroService.this.capture(true)) {
                    for (String guard : guards) if (vision.match(f, guard) == null) throw new IOException("Screen changed before " + key + ".");
                    int[] p = vision.match(f, key); if (p == null) throw new IOException("Button changed: " + key);
                    press(p);
                }
            }
            @Override public long pay(String resource, long price, long reserve, long cap) throws Exception {
                try (Frame f = MacroService.this.capture(true)) {
                    String key = "confirm_" + resource;
                    int[] p = vision.match(f, key);
                    if (p == null || vision.match(f, "wall_confirm") == null) throw new IOException("Wall confirmation changed before payment.");
                    Long currentPrice = Rules.number(vision.read(f, key + "_cost"));
                    Long balance = Rules.number(vision.read(f, "home_" + resource));
                    if (!Long.valueOf(price).equals(currentPrice) || !Rules.affordable(balance, currentPrice, reserve, cap)) throw new IOException("Final wall price or available resources changed. No payment sent.");
                    // Re-capture after OCR. Exact comparison of payment-critical crops
                    // closes the gap between recognizing a price and tapping its button.
                    try (Frame fresh = MacroService.this.capture(true)) {
                        int[] finalPoint = vision.match(fresh, key);
                        if (!vision.sameRegion(f, fresh, key + "_cost") || !vision.sameRegion(f, fresh, "home_" + resource)
                                || finalPoint == null || vision.match(fresh, "wall_confirm") == null) {
                            throw new IOException("Payment details changed during recognition. No payment sent.");
                        }
                        press(finalPoint);
                    }
                    return balance;
                }
            }
            @Override public void point(int[] p, String guard) throws Exception {
                try (Frame f = MacroService.this.capture(true)) {
                    if (vision.match(f, guard) == null) throw new IOException(guard.equals("home_anchor") ? "Village camera or landmark changed. Restore the calibrated view before upgrading walls." : "Expected " + guard + " before tap.");
                    if (guard.equals("home_anchor") && vision.match(f, "home") == null) throw new IOException("Home is not ready for wall selection.");
                    press(p);
                }
            }
            @Override public void pause(long ms) throws Exception { MacroService.this.pause(ms); }
            @Override public void zoom(String guard) throws Exception {
                for (int i = 0; i < 3; i++) {
                    try (Frame f = MacroService.this.capture(true)) {
                        if (vision.match(f, guard) == null) throw new IOException("Unknown screen before zoom.");
                        float w = f.width(), h = f.height();
                        Path first = new Path(), second = new Path();
                        first.moveTo(f.left + w * .25f, f.top + h * .34f); first.lineTo(f.left + w * .46f, f.top + h * .45f);
                        second.moveTo(f.left + w * .75f, f.top + h * .66f); second.lineTo(f.left + w * .54f, f.top + h * .55f);
                        gesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(first, 0, 350))
                                .addStroke(new GestureDescription.StrokeDescription(second, 0, 350)).build());
                    }
                    MacroService.this.pause(100);
                }
                MacroService.this.pause(400);
            }
            @Override public void status(String s) { update(s); }
            @Override public void log(String event, String detail) { store.log(event, detail); }
        };
    }
    public void stopWork(String reason) {
        stopped = true; busy = false; deadline = 0; int token = epoch.incrementAndGet();
        if (job != null) { job.cancel(true); job = null; }
        if (store != null) store.log("stopped", reason);
        if (store != null) try { store.report("last-status.txt", reason); } catch (IOException ignored) {}
        update(reason); main.post(() -> { if (token == epoch.get()) removeOverlay(); });
    }
    private void complete(int token, String message, boolean error) {
        if (token != epoch.get()) return;
        store.log(error ? "error" : "complete", message);
        try {
            store.report("last-status.txt", message);
            if (error) synchronized (this) { if (latest != null) store.saveBitmap("last-stop.png", latest); }
        } catch (Exception ignored) {}
        main.post(() -> { if (token == epoch.get()) { deadline = 0; update(message); removeOverlay(); busy = false; toast(message); } });
    }
    private void showPicker(Frame snapshot, int stageIndex, int token) {
        removeOverlay();
        PickerView picker = new PickerView(this, snapshot, Stages.ALL.get(stageIndex), store, new PickerView.Listener() {
            @Override public void finished() { complete(token, "Saved " + Stages.ALL.get(stageIndex).name + ". Return to Dragon Farmer for the next screen.", false); }
            @Override public void cancelled() { complete(token, "Calibration cancelled. Saved fields remain.", false); }
        });
        overlay = picker;
        WindowManager.LayoutParams params = params(true); windows.addView(picker, params);
        picker.setOnDetachCleanup(snapshot::close);
    }
    private WindowManager.LayoutParams params(boolean full) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(full ? -1 : -2, full ? -1 : -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT; p.x = full ? 0 : dp(8); p.y = full ? 0 : dp(8);
        p.setFitInsetsTypes(0);
        return p;
    }
    private void showControl(String action, Runnable callback) {
        removeOverlay();
        LinearLayout row = new LinearLayout(this); row.setPadding(dp(4), dp(3), dp(4), dp(3)); row.setBackgroundColor(Color.rgb(22, 49, 41));
        statusView = new TextView(this); statusView.setTextColor(Color.WHITE); statusView.setTextSize(11); statusView.setText(status);
        // Reserve button space even when this overlay is first measured in portrait.
        row.addView(statusView, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) { Button start = new Button(this); start.setText(action); start.setSingleLine(); start.setTextSize(12); start.setPadding(dp(6), 0, dp(6), 0); start.setOnClickListener(v -> callback.run()); row.addView(start, new LinearLayout.LayoutParams(dp(88), dp(48))); }
        Button stop = new Button(this); stop.setText("STOP"); stop.setSingleLine(); stop.setTextSize(12); stop.setPadding(dp(6), 0, dp(6), 0); stop.setOnClickListener(v -> stopWork("Stopped by user")); row.addView(stop, new LinearLayout.LayoutParams(dp(64), dp(48)));
        WindowManager.LayoutParams p = params(false);
        p.width = Math.min(dp(action == null ? 256 : 344), windows.getCurrentWindowMetrics().getBounds().width() - dp(16));
        final float[] drag = new float[4];
        statusView.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { drag[0] = e.getRawX(); drag[1] = e.getRawY(); drag[2] = p.x; drag[3] = p.y; }
            else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                Rect bounds = windows.getCurrentWindowMetrics().getBounds();
                p.x = Math.max(0, Math.min(Math.max(0, bounds.width() - row.getWidth()), (int)(drag[2] + e.getRawX() - drag[0])));
                p.y = Math.max(0, Math.min(Math.max(0, bounds.height() - row.getHeight()), (int)(drag[3] + e.getRawY() - drag[1])));
                windows.updateViewLayout(row, p);
            }
            else if (e.getAction() == MotionEvent.ACTION_UP) v.performClick();
            return true;
        });
        overlay = row; windows.addView(row, p);
    }
    private void removeOverlay() {
        if (overlay != null) { try { windows.removeViewImmediate(overlay); } catch (Exception ignored) {} overlay = null; }
        statusView = null;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String text) { main.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show()); }
}
