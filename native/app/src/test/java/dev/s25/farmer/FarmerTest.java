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
        int rejectedDeployments;
        boolean insufficientArmy, rejectSecond, secondRejected;
        List<String> taps = new ArrayList<>();
        Sim() {
            p.maxAttacks = 1; p.dragons = 2; p.deployments = List.of(new int[]{10,300},new int[]{500,300});
            p.walls = List.of(new int[]{100,100},new int[]{200,100}); p.blank = new int[]{400,400};
        }
        void move(String next) { state = "transition"; pending = next; transitionTime = time + 300; }
        @Override public long now() { return time; }
        @Override public void pause(long ms) { time += ms; }
        @Override public Farmer.Screen capture() { if (pending != null && time >= transitionTime) { state = pending; pending = null; } return new Screen(state); }
        @Override public boolean has(Farmer.Screen f, String k) {
            String s = ((Screen) f).state;
            return switch(k) {
                case "home" -> s.equals("home") || s.equals("selected");
                case "home_anchor" -> !shiftedVillage && (s.equals("home") || s.equals("selected"));
                case "find_match" -> s.equals("menu");
                case "scout", "dragon" -> s.equals("scout");
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
            switch(k) {
                case "home" -> move(unknownMenu ? "unknown" : "menu");
                case "find_match" -> move("scout");
                case "scout" -> { skips++; if (!fastSkip) move("scout"); }
                case "result" -> { dragons=0; move("home"); }
                case "wall_gold", "wall_elixir" -> { resource = k.substring(5); move("confirm"); }
                case "confirm_gold", "confirm_elixir" -> {
                    long cost = badDeduction ? 1 : resource.equals("gold") ? 2_000_000 : 3_000_000;
                    if (resource.equals("gold")) gold -= cost; else elixir -= cost;
                    levels[wallIndex]++; move(autoDeselect ? "home" : "selected");
                }
            }
        }
        @Override public long pay(String currency, long price, long reserve, long cap) {
            long balance = currency.equals("gold") ? gold : elixir;
            tap("confirm_" + currency, "wall_confirm", "confirm_" + currency); return balance;
        }
        @Override public void point(int[] p, String guard) {
            try (Farmer.Screen f = capture()) { if (!has(f,guard)) throw new IllegalStateException("Missing point guard " + guard); }
            if (p[1] == 300) {
                if (rejectedDeployments > 0) { rejectedDeployments--; return; }
                if (rejectSecond && dragons == 1 && !secondRejected) { secondRejected=true; return; }
                dragons++; totalDeployed++; state = dragons == this.p.dragons ? "result" : "battle";
            }
            else if (p[0] == 400) state = wrongDeselect ? "selected" : "home";
            else { wallIndex = p[0] == 100 ? 0 : 1; state = "selected"; }
        }
        @Override public Integer wall(Farmer.Screen f) { return ((Screen) f).state.equals("selected") && !wrongBuilding && !(hiddenLevel && levels[wallIndex] > 17) ? levels[wallIndex] : null; }
        @Override public String read(Farmer.Screen f, String key) {
            if (key.equals("dragon_count")) return "x" + ((insufficientArmy ? 1 : p.dragons) - dragons);
            if (key.equals("home_gold")) return Long.toString(gold);
            if (key.equals("home_elixir")) return Long.toString(elixir);
            if (key.startsWith("loot_")) return badLoot ? "1O00000" : skips < poorBases ? "100000" : "1000000";
            if (key.startsWith("confirm_") && changedPrice) return "999999";
            return key.contains("gold") ? "2000000" : "3000000";
        }
        @Override public void zoom(String guard) { time += 1500; }
        @Override public void status(String text) {}
        @Override public void log(String e,String d) {}
        String run() throws Exception { return new Farmer(this,p).run(); }
    }
    @Test public void attackThenBothWallResources() throws Exception {
        Sim s = new Sim(); assertEquals("1 attacks and 2 verified wall upgrades.",s.run());
        assertEquals(8_000_000,s.gold); assertEquals(7_000_000,s.elixir);
        assertEquals(List.of("home","find_match","dragon","result","wall_gold","confirm_gold","wall_elixir","confirm_elixir"),s.taps);
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
    @Test public void badLootNeverDeploys() {
        Sim s = new Sim(); s.badLoot=true; assertThrows(IllegalStateException.class,s::run); assertFalse(s.taps.contains("dragon"));
    }
    @Test public void unknownMenuIsNotClicked() {
        Sim s = new Sim(); s.unknownMenu=true; assertThrows(IllegalStateException.class,s::run); assertEquals(List.of("home"),s.taps);
    }
    @Test public void wrongBuildingNeverReachesPayment() {
        Sim s = new Sim(); s.wrongBuilding=true; assertThrows(IllegalStateException.class,s::run); assertFalse(s.taps.contains("wall_gold"));
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
    @Test public void shiftedVillagePreventsWallSelectionAndPayment() {
        Sim s = new Sim(); s.shiftedVillage = true;
        assertThrows(IllegalStateException.class, s::run); assertFalse(s.taps.contains("wall_gold"));
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
    @Test public void insufficientArmyStopsBeforeSelectingTroops() {
        Sim s = new Sim(); s.insufficientArmy = true; assertThrows(IllegalStateException.class,s::run); assertFalse(s.taps.contains("dragon"));
    }
}
