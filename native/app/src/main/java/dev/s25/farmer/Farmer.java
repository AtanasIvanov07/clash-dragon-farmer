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
        void tap(String key, String... guards) throws Exception;
        long pay(String resource, long price, long reserve, long cap) throws Exception;
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
    private int attacks, upgraded, wallIndex;
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
        List<Long> seen = new ArrayList<>();
        for (int read = 0; read < 3; read++) {
            if (read > 0) io.pause(300);
            Long n;
            try (Screen f = io.capture()) { require(f, guard); n = Rules.number(io.read(f, region)); }
            if (n != null && seen.contains(n)) return n;
            if (n != null) seen.add(n);
        }
        return null;
    }
    private void deselect() throws Exception {
        try (Screen f = io.capture()) {
            require(f, "home");
            if (io.wall(f) == null) throw new IllegalStateException("Selected-wall title disappeared before deselecting.");
        }
        io.point(plan.blank, "home"); io.pause(500); waitFor("home", 8);
        try (Screen f = io.capture()) {
            if (io.wall(f) != null) throw new IllegalStateException("Empty-ground point did not close the wall panel. Recalibrate it.");
        }
    }
    private void walls() throws Exception {
        if (!plan.wallsEnabled) return;
        io.status("Checking walls");
        for (int visited = 0, paidThisVisit = 0; visited < plan.walls.size() && paidThisVisit < plan.wallsPerVisit; visited++) {
            waitFor("home", 8);
            Long gold = number("home_gold", "home"), elixir = number("home_elixir", "home");
            if (gold == null || elixir == null) throw new IllegalStateException("Stored resource amounts are unreadable.");
            int[] point = plan.walls.get(wallIndex++ % plan.walls.size());
            // The Attack button stays fixed even when the village camera moves.
            io.point(point, "home_anchor"); io.pause(600);
            Integer level;
            try (Screen f = io.capture()) { level = io.wall(f); }
            if (level == null) throw new IllegalStateException("Wall position selected something else. Recalibrate the village view.");
            if (plan.targetWallLevel > 0 && level >= plan.targetWallLevel) { deselect(); continue; }
            List<String> resources = preferred.equals("gold") ? List.of("gold", "elixir") : List.of("elixir", "gold");
            boolean paid = false;
            for (String resource : resources) {
                String button = "wall_" + resource;
                try (Screen f = io.capture()) { if (!io.has(f, button)) continue; }
                Long price = number(button + "_cost", button);
                if (price == null) throw new IllegalStateException("Unreadable " + resource + " wall price.");
                Long balance = resource.equals("gold") ? gold : elixir;
                long reserve = resource.equals("gold") ? plan.reserveGold : plan.reserveElixir;
                if (!Rules.affordable(balance, price, reserve, plan.maxWallCost)) continue;
                try (Screen f = io.capture()) {
                    if (!level.equals(io.wall(f))) throw new IllegalStateException("Selected wall changed.");
                }
                io.tap(button, "home", button);
                waitFor("wall_confirm", 6);
                String confirm = "confirm_" + resource;
                Long finalPrice = number(confirm + "_cost", confirm);
                if (!price.equals(finalPrice)) throw new IllegalStateException("Final price differs from the selected wall price. No payment sent.");
                io.status("Wall: " + price + " " + resource);
                balance = io.pay(resource, price, reserve, plan.maxWallCost);
                waitGone("wall_confirm", 8); io.pause(1200);
                Integer nextLevel;
                try (Screen f = io.capture()) { require(f, "home"); nextLevel = io.wall(f); }
                if (nextLevel == null) {
                    // Some game flows deselect after an upgrade. Re-select the same
                    // calibrated wall and verify its new level before counting it.
                    io.point(point, "home_anchor"); io.pause(600);
                    try (Screen f = io.capture()) { nextLevel = io.wall(f); }
                }
                if (nextLevel == null || nextLevel != level + 1) throw new IllegalStateException("Wall level did not advance as expected.");
                deselect();
                Long after = number("home_" + resource, "home");
                if (!Rules.payment(balance, after, price)) throw new IllegalStateException("Resource deduction could not be verified. No further spending.");
                preferred = resource.equals("gold") ? "elixir" : "gold";
                upgraded++; paidThisVisit++; paid = true;
                io.log("wall-upgraded", resource + " " + price + " at " + Arrays.toString(point));
                break;
            }
            if (!paid) deselect();
        }
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
        io.tap("dragon", "scout", "dragon"); io.pause(150);
        // Fast deployment: one tap per dragon still to place, sent back to back, then a
        // single verified count. Taps on forbidden ground place nothing, so the next burst
        // uses the following points. A burst can never place more dragons than it taps.
        int target = remaining - plan.dragons, next = 0, emptyTaps = 0;
        while (remaining > target) {
            List<int[]> burst = new ArrayList<>();
            for (int i = remaining - target; i > 0; i--) burst.add(plan.deployments.get(next++ % plan.deployments.size()));
            io.status("Deploying " + burst.size() + " dragons");
            int sent = io.burst(burst, "scout", "battle");
            io.pause(400);
            Integer left = dragonsLeft(remaining, target);
            if (left == null) throw new IllegalStateException("Cannot verify remaining dragons after deploying. No further deployment taps.");
            if (left < 0) break; // battle already over
            io.log("deploy-burst", sent + " taps, " + (remaining - left) + " placed, " + (left - target) + " to go");
            // Give up only once every marked point was tapped in a row without placing a dragon.
            emptyTaps = left < remaining ? 0 : emptyTaps + burst.size();
            remaining = left;
            if (remaining > target && emptyTaps >= plan.deployments.size()) throw new IllegalStateException("No marked deployment point placed a dragon. Recalibrate outer-edge points.");
        }
        io.log("deployed", plan.dragons - (remaining - target) + " dragons");
        // Siege machine, then heroes: select the card, tap the next point along the same edge.
        // A deployed hero's card turns into its ability button at the same spot.
        Map<String, int[]> abilities = new LinkedHashMap<>();
        for (String card : plan.extras) {
            int[] at;
            try (Screen f = io.capture()) {
                if (io.has(f, "result")) break;
                at = io.find(f, card);
            }
            if (at == null) { io.log("extra-skipped", card + " not in this army"); continue; }
            io.status("Deploying " + card.replace('_', ' '));
            io.tap(card, card); io.pause(150);
            io.burst(List.of(plan.deployments.get(next++ % plan.deployments.size())), "battle", "scout");
            io.pause(250);
            io.log("deployed", card);
            if (card.startsWith("hero_")) abilities.put(card, at);
        }
        io.status("Waiting for battle results");
        long until = io.now() + 240_000, abilityAt = io.now() + plan.abilitySeconds * 1000L;
        while (true) {
            try (Screen f = io.capture()) { if (io.has(f, "result")) break; }
            if (!abilities.isEmpty() && io.now() >= abilityAt) { useAbilities(abilities); abilities.clear(); }
            if (io.now() >= until) throw new IllegalStateException("Timed out waiting for result. Check the game or recapture that button.");
            io.pause(250);
        }
        io.tap("result", "result"); waitFor("home", 30); io.zoom("home");
        attacks++; io.log("attack-complete", Integer.toString(attacks));
    }
    /** Taps each deployed hero's card again. A hero that died or a battle that just ended only skips the rest. */
    private void useAbilities(Map<String, int[]> heroes) throws Exception {
        io.status("Hero abilities");
        for (Map.Entry<String, int[]> hero : heroes.entrySet()) {
            try { io.point(hero.getValue(), "battle"); io.log("ability", hero.getKey()); io.pause(150); }
            catch (java.io.IOException | IllegalStateException e) { io.log("ability-skipped", hero.getKey() + ": " + e.getMessage()); return; }
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
        return attacks + " attacks and " + upgraded + " verified wall upgrades.";
    }
}
