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
        /** Upgrade More asks "upgrade the selected Walls for N?" (Cancel / Okay); with okayThenConfirm the wall dialog follows. */
        boolean question, okayThenConfirm, wrongQuestion, titleUnreadable; int okays;
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
            if (shiftedVillage) return out;
            if (endlessWalls) levels = new int[] {17, 17}; // plenty more walls at the picked level
            if (levels[0] == 17 || wrongBuilding) out.add(new int[] {100, 100});
            if (levels[1] == 17 || wrongBuilding) out.add(new int[] {200, 100});
            return out;
        }
        @Override public boolean has(Farmer.Screen f, String k) {
            String s = ((Screen) f).state;
            return switch(k) {
                case "home" -> s.equals("home") || s.equals("selected") || s.equals("batch");
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
                case "wall_gold", "wall_elixir" -> s.equals("selected");
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
            if (!currency.equals(resource) || asked != total) throw new java.io.IOException("Upgrade More asked for " + asked);
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
                else state = "selected-other";
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
        @Override public Integer wall(Farmer.Screen f) { return (((Screen) f).state.equals("selected") || ((Screen) f).state.equals("batch")) && !wrongBuilding && !(hiddenLevel && levels[wallIndex] > 17) ? levels[wallIndex] : null; }
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
        @Override public void status(String text) {}
        @Override public void log(String e,String d) {}
        String run() throws Exception { return new Farmer(this,p).run(); }
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
        Sim s = new Sim(); s.unknownMenu=true; assertThrows(IllegalStateException.class,s::run); assertEquals(List.of("home"),s.taps);
    }
    @Test public void wrongBuildingNeverReachesPayment() throws Exception {
        // A point selecting something else is closed and skipped; farming goes on.
        Sim s = new Sim(); s.wrongBuilding=true;
        assertEquals("1 attacks and 0 verified wall upgrades. Walls skipped 1× (last: no wall of the picked level could be selected).", s.run());
        assertFalse(s.taps.contains("wall_gold"));
    }
    @Test public void changedConfirmationPricePreventsPayment() {
        Sim s = new Sim(); s.changedPrice=true; assertThrows(IllegalStateException.class,s::run); assertFalse(s.taps.contains("confirm_gold"));
    }
    @Test public void reservesSkipWalls() throws Exception {
        Sim s = new Sim(); s.p.reserveGold=9_500_000; s.p.reserveElixir=9_500_000;
        assertEquals("1 attacks and 0 verified wall upgrades.",s.run()); assertFalse(s.taps.contains("wall_gold"));
    }
    @Test public void wrongDeductionStopsFurtherSpending() {
        Sim s = new Sim(); s.badDeduction=true; assertThrows(IllegalStateException.class,s::run); assertFalse(s.taps.contains("confirm_elixir"));
    }
    @Test public void wrongDeselectDoesNotContinueQueue() {
        Sim s = new Sim(); s.wrongDeselect=true; assertThrows(IllegalStateException.class,s::run); assertFalse(s.taps.contains("wall_elixir"));
    }
    @Test public void targetLevelSkipsFinishedWalls() throws Exception {
        Sim s = new Sim(); s.p.targetWallLevel=17; assertEquals("1 attacks and 0 verified wall upgrades.",s.run());
    }
    @Test public void fastNextTransitionDoesNotNeedAnInvisibleFrame() throws Exception {
        Sim s = new Sim(); s.fastSkip = true; s.poorBases = 2;
        assertEquals("1 attacks and 2 verified wall upgrades.", s.run()); assertEquals(2, s.skips);
    }
    @Test public void noMatchingWallsSkipsWallsButKeepsFarming() throws Exception {
        Sim s = new Sim(); s.shiftedVillage = true;
        assertEquals("1 attacks and 0 verified wall upgrades. Walls skipped 1× (last: no wall like the picked ones on screen).", s.run());
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
    @Test public void wrongAmountInUpgradeMoreQuestionIsNeverPaid() {
        Sim s = new Sim(); s.question = true; s.wrongQuestion = true; s.p.batch = true; s.p.wallsPerVisit = 50; s.endlessWalls = true;
        assertThrows(java.io.IOException.class, s::run);
        assertEquals(0, s.okays); assertEquals(10_000_000, s.gold);
    }
    @Test public void wrongBatchTotalIsNeverPaid() throws Exception {
        Sim s = new Sim(); s.p.batch = true; s.p.wallsPerVisit = 50; s.wrongBatchTotal = true; s.endlessWalls = true;
        assertTrue(s.run().endsWith("(last: Upgrade More check failed, nothing paid)."));
        assertFalse(s.taps.contains("batch_gold")); assertEquals(10_000_000, s.gold);
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
        assertEquals("1 attacks and 0 verified wall upgrades. Walls skipped 1× (last: no free builder).", s.run());
        assertFalse(s.taps.contains("wall_gold"));
    }
    @Test public void unreadableBuilderCounterSkipsWalls() throws Exception {
        Sim s = new Sim(); s.p.checkBuilders = true; s.builders = "";
        assertTrue(s.run().endsWith("(last: builder counter unreadable).")); assertFalse(s.taps.contains("wall_gold"));
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
    @Test public void hiddenWallLevelCannotCountAsVerifiedUpgrade() {
        Sim s = new Sim(); s.hiddenLevel = true;
        assertThrows(IllegalStateException.class, s::run); assertFalse(s.taps.contains("confirm_elixir"));
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
