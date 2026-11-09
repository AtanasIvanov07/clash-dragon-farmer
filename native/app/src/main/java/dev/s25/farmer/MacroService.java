package dev.s25.farmer;

import android.accessibilityservice.*;
import android.content.*;
import android.content.res.Configuration;
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
import java.util.concurrent.atomic.AtomicBoolean;

public final class MacroService extends AccessibilityService {
    private static volatile java.lang.ref.WeakReference<MacroService> current = new java.lang.ref.WeakReference<>(null);
    public static MacroService getInstance() { return current.get(); }
    public static final String GAME = "com.supercell.clashofclans";
    private static final String TEST_SCENE = "dev.s25.testscene";
    public static final int DIAGNOSE = -1, ONE_ATTACK = -2, FARM = -3, WALLS = -4;
    private static final int HOME_PINCHES = 6;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger epoch = new AtomicInteger();
    private final ThreadLocal<Integer> workerToken = new ThreadLocal<>();
    private volatile boolean workerRunning;
    private Future<?> job;
    private WindowManager windows;
    private View overlay;
    private WindowManager.LayoutParams controlLayout;
    private int controlX = -1, controlY = -1, controlWidth;
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
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        main.post(this::clampControl);
    }
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
        Frame frame = awaitCapture(result);
        try {
            check(); Target after = target();
            if (after.id != t.id || !after.bounds.equals(t.bounds)) throw new IOException("Game window changed during capture.");
            if (calibrated && (frame.width() != store.width() || frame.height() != store.height() || frame.left != store.left() || frame.top != store.top())) {
                throw new IOException("Display or game window size changed. Restore the calibrated screen settings: same screen resolution, same landscape direction (camera cutout on the same side), no split screen.");
            }
            synchronized (this) {
                if (latest != null) latest.recycle();
                latest = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false);
            }
            return frame;
        } catch (Exception e) { frame.close(); throw e; }
    }
    static Frame awaitCapture(CompletableFuture<Frame> result) throws Exception {
        try { return result.get(5, TimeUnit.SECONDS); }
        catch (Exception e) {
            // Completion can win the race with interruption/timeout. In that case
            // the callback transferred ownership, but this worker never received it.
            if (!result.cancel(false)) result.thenAccept(Frame::close);
            if (e instanceof TimeoutException) throw new IOException("Android screenshot callback timed out.", e);
            throw e;
        }
    }
    private void gesture(GestureDescription gesture) throws Exception {
        try { gestureOnce(gesture); }
        catch (Farmer.Blocked e) {
            // Our own STOP control was dragged over a tap: move it clear once and retry.
            if (!e.control) throw e;
            CompletableFuture<Boolean> moved = new CompletableFuture<>();
            main.post(() -> moved.complete(moveControlClear()));
            if (!moved.get(2, TimeUnit.SECONDS)) throw e;
            store.log("control-moved", "floating control moved clear of calibrated taps");
            pause(400);
            gestureOnce(gesture);
        }
    }
    private void gestureOnce(GestureDescription gesture) throws Exception {
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
        // Surface main-thread refusals (such as Farmer.Blocked) as themselves.
        catch (ExecutionException e) { result.cancel(false); throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e; }
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
                if (RectF.intersects(path, new RectF(bounds))) {
                    store.log("obstruction", describe(w));
                    Rect control = controlBounds();
                    boolean own = w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && control != null
                            && Math.abs(control.left - bounds.left) <= 4 && Math.abs(control.top - bounds.top) <= 4;
                    throw new Farmer.Blocked("A floating control or system window covers the next tap: " + describe(w)
                            + ". Move the STOP control away; on Poco/Xiaomi turn off the Game Turbo toolbox for Clash. Then restart.", own);
                }
            }
        }
    }
    private static String describe(AccessibilityWindowInfo w) {
        Rect b = new Rect(); w.getBoundsInScreen(b);
        String type = switch (w.getType()) {
            case AccessibilityWindowInfo.TYPE_SYSTEM -> "system window";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility overlay";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split-screen divider";
            case AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "magnification overlay";
            default -> "app window";
        };
        return type + (w.getTitle() == null ? "" : " \"" + w.getTitle() + "\"") + " at " + b.toShortString();
    }
    /** Windows drawn above Clash (Game Turbo handles, bubbles, our STOP control) and the calibrated taps they would block. */
    private String overlayReport() throws Exception {
        Target t = target();
        List<AccessibilityWindowInfo> visible = getWindows();
        int gameLayer = Integer.MIN_VALUE;
        for (AccessibilityWindowInfo w : visible) if (w.getId() == t.id) gameLayer = w.getLayer();
        StringBuilder report = new StringBuilder();
        for (AccessibilityWindowInfo w : visible) {
            if (w.getDisplayId() != Display.DEFAULT_DISPLAY || w.getId() == t.id || w.getLayer() <= gameLayer) continue;
            Rect bounds = new Rect(); w.getBoundsInScreen(bounds); bounds.inset(-2, -2);
            report.append("• ").append(describe(w));
            for (String key : List.of("deploy", "walls", "home_blank")) {
                List<int[]> points = store.points(key);
                for (int i = 0; i < points.size(); i++) {
                    int[] p = points.get(i);
                    if (p != null && p.length == 2 && bounds.contains(p[0] + store.left(), p[1] + store.top())) report.append(" — BLOCKS ").append(key).append(' ').append(i + 1);
                }
            }
            report.append('\n');
        }
        return report.length() == 0 ? "none\n" : report.toString();
    }
    /** One two-finger pinch toward the middle of the calibrated game window. Farming and Home calibration share it. */
    private void pinchOut() throws Exception {
        float l = store.left(), t = store.top(), w = store.width(), h = store.height();
        Path first = new Path(), second = new Path();
        first.moveTo(l + w * .25f, t + h * .34f); first.lineTo(l + w * .46f, t + h * .45f);
        second.moveTo(l + w * .75f, t + h * .66f); second.lineTo(l + w * .54f, t + h * .55f);
        gesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(first, 0, 350))
                .addStroke(new GestureDescription.StrokeDescription(second, 0, 350)).build());
    }
    private void press(int[] point) throws Exception {
        if (!Rules.point(point, store.width(), store.height())) throw new IOException("Tap is outside the game window.");
        Path path = new Path(); path.moveTo(point[0] + store.left(), point[1] + store.top());
        gesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build());
    }
    public void prepare(int mode) {
        if (busy || store == null) return;
        if (workerRunning) { toast("Previous work is stopping. Try again in a moment."); return; }
        if (mode < WALLS || mode >= Stages.ALL.size()) { toast("Unknown operation."); return; }
        try { store = new Store(this); } catch (Exception e) { toast("Cannot load saved settings: " + e.getMessage()); return; }
        if (mode == FARM || mode == ONE_ATTACK || mode == WALLS) {
            List<String> missing = store.missing();
            if (!missing.isEmpty()) { toast("Calibrate first: " + String.join(", ", missing)); return; }
        }
        stopped = false; deadline = 0; busy = true;
        int token = epoch.incrementAndGet();
        String text = mode >= 0 ? Stages.ALL.get(mode).instruction : mode == DIAGNOSE ? "Show the game screen you want to check."
                : mode == WALLS ? "Open Home with one builder free. Walls only, no attack." : "Open Home with dragons ready and one builder free.";
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
                // Wall points are only valid at the zoom the farmer uses after each battle,
                // so Home is captured after the very same pinch-out.
                if (mode == 0 && store.width() > 0) {
                    update("Zooming out like after a battle…");
                    for (int i = 0; i < HOME_PINCHES; i++) { pinchOut(); pause(100); }
                    pause(600);
                }
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
                                Number parsed = field.key.endsWith("_count") ? (Number) Rules.troopCount(value)
                                        : field.key.equals("home_builders") ? (Number) Rules.freeBuilders(value) : Rules.number(value);
                                report.append(field.key).append(": ").append(value).append(" → ").append(parsed)
                                        .append(field.key.equals("home_builders") ? " free builders\n" : "\n");
                            }
                        }
                        if (vision.match(f, "home") != null) {
                            List<int[]> walls = vision.findWalls(f);
                            report.append("Walls like your picked ones on screen: ").append(walls.size())
                                    .append(walls.isEmpty() ? " — recapture Home village and tap walls of your lowest level" : "").append('\n');
                        }
                        report.append("Selected wall level: ").append(vision.wall(f));
                        store.saveBitmap("last-diagnostic.png", f.bitmap);
                    }
                    report.append("\n\nWindows above Clash (the floating STOP control is an accessibility overlay):\n").append(overlayReport());
                    store.report("last-diagnostic.txt", report.toString());
                    complete(token, "Recognition report saved. Open Dragon Farmer to read it.", false);
                } else {
                    Farmer.Plan plan = plan(); if (mode == ONE_ATTACK) plan.maxAttacks = 1;
                    deadline = SystemClock.elapsedRealtime() + plan.maxMinutes * 60_000L;
                    store.log("session-start", "native APK " + BuildConfig.VERSION_NAME + "; " + (mode == WALLS ? "walls only" : plan.maxAttacks + " attacks; " + plan.dragons + " dragons" + (plan.pekka ? " + event P.E.K.K.As" : "")));
                    Farmer farmer = new Farmer(port(vision), plan);
                    String summary = mode == WALLS ? farmer.wallsOnly() : farmer.run();
                    complete(token, summary, false);
                }
            }
        } catch (Throwable e) {
            // Errors (such as running out of memory) end the session visibly too, instead of
            // leaving the floating control frozen on its last step.
            try { StringWriter trace = new StringWriter(); e.printStackTrace(new PrintWriter(trace)); store.report("last-error.txt", trace.toString()); } catch (Throwable ignored) {}
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
        p.pekka = store.hasTemplate("pekka") && Rules.rect(store.rectangle("regions", "pekka_count"), store.width(), store.height());
        for (String card : Stages.EXTRA_CARDS) if (store.hasTemplate(card)) p.extras.add(card);
        p.abilitySeconds = (int) store.setting("heroAbilitySeconds");
        p.rage = store.hasTemplate("rage"); p.ragePoints = store.points("rage_points"); p.rageSeconds = (int) store.setting("rageSeconds");
        p.checkBuilders = Rules.rect(store.rectangle("regions", "home_builders"), store.width(), store.height());
        p.batch = store.hasTemplate("wall_more") && store.hasTemplate("batch_add1") && store.hasTemplate("batch_add10")
                && store.hasTemplate("batch_gold") && store.hasTemplate("batch_elixir")
                && Rules.rect(store.rectangle("regions", "batch_gold_cost"), store.width(), store.height())
                && Rules.rect(store.rectangle("regions", "batch_elixir_cost"), store.width(), store.height());
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
            @Override public int[] find(Farmer.Screen f, String key) throws Exception {
                int[] at = vision.card((Frame) f, key);
                // Shows what the troop bar looked like when a calibrated card was missing.
                if (at == null) store.saveBitmap("army-cards.png", ((Frame) f).bitmap);
                return at;
            }
            @Override public void tap(String key, String... guards) throws Exception {
                // A menu still sliding in (Multiplayer after Home's Battle) can miss one frame's match:
                // look up to four times before giving up. Nothing is tapped until every guard matches.
                for (int look = 0; ; look++) {
                    try (Frame f = MacroService.this.capture(true)) {
                        boolean ready = true;
                        for (String guard : guards) if (vision.match(f, guard) == null) { ready = false; break; }
                        int[] p = ready ? vision.match(f, key) : null;
                        if (p != null) { press(p); return; }
                        if (look == 3) throw new IOException(ready ? "Button changed: " + key : "Screen changed before " + key + ".");
                    }
                    MacroService.this.pause(300);
                }
            }
            @Override public boolean okay(String resource, long total, long balance, long reserve, long cap) throws Exception {
                try (Frame f = MacroService.this.capture(true)) {
                    List<com.google.mlkit.vision.text.Text.Line> lines = vision.textLines(f.bitmap);
                    // Right after an Upgrade More payment button, an Okay/Cancel pair is that question even when its sentence is misread.
                    if (!Rules.batchQuestion(Vision.texts(lines)) && (Vision.word(lines, "Okay") == null || Vision.word(lines, "Cancel") == null)) return false;
                }
                // Clash grows the dialog in (about 8 % larger and Okay ~15 px off while it animates): read it
                // once it has settled, and tap Okay only after two frames in a row name exactly the total
                // with Okay in the same place. A wrong amount read twice is cancelled, never paid.
                MacroService.this.pause(700);
                Frame last = null; List<com.google.mlkit.vision.text.Text.Line> lastLines = null;
                Long previous = null; int[] previousOkay = null;
                try {
                    for (int read = 0; read < 6; read++) {
                        if (read > 0) MacroService.this.pause(300);
                        Frame f = MacroService.this.capture(true);
                        if (last != null) last.close();
                        last = f;
                        List<com.google.mlkit.vision.text.Text.Line> lines = vision.textLines(f.bitmap);
                        lastLines = lines;
                        Long asked = Rules.batchDialog(Vision.texts(lines), resource);
                        int[] okay = Vision.word(lines, "Okay");
                        if (asked != null && asked.equals(previous) && asked != total)
                            refuseQuestion(f, lines, "Upgrade More asked for " + asked + " " + resource + ", expected " + total + ".");
                        if (asked != null && asked == total && okay != null && Long.valueOf(total).equals(previous) && previousOkay != null
                                && Math.abs(okay[0] - previousOkay[0]) <= 12 && Math.abs(okay[1] - previousOkay[1]) <= 12) {
                            if (!Rules.affordable(balance, total, reserve, cap)) refuseQuestion(f, lines, "Upgrade More's total " + total + " is not affordable above the reserve.");
                            press(okay);
                            store.log("batch-okay", total + " " + resource);
                            return true;
                        }
                        previous = asked; previousOkay = okay;
                    }
                    refuseQuestion(last, lastLines, "Upgrade More's question was not read the same twice (last: " + previous + " " + resource
                            + ", Okay " + (previousOkay == null ? "not found" : "found") + "), expected " + total + ".");
                    return false;
                } finally { if (last != null) last.close(); }
            }
            /**
             * Keeps what was read, then closes the question with Cancel (which never pays) so Home comes back
             * and the walls can go one at a time. Always throws.
             */
            private void refuseQuestion(Frame f, List<com.google.mlkit.vision.text.Text.Line> lines, String why) throws Exception {
                store.saveBitmap("upgrade-walls-question.png", f.bitmap);
                store.log("batch-question", String.join(" | ", Vision.texts(lines)));
                int[] cancel = Vision.word(lines, "Cancel");
                if (cancel != null) { press(cancel); MacroService.this.pause(800); }
                throw new IOException(why + " No payment sent" + (cancel == null ? "; Cancel not found." : "; question cancelled."));
            }
            @Override public void pay(String resource, long price, long balance, long reserve, long cap) throws Exception {
                try (Frame f = MacroService.this.capture(true)) {
                    String key = "confirm_" + resource;
                    if (vision.match(f, key) == null || vision.match(f, "wall_confirm") == null) throw new IOException("Wall confirmation changed before payment.");
                    // The resource bar behind the dialog is dimmed and partly under its close
                    // button, so the balance is the one read on Home just before.
                    if (!Rules.shows(vision.read(f, key + "_cost"), price) || !Rules.affordable(balance, price, reserve, cap)) throw new IOException("Final wall price or available resources changed. No payment sent.");
                    // Re-capture after OCR. Exact comparison of the price crop closes the
                    // gap between recognizing a price and tapping its button.
                    try (Frame fresh = MacroService.this.capture(true)) {
                        int[] finalPoint = vision.match(fresh, key);
                        if (!vision.sameRegion(f, fresh, key + "_cost") || finalPoint == null || vision.match(fresh, "wall_confirm") == null) {
                            throw new IOException("Payment details changed during recognition. No payment sent.");
                        }
                        press(finalPoint);
                    }
                }
            }
            @Override public void point(int[] p, String guard) throws Exception {
                try (Frame f = MacroService.this.capture(true)) {
                    if (vision.match(f, guard) == null) throw new IOException(guard.equals("home_anchor") ? "Village camera or landmark changed. Restore the calibrated view before upgrading walls." : "Expected " + guard + " before tap.");
                    if (guard.equals("home_anchor") && vision.match(f, "home") == null) throw new IOException("Home is not ready for wall selection.");
                    press(p);
                }
            }
            @Override public int burst(List<int[]> points, String... guards) throws Exception {
                // Troops and spell effects passing behind a button can spoil one frame's match.
                boolean known = false;
                for (int look = 0; look < 4 && !known; look++) {
                    if (look > 0) MacroService.this.pause(250);
                    try (Frame f = MacroService.this.capture(true)) {
                        for (String guard : guards) if (store.hasTemplate(guard) && vision.match(f, guard) != null) { known = true; break; }
                        if (!known && look == 3) store.saveBitmap("unknown-screen.png", f.bitmap);
                    }
                }
                if (!known) throw new IOException("Unknown screen before deployment.");
                // No screenshot between taps: Android allows only ~3 per second. Each press
                // still checks the game window, its position and covering windows.
                int sent = 0;
                for (int[] p : points) {
                    try { press(p); sent++; }
                    catch (Farmer.Blocked e) { store.log("deploy-blocked", Arrays.toString(p) + ": " + e.getMessage()); }
                }
                return sent;
            }
            @Override public void pause(long ms) throws Exception { MacroService.this.pause(ms); }
            @Override public void zoom(String guard) throws Exception {
                // Home comes back from a battle zoomed in; extra pinches at full zoom-out do
                // nothing, so Home always ends at the same zoom as its calibration.
                for (int i = 0, times = guard.equals("home") ? HOME_PINCHES : 3; i < times; i++) {
                    // Loot flying into the storages after a battle can spoil one frame's match.
                    for (int look = 0; ; look++) {
                        try (Frame f = MacroService.this.capture(true)) { if (vision.match(f, guard) != null) break; }
                        if (look == 3) throw new IOException("Unknown screen before zoom.");
                        MacroService.this.pause(300);
                    }
                    pinchOut();
                    MacroService.this.pause(100);
                }
                MacroService.this.pause(400);
            }
            @Override public Integer wallCount(Farmer.Screen f) throws Exception { return vision.wallCount((Frame) f); }
            @Override public List<int[]> walls(Farmer.Screen f) throws Exception {
                List<int[]> found = vision.findWalls((Frame) f);
                store.log("walls-found", found.size() + " walls like the picked ones"
                        + (vision.lastLearned == 0 ? "" : ", " + vision.lastLearned + " of them like the learned level " + vision.lastLearnedLevel + " walls"));
                if (found.isEmpty()) store.saveBitmap("walls-skipped.png", ((Frame) f).bitmap);
                return found;
            }
            @Override public void status(String s) { update(s); }
            @Override public void log(String event, String detail) { store.log(event, detail); }
            @Override public void back() throws Exception {
                target(); // only ever inside Clash
                if (!performGlobalAction(GLOBAL_ACTION_BACK)) throw new IOException("Android refused Back.");
                store.log("recover-back", "Android Back");
                MacroService.this.pause(800);
            }
            @Override public boolean tapWord(String... words) throws Exception {
                try (Frame f = MacroService.this.capture(true)) {
                    List<com.google.mlkit.vision.text.Text.Line> lines = vision.textLines(f.bitmap);
                    for (String word : words) {
                        int[] at = Vision.word(lines, word);
                        if (at == null) continue;
                        store.log("recover-tap", word);
                        press(at);
                        return true;
                    }
                }
                return false;
            }
            @Override public void restore() throws Exception {
                try { target(); return; }
                catch (IOException e) { if (e.getMessage() == null || !e.getMessage().contains("foreground")) throw e; }
                Intent game = getPackageManager().getLaunchIntentForPackage(GAME);
                if (game == null) throw new IOException("Clash of Clans is not installed in this Android profile.");
                store.log("game-reopened", "Clash was not in front");
                game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                main.post(() -> { try { startActivity(game); } catch (Exception ignored) {} });
                MacroService.this.pause(15000);
            }
            @Override public void learnWall(int[] point, int level) throws Exception {
                try (Frame f = MacroService.this.capture(true)) {
                    if (vision.match(f, "home") == null) { store.log("wall-learn", "Home not visible, nothing kept"); return; }
                    if (vision.wall(f) != null) { store.log("wall-learn", "a wall is still selected, nothing kept"); return; }
                    store.log("wall-learn", Arrays.toString(point) + ": " + vision.learnWall(f, point, level));
                }
            }
            @Override public void save(Farmer.Screen f, String name) {
                try { store.saveBitmap(name, ((Frame) f).bitmap); } catch (IOException e) { store.log("save-failed", name + ": " + e.getMessage()); }
            }
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
        if (action != null) {
            Button start = new Button(this); start.setText(action); start.setSingleLine(); start.setTextSize(12); start.setPadding(dp(6), 0, dp(6), 0);
            AtomicBoolean clicked = new AtomicBoolean();
            start.setOnClickListener(v -> { if (clicked.compareAndSet(false, true)) callback.run(); });
            row.addView(start, new LinearLayout.LayoutParams(dp(88), dp(48)));
        }
        Button stop = new Button(this); stop.setText("STOP"); stop.setSingleLine(); stop.setTextSize(12); stop.setPadding(dp(6), 0, dp(6), 0); stop.setOnClickListener(v -> stopWork("Stopped by user")); row.addView(stop, new LinearLayout.LayoutParams(dp(64), dp(48)));
        WindowManager.LayoutParams p = params(false);
        controlWidth = dp(action == null ? 256 : 344);
        p.width = Math.min(controlWidth, windows.getCurrentWindowMetrics().getBounds().width() - dp(16));
        if (controlX >= 0) { p.x = controlX; p.y = controlY; }
        final float[] drag = new float[4];
        statusView.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) { drag[0] = e.getRawX(); drag[1] = e.getRawY(); drag[2] = p.x; drag[3] = p.y; }
            else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                p.x = (int)(drag[2] + e.getRawX() - drag[0]);
                p.y = (int)(drag[3] + e.getRawY() - drag[1]);
                clampControl();
            }
            else if (e.getAction() == MotionEvent.ACTION_UP) v.performClick();
            return true;
        });
        overlay = row; controlLayout = p; windows.addView(row, p);
        row.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> clampControl());
        row.post(this::clampControl);
        // While work runs, start from a spot that covers none of the calibrated taps.
        if (action == null) row.post(() -> { if (overlay == row) moveControlClear(); });
    }
    private Rect controlBounds() {
        if (overlay == null || controlLayout == null || !overlay.isAttachedToWindow()) return null;
        int[] at = new int[2]; overlay.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + overlay.getWidth(), at[1] + overlay.getHeight());
    }
    /** Screen areas a session taps or swipes: calibrated points, tapped buttons, the whole troop-bar row and the zoom pinch. */
    private List<Rect> tapAreas() {
        List<Rect> areas = new ArrayList<>();
        int l = store.left(), t = store.top(), w = store.width(), h = store.height(), m = dp(12);
        for (String key : List.of("deploy", "walls", "home_blank", "rage_points")) {
            for (int[] p : store.points(key)) if (p != null && p.length == 2) areas.add(new Rect(l + p[0] - m, t + p[1] - m, l + p[0] + m, t + p[1] + m));
        }
        for (String key : List.of("home", "find_match", "start_attack", "scout", "result", "wall_gold", "wall_elixir", "confirm_gold", "confirm_elixir")) {
            int[] r = store.rectangle("templates", key);
            if (r != null && r.length == 4) areas.add(new Rect(l + r[0] - m, t + r[1] - m, l + r[0] + r[2] + m, t + r[1] + r[3] + m));
        }
        int[] dragon = store.rectangle("templates", "dragon"); // the card slides along the bar
        if (dragon != null && dragon.length == 4) areas.add(new Rect(l, t + dragon[1] - m, l + w, t + dragon[1] + dragon[3] + m));
        areas.add(new Rect(l + w / 4 - m, t + h * 34 / 100 - m, l + w * 46 / 100 + m, t + h * 45 / 100 + m));
        areas.add(new Rect(l + w * 54 / 100 - m, t + h * 55 / 100 - m, l + w * 3 / 4 + m, t + h * 66 / 100 + m));
        return areas;
    }
    /** Moves the floating control to the first spot, top edge first and center outward, that covers no tap area. */
    private boolean moveControlClear() {
        if (controlBounds() == null || store == null || store.width() <= 0) return false;
        Rect screen = windows.getCurrentWindowMetrics().getBounds();
        int cw = Math.max(1, overlay.getWidth()), ch = Math.max(dp(54), overlay.getHeight());
        int spanX = Math.max(0, screen.width() - cw), spanY = Math.max(0, screen.height() - ch), step = Math.max(1, spanX / 20);
        List<Integer> rows = new ArrayList<>(List.of(0, spanY));
        for (int k = 1; k < 8; k++) rows.add(spanY * k / 8);
        List<Rect> areas = tapAreas();
        for (int y : rows) for (int i = 0; i <= 40; i++) {
            int x = spanX / 2 + (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * step;
            if (x < 0 || x > spanX) continue;
            Rect spot = new Rect(x, y, x + cw, y + ch); boolean clear = true;
            for (Rect area : areas) if (Rect.intersects(spot, area)) { clear = false; break; }
            if (!clear) continue;
            controlLayout.x = x; controlLayout.y = y; controlX = x; controlY = y;
            windows.updateViewLayout(overlay, controlLayout);
            return true;
        }
        return false;
    }
    private void clampControl() {
        if (overlay == null || controlLayout == null || !overlay.isAttachedToWindow()) return;
        Rect bounds = windows.getCurrentWindowMetrics().getBounds();
        int width = Math.min(controlWidth, Math.max(1, bounds.width() - dp(16)));
        int x = Math.max(0, Math.min(Math.max(0, bounds.width() - width), controlLayout.x));
        int height = Math.max(dp(54), overlay.getHeight());
        int y = Math.max(0, Math.min(Math.max(0, bounds.height() - height), controlLayout.y));
        boolean changed = controlLayout.width != width || controlLayout.x != x || controlLayout.y != y;
        controlLayout.width = width; controlLayout.x = x; controlLayout.y = y;
        controlX = x; controlY = y;
        if (changed) windows.updateViewLayout(overlay, controlLayout);
    }
    private void removeOverlay() {
        if (overlay != null) { try { windows.removeViewImmediate(overlay); } catch (Exception ignored) {} overlay = null; }
        statusView = null; controlLayout = null;
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String text) { main.post(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show()); }
}
