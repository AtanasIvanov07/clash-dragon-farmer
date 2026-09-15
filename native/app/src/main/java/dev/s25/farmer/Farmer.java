package dev.s25.farmer;

import java.util.*;

/** Farming state machine. Tests use the same class with a simulated screen port. */
public final class Farmer {
    public interface Screen extends AutoCloseable { @Override void close(); }
    public interface Port {
        long now();
        Screen capture() throws Exception;
        boolean has(Screen screen, String key) throws Exception;
        String read(Screen screen, String key) throws Exception;
        Integer wall(Screen screen) throws Exception;
        void tap(String key, String... guards) throws Exception;
        long pay(String resource, long price, long reserve, long cap) throws Exception;
        void point(int[] point, String guard) throws Exception;
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
        String a, b;
        try (Screen f = io.capture()) { require(f, guard); a = io.read(f, region); }
        io.pause(300);
        try (Screen f = io.capture()) { require(f, guard); b = io.read(f, region); }
        return Rules.stable(a, b);
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
        waitFor("scout", 60);
        boolean accepted = false;
        for (int skip = 0; skip <= plan.maxSkips; skip++) {
            io.zoom("scout");
            Long gold = number("loot_gold", "scout"), elixir = number("loot_elixir", "scout");
            if (gold == null || elixir == null) throw new IllegalStateException("Loot is unreadable. Recheck the digits-only crops.");
            io.status("Loot: " + gold + " gold / " + elixir + " elixir");
            io.log("scout", gold + " gold, " + elixir + " elixir, " + skip + " skips");
            if (Rules.loot(gold, elixir, plan.minGold, plan.minElixir, plan.minCombined)) { accepted = true; break; }
            if (skip == plan.maxSkips) break;
            // A fast next-base transition can finish between screenshots. Do not
            // require seeing the transient disappearance of the Next button.
            io.tap("scout", "scout"); io.pause(1000); waitFor("scout", 60);
        }
        if (!accepted) throw new IllegalStateException("Maximum skipped bases reached without enough loot.");
        Integer remaining, checkCount;
        try (Screen f = io.capture()) { require(f, "scout"); remaining = Rules.troopCount(io.read(f, "dragon_count")); }
        io.pause(300);
        try (Screen f = io.capture()) { require(f, "scout"); checkCount = Rules.troopCount(io.read(f, "dragon_count")); }
        if (remaining == null || !remaining.equals(checkCount) || remaining < plan.dragons) throw new IllegalStateException("Dragon count is unreadable, changing, or below your configured army size.");
        io.tap("dragon", "scout", "dragon"); io.pause(200);
        deployment:
        for (int i = 0; i < plan.dragons; i++) {
            boolean deployed = false;
            for (int attempt = 0; attempt < plan.deployments.size(); attempt++) {
                String guard;
                try (Screen f = io.capture()) {
                    if (io.has(f, "result")) break deployment;
                    guard = io.has(f, "scout") ? "scout" : "battle"; require(f, guard);
                    if (!remaining.equals(Rules.troopCount(io.read(f, "dragon_count")))) throw new IllegalStateException("Army changed before deployment.");
                }
                io.point(plan.deployments.get((i + attempt) % plan.deployments.size()), guard);
                // A tap on forbidden ground consumes no troop; retry another point.
                for (int poll = 0; poll < 3; poll++) {
                    io.pause(300);
                    try (Screen f = io.capture()) {
                        if (io.has(f, "result")) break deployment;
                        if (!io.has(f, "scout") && !io.has(f, "battle")) throw new IllegalStateException("Unknown screen during deployment.");
                        String rawCount = io.read(f, "dragon_count");
                        Integer after = Rules.troopCount(rawCount);
                        if (after == null) throw new IllegalStateException("Cannot verify remaining dragons (read: " + rawCount + "). No further deployment taps.");
                        if (after == remaining - 1) { remaining = after; deployed = true; break; }
                        if (!after.equals(remaining)) throw new IllegalStateException("Unexpected troop count change.");
                    }
                }
                if (deployed) break;
            }
            if (!deployed) throw new IllegalStateException("No marked deployment point consumed a dragon. Recalibrate outer-edge points.");
        }
        io.status("Waiting for battle results"); waitFor("result", 240);
        io.tap("result", "result"); waitFor("home", 30); io.zoom("home");
        attacks++; io.log("attack-complete", Integer.toString(attacks));
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
