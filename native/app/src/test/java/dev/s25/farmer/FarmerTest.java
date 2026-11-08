package dev.s25.farmer;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class FarmerTest {
    private static final class Screen implements Farmer.Screen {
        String state; Screen(String state) { this.state = state; }
        @Override public void close() {}
    }
    private static final class Sim implements Farmer.Port {
        Farmer.Plan p = new Farmer.Plan();
        long time, transitionTime; String state = "home", pending, resource;
        int dragons, skips, poorBases, wallIndex, totalDeployed;
        long gold = 10_000_000, elixir = 10_000_000;
        int[] levels = {17, 17};
        boolean badLoot, wrongBuilding, changedPrice, badDeduction, unknownMenu, wrongDeselect;
        boolean fastSkip, shiftedVillage, hiddenLevel, autoDeselect;
        /** A non-wall spot is found first; tapping it collects this much elixir, like a full collector. */
        boolean strayCandidate; long collectOnMiss;
        /** Every other read of the wall title fails, like the small title on the Poco. */
        boolean flakyTitle; int titleReads;
        int rejectedDeployments, unreadableBases, blockedX = -1, missedCountReads, bursts;
        String selected;
        long battleLength, battleEnd, heroLanded;
        boolean greyEmptyCard, rageInArmy = true, rageCast;
        int[] pan = {0, 0};
        String builders = "1/7";
        List<Long> rageDrops = new ArrayList<>();
        long lastDeployed;
        int battleGuardMisses;
        boolean endlessWalls, wrongBatchTotal, sale;
        /** Both walls look like the picked ones whatever their level (the next level is similar). */
        boolean lookAlike, noUpgradeButtons;
        /** Upgrade More asks "upgrade the selected Walls for N?" (Cancel / Okay); with okayThenConfirm the wall dialog follows. */
        boolean question, okayThenConfirm, wrongQuestion, titleUnreadable; int okays;
        /** The misread question has no readable Cancel either and stays open. */
        boolean questionStuck;
        Set<String> unreadablePrices = new HashSet<>();
        List<String> cardTaps = new ArrayList<>();
        boolean autoSelect; String lifted;
        int batchCount, wallsLeft = 1000;
        static long unitPrice(String resource) { return resource.equals("gold") ? 2_000_000 : 3_000_000; }
        @Override public Integer wallCount(Farmer.Screen f) { return ((Screen) f).state.equals("batch") && !titleUnreadable ? batchCount : null; }
        List<String> abilities = new ArrayList<>();
        List<String> extrasDeployed = new ArrayList<>(), order = new ArrayList<>(), armyExtras = new ArrayList<>(List.of("siege", "hero_1", "hero_2"));
        boolean insufficientArmy, rejectSecond, secondRejected;
        List<String> taps = new ArrayList<>();
        Sim() {
            p.maxAttacks = 1; p.dragons = 2; p.deployments = List.of(new int[]{10,300},new int[]{500,300});
            p.walls = List.of(new int[]{100,100},new int[]{200,100}); p.blank = new int[]{400,400};
        }
        void move(String next) { state = "transition"; pending = next; transitionTime = time + 300; }
        @Override public long now() { return time; }
        @Override public void pause(long ms) { time += ms; }
        @Override public Farmer.Screen capture() {
            if (pending != null && time >= transitionTime) { state = pending; pending = null; }
            if (battleEnd > 0 && time >= battleEnd && state.equals("battle")) { state = "result"; battleEnd = 0; }
            return new Screen(state);
        }
        // Card spots in the troop bar: x 600 + index (rage at 599), y 900.
        @Override public int[] find(Farmer.Screen f, String k) { return has(f, k) ? new int[] {600 + Stages.EXTRA_CARDS.indexOf(k), 900} : null; }
        static String cardAt(int[] p) { return p[0] == 599 ? "rage" : Stages.EXTRA_CARDS.get(p[0] - 600); }
        @Override public List<int[]> walls(Farmer.Screen f) {
            // Only walls still at the picked level look like the picked ones.
            List<int[]> out = new ArrayList<>();
            if (shiftedVillage || wallsLeft <= 0) return out;
            if (strayCandidate) out.add(new int[] {300, 100});
            if (endlessWalls) levels = new int[] {17, 17}; // plenty more walls at the picked level
            if (levels[0] == 17 || wrongBuilding || lookAlike) out.add(new int[] {100, 100});
            if (levels[1] == 17 || wrongBuilding || lookAlike) out.add(new int[] {200, 100});
            return out;
        }
        @Override public boolean has(Farmer.Screen f, String k) {
            String s = ((Screen) f).state;
            return switch(k) {
                case "home" -> s.equals("home") || s.equals("selected") || s.equals("batch") || s.equals("selected-other");
                case "wall_more" -> s.equals("selected");
                case "batch_add1", "batch_add10", "batch_gold", "batch_elixir" -> s.equals("batch");
                case "home_anchor" -> !shiftedVillage && (s.equals("home") || s.equals("selected"));
                case "find_match" -> s.equals("menu");
                case "start_attack" -> s.equals("army");
                case "scout", "dragon" -> s.equals("scout");
                case "siege", "hero_1", "hero_2" -> armyExtras.contains(k) && !extrasDeployed.contains(k) && !k.equals(lifted) && (s.equals("scout") || s.equals("battle"));
                case "rage" -> rageInArmy && !rageCast && (s.equals("scout") || s.equals("battle"));
                case "battle" -> s.equals("battle");
                case "result" -> s.equals("result");
                case "wall_gold", "wall_elixir" -> s.equals("selected") && !noUpgradeButtons;
                case "wall_confirm" -> s.equals("confirm");
                case "confirm_gold", "confirm_elixir" -> s.equals("confirm") && k.equals("confirm_" + resource);
                default -> false;
            };
        }
        @Override public void tap(String k, String... guards) {
            try (Farmer.Screen f = capture()) { for (String g : guards) if (!has(f,g)) throw new IllegalStateException("Missing guard " + g); }
            taps.add(k);
            if (k.equals("dragon") || k.equals("rage") || p.extras.contains(k)) selected = k;
            switch(k) {
                case "home" -> move(unknownMenu ? "unknown" : "menu");
                case "find_match" -> move("army");
                case "start_attack" -> move("scout");
                case "scout" -> { skips++; if (!fastSkip) move("scout"); }
                case "result" -> { dragons=0; move("home"); }
                case "wall_gold", "wall_elixir" -> { resource = k.substring(5); move("confirm"); }
                case "wall_more" -> { state = "batch"; batchCount = 1; }
                case "batch_add10" -> batchCount = Math.min(batchCount + 10, wallsLeft);
                case "batch_add1" -> batchCount = Math.min(batchCount + 1, wallsLeft);
                case "batch_gold", "batch_elixir" -> { resource = k.substring(6); move(question ? "question" : "confirm"); }
                case "confirm_gold", "confirm_elixir" -> {
                    long cost = badDeduction ? 1 : unitPrice(resource) * Math.max(1, batchCount);
                    if (resource.equals("gold")) gold -= cost; else elixir -= cost;
                    wallsLeft -= Math.max(1, batchCount); batchCount = 0;
                    levels[wallIndex]++; move(autoDeselect ? "home" : "selected");
                }
            }
        }
        @Override public boolean okay(String currency, long total, long balance, long reserve, long cap) throws Exception {
            try (Farmer.Screen f = capture()) { if (!((Screen) f).state.equals("question")) return false; }
            long asked = unitPrice(resource) * batchCount + (wrongQuestion ? 1 : 0);
            if (!currency.equals(resource) || asked != total) {
                if (!questionStuck) { taps.add("cancel"); state = "batch"; } // Cancel: back to the walls still selected
                throw new java.io.IOException("Upgrade More asked for " + asked);
            }
            okays++;
            if (okayThenConfirm) { move("confirm"); return true; }
            if (resource.equals("gold")) gold -= total; else elixir -= total;
            wallsLeft -= batchCount; batchCount = 0; levels[wallIndex]++; move("selected");
            return true;
        }
        @Override public void pay(String currency, long price, long balance, long reserve, long cap) {
            if (balance != (currency.equals("gold") ? gold : elixir)) throw new IllegalStateException("Payment checked against a stale balance");
            tap("confirm_" + currency, "wall_confirm", "confirm_" + currency);
        }
        @Override public void point(int[] p, String guard) throws Exception {
            if (guard.equals("battle") && battleGuardMisses > 0) { battleGuardMisses--; throw new java.io.IOException("Expected battle before tap."); }
            try (Farmer.Screen f = capture()) { if (!has(f,guard)) throw new IllegalStateException("Missing point guard " + guard); }
            if (p[1] == 300) throw new IllegalStateException("Deployment must use burst()");
            else if (p[1] == 900) abilities.add(cardAt(p) + "@" + time);
            else {
                // Village taps land where the camera pan moved the calibrated points.
                int x = p[0] - pan[0], y = p[1] - pan[1];
                if (x == 400 && y == 400) { state = wrongDeselect ? "selected" : "home"; batchCount = 0; }
                else if ((x == 100 || x == 200) && y == 100) { wallIndex = x == 100 ? 0 : 1; state = "selected"; }
                else { state = "selected-other"; elixir += collectOnMiss; }
            }
        }
        @Override public int burst(List<int[]> points, String... guards) {
            try (Farmer.Screen f = capture()) { if (Arrays.stream(guards).noneMatch(g -> has(f, g))) throw new IllegalStateException("Missing burst guard"); }
            if (points.size() == 1 && points.get(0)[1] == 900) {
                // Tapping a card in the troop bar selects it, even while the game has it lifted.
                String card = cardAt(points.get(0));
                if (card.equals("rage") ? !rageInArmy : !armyExtras.contains(card)) throw new IllegalStateException("No such card " + card);
                selected = card; cardTaps.add(card); return 1;
            }
            bursts++; int sent = 0;
            if (popupInBattle && bursts == 1 && !"rage".equals(selected) && !this.p.extras.contains(selected)) { popupInBattle = false; state = "popup"; return points.size(); }
            boolean casting = "rage".equals(selected);
            for (int[] p : points) {
                if (p[0] == blockedX) continue;
                sent++;
                if (state.equals("result")) continue;
                if (casting) { rageDrops.add(time); continue; }
                lastDeployed = time;
                if (this.p.extras.contains(selected)) { if (selected.startsWith("hero_")) heroLanded = time; extrasDeployed.add(selected); selected = null; }
                else {
                    if (rejectedDeployments > 0) { rejectedDeployments--; continue; }
                    if (rejectSecond && dragons == 1 && !secondRejected) { secondRejected=true; continue; }
                    dragons++; totalDeployed++; order.add("dragon");
                    // Like Clash: when the dragons run out, the next card is selected and lifted.
                    if (autoSelect && dragons == this.p.dragons) lifted = "siege";
                }
                if (selected == null) order.add(extrasDeployed.get(extrasDeployed.size() - 1));
                boolean extrasDone = this.p.extras.stream().filter(armyExtras::contains).allMatch(extrasDeployed::contains);
                boolean over = dragons == this.p.dragons && extrasDone;
                if (over && battleLength > 0) { if (battleEnd == 0) battleEnd = time + battleLength; over = false; }
                state = over ? "result" : "battle";
            }
            if (casting) { rageCast = true; selected = null; }
            return sent;
        }
        @Override public Integer wall(Farmer.Screen f) {
            if (flakyTitle && titleReads++ % 2 == 0) return null;
            return (((Screen) f).state.equals("selected") || ((Screen) f).state.equals("batch")) && !wrongBuilding && !(hiddenLevel && levels[wallIndex] > 17) ? levels[wallIndex] : null; }
        @Override public String read(Farmer.Screen f, String key) {
            if (key.equals("dragon_count")) {
                if (missedCountReads > 0 && dragons > 0) { missedCountReads--; return ""; }
                if (greyEmptyCard && dragons == p.dragons) return "";
            }
            if (key.equals("home_builders")) return builders;
            if (key.equals("dragon_count")) {
                return "x" + ((insufficientArmy ? 1 : p.dragons) - dragons);
            }
            if (key.equals("home_gold")) return Long.toString(gold);
            if (key.equals("home_elixir")) return Long.toString(elixir);
            if (key.startsWith("loot_")) return badLoot || skips < unreadableBases ? "1O00000" : skips < poorBases ? "100000" : "1000000";
            if (key.startsWith("confirm_") && changedPrice) return "999999";
            if (unreadablePrices.contains(key)) return "1 68O OOO";
            long unit = unitPrice(key.contains("gold") ? "gold" : "elixir");
            if (key.startsWith("batch_")) return Long.toString(unit * batchCount + (wrongBatchTotal ? 1 : 0));
            // On sale the confirm button shows the price above the old one crossed out.
            if (key.startsWith("confirm_")) return unit * Math.max(1, batchCount) + (sale ? "\n" + 2 * unit * Math.max(1, batchCount) : "");
            return Long.toString(unit);
        }
        @Override public void zoom(String guard) { time += 1500; }
        /** A popup covers the battle once, right after the first dragons are sent (Back closes it; the battle has ended behind it). */
        boolean popupInBattle; int backs;
        @Override public void back() {
            backs++;
            switch (state) {
                case "unknown", "menu", "army" -> state = "home";
                case "question" -> state = "batch";   // Back answers the question with Cancel
                case "confirm" -> state = "selected"; // Back closes the wall confirmation
                case "popup" -> { state = "result"; dragons = p.dragons; }
                default -> { }
            }
        }
        @Override public boolean tapWord(String... words) { return false; }
        @Override public void restore() {}
        List<String> statuses = new ArrayList<>(), events = new ArrayList<>();
        @Override public void status(String text) { statuses.add(text); }
        @Override public void log(String e,String d) { events.add(e + ": " + d); }
        List<String> saved = new ArrayList<>();
        @Override public void save(Farmer.Screen f, String name) { saved.add(name + "@" + ((Screen) f).state); }
        String run() throws Exception { return new Farmer(this,p).run(); }
        String wallsOnly() throws Exception { return new Farmer(this,p).wallsOnly(); }
    }
    @Test public void attackThenBothWallResources() throws Exception {
        Sim s = new Sim(); assertEquals("1 attacks and 2 verified wall upgrades.",s.run());
        assertEquals(8_000_000,s.gold); assertEquals(7_000_000,s.elixir);
        assertEquals(List.of("home","find_match","start_attack","dragon","result","wall_gold","confirm_gold","wall_elixir","confirm_elixir"),s.taps);
    }
    @Test public void repeatsConfiguredAttacks() throws Exception {
        Sim s = new Sim(); s.p.maxAttacks=3; s.p.wallsEnabled=false;
        assertEquals("3 attacks and 0 verified wall upgrades.",s.run());
    }
    @Test public void skipsPoorBases() throws Exception {
        Sim s = new Sim(); s.poorBases=2; s.run(); assertEquals(2,s.skips);
    }
    @Test public void stopsAtSkipLimit() {
        Sim s = new Sim(); s.poorBases=100; s.p.maxSkips=2;
        assertThrows(IllegalStateException.class,s::run); assertFalse(s.taps.contains("dragon"));
    }
    @Test public void skipsOccasionalUnreadableLoot() throws Exception {
        Sim s = new Sim(); s.unreadableBases=2; s.p.wallsEnabled=false;
        assertEquals("1 attacks and 0 verified wall upgrades.",s.run()); assertEquals(2,s.skips);
    }
    @Test public void badLootNeverDeploys() {
        Sim s = new Sim(); s.badLoot=true; assertThrows(IllegalStateException.class,s::run); assertFalse(s.taps.contains("dragon"));
    }
    @Test public void unknownMenuIsNotClicked() {
        // Nothing on an unknown screen is tapped; Back closes it each time until the session gives up.
        Sim s = new Sim(); s.unknownMenu=true; assertThrows(IllegalStateException.class,s::run);
        assertFalse(s.taps.contains("find_match")); assertEquals(Farmer.FAILURES_IN_A_ROW - 1, s.backs); // no recovery after the last one
    }
    @Test public void wrongBuildingNeverReachesPayment() throws Exception {
        // A point selecting something else is closed and skipped; farming goes on.
        Sim s = new Sim(); s.wrongBuilding=true;
        assertEquals("1 attacks and 0 verified wall upgrades. Walls skipped 1× (last: none of the 2 spots tapped selected a wall).", s.run());
        assertFalse(s.taps.contains("wall_gold"));
    }
    @Test public void changedConfirmationPricePreventsPayment() throws Exception {
        // The confirmation is never paid; Back closes it and the session goes on.
        Sim s = new Sim(); s.changedPrice=true;
        assertTrue(s.run().contains("Walls skipped 1× (last: Final price differs from the selected wall price. No payment sent.)"));
        assertFalse(s.taps.contains("confirm_gold")); assertFalse(s.taps.contains("confirm_elixir")); assertEquals(1, s.backs);
    }
    @Test public void reservesSkipWalls() throws Exception {
        // The wall is selected but not paid for: the result says why and the screen is kept.
        Sim s = new Sim(); s.p.reserveGold=9_500_000; s.p.reserveElixir=9_500_000;
        assertEquals("1 attacks and 0 verified wall upgrades. Walls skipped 1× (last: not enough gold or elixir above your reserves for a 3000000 wall).",s.run());
        assertFalse(s.taps.contains("wall_gold")); assertEquals(List.of("walls-stop.png@selected"), s.saved);
    }
    @Test public void unrecognizedUpgradeButtonsAreReported() throws Exception {
        Sim s = new Sim(); s.noUpgradeButtons = true;
        assertEquals("1 attacks and 0 verified wall upgrades. Walls skipped 1× (last: no gold or elixir Upgrade button recognized (recapture Selected wall)).", s.run());
        assertEquals(List.of("walls-stop.png@selected"), s.saved);
    }
    @Test public void wrongDeductionStopsFurtherSpending() throws Exception {
        // Farming goes on; walls never again in this session.
        Sim s = new Sim(); s.badDeduction=true; s.p.maxAttacks = 2;
        assertEquals("2 attacks and 0 verified wall upgrades. Walls skipped 1× (last: Resource deduction could not be verified (gold 10000000 - 2000000 is not 9999999). No further spending.).", s.run());
        assertEquals(1, Collections.frequency(s.taps, "confirm_gold")); assertFalse(s.taps.contains("confirm_elixir"));
    }
    @Test public void wrongDeselectIsReportedButPaidWallsStillCount() throws Exception {
        // The empty-ground point leaves the panel open: noted in the log. The next wall is selected by its own
        // tap and checked again before any payment, so the queue goes on.
        Sim s = new Sim(); s.wrongDeselect=true;
        assertEquals("1 attacks and 2 verified wall upgrades.", s.run());
        assertTrue(s.events.stream().anyMatch(e -> e.startsWith("deselect-failed: Empty-ground point did not close the wall panel.")));
    }
    @Test public void farmingGoesOnAfterAnUnconfirmedWall() throws Exception {
        // The wall's new level cannot be read: the exact deduction already proved the upgrade, farming goes on.
        Sim s = new Sim(); s.hiddenLevel = true; s.p.maxAttacks = 2;
        assertTrue(s.run().startsWith("2 attacks and 2 verified wall upgrades."));
        assertEquals(2, s.events.stream().filter(e -> e.startsWith("wall-level-unconfirmed")).count());
    }
    @Test public void targetLevelSkipsFinishedWalls() throws Exception {
        Sim s = new Sim(); s.p.targetWallLevel=17; assertEquals("1 attacks and 0 verified wall upgrades.",s.run());
    }
    @Test public void fastNextTransitionDoesNotNeedAnInvisibleFrame() throws Exception {
        Sim s = new Sim(); s.fastSkip = true; s.poorBases = 2;
        assertEquals("1 attacks and 2 verified wall upgrades.", s.run()); assertEquals(2, s.skips);
    }
    @Test public void lowerWallFoundLaterIsUpgradedToo() throws Exception {
        // The best match is a level 18 wall: it is upgraded at once, then the level 17 one found next.
        Sim s = new Sim(); s.lookAlike = true; s.levels = new int[] {18, 17};
        assertEquals("1 attacks and 2 verified wall upgrades.", s.run());
        assertArrayEquals(new int[] {19, 18}, s.levels);
        assertEquals(8_000_000, s.gold); assertEquals(7_000_000, s.elixir);
    }
    @Test public void collectorTappedBeforePayingDoesNotStopWalls() throws Exception {
        // Like the Poco at 22:53: a spot that was not a wall collected 480 elixir before the wall was paid.
        // The deduction is checked against the balance read with the wall selected, so walls go on.
        Sim s = new Sim(); s.strayCandidate = true; s.collectOnMiss = 480; s.p.maxAttacks = 2; s.endlessWalls = true;
        assertEquals("2 attacks and 4 verified wall upgrades.", s.run());
        Sim b = new Sim(); b.strayCandidate = true; b.collectOnMiss = 480; b.question = true; b.p.batch = true; b.p.wallsPerVisit = 50; b.endlessWalls = true;
        assertEquals("1 attacks and 7 verified wall upgrades.", b.run());
    }
    @Test public void popupDuringBattleIsRecoveredAndFarmingGoesOn() throws Exception {
        // Like 23:21 on the Poco: the battle screen was not recognized during deployment. The session
        // gets back to Home (Back, then Return Home) and keeps farming instead of ending.
        Sim s = new Sim(); s.popupInBattle = true; s.p.maxAttacks = 2; s.endlessWalls = true;
        assertTrue(s.run().startsWith("2 attacks and "));
        assertTrue(s.events.stream().anyMatch(e -> e.startsWith("attack-failed: Unknown screen during deployment. (1 in a row)")));
        assertTrue(s.events.stream().anyMatch(e -> e.startsWith("recovered: ")));
        assertEquals(1, s.backs);
    }
    @Test public void aProblemThatKeepsComingBackEndsTheSession() {
        Sim s = new Sim(); s.unknownMenu = true; s.p.maxAttacks = 30;
        Exception e = assertThrows(IllegalStateException.class, s::run);
        assertTrue(e.getMessage().startsWith(Farmer.FAILURES_IN_A_ROW + " attacks in a row failed."));
        assertFalse(s.taps.contains("find_match"));
        assertEquals(Farmer.FAILURES_IN_A_ROW, Collections.frequency(s.taps, "home"));
    }
    @Test public void wallsOnlyUpgradesFromHomeWithoutAttacking() throws Exception {
        Sim s = new Sim();
        assertEquals("Walls only: 2 verified wall upgrades. Walls done: 2 upgraded (your maximum per visit).", s.wallsOnly());
        assertFalse(s.taps.contains("find_match")); assertEquals(0, s.dragons);
        Sim b = new Sim(); b.p.batch = true; b.question = true; b.p.wallsPerVisit = 50; b.endlessWalls = true;
        assertTrue(b.wallsOnly().startsWith("Walls only: 7 verified wall upgrades."));
    }
    @Test public void missedWallTitleReadsDoNotStopWalls() throws Exception {
        // One failed read of the small "Wall (Level N)" title is retried, not taken as a changed wall.
        Sim s = new Sim(); s.flakyTitle = true;
        assertEquals("1 attacks and 2 verified wall upgrades.", s.run());
        Sim b = new Sim(); b.flakyTitle = true; b.p.batch = true; b.p.wallsPerVisit = 50; b.endlessWalls = true;
        assertEquals("1 attacks and 7 verified wall upgrades.", b.run());
    }
    @Test public void higherWallsAreUpgradedOnALaterVisit() throws Exception {
        // Like the Poco session: a level 15 wall upgraded once must not block the level 16 walls on every later visit.
        Sim s = new Sim(); s.lookAlike = true; s.levels = new int[] {17, 18}; s.p.maxAttacks = 2; s.p.wallsPerVisit = 1;
        assertEquals("2 attacks and 2 verified wall upgrades.", s.run());
    }
    @Test public void lookAlikeAtTheTargetLevelDoesNotEndWalls() throws Exception {
        Sim s = new Sim(); s.lookAlike = true; s.levels = new int[] {18, 17}; s.p.targetWallLevel = 18;
        assertEquals("1 attacks and 1 verified wall upgrades.", s.run());
        assertArrayEquals(new int[] {18, 18}, s.levels);
    }
    @Test public void noMatchingWallsSkipsWallsButKeepsFarming() throws Exception {
        Sim s = new Sim(); s.shiftedVillage = true;
        assertEquals("1 attacks and 0 verified wall upgrades. Walls skipped 1× (last: no wall like the picked ones on screen (recapture Home village and tap walls of your lowest level)).", s.run());
        assertFalse(s.taps.contains("wall_gold"));
    }
    @Test public void keepsUpgradingUntilTheReserves() throws Exception {
        // 10M each, 1M reserves: gold pays 4 × 2M, elixir 3 × 3M; the visit limit is not the brake.
        Sim s = new Sim(); s.p.wallsPerVisit = 50; s.p.reserveGold = 1_000_000; s.p.reserveElixir = 1_000_000;
        s.levels = new int[] {17, 17}; s.endlessWalls = true;
        assertEquals("1 attacks and 7 verified wall upgrades.", s.run());
        assertEquals(2_000_000, s.gold); assertEquals(1_000_000, s.elixir);
    }
    @Test public void upgradeMorePaysForManyWallsAtOnce() throws Exception {
        // 10M each, 1M reserves: one gold payment for 4 walls (8M), one elixir payment for 3 (9M).
        Sim s = new Sim(); s.p.batch = true; s.p.wallsPerVisit = 50; s.p.reserveGold = 1_000_000; s.p.reserveElixir = 1_000_000; s.endlessWalls = true;
        assertEquals("1 attacks and 7 verified wall upgrades.", s.run());
        assertEquals(2_000_000, s.gold); assertEquals(1_000_000, s.elixir);
        assertEquals(2, Collections.frequency(s.taps, "wall_more")); assertEquals(5, Collections.frequency(s.taps, "batch_add1"));
        assertEquals(2, Collections.frequency(s.taps, "confirm_gold") + Collections.frequency(s.taps, "confirm_elixir"));
    }
    @Test public void biggerBudgetPaysFirstThenTheOther() throws Exception {
        // Like the Poco run: little gold above the reserve, plenty of elixir. Elixir buys 3 walls, then gold 2.
        Sim s = new Sim(); s.p.batch = true; s.p.wallsPerVisit = 50; s.gold = 5_000_000; s.endlessWalls = true;
        assertEquals("1 attacks and 5 verified wall upgrades.", s.run());
        assertTrue(s.taps.indexOf("batch_elixir") < s.taps.indexOf("batch_gold"));
        assertEquals(1_000_000, s.gold); assertEquals(1_000_000, s.elixir);
    }
    @Test public void upgradeMoreUsesPlusTenForBigBudgets() throws Exception {
        Sim s = new Sim(); s.p.batch = true; s.p.wallsPerVisit = 14; s.gold = 30_000_000; s.p.reserveGold = 1_000_000; s.endlessWalls = true;
        s.run();
        assertEquals(1, Collections.frequency(s.taps, "batch_add10")); assertEquals(3, Collections.frequency(s.taps, "batch_add1"));
        assertEquals(2_000_000, s.gold); // 14 × 2M
    }
    @Test public void upgradeMorePaysOnlyForWallsThatExist() throws Exception {
        Sim s = new Sim(); s.p.batch = true; s.p.wallsPerVisit = 50; s.gold = 30_000_000; s.p.reserveGold = 1_000_000; s.endlessWalls = true; s.wallsLeft = 5;
        s.run(); assertEquals(20_000_000, s.gold); // only 5 walls left at that level: 5 × 2M
    }
    @Test public void unreadableWallCountInTitleStillPaysByTheTotal() throws Exception {
        // The small "x N" in the Upgrade More title is often missed by OCR; the button total decides.
        Sim s = new Sim(); s.titleUnreadable = true; s.question = true; s.p.batch = true; s.p.wallsPerVisit = 50; s.endlessWalls = true;
        assertEquals("1 attacks and 7 verified wall upgrades.", s.run());
        assertEquals(2_000_000, s.gold); assertEquals(1_000_000, s.elixir);
    }
    @Test public void upgradeMoreQuestionIsAnsweredOkay() throws Exception {
        Sim s = new Sim(); s.question = true; s.p.batch = true; s.p.wallsPerVisit = 50; s.endlessWalls = true;
        assertEquals("1 attacks and 7 verified wall upgrades.", s.run());
        assertEquals(2, s.okays); assertEquals(2_000_000, s.gold); assertEquals(1_000_000, s.elixir);
        assertFalse(s.taps.contains("confirm_gold") || s.taps.contains("confirm_elixir"));
    }
    @Test public void upgradeMoreQuestionThenWallDialogPaysOnce() throws Exception {
        Sim s = new Sim(); s.question = true; s.okayThenConfirm = true; s.p.batch = true; s.p.wallsPerVisit = 50; s.endlessWalls = true;
        assertEquals("1 attacks and 7 verified wall upgrades.", s.run());
        assertEquals(2, s.okays); assertEquals(2_000_000, s.gold);
    }
    @Test public void wrongAmountInUpgradeMoreQuestionIsCancelledAndWallsGoOneAtATime() throws Exception {
        // Like the Poco's "… Walls for 16000000 Elixir?" that was not understood: Okay is never tapped,
        // Cancel closes the question, and the plain Upgrade button pays wall by wall.
        Sim s = new Sim(); s.question = true; s.wrongQuestion = true; s.p.batch = true; s.p.wallsPerVisit = 50; s.endlessWalls = true;
        assertEquals("1 attacks and 7 verified wall upgrades.", s.run());
        assertEquals(0, s.okays); assertEquals(1, Collections.frequency(s.taps, "cancel"));
        assertEquals(1, Collections.frequency(s.taps, "wall_more"));
        assertEquals(2_000_000, s.gold); assertEquals(1_000_000, s.elixir);
    }
    @Test public void questionThatCannotBeClosedIsNeverPaid() throws Exception {
        // Without a readable Cancel, Android Back closes the question: nothing paid, the session goes on.
        Sim s = new Sim(); s.question = true; s.wrongQuestion = true; s.questionStuck = true; s.p.batch = true; s.p.wallsPerVisit = 50; s.endlessWalls = true;
        assertTrue(s.run().startsWith("1 attacks and 0 verified wall upgrades."));
        assertEquals(0, s.okays); assertEquals(10_000_000, s.gold); assertEquals(10_000_000, s.elixir); assertEquals(1, s.backs);
    }
    @Test public void wrongBatchTotalIsNeverPaidAndWallsGoOneAtATime() throws Exception {
        // Upgrade More is never paid on a wrong total; the plain Upgrade button takes over: 4 × 2M gold, 3 × 3M elixir.
        Sim s = new Sim(); s.p.batch = true; s.p.wallsPerVisit = 50; s.wrongBatchTotal = true; s.endlessWalls = true;
        assertEquals("1 attacks and 7 verified wall upgrades.", s.run());
        assertFalse(s.taps.contains("batch_gold") || s.taps.contains("batch_elixir"));
        assertEquals(1, Collections.frequency(s.taps, "wall_more"));
        assertEquals(2_000_000, s.gold); assertEquals(1_000_000, s.elixir);
    }
    @Test public void unreadableWallPriceSkipsWallsButKeepsFarming() throws Exception {
        Sim s = new Sim(); s.unreadablePrices.addAll(List.of("wall_gold_cost", "wall_elixir_cost"));
        assertEquals("1 attacks and 0 verified wall upgrades. Walls skipped 1× (last: wall price unreadable, nothing paid).", s.run());
        assertFalse(s.taps.contains("wall_gold")); assertFalse(s.taps.contains("wall_elixir"));
    }
    @Test public void unreadableBatchPriceClosesThePanelWithoutPaying() throws Exception {
        Sim s = new Sim(); s.p.batch = true; s.unreadablePrices.addAll(List.of("wall_gold_cost", "wall_elixir_cost"));
        assertEquals("1 attacks and 0 verified wall upgrades. Walls skipped 1× (last: wall price unreadable, nothing paid).", s.run());
        assertFalse(s.taps.contains("wall_more")); assertEquals("home", s.state);
    }
    @Test public void unreadableGoldPriceStillPaysWithElixir() throws Exception {
        Sim s = new Sim(); s.p.wallsPerVisit = 1; s.unreadablePrices.add("wall_gold_cost");
        assertEquals("1 attacks and 1 verified wall upgrades.", s.run());
        assertEquals(10_000_000, s.gold); assertEquals(7_000_000, s.elixir);
    }
    @Test public void saleConfirmationWithTheOldPriceCrossedOutIsPaid() throws Exception {
        Sim s = new Sim(); s.sale = true;
        assertEquals("1 attacks and 2 verified wall upgrades.", s.run());
        Sim b = new Sim(); b.sale = true; b.p.batch = true; b.p.wallsPerVisit = 50; b.endlessWalls = true;
        assertEquals("1 attacks and 7 verified wall upgrades.", b.run());
    }
    @Test public void noFreeBuilderSkipsWalls() throws Exception {
        Sim s = new Sim(); s.p.checkBuilders = true; s.builders = "0/7";
        assertEquals("1 attacks and 0 verified wall upgrades. Walls skipped 1× (last: no free builder (a wall upgrade needs an idle builder)).", s.run());
        assertFalse(s.taps.contains("wall_gold"));
    }
    @Test public void skippedWallsStayOnTheControlDuringTheNextAttack() throws Exception {
        // The reason must not flash by: it stays under every step until walls are checked again.
        Sim s = new Sim(); s.p.checkBuilders = true; s.builders = "0/7"; s.p.maxAttacks = 2;
        s.run();
        String note = "\nWalls skipped: no free builder (a wall upgrade needs an idle builder)";
        assertTrue(s.statuses.contains("1 attacks / 0 walls" + note));
        assertTrue(s.statuses.contains("Finding an opponent" + note));
        assertTrue(s.statuses.contains("Waiting for battle results" + note));
        assertTrue(s.statuses.contains("Checking walls")); // a new check starts clean
    }
    @Test public void wallStepsAreLoggedWithTheResourcesRead() throws Exception {
        Sim s = new Sim(); s.p.checkBuilders = true; s.builders = "1/7";
        s.run();
        assertTrue(s.events.contains("walls-check: gold 10000000, elixir 10000000, free builders 1"));
        assertTrue(s.events.contains("walls-done: 2 upgraded (your maximum per visit)"));
        assertTrue(s.statuses.contains("Checking walls: tapping wall 1 of 2 found"));
    }
    @Test public void stoppedSessionStillLogsItsSummary() {
        Sim s = new Sim(); s.unknownMenu = true;
        assertThrows(IllegalStateException.class, s::run);
        assertTrue(s.events.stream().anyMatch(e -> e.startsWith("session-summary: 0 attacks and ") && e.contains("Ended by: 5 attacks in a row failed.")));
    }
    @Test public void unreadableBuilderCounterSkipsWalls() throws Exception {
        Sim s = new Sim(); s.p.checkBuilders = true; s.builders = "";
        assertTrue(s.run().endsWith("(last: builder counter unreadable (recalibrate the builder counter)).")); assertFalse(s.taps.contains("wall_gold"));
    }
    @Test public void freeBuilderAllowsWalls() throws Exception {
        Sim s = new Sim(); s.p.checkBuilders = true; s.builders = "1/7";
        assertEquals("1 attacks and 2 verified wall upgrades.", s.run());
    }
    @Test public void rageLandsOnItsPointsAfterTheDelay() throws Exception {
        Sim s = new Sim(); s.p.dragons = 3; s.p.wallsEnabled = false; s.p.extras = List.of("siege", "hero_1");
        s.p.rage = true; s.p.ragePoints = List.of(new int[]{700,500}, new int[]{720,500}, new int[]{740,500}); s.p.rageSeconds = 4; s.battleLength = 60_000;
        assertEquals("1 attacks and 0 verified wall upgrades.", s.run());
        assertEquals(3, s.rageDrops.size()); assertEquals(3, s.totalDeployed); // spells never count as dragons
        for (long t : s.rageDrops) assertTrue(t - s.lastDeployed >= 4_000);
    }
    @Test public void missingRageCardIsSkipped() throws Exception {
        Sim s = new Sim(); s.p.dragons = 2; s.p.wallsEnabled = false; s.p.rage = true; s.p.ragePoints = List.of(new int[]{700,500}); s.rageInArmy = false; s.battleLength = 30_000;
        assertEquals("1 attacks and 0 verified wall upgrades.", s.run()); assertTrue(s.rageDrops.isEmpty());
    }
    @Test public void hiddenWallLevelStillCountsOnceTheDeductionIsExact() throws Exception {
        // Clash took exactly the wall's price after its own wall confirmation: that is the proof; the title is only noted.
        Sim s = new Sim(); s.hiddenLevel = true;
        assertEquals("1 attacks and 2 verified wall upgrades.", s.run());
        assertTrue(s.events.stream().anyMatch(e -> e.startsWith("wall-level-unconfirmed: expected level 18, read null")));
    }
    @Test public void reselectsWallWhenGameClosesPanelAfterPayment() throws Exception {
        Sim s = new Sim(); s.autoDeselect = true;
        assertEquals("1 attacks and 2 verified wall upgrades.", s.run());
    }
    @Test public void triesAnotherEdgeWhenFirstDeploymentIsRejected() throws Exception {
        Sim s = new Sim(); s.rejectedDeployments = 1;
        assertEquals("1 attacks and 2 verified wall upgrades.", s.run());
    }
    @Test public void stopsWhenEveryInitialDeploymentIsRejected() {
        Sim s = new Sim(); s.rejectedDeployments = 100;
        assertThrows(IllegalStateException.class, s::run); assertEquals(0, s.dragons); assertFalse(s.taps.contains("wall_gold"));
    }
    @Test public void rejectedLaterTapDoesNotCountAsADeployedDragon() throws Exception {
        Sim s = new Sim(); s.rejectSecond = true; s.run(); assertTrue(s.secondRejected); assertEquals(2,s.totalDeployed);
    }
    @Test public void deploysWholeArmyPastACoveredPoint() throws Exception {
        Sim s = new Sim(); s.p.dragons = 17; s.p.wallsEnabled = false; s.blockedX = 30;
        s.p.deployments = List.of(new int[]{10,300}, new int[]{20,300}, new int[]{30,300}, new int[]{40,300});
        assertEquals("1 attacks and 0 verified wall upgrades.", s.run()); assertEquals(17, s.totalDeployed);
    }
    @Test public void wholeArmyGoesOutInOneBurst() throws Exception {
        Sim s = new Sim(); s.p.dragons = 17; s.p.wallsEnabled = false;
        s.p.deployments = List.of(new int[]{10,300}, new int[]{20,300}, new int[]{40,300});
        assertEquals("1 attacks and 0 verified wall upgrades.", s.run()); assertEquals(17, s.totalDeployed); assertEquals(1, s.bursts);
    }
    @Test public void siegeDeploysEvenWhenTheGameAutoSelectsIt() throws Exception {
        Sim s = new Sim(); s.p.dragons = 3; s.p.wallsEnabled = false; s.p.extras = List.of("siege", "hero_1", "hero_2"); s.autoSelect = true;
        assertEquals("1 attacks and 0 verified wall upgrades.", s.run());
        assertEquals(List.of("dragon", "dragon", "dragon", "siege", "hero_1", "hero_2"), s.order);
    }
    @Test public void siegeAndHeroesFollowTheDragons() throws Exception {
        Sim s = new Sim(); s.p.dragons = 5; s.p.wallsEnabled = false; s.p.extras = List.of("siege", "hero_1", "hero_2");
        assertEquals("1 attacks and 0 verified wall upgrades.", s.run());
        assertEquals(List.of("dragon", "dragon", "dragon", "dragon", "dragon", "siege", "hero_1", "hero_2"), s.order);
    }
    @Test public void heroAbilitiesFireAfterTheDelay() throws Exception {
        Sim s = new Sim(); s.p.dragons = 3; s.p.wallsEnabled = false; s.p.extras = List.of("siege", "hero_1", "hero_2");
        s.p.abilitySeconds = 8; s.battleLength = 60_000;
        assertEquals("1 attacks and 0 verified wall upgrades.", s.run());
        assertEquals(2, s.abilities.size()); // the siege machine has no ability
        for (String a : s.abilities) {
            assertTrue(a, a.startsWith("hero_"));
            assertTrue(a, Long.parseLong(a.split("@")[1]) - s.heroLanded >= 8_000);
        }
    }
    @Test public void unreadableEmptyCardStillDeploysSiegeAndHeroes() throws Exception {
        Sim s = new Sim(); s.p.dragons = 4; s.p.wallsEnabled = false; s.p.extras = List.of("siege", "hero_1"); s.greyEmptyCard = true;
        s.p.deployments = List.of(new int[]{10,300}, new int[]{20,300}, new int[]{40,300});
        assertEquals("1 attacks and 0 verified wall upgrades.", s.run());
        assertEquals(List.of("dragon", "dragon", "dragon", "dragon", "siege", "hero_1"), s.order); assertEquals(1, s.bursts - 2);
    }
    @Test public void abilityRetriesAfterAMissedBattleButton() throws Exception {
        Sim s = new Sim(); s.p.dragons = 2; s.p.wallsEnabled = false; s.p.extras = List.of("hero_1", "hero_2");
        s.battleLength = 60_000; s.battleGuardMisses = 2;
        assertEquals("1 attacks and 0 verified wall upgrades.", s.run()); assertEquals(2, s.abilities.size());
    }
    @Test public void battleEndingFirstSkipsAbilities() throws Exception {
        Sim s = new Sim(); s.p.dragons = 3; s.p.wallsEnabled = false; s.p.extras = List.of("hero_1");
        s.p.abilitySeconds = 30; s.battleLength = 5_000;
        assertEquals("1 attacks and 0 verified wall upgrades.", s.run()); assertTrue(s.abilities.isEmpty());
    }
    @Test public void missingSiegeIsSkippedNotFatal() throws Exception {
        Sim s = new Sim(); s.p.dragons = 3; s.p.wallsEnabled = false; s.p.extras = List.of("siege", "hero_1"); s.armyExtras.remove("siege");
        s.run(); assertEquals(List.of("dragon", "dragon", "dragon", "hero_1"), s.order);
    }
    @Test public void rejectedTapsAreRetriedInAnotherBurst() throws Exception {
        Sim s = new Sim(); s.p.dragons = 17; s.p.wallsEnabled = false; s.rejectedDeployments = 5;
        assertEquals("1 attacks and 0 verified wall upgrades.", s.run()); assertEquals(17, s.totalDeployed); assertEquals(2, s.bursts);
    }
    @Test public void missedCountReadsDoNotStopDeployment() throws Exception {
        Sim s = new Sim(); s.p.dragons = 17; s.p.wallsEnabled = false; s.missedCountReads = 3; s.rejectedDeployments = 4;
        assertEquals("1 attacks and 0 verified wall upgrades.", s.run()); assertEquals(17, s.totalDeployed);
    }
    @Test public void everyPointCoveredStopsWithoutDeploying() {
        Sim s = new Sim(); s.p.deployments = List.of(new int[]{30,300}); s.blockedX = 30;
        assertThrows(IllegalStateException.class, s::run); assertEquals(0, s.totalDeployed);
    }
    @Test public void insufficientArmyStopsBeforeSelectingTroops() {
        Sim s = new Sim(); s.insufficientArmy = true; assertThrows(IllegalStateException.class,s::run); assertFalse(s.taps.contains("dragon"));
    }
}
