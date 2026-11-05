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
         * the reserve; throws without tapping when it names anything else.
         */
        boolean okay(String resource, long total, long balance, long reserve, long cap) throws Exception;
        void point(int[] point, String guard) throws Exception;
        /** Checks one guard is visible, then taps every point back to back. Covered points are skipped. Returns taps sent. */
        int burst(List<int[]> points, String... guards) throws Exception;
        void pause(long millis) throws Exception;
        void zoom(String guard) throws Exception;
        void status(String text);
        void log(String event, String detail);
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
    /** Lowest wall level selected so far this session, and the last wall price read. */
    private Integer lowestWall;
    private long wallPrice;
    private String preferred = "gold";
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
        for (int read = 0, reads = region.startsWith("home_") ? 10 : 3; read < reads; read++) {
            if (read > 0) io.pause(300);
            Long n;
            try (Screen f = io.capture()) { require(f, guard); n = Rules.number(io.read(f, region)); }
            if (n != null && seen.contains(n)) return n;
            if (n != null) seen.add(n);
        }
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
            if (io.wall(f) == null) throw new IllegalStateException("Selected-wall title disappeared before deselecting.");
        }
        closePanel();
        try (Screen f = io.capture()) {
            if (io.wall(f) != null) throw new IllegalStateException("Empty-ground point did not close the wall panel. Recalibrate it.");
        }
    }
    private void closePanel() throws Exception { io.point(plan.blank, "home"); io.pause(500); waitFor("home", 8); }
    private void skipWalls(String reason) {
        wallSkips++; wallSkipReason = reason;
        io.log("walls-skipped", reason); io.status("Walls skipped: " + reason);
    }
    private Integer freeBuilders() throws Exception {
        List<Integer> seen = new ArrayList<>();
        for (int read = 0; read < 3; read++) {
            if (read > 0) io.pause(300);
            Integer n;
            try (Screen f = io.capture()) { require(f, "home"); n = Rules.freeBuilders(io.read(f, "home_builders")); }
            if (n != null && seen.contains(n)) return n;
            if (n != null) seen.add(n);
        }
        return null;
    }
    private void walls() throws Exception {
        if (!plan.wallsEnabled) return;
        io.status("Checking walls");
        waitFor("home", 8);
        // Walls need a free builder; otherwise Clash offers to finish an upgrade with gems.
        if (plan.checkBuilders) {
            Integer free = freeBuilders();
            if (free == null) { skipWalls("builder counter unreadable"); return; }
            if (free == 0) { skipWalls("no free builder"); return; }
        }
        io.pause(600); // let Clash's zoom animation settle
        // Walls are found by how they look (like the ones picked in calibration), so the camera
        // position does not matter. An upgraded wall looks different and drops out of the search.
        for (int paidThisVisit = 0; paidThisVisit < plan.wallsPerVisit; ) {
            waitFor("home", 8);
            Long gold = number("home_gold", "home"), elixir = number("home_elixir", "home");
            if (gold == null || elixir == null) { skipWalls("stored resources unreadable"); return; }
            // Every wall of one level costs the same: below that price on both resources, this visit is done.
            if (wallPrice > 0 && !Rules.affordable(gold, wallPrice, plan.reserveGold, plan.maxWallCost)
                    && !Rules.affordable(elixir, wallPrice, plan.reserveElixir, plan.maxWallCost)) { io.log("walls-done", "gold and elixir are at their reserves"); return; }
            List<int[]> found;
            try (Screen f = io.capture()) { require(f, "home"); found = io.walls(f); }
            int[] point = null; Integer level = null;
            for (int[] candidate : found.subList(0, Math.min(5, found.size()))) {
                io.point(candidate, "home"); io.pause(600);
                Integer seen;
                try (Screen f = io.capture()) { seen = io.wall(f); }
                if (seen != null && plan.targetWallLevel > 0 && seen >= plan.targetWallLevel) {
                    deselect(); io.log("walls-done", "walls reached the target level " + plan.targetWallLevel); return;
                }
                if (seen != null && (lowestWall == null || seen <= lowestWall)) { point = candidate; level = seen; break; }
                io.log("wall-miss", Arrays.toString(candidate) + (seen == null ? " is not a wall" : " is wall level " + seen));
                closePanel();
            }
            if (point == null) {
                if (paidThisVisit == 0) skipWalls(found.isEmpty() ? "no wall like the picked ones on screen" : "no wall of the picked level could be selected");
                else io.log("walls-done", "no more walls of level " + lowestWall + " visible");
                return;
            }
            lowestWall = level; // stay on the lowest level; higher walls wait until it is finished
            int paid = plan.batch ? upgradeBatch(point, level, gold, elixir, plan.wallsPerVisit - paidThisVisit)
                    : upgradeSelected(point, level, gold, elixir) ? 1 : 0;
            if (paid < 0) return; // batch check failed: panel closed, nothing paid
            if (paid == 0) { deselect(); return; }
            paidThisVisit += paid;
        }
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
        List<String> resources = preferred.equals("gold") ? List.of("gold", "elixir") : List.of("elixir", "gold");
        boolean unreadable = false;
        for (String resource : resources) {
            String button = "wall_" + resource;
            try (Screen f = io.capture()) { if (!io.has(f, button)) continue; }
            Long unit = number(button + "_cost", button);
            // Nothing is open yet but the wall panel: the other resource may still be readable.
            if (unit == null) { unreadable = true; io.log("price-unreadable", resource + " wall price"); continue; }
            wallPrice = unit;
            long balance = resource.equals("gold") ? gold : elixir;
            long reserve = resource.equals("gold") ? plan.reserveGold : plan.reserveElixir;
            if (!Rules.affordable(balance, unit, reserve, plan.maxWallCost)) continue;
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
                if (!answered && io.okay(resource, total, balance, reserve, count * plan.maxWallCost)) { answered = true; until = io.now() + 8000; continue; }
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
            closePanel();
            io.point(point, "home"); io.pause(600);
            Integer nextLevel;
            try (Screen f = io.capture()) { nextLevel = io.wall(f); }
            if (nextLevel == null || nextLevel != level + 1) throw new IllegalStateException("Wall level did not advance as expected.");
            deselect();
            Long after = number("home_" + resource, "home");
            if (!Rules.payment(balance, after, total)) throw new IllegalStateException("Resource deduction could not be verified. No further spending.");
            preferred = resource.equals("gold") ? "elixir" : "gold";
            upgraded += count;
            io.log("walls-upgraded", count + " walls, level " + level + " → " + nextLevel + ", " + total + " " + resource);
            return count;
        }
        if (unreadable) { closePanel(); skipWalls("wall price unreadable, nothing paid"); return -1; }
        return 0;
    }
    private int abandonBatch(String reason) throws Exception {
        io.log("batch-abandoned", reason);
        closePanel();
        skipWalls("Upgrade More check failed, nothing paid");
        return -1;
    }
    /** Upgrades the selected wall with whichever resource is preferred and affordable; false if neither is. */
    private boolean upgradeSelected(int[] point, int level, long gold, long elixir) throws Exception {
        List<String> resources = preferred.equals("gold") ? List.of("gold", "elixir") : List.of("elixir", "gold");
        boolean unreadable = false;
        for (String resource : resources) {
            String button = "wall_" + resource;
            try (Screen f = io.capture()) { if (!io.has(f, button)) continue; }
            Long price = number(button + "_cost", button);
            if (price == null) { unreadable = true; io.log("price-unreadable", resource + " wall price"); continue; }
            wallPrice = price;
            long balance = resource.equals("gold") ? gold : elixir;
            long reserve = resource.equals("gold") ? plan.reserveGold : plan.reserveElixir;
            if (!Rules.affordable(balance, price, reserve, plan.maxWallCost)) continue;
            try (Screen f = io.capture()) {
                if (!Integer.valueOf(level).equals(io.wall(f))) throw new IllegalStateException("Selected wall changed.");
            }
            io.tap(button, "home", button);
            waitFor("wall_confirm", 6);
            String confirm = "confirm_" + resource;
            if (!shows(confirm + "_cost", confirm, price)) throw new IllegalStateException("Final price differs from the selected wall price. No payment sent.");
            io.status("Wall: " + price + " " + resource);
            io.pay(resource, price, balance, reserve, plan.maxWallCost);
            waitGone("wall_confirm", 8); io.pause(1200);
            Integer nextLevel;
            try (Screen f = io.capture()) { require(f, "home"); nextLevel = io.wall(f); }
            if (nextLevel == null) {
                // Some game flows deselect after an upgrade. Re-select the same
                // wall and verify its new level before counting it.
                io.point(point, "home"); io.pause(600);
                try (Screen f = io.capture()) { nextLevel = io.wall(f); }
            }
            if (nextLevel == null || nextLevel != level + 1) throw new IllegalStateException("Wall level did not advance as expected.");
            deselect();
            Long after = number("home_" + resource, "home");
            if (!Rules.payment(balance, after, price)) throw new IllegalStateException("Resource deduction could not be verified. No further spending.");
            preferred = resource.equals("gold") ? "elixir" : "gold";
            upgraded++;
            io.log("wall-upgraded", resource + " " + price + ", level " + level + " → " + nextLevel + " at " + Arrays.toString(point));
            return true;
        }
        if (unreadable) skipWalls("wall price unreadable, nothing paid");
        return false;
    }
    private void attack() throws Exception {
        io.status("Finding an opponent"); waitFor("home", 10); io.tap("home", "home");
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
                io.status("Loot: " + gold + " gold / " + elixir + " elixir");
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
            io.status("Deploying " + burst.size() + " dragons");
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
            io.status("Deploying " + card.replace('_', ' '));
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
        io.status("Waiting for battle results");
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
        io.status("Rage spells");
        try {
            io.burst(List.of(card), "battle"); io.pause(150);
            io.log("rage", io.burst(plan.ragePoints, "battle") + " spells dropped");
        } catch (java.io.IOException | IllegalStateException e) { io.log("rage-skipped", e.getMessage()); }
    }
    /** Taps each deployed hero's card again. A failed check is retried; one hero failing never skips the others. */
    private void useAbilities(Map<String, int[]> heroes) throws Exception {
        io.status("Hero abilities");
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
        for (int read = 0; read < 5; read++) {
            if (read > 0) io.pause(200);
            Integer n;
            try (Screen f = io.capture()) {
                if (io.has(f, "result")) return -1;
                if (!io.has(f, "scout") && !io.has(f, "battle")) throw new IllegalStateException("Unknown screen during deployment.");
                n = Rules.troopCount(io.read(f, "dragon_count"));
            }
            if (n != null && (n < target || n > remaining)) n = null;
            if (n != null && n.equals(previous)) return n;
            previous = n;
        }
        return null;
    }
    public String run() throws Exception {
        long end = io.now() + plan.maxMinutes * 60_000L;
        while (attacks < plan.maxAttacks && io.now() < end) {
            attack(); walls();
            io.status(attacks + " attacks / " + upgraded + " walls");
        }
        return attacks + " attacks and " + upgraded + " verified wall upgrades."
                + (wallSkips == 0 ? "" : " Walls skipped " + wallSkips + "× (last: " + wallSkipReason + ").");
    }
}
