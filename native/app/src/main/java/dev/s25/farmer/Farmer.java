package dev.s25.farmer;

import java.util.*;

/** Farming state machine. Tests use the same class with a simulated screen port. */
public final class Farmer {
    public interface Screen extends AutoCloseable { @Override void close(); }
    /** A tap refused because another window covers it. Nothing was sent to the game. */
    public static final class Blocked extends java.io.IOException {
        /** True when the covering window is this app's own floating control, which can be moved. */
        public final boolean control;
        public Blocked(String message) { this(message, false); }
        public Blocked(String message, boolean control) { super(message); this.control = control; }
    }
    public interface Port {
        long now();
        Screen capture() throws Exception;
        boolean has(Screen screen, String key) throws Exception;
        String read(Screen screen, String key) throws Exception;
        Integer wall(Screen screen) throws Exception;
        /** Center of a visible template, or null. */
        int[] find(Screen screen, String key) throws Exception;
        /** Centers of village walls that look like the picked ones, best match first. */
        List<int[]> walls(Screen screen) throws Exception;
        /** Walls selected in Upgrade More (the "x N" in the title), or null. */
        Integer wallCount(Screen screen) throws Exception;
        void tap(String key, String... guards) throws Exception;
        /** Pays on the open confirmation once it still shows {@code price} and {@code balance} (read on Home) covers it above the reserve. */
        void pay(String resource, long price, long balance, long reserve, long cap) throws Exception;
        /**
         * Upgrade More's question "upgrade the selected Walls for N Elixir?": false when it is not on screen.
         * Taps Okay once it names exactly {@code total} of {@code resource} and the balance covers it above
         * the reserve; when it names anything else (or cannot be read) it never taps Okay: it taps Cancel when it
         * can, then throws an IOException.
         */
        boolean okay(String resource, long total, long balance, long reserve, long cap) throws Exception;
        void point(int[] point, String guard) throws Exception;
        /** Checks one guard is visible, then taps every point back to back. Covered points are skipped. Returns taps sent. */
        int burst(List<int[]> points, String... guards) throws Exception;
        void pause(long millis) throws Exception;
        void zoom(String guard) throws Exception;
        void status(String text);
        void log(String event, String detail);
        /** Keeps this screenshot with the exported test details. */
        void save(Screen screen, String name);
        /** Android Back inside Clash (closes a popup or answers a question with Cancel). Never used on Home. */
        void back() throws Exception;
        /** Taps the one button whose label OCR reads as one of {@code words}; false when there is none. */
        boolean tapWord(String... words) throws Exception;
        /** Brings Clash back to the front if another app or the launcher took over. */
        void restore() throws Exception;
        /**
         * Keeps a picture of the wall at {@code point} (just upgraded, panel closed) as a wall of {@code level},
         * so the walls of that level are found once the picked level runs out.
         */
        void learnWall(int[] point, int level) throws Exception;
    }
    public static final class Plan {
        public long minGold = 0, minElixir = 0, minCombined = 1_500_000;
        public long reserveGold = 1_000_000, reserveElixir = 1_000_000, maxWallCost = 10_000_000;
        public int dragons = 18, maxAttacks = 30, maxMinutes = 90, maxSkips = 40, wallsPerVisit = 2, targetWallLevel = 0;
        public boolean wallsEnabled = true;
        public List<int[]> deployments = new ArrayList<>(), walls = new ArrayList<>();
        /** Calibrated siege/hero cards, deployed after the dragons in this order. */
        public List<String> extras = new ArrayList<>();
        /** Seconds after the heroes land before their abilities are tapped. */
        public int abilitySeconds = 8;
        /** Rage spells: dropped on ragePoints this many seconds after everything is deployed. */
        public boolean rage;
        public List<int[]> ragePoints = new ArrayList<>();
        public int rageSeconds = 4;
        /** Read the free-builder counter before walls. */
        public boolean checkBuilders;
        /** Upgrade More is calibrated: pay for many walls of one level at once. */
        public boolean batch;
        public int[] blank;
        public void validate() {
            if (dragons < 1 || dragons > 100 || maxAttacks < 1 || maxAttacks > 10000 || maxMinutes < 1 || maxMinutes > 1440
                    || maxSkips < 1 || maxSkips > 1000 || deployments.isEmpty()
                    || minGold < 0 || minElixir < 0 || minCombined < 0 || reserveGold < 0 || reserveElixir < 0
                    || maxWallCost <= 0 || targetWallLevel < 0 || targetWallLevel > 100
                    || (wallsEnabled && (walls.isEmpty() || blank == null || wallsPerVisit < 1 || wallsPerVisit > 50))) {
                throw new IllegalArgumentException("Settings or calibration are incomplete.");
            }
        }
    }
    private final Port io;
    private final Plan plan;
    private int attacks, upgraded, wallSkips;
    private String wallSkipReason;
    /** The last walls outcome. It stays on the floating control, under the current step, until walls are checked again. */
    private String wallNote;
    /** Lowest wall level selected so far this session, and the last wall price read. */
    private Integer lowestWall;
    private long wallPrice;
    /** A payment that cannot be accounted for: no more wall spending this session. */
    static final class SpendingHalted extends IllegalStateException {
        SpendingHalted(String message) { super(message); }
    }
    /** Set after a {@link SpendingHalted}: farming goes on, walls do not. */
    private boolean wallsOff;
    /** Why the selected wall was not paid for, shown in the result. */
    private String refusal;
    /** Upgrade More failed a check this session; walls go one at a time with the plain Upgrade button. */
    private boolean batchFailed;
    public Farmer(Port port, Plan plan) { this.io = port; this.plan = plan; plan.validate(); }
    private void require(Screen f, String key) throws Exception {
        if (!io.has(f, key)) throw new IllegalStateException("Expected " + key + ". The screen changed.");
    }
    private void waitFor(String key, long seconds) throws Exception {
        long until = io.now() + seconds * 1000;
        do {
            try (Screen f = io.capture()) { if (io.has(f, key)) return; }
            io.pause(250);
        } while (io.now() < until);
        throw new IllegalStateException("Timed out waiting for " + key + ". Check the game or recapture that button.");
    }
    private void waitGone(String key, long seconds) throws Exception {
        long until = io.now() + seconds * 1000;
        do {
            try (Screen f = io.capture()) { if (!io.has(f, key)) return; }
            io.pause(200);
        } while (io.now() < until);
        throw new IllegalStateException(key + " did not change after its tap. Stopped to avoid repeated taps.");
    }
    private Long number(String region, String guard) throws Exception {
        // A value counts only once two separate captures agree; one OCR miss costs a third read.
        // Stored resources count up after a battle and down after a payment, so those get
        // up to ~4 s to settle (an animation never shows the same number twice).
        List<Long> seen = new ArrayList<>();
        boolean guarded = false;
        for (int read = 0, reads = region.startsWith("home_") ? 10 : 3; read < reads; read++) {
            if (read > 0) io.pause(300);
            Long n;
            try (Screen f = io.capture()) {
                // One frame without its guard (a flame over Home's button, a fading dialog) is a missed read.
                if (!io.has(f, guard)) continue;
                guarded = true; n = Rules.number(io.read(f, region));
            }
            if (n != null && seen.contains(n)) return n;
            if (n != null) seen.add(n);
        }
        if (!guarded) throw new IllegalStateException("Expected " + guard + ". The screen changed.");
        return null;
    }
    /** True once a read of the confirmation shows exactly {@code amount}. During a sale the old price is shown too, crossed out. */
    private boolean shows(String region, String guard, long amount) throws Exception {
        for (int read = 0; read < 3; read++) {
            if (read > 0) io.pause(300);
            try (Screen f = io.capture()) { require(f, guard); if (Rules.shows(io.read(f, region), amount)) return true; }
        }
        return false;
    }
    private void deselect() throws Exception {
        try (Screen f = io.capture()) {
            require(f, "home");
        }
        if (wallLevel(3, null) == null) throw new IllegalStateException("Selected-wall title disappeared before deselecting.");
        closePanel();
        try (Screen f = io.capture()) {
            if (io.wall(f) != null) throw new IllegalStateException("Empty-ground point did not close the wall panel. Recalibrate it.");
        }
    }
    private void closePanel() throws Exception { io.point(plan.blank, "home"); io.pause(500); waitFor("home", 8); }
    /**
     * A wall step that went wrong ends walls for this visit (for the whole session after a payment that
     * cannot be accounted for), not the farming: the panel is closed and the next attack starts from Home.
     * STOP and time limits are interruptions and never land here. If Home cannot be restored, that error
     * ends the session as before.
     */
    private void wallsStopped(Exception e) throws Exception {
        wallsOff |= e instanceof SpendingHalted;
        try (Screen f = io.capture()) { io.save(f, "walls-stop.png"); }
        skipWalls(e.getMessage());
        closePanel();
    }
    private void skipWalls(String reason) {
        wallSkips++; wallSkipReason = reason; wallNote = "Walls skipped: " + reason;
        io.log("walls-skipped", reason); io.status(wallNote);
    }
    private void wallsDone(String reason) { io.log("walls-done", reason); wallNote = "Walls done: " + reason; }
    /** The current step; the last walls outcome is shown under it so a skipped visit is not hidden by the next attack. */
    private void status(String text) { io.status(wallNote == null ? text : text + "\n" + wallNote); }
    private Integer freeBuilders() throws Exception {
        // Right after a battle the counter is often not readable yet (half of the Poco visits failed
        // within one second): up to ~5 s of reads, and what was read is kept when it never settles.
        List<Integer> seen = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (int read = 0; read < 10; read++) {
            if (read > 0) io.pause(400);
            String text;
            try (Screen f = io.capture()) { if (!io.has(f, "home")) continue; text = io.read(f, "home_builders"); }
            Integer n = Rules.freeBuilders(text);
            if (n != null && seen.contains(n)) return n;
            if (n != null) seen.add(n);
            texts.add(text == null ? "null" : "\"" + text.replace('\n', '/') + "\"");
        }
        io.log("builders-unreadable", String.join(", ", texts));
        return null;
    }
    /**
     * The selected wall's level from up to {@code reads} captures: OCR of the small title misses now and
     * then. Returns {@code want} as soon as it is read, else the last level read, else null.
     */
    private Integer wallLevel(int reads, Integer want) throws Exception {
        Integer last = null;
        for (int read = 0; read < reads; read++) {
            if (read > 0) io.pause(300);
            Integer n;
            try (Screen f = io.capture()) { n = io.wall(f); }
            if (n != null && (want == null || n.equals(want))) return n;
            if (n != null) last = n;
        }
        return last;
    }
    private void walls() throws Exception {
        if (!plan.wallsEnabled) return;
        wallNote = null;
        // Each visit starts fresh: a lower level seen on an earlier visit, or one Upgrade More misread,
        // must not block the walls that are on screen now.
        lowestWall = null; batchFailed = false;
        status("Checking walls");
        waitFor("home", 8);
        // Walls need a free builder; otherwise Clash offers to finish an upgrade with gems.
        Integer free = null;
        if (plan.checkBuilders) {
            status("Checking walls: free builders");
            free = freeBuilders();
            if (free == null) { skipWalls("builder counter unreadable (recalibrate the builder counter)"); return; }
            if (free == 0) { skipWalls("no free builder (a wall upgrade needs an idle builder)"); return; }
        }
        io.pause(600); // let Clash's zoom animation settle
        // Walls are found by how they look (like the ones picked in calibration), so the camera
        // position does not matter. An upgraded wall looks different and drops out of the search.
        int paidThisVisit = 0;
        while (paidThisVisit < plan.wallsPerVisit) {
            waitFor("home", 8);
            status("Checking walls: gold and elixir");
            Long gold = number("home_gold", "home"), elixir = number("home_elixir", "home");
            io.log("walls-check", "gold " + gold + ", elixir " + elixir + (free == null ? "" : ", free builders " + free));
            if (gold == null || elixir == null) { skipWalls("stored gold or elixir unreadable"); return; }
            // Every wall of one level costs the same: below that price on both resources, this visit is done.
            if (wallPrice > 0 && !Rules.affordable(gold, wallPrice, plan.reserveGold, plan.maxWallCost)
                    && !Rules.affordable(elixir, wallPrice, plan.reserveElixir, plan.maxWallCost)) { wallsDone("gold and elixir are at their reserves"); return; }
            status("Checking walls: looking for walls");
            List<int[]> found;
            try (Screen f = io.capture()) { require(f, "home"); found = io.walls(f); }
            // The first wall read is taken: going back to a wall after tapping others is unreliable (a
            // second tap on a crowded spot can select a neighbour). A lower wall found later is taken
            // next and from then on sets the level; walls already at the target level are passed over.
            int[] point = null; Integer level = null;
            boolean reachedTarget = false;
            List<int[]> tries = found.subList(0, Math.min(8, found.size()));
            for (int[] candidate : tries) {
                status("Checking walls: tapping wall " + (tries.indexOf(candidate) + 1) + " of " + tries.size() + " found");
                io.point(candidate, "home"); io.pause(600);
                Integer seen = wallLevel(2, null);
                boolean finished = seen != null && plan.targetWallLevel > 0 && seen >= plan.targetWallLevel;
                reachedTarget |= finished;
                if (seen != null && !finished && (lowestWall == null || seen <= lowestWall)) { point = candidate; level = seen; break; }
                io.log("wall-miss", Arrays.toString(candidate) + (seen == null ? " is not a wall" : " is wall level " + seen));
                closePanel();
            }
            if (point == null) {
                if (reachedTarget) wallsDone("walls reached the target level " + plan.targetWallLevel);
                else if (paidThisVisit == 0) skipWalls(found.isEmpty() ? "no wall like the picked ones on screen (recapture Home village and tap walls of your lowest level)" : "none of the " + tries.size() + " spots tapped selected a wall" + (lowestWall == null ? "" : " of level " + lowestWall + " or lower"));
                else wallsDone(paidThisVisit + " upgraded; no more walls of level " + lowestWall + " visible");
                return;
            }
            lowestWall = level; // stay on the lowest level; higher walls wait until it is finished
            int paid = plan.batch && !batchFailed ? upgradeBatch(point, level, gold, elixir, plan.wallsPerVisit - paidThisVisit)
                    : upgradeSelected(point, level, gold, elixir) ? 1 : 0;
            if (paid < 0 && batchFailed) continue; // Upgrade More misbehaved, nothing paid: one wall at a time from now on
            if (paid < 0) return; // price unreadable: panel closed, nothing paid
            if (paid == 0) {
                // A wall is selected but nothing was paid: keep the screen that shows why.
                try (Screen f = io.capture()) { io.save(f, "walls-stop.png"); }
                deselect();
                if (paidThisVisit == 0) skipWalls(refusal); else wallsDone(paidThisVisit + " upgraded; " + refusal);
                return;
            }
            paidThisVisit += paid;
        }
        wallsDone(paidThisVisit + " upgraded (your maximum per visit)");
    }
    private Integer selectedCount() throws Exception {
        Integer seen = null;
        for (int read = 0; read < 3; read++) {
            if (read > 0) io.pause(300);
            Integer n;
            try (Screen f = io.capture()) { n = io.wallCount(f); }
            if (n != null && n.equals(seen)) return n;
            seen = n;
        }
        return null;
    }
    /**
     * Clash's Upgrade More: one payment for as many walls of this level as the budget above the reserve
     * allows. The title count and the total must equal count × the single-wall price before paying, and
     * the deduction is verified after. Returns walls paid, 0 when nothing is affordable, -1 when a check
     * failed and the batch was abandoned without paying.
     */
    private int upgradeBatch(int[] point, int level, long gold, long elixir, int limit) throws Exception {
        List<String> resources = resources(gold, elixir);
        boolean unreadable = false;
        refusal = "no gold or elixir Upgrade button recognized (recapture Selected wall)";
        for (String resource : resources) {
            String button = "wall_" + resource;
            try (Screen f = io.capture()) { if (!io.has(f, button)) continue; }
            Long unit = number(button + "_cost", button);
            // Nothing is open yet but the wall panel: the other resource may still be readable.
            if (unit == null) { unreadable = true; io.log("price-unreadable", resource + " wall price"); continue; }
            wallPrice = unit;
            // Read now, with the wall selected: a spot tapped earlier that was not a wall may have been a
            // collector, and collecting it changed the balance read at the start of the visit.
            Long fresh = number("home_" + resource, "home");
            if (fresh == null) { refusal = "stored " + resource + " unreadable with the wall selected"; continue; }
            long balance = fresh;
            long reserve = resource.equals("gold") ? plan.reserveGold : plan.reserveElixir;
            if (!Rules.affordable(balance, unit, reserve, plan.maxWallCost)) { refusal = unaffordable(unit); continue; }
            int want = (int) Math.min(limit, (balance - reserve) / unit);
            if (want < 2) return upgradeSelected(point, level, gold, elixir) ? 1 : 0;
            io.status("Selecting " + want + " walls (level " + level + ")");
            io.tap("wall_more", "home", "wall_more");
            waitFor("batch_add1", 6);
            // The walls selected follow from the button's total (count × unit). The small "x N" in the
            // title is often not read; when it is, it must agree.
            String batchButton = "batch_" + resource;
            Long first = number(batchButton + "_cost", batchButton);
            Integer title = selectedCount();
            if (first == null || !first.equals(unit) || title != null && title != 1)
                return abandonBatch("Upgrade More did not start at one wall (total " + first + ", title x" + title + ", one wall " + unit + ")");
            for (int i = 0; i < (want - 1) / 10; i++) { io.tap("batch_add10", "batch_add10"); io.pause(250); }
            for (int i = 0; i < (want - 1) % 10; i++) { io.tap("batch_add1", "batch_add1"); io.pause(250); }
            io.pause(400);
            Long total = number(batchButton + "_cost", batchButton);
            title = selectedCount();
            // Fewer walls of this level may be left than asked for; the total must still be a whole number of walls.
            int count = total == null || total % unit != 0 ? 0 : (int) (total / unit);
            if (count < 1 || count > want || title != null && title != count
                    || !Rules.affordable(balance, total, reserve, count * plan.maxWallCost)) {
                return abandonBatch("selection did not match: total " + total + ", title x" + title + ", expected up to x" + want + " at " + unit + " each");
            }
            io.tap(batchButton, "home", batchButton);
            io.status("Walls: " + count + " × " + unit + " " + resource);
            // Upgrade More asks its own question with Cancel / Okay; the single-wall dialog may follow.
            boolean answered = false;
            for (long until = io.now() + 8000; ; io.pause(250)) {
                if (!answered) {
                    boolean okay;
                    try { okay = io.okay(resource, total, balance, reserve, count * plan.maxWallCost); }
                    catch (java.io.IOException e) {
                        // Nothing was paid. Once the question is cancelled and Home is back, the walls go one at a time.
                        boolean back;
                        try (Screen f = io.capture()) { back = io.has(f, "home") && !io.has(f, "wall_confirm"); }
                        if (back) return abandonBatch(e.getMessage());
                        throw e;
                    }
                    if (okay) { answered = true; until = io.now() + 8000; continue; }
                }
                try (Screen f = io.capture()) {
                    if (io.has(f, "wall_confirm") || answered && io.has(f, "home")) break;
                }
                if (io.now() >= until) throw new IllegalStateException(answered ? "Upgrade More's question did not close after Okay." : "Timed out waiting for the wall confirmation. No payment sent.");
            }
            boolean dialog;
            try (Screen f = io.capture()) { dialog = io.has(f, "wall_confirm"); }
            if (dialog) {
                String confirm = "confirm_" + resource;
                if (!shows(confirm + "_cost", confirm, total)) throw new IllegalStateException("Final price differs from the selected walls' total. No payment sent.");
                io.pay(resource, total, balance, reserve, count * plan.maxWallCost);
                waitGone("wall_confirm", 8);
            }
            io.pause(1200); waitFor("home", 8);
            // The first selected wall is always part of the batch: it must now be one level higher.
            Integer nextLevel = upgradedLevel(point, level);
            // The deduction first: a payment that does not add up stops wall spending for the session.
            Long after = number("home_" + resource, "home");
            verifyPayment(resource, balance, after, total);
            // Clash took exactly the walls' total: they are upgraded and counted now, whatever follows.
            upgraded += count;
            io.log("walls-upgraded", count + " walls, level " + level + " → " + (nextLevel == null ? "?" : nextLevel) + ", " + total + " " + resource);
            afterUpgrade(point, level, nextLevel);
            return count;
        }
        if (unreadable) { closePanel(); skipWalls("wall price unreadable, nothing paid"); return -1; }
        return 0;
    }
    /**
     * The balance must have dropped by exactly the amount paid. An unreadable balance stops walls for this
     * visit; a wrong amount stops wall spending for the whole session.
     */
    private void verifyPayment(String resource, long before, Long after, long paid) {
        if (after == null) throw new IllegalStateException("Stored " + resource + " unreadable after paying " + paid + "; walls stop for this visit.");
        if (!Rules.payment(before, after, paid))
            throw new SpendingHalted("Resource deduction could not be verified (" + resource + " " + before + " - " + paid + " is not " + after + "). No further spending.");
    }
    /**
     * After a verified payment: an unread or unexpected level is only noted (the deduction already proved
     * the upgrade), and the panel is closed. If it does not close, the next tap simply selects another wall.
     */
    private void afterUpgrade(int[] point, int level, Integer nextLevel) throws Exception {
        if (nextLevel == null || nextLevel != level + 1) io.log("wall-level-unconfirmed", "expected level " + (level + 1) + ", read " + nextLevel);
        boolean closed = true;
        try { deselect(); }
        catch (IllegalStateException | java.io.IOException e) { io.log("deselect-failed", e.getMessage()); closePanel(); closed = false; }
        // The wall just paid for now shows the next level: a picture of it finds that level's walls
        // once the picked ones are all upgraded. Only taken from a cleanly closed panel; never fatal.
        if (!closed) return;
        try { io.pause(500); io.learnWall(point, level + 1); }
        catch (InterruptedException e) { throw e; }
        catch (Exception e) { io.log("wall-learn-failed", message(e)); }
    }
    private int abandonBatch(String reason) throws Exception {
        io.log("batch-abandoned", reason + "; one wall at a time from now on");
        io.status("Upgrade More check failed, nothing paid. Upgrading one wall at a time.");
        batchFailed = true;
        closePanel();
        return -1;
    }
    private String unaffordable(long price) {
        return price > plan.maxWallCost ? "wall price " + price + " is above your maximum wall cost"
                : "not enough gold or elixir above your reserves for a " + price + " wall";
    }
    /**
     * The resource with more to spend above its reserve pays first; the other one is tried if it cannot.
     * Spending one resource makes the other the bigger budget, so payments alternate on their own.
     */
    private List<String> resources(long gold, long elixir) {
        return gold - plan.reserveGold >= elixir - plan.reserveElixir ? List.of("gold", "elixir") : List.of("elixir", "gold");
    }
    /**
     * The paid wall's level. It is read where the wall is still selected; when Clash has deselected it or
     * not redrawn its title yet, the panel is closed and the wall selected again.
     */
    private Integer upgradedLevel(int[] point, int level) throws Exception {
        try (Screen f = io.capture()) { require(f, "home"); }
        Integer now = wallLevel(4, level + 1);
        if (now != null && now == level + 1) return now;
        closePanel();
        io.point(point, "home"); io.pause(600);
        return wallLevel(3, level + 1);
    }
    /** Upgrades the selected wall with whichever resource has more to spend and is affordable; false if neither is. */
    private boolean upgradeSelected(int[] point, int level, long gold, long elixir) throws Exception {
        List<String> resources = resources(gold, elixir);
        boolean unreadable = false;
        String why = "no gold or elixir Upgrade button recognized (recapture Selected wall)";
        for (String resource : resources) {
            String button = "wall_" + resource;
            try (Screen f = io.capture()) { if (!io.has(f, button)) continue; }
            Long price = number(button + "_cost", button);
            if (price == null) { unreadable = true; io.log("price-unreadable", resource + " wall price"); continue; }
            wallPrice = price;
            // Read now, with the wall selected (see upgradeBatch): the deduction is checked against this.
            Long fresh = number("home_" + resource, "home");
            if (fresh == null) { why = "stored " + resource + " unreadable with the wall selected"; continue; }
            long balance = fresh;
            long reserve = resource.equals("gold") ? plan.reserveGold : plan.reserveElixir;
            if (!Rules.affordable(balance, price, reserve, plan.maxWallCost)) { why = unaffordable(price); continue; }
            if (!Integer.valueOf(level).equals(wallLevel(3, level))) throw new IllegalStateException("Selected wall changed.");
            io.tap(button, "home", button);
            waitFor("wall_confirm", 6);
            String confirm = "confirm_" + resource;
            if (!shows(confirm + "_cost", confirm, price)) throw new IllegalStateException("Final price differs from the selected wall price. No payment sent.");
            io.status("Wall: " + price + " " + resource);
            io.pay(resource, price, balance, reserve, plan.maxWallCost);
            waitGone("wall_confirm", 8); io.pause(1200);
            Integer nextLevel = upgradedLevel(point, level);
            // The deduction first: a payment that does not add up stops wall spending for the session.
            Long after = number("home_" + resource, "home");
            verifyPayment(resource, balance, after, price);
            // Clash took exactly the price: the wall is upgraded and counted now, whatever follows.
            upgraded++;
            io.log("wall-upgraded", resource + " " + price + ", level " + level + " → " + (nextLevel == null ? "?" : nextLevel) + " at " + Arrays.toString(point));
            afterUpgrade(point, level, nextLevel);
            return true;
        }
        refusal = unreadable ? "wall price unreadable, nothing paid" : why;
        return false;
    }
    private void attack() throws Exception {
        status("Finding an opponent"); waitFor("home", 10); io.tap("home", "home");
        waitFor("find_match", 10); io.tap("find_match", "find_match");
        // Find a Match opens the army screen; its Attack! button starts the search.
        waitFor("start_attack", 15); io.tap("start_attack", "start_attack");
        waitFor("scout", 60);
        boolean accepted = false;
        for (int skip = 0, unreadable = 0; skip <= plan.maxSkips; skip++) {
            io.zoom("scout");
            Long gold = number("loot_gold", "scout"), elixir = number("loot_elixir", "scout");
            if (gold == null || elixir == null) {
                // OCR occasionally misreads one base; skipping it is cheap. A wrong crop fails every base.
                if (++unreadable >= 5) throw new IllegalStateException("Loot is unreadable on 5 bases in a row. Recheck the digits-only crops.");
                io.log("scout", "loot unreadable, skipping base; " + skip + " skips");
            } else {
                unreadable = 0;
                status("Loot: " + gold + " gold / " + elixir + " elixir");
                io.log("scout", gold + " gold, " + elixir + " elixir, " + skip + " skips");
                if (Rules.loot(gold, elixir, plan.minGold, plan.minElixir, plan.minCombined)) { accepted = true; break; }
            }
            if (skip == plan.maxSkips) break;
            // A fast next-base transition can finish between screenshots. Do not
            // require seeing the transient disappearance of the Next button.
            io.tap("scout", "scout"); io.pause(1000); waitFor("scout", 60);
        }
        if (!accepted) throw new IllegalStateException("Maximum skipped bases reached without enough loot.");
        // Two matching reads in a row; a single OCR miss only costs another read.
        Integer remaining = null, previous = null;
        for (int read = 0; read < 4 && remaining == null; read++) {
            if (read > 0) io.pause(300);
            Integer count;
            try (Screen f = io.capture()) { require(f, "scout"); count = Rules.troopCount(io.read(f, "dragon_count")); }
            if (count != null && count.equals(previous)) remaining = count;
            previous = count;
        }
        if (remaining == null || remaining < plan.dragons) throw new IllegalStateException("Dragon count is unreadable, changing, or below your configured army size.");
        // Remember where the siege, hero and rage cards sit while nothing is selected. When a troop
        // runs out Clash auto-selects the next card, which lifts and outlines it so it no longer
        // matches its picture; the troop bar itself does not move during the battle.
        Map<String, int[]> cards = new LinkedHashMap<>();
        try (Screen f = io.capture()) {
            require(f, "scout");
            for (String card : plan.extras) { int[] at = io.find(f, card); if (at != null) cards.put(card, at); }
            if (plan.rage) { int[] at = io.find(f, "rage"); if (at != null) cards.put("rage", at); }
        }
        io.log("army-cards", cards.isEmpty() ? "no siege, hero or rage card found" : String.join(", ", cards.keySet()));
        io.tap("dragon", "scout", "dragon"); io.pause(150);
        // Fast deployment: one tap per dragon still to place, sent back to back, then a
        // single verified count. Taps on forbidden ground place nothing, so the next burst
        // uses the following points. A burst can never place more dragons than it taps.
        int target = remaining - plan.dragons, next = 0, emptyTaps = 0;
        boolean verified = true;
        while (remaining > target) {
            List<int[]> burst = new ArrayList<>();
            for (int i = remaining - target; i > 0; i--) burst.add(plan.deployments.get(next++ % plan.deployments.size()));
            status("Deploying " + burst.size() + " dragons");
            int sent = io.burst(burst, "scout", "battle");
            io.pause(400);
            Integer left = dragonsLeft(remaining, target);
            if (left == null) {
                // A depleted card is greyed and its "x0" is often unreadable. Never tap more
                // dragons without a verified count, but let the battle and the heroes go on.
                io.log("deploy-unverified", "dragon count unreadable after " + sent + " taps; no further dragon taps");
                verified = false;
                break;
            }
            if (left < 0) break; // battle already over
            io.log("deploy-burst", sent + " taps, " + (remaining - left) + " placed, " + (left - target) + " to go");
            // Give up only once every marked point was tapped in a row without placing a dragon.
            emptyTaps = left < remaining ? 0 : emptyTaps + burst.size();
            remaining = left;
            if (remaining > target && emptyTaps >= plan.deployments.size()) throw new IllegalStateException("No marked deployment point placed a dragon. Recalibrate outer-edge points.");
        }
        io.log("deployed", verified ? plan.dragons - (remaining - target) + " dragons" : "dragons sent, last count unreadable (greyed card)");
        // Siege machine, then heroes: select the card at its recorded spot, tap the next point
        // along the same edge. A deployed hero's card turns into its ability button there.
        Map<String, int[]> abilities = new LinkedHashMap<>();
        for (String card : plan.extras) {
            int[] at = cards.get(card);
            if (at == null) { io.log("extra-skipped", card + " not in this army"); continue; }
            try (Screen f = io.capture()) { if (io.has(f, "result")) break; }
            status("Deploying " + card.replace('_', ' '));
            // A selected card hides the battle button's match. Cards still waiting in the
            // troop bar prove the battle screen just as well.
            List<String> guards = new ArrayList<>(List.of("battle", "scout"));
            for (String other : cards.keySet()) if (!other.equals(card) && !abilities.containsKey(other)) guards.add(other);
            String[] guard = guards.toArray(new String[0]);
            try {
                io.burst(List.of(at), guard); io.pause(150);
                io.burst(List.of(plan.deployments.get(next++ % plan.deployments.size())), guard);
            } catch (java.io.IOException | IllegalStateException e) {
                // One card failing must not abandon an attack already under way.
                io.log("extra-skipped", card + ": " + e.getMessage()); continue;
            }
            io.pause(250);
            io.log("deployed", card);
            if (card.startsWith("hero_")) abilities.put(card, at);
        }
        status("Waiting for battle results");
        long until = io.now() + 240_000, abilityAt = io.now() + plan.abilitySeconds * 1000L, rageAt = io.now() + plan.rageSeconds * 1000L;
        boolean rage = plan.rage && !plan.ragePoints.isEmpty();
        while (true) {
            try (Screen f = io.capture()) { if (io.has(f, "result")) break; }
            if (rage && io.now() >= rageAt) { castRage(cards.get("rage")); rage = false; }
            if (!abilities.isEmpty() && io.now() >= abilityAt) { useAbilities(abilities); abilities.clear(); }
            if (io.now() >= until) throw new IllegalStateException("Timed out waiting for result. Check the game or recapture that button.");
            io.pause(250);
        }
        io.tap("result", "result"); waitFor("home", 30); io.zoom("home");
        attacks++; io.log("attack-complete", Integer.toString(attacks));
    }
    /** Selects the rage card and drops one spell on each marked point, in front of the army. */
    private void castRage(int[] card) throws Exception {
        if (card == null) { io.log("rage-skipped", "rage card not in this army"); return; }
        status("Rage spells");
        try {
            io.burst(List.of(card), "battle"); io.pause(150);
            io.log("rage", io.burst(plan.ragePoints, "battle") + " spells dropped");
        } catch (java.io.IOException | IllegalStateException e) { io.log("rage-skipped", e.getMessage()); }
    }
    /** Taps each deployed hero's card again. A failed check is retried; one hero failing never skips the others. */
    private void useAbilities(Map<String, int[]> heroes) throws Exception {
        status("Hero abilities");
        for (Map.Entry<String, int[]> hero : heroes.entrySet()) {
            for (int attempt = 0; ; attempt++) {
                try { io.point(hero.getValue(), "battle"); io.log("ability", hero.getKey()); io.pause(150); break; }
                catch (java.io.IOException | IllegalStateException e) {
                    try (Screen f = io.capture()) { if (io.has(f, "result")) return; } // battle over
                    // Troops or spell effects behind the battle button can spoil one frame's match.
                    if (attempt == 2) { io.log("ability-skipped", hero.getKey() + ": " + e.getMessage()); break; }
                    io.pause(300);
                }
            }
        }
    }
    /** Dragons still in the card, from two agreeing reads within [target, remaining]; -1 once the battle result shows; null if unreadable. */
    private Integer dragonsLeft(int remaining, int target) throws Exception {
        Integer previous = null;
        int unknown = 0;
        for (int read = 0; read < 5; read++) {
            if (read > 0) io.pause(200);
            Integer n;
            try (Screen f = io.capture()) {
                if (io.has(f, "result")) return -1;
                // Dragons flying over the battle button can spoil one frame's match; only a screen
                // that stays unknown for every read ends the attack.
                if (!io.has(f, "scout") && !io.has(f, "battle")) {
                    if (++unknown == 5) throw new IllegalStateException("Unknown screen during deployment.");
                    previous = null; continue;
                }
                n = Rules.troopCount(io.read(f, "dragon_count"));
            }
            if (n != null && (n < target || n > remaining)) n = null;
            if (n != null && n.equals(previous)) return n;
            previous = n;
        }
        return null;
    }
    /** Attacks in a row that may fail, each followed by a recovery to Home, before a session gives up. */
    static final int FAILURES_IN_A_ROW = 5;
    public String run() throws Exception {
        long end = io.now() + plan.maxMinutes * 60_000L;
        int failures = 0;
        try {
            while (attacks < plan.maxAttacks && io.now() < end) {
                try { attack(); failures = 0; }
                catch (InterruptedException e) { throw e; }
                catch (Exception e) {
                    // An unexpected screen during one attack must not end a night of farming: get back
                    // to Home and go on. Only a problem that keeps coming back ends the session.
                    if (++failures >= FAILURES_IN_A_ROW) throw new IllegalStateException(failures + " attacks in a row failed. Last: " + message(e), e);
                    io.log("attack-failed", message(e) + " (" + failures + " in a row); getting back to Home");
                    try (Screen f = io.capture()) { io.save(f, "attack-failed-" + failures + ".png"); }
                    catch (InterruptedException x) { throw x; } catch (Exception ignored) {}
                    if (!recover(message(e))) continue;
                }
                if (!wallsOff) visitWalls();
                status(attacks + " attacks / " + upgraded + " walls");
            }
        } catch (Exception e) {
            // STOP or an error: what was done so far still goes into the log for Read last result.
            io.log("session-summary", summary() + " Ended by: " + e.getMessage());
            throw e;
        }
        return summary();
    }
    /**
     * Only the wall part, from Home, without an attack first: a quick way to test wall upgrading.
     * The same visit as after a battle (zoom, builders, resources, walls), once.
     */
    public String wallsOnly() throws Exception {
        if (!plan.wallsEnabled) return "Walls are switched off in the settings.";
        try {
            status("Walls only: zooming out"); waitFor("home", 10); io.zoom("home");
            visitWalls();
        } catch (Exception e) {
            io.log("session-summary", "walls only: " + upgraded + " verified wall upgrades. Ended by: " + e.getMessage());
            throw e;
        }
        return "Walls only: " + upgraded + " verified wall upgrades." + (wallNote == null ? "" : " " + wallNote + ".");
    }
    /** One walls visit. Whatever goes wrong in it ends the visit, never the session (STOP and time limits aside). */
    private void visitWalls() throws Exception {
        try { walls(); }
        catch (InterruptedException e) { throw e; }
        catch (Exception e) {
            try { wallsStopped(e); }
            catch (InterruptedException again) { throw again; }
            catch (Exception again) { recover("walls: " + message(again)); }
        }
    }
    /**
     * Back to Home without spending anything, after a failed attack or wall step. A battle is waited out
     * (its result's Return Home tapped), a popup or question is closed with Back or its Reload / Try Again
     * / Close button, and Clash is brought back to the front. Okay, prices and confirm buttons are never
     * tapped here. True once Home is back (zoomed out like after a battle); false after 8 minutes.
     */
    private boolean recover(String why) throws Exception {
        status("Recovering: " + why);
        long until = io.now() + 8 * 60_000L;
        for (int unknown = 0; io.now() < until; io.pause(2500)) {
            try {
                io.restore();
                boolean home, result, fighting;
                try (Screen f = io.capture()) {
                    home = io.has(f, "home");
                    result = !home && io.has(f, "result");
                    fighting = !home && !result && (io.has(f, "battle") || io.has(f, "scout"));
                }
                if (home) {
                    io.zoom("home");
                    io.log("recovered", "back on Home after: " + why); status("Back on Home");
                    return true;
                }
                if (result) { io.tap("result", "result"); continue; }
                if (fighting) { status("Recovering: waiting for the battle to end"); unknown = 0; continue; }
                // A popup, a question, a menu or a lost connection: Back closes most; Reload needs its button.
                if (++unknown % 2 == 0 && !io.tapWord("Reload", "Try Again", "Return Home", "Close")) io.back();
            }
            catch (InterruptedException e) { throw e; }
            catch (Exception e) { io.log("recover-step", message(e)); }
        }
        io.log("recover-failed", "Home did not come back within 8 minutes after: " + why);
        return false;
    }
    private static String message(Exception e) { return e.getMessage() == null ? e.toString() : e.getMessage(); }
    private String summary() {
        return attacks + " attacks and " + upgraded + " verified wall upgrades."
                + (wallSkips == 0 ? "" : " Walls skipped " + wallSkips + "× (last: " + wallSkipReason + ").");
    }
}
