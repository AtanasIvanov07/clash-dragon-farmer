package dev.s25.farmer;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RulesTest {
    @Test public void groupedNumbers() {
        for (String n : List.of("1234567", "1,234,567", "1 234 567", "1.234.567", "1\u202f234\u202f567", "1234 567")) assertEquals(Long.valueOf(1234567), Rules.number(n));
    }
    @Test public void upgradeMoreTitle() {
        assertEquals(Integer.valueOf(15), Rules.selectedWall(List.of("Wall (Level 15)x 1")));
        assertEquals(Integer.valueOf(8), Rules.selectedWallCount(List.of("Battle", "Wall (Level 15)x 8")));
        assertEquals(Integer.valueOf(12), Rules.selectedWallCount(List.of("Wall (Level 15)", "x 12")));
        assertEquals(Integer.valueOf(1), Rules.selectedWallCount(List.of("Wall (Level 15)x I")));
        assertEquals(Integer.valueOf(1), Rules.selectedWallCount(List.of("Wall (Level 15)x i")));
        assertEquals(Integer.valueOf(10), Rules.selectedWallCount(List.of("Wall (Level 15)x lO")));
        assertNull(Rules.selectedWallCount(List.of("Wall (Level 15)x S")));
        assertNull(Rules.selectedWallCount(List.of("Wall (Level 15)")));
        assertNull(Rules.selectedWallCount(List.of("Wall (Level 15)x 2", "Wall (Level 15)x 3")));
    }
    @Test public void freeBuilderCounter() {
        assertEquals(Integer.valueOf(1), Rules.freeBuilders("1/7"));
        assertEquals(Integer.valueOf(0), Rules.freeBuilders("0 / 6"));
        for (String n : List.of("", "8/7", "1/0", "17", "1/7/2", "x1/7")) assertNull(n, Rules.freeBuilders(n));
    }
    @Test public void lostSpaceAfterLeadingDigitKeepsTheValue() {
        assertEquals(Long.valueOf(1574019), Rules.number("1574 019"));
        assertEquals(Long.valueOf(1000000), Rules.number("1000 000"));
        for (String n : List.of("12345 678", "1574 01", "1574,019", "1574 019 12")) assertNull(n, Rules.number(n));
    }
    @Test public void troopCounterRejectsOtherTextAndMultipleRows() {
        assertEquals(Integer.valueOf(18), Rules.troopCount("x18"));
        assertEquals(Integer.valueOf(2), Rules.troopCount("× 2"));
        assertEquals(Integer.valueOf(0), Rules.troopCount("0"));
        for (String s : List.of("x1O", "18\n12", "101", "", "Level 18")) assertNull(Rules.troopCount(s));
    }
    @Test public void ambiguousNumbersCannotAuthorizeSpending() {
        for (String n : List.of("", "1O00000", "1.5M", "12\n34", "1 23 456", "-1", "200 gems", "1,234.567", "1 234\n567")) assertNull(n, Rules.number(n));
        assertNull(Rules.number(null)); assertNull(Rules.stable("1000000", "1000001"));
    }
    @Test public void reservesAndCaps() {
        assertTrue(Rules.affordable(10_000_000L, 9_000_000L, 1_000_000, 10_000_000));
        assertFalse(Rules.affordable(10_000_000L, 9_000_001L, 1_000_000, 10_000_000));
        assertFalse(Rules.affordable(20_000_000L, 11_000_000L, 0, 10_000_000));
        assertFalse(Rules.affordable(10_000_000L, null, 0, 10_000_000));
    }
    @Test public void recognizesOnlySelectedWall() {
        assertEquals(Integer.valueOf(17), Rules.selectedWall(List.of("Attack", "Wall", "(Level 17)", "Upgrade")));
        assertNull(Rules.selectedWall(List.of("Town Hall (Level 18)", "Wall Breaker (Level 12)")));
        assertNull(Rules.selectedWall(List.of("Wall (Level 17)", "Wall (Level 18)")));
    }
    @Test public void confirmationMustShowTheExactAmount() {
        assertTrue(Rules.shows("1 680 000", 1_680_000));
        assertTrue(Rules.shows("1 680 000\n4 000 000", 1_680_000)); // sale: old price crossed out below
        assertFalse(Rules.shows("1 680 000\n4 000 000", 4_000_001));
        assertFalse(Rules.shows("16 800 000", 1_680_000));
        assertFalse(Rules.shows("1 680 000 4 000 000", 1_680_000));
        assertFalse(Rules.shows(null, 1_680_000)); assertFalse(Rules.shows("", 0));
    }
    // Troop bar 1200 × 300 like Clash's: dark band from row 50, cards 130 × 190 from row 60.
    // The siege was calibrated as {x, 30, 135, 231}: a little of the battlefield above the card.
    private static final int W = 1200, H = 300;
    private static final int[] SIEGE = {100, 30, 135, 231};
    private static int[] bar() { int[] p = new int[W * H]; java.util.Arrays.fill(p, 0xff0e1c25); return p; }
    private static void fill(int[] p, int x0, int y0, int x1, int y1, int color) {
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) p[y * W + x] = color;
    }
    /** A siege card: cyan background, its machine (any colors) in the middle, level badge and switch at the bottom. */
    private static void siege(int[] p, int x, int machine) {
        fill(p, x, 60, x + 130, 250, 0xff5bb1c8);
        fill(p, x + 30, 85, x + 100, 200, machine); fill(p, x + 58, 90, x + 72, 170, 0xffffffff); // the "!"
        fill(p, x + 10, 205, x + 45, 240, 0xffe8b35f); fill(p, x + 55, 205, x + 125, 240, 0xff94c951);
    }
    private static void troop(int[] p, int x) { fill(p, x, 60, x + 130, 250, 0xff6799dd); fill(p, x, 60, x + 130, 61, 0xff7ad0e2); }
    private static void hero(int[] p, int x) { fill(p, x, 60, x + 130, 250, 0xff1c0766); }
    /** Freeze spell: violet card, cyan ice at the bottom. */
    private static void freeze(int[] p, int x) { fill(p, x, 60, x + 130, 250, 0xff7759df); fill(p, x + 15, 200, x + 115, 215, 0xff83d9ea); }
    @Test public void siegeCardIsFoundWhicheverMachineItHolds() {
        for (int machine : new int[] {0xffc94640, 0xff8a5a2b, 0xff30281b}) { // red ball, Log Launcher, dark barracks
            for (int x : new int[] {200, 400, 760}) {
                int[] p = bar(); troop(p, x - 160); siege(p, x, machine); hero(p, x + 150); hero(p, x + 290);
                if (x + 440 < W - 130) freeze(p, x + 440);
                // Water above the bar, broken into card-wide pieces, must not hide the siege.
                for (int w = 0; w < W; w += 140) fill(p, w, 30, w + 120, 50, 0xff4fa8c4);
                int[] at = Rules.siegeCard(p, W, H, SIEGE);
                assertNotNull("siege at " + x, at);
                assertEquals(x + 65, at[0], 3);
                assertTrue("tap the icon, above the level switch: " + at[1], at[1] > 60 && at[1] < 200);
            }
        }
    }
    @Test public void noSiegeCardMeansNoSiege() {
        int[] p = bar(); troop(p, 100); troop(p, 250); hero(p, 400); hero(p, 550); freeze(p, 700);
        assertNull(Rules.siegeCard(p, W, H, SIEGE));
        // Water above the troop bar: cyan, but far wider than a card and with the dark bar below it.
        fill(p, 0, 30, W, 50, 0xff4fa8c4);
        assertNull(Rules.siegeCard(p, W, H, SIEGE));
        fill(p, 0, 30, W, 50, 0xff0e1c25); for (int x = 0; x < W; x += 140) fill(p, x, 30, x + 120, 50, 0xff4fa8c4);
        assertNull(Rules.siegeCard(p, W, H, SIEGE));
        assertNull(Rules.siegeCard(p, W, H, null));
    }
    @Test public void upgradeMoreQuestionNamesTheTotal() {
        List<String> q = List.of("Upgrade Walls", "Do you really want to upgrade the selected", "Walls for 18480000 Elixir?", "Cancel", "Okay");
        assertEquals(Long.valueOf(18_480_000), Rules.batchDialog(q, "elixir"));
        assertNull(Rules.batchDialog(q, "gold"));
        assertEquals(Long.valueOf(3_360_000), Rules.batchDialog(List.of("upgrade the selected Walls for 3 360 000 Gold?"), "gold"));
        assertEquals(Long.valueOf(18_480_000), Rules.batchDialog(List.of("upgrade the selected Walls for 1848OOOO Elixir?"), "elixir"));
        assertNull(Rules.batchDialog(List.of("upgrade the selected Walls for SOD Elixir?"), "elixir"));
    }
    @Test public void pocoUpgradeMoreQuestionIsUnderstood() {
        // The question on the Poco F9 Pro: "Do you really want to upgrade the selected / Walls for 16000000 Elixir?".
        List<String> q = List.of("Upgrade Walls", "Do you really want to upgrade the selected", "Walls for 16000000 Elixir?", "Cancel", "Okay");
        assertEquals(Long.valueOf(16_000_000), Rules.batchDialog(q, "elixir"));
        // OCR order across blocks, a lost "?", "EIixir", blocky zeros.
        assertEquals(Long.valueOf(16_000_000), Rules.batchDialog(List.of("15 990 137", "Okay", "Walls for 16000000 EIixir", "Cancel", "Do you really want to upgrade the selected"), "elixir"));
        assertEquals(Long.valueOf(16_000_000), Rules.batchDialog(List.of("Do you really want to upgrade the selected", "Walls for I6OOOOOO Elixir!"), "elixir"));
        assertEquals(Long.valueOf(3_200_000), Rules.batchDialog(List.of("Do you really want to upgrade the selected", "Walls f0r 3200000 Go1d?"), "gold"));
        assertNull(Rules.batchDialog(List.of("Do you really want to upgrade the selected", "Walls for 16000000 Elixir?"), "gold"));
        assertNull(Rules.batchDialog(List.of("Walls for 16000000 Elixir?"), "elixir")); // not the question
        assertTrue(Rules.batchQuestion(q)); assertFalse(Rules.batchQuestion(List.of("Wall (Level 15)x 4", "Upgrade More")));
        // Exactly what the Poco read at 22:45 (v0.7.10 cancelled it because of "Por").
        List<String> poco = List.of("226", "Ih 18m", "Batle", "Just asko", "BPEME PA31ENHO", "Do you really want to upgrade the selected",
                "Walls Por 12000000 Elixir?", "ReMOve", "UPgRade Walls", "Wall", "CaNcel", "Addkiwaiig", "77", "Okay", "AUpgROde", "UpgRade",
                "4H ZM", "14 338 S21", "14 777 338", "22H 58M", "S00 O00", "5937-", "7H 47M", "SHOP", "5/6 1/2", "Build");
        assertEquals(Long.valueOf(12_000_000), Rules.batchDialog(poco, "elixir"));
        assertNull(Rules.batchDialog(poco, "gold"));
        assertTrue(Rules.buttonWord("CaNcel", "Cancel"));
        // "for" itself misread, and tabs or line breaks inside one OCR line.
        assertEquals(Long.valueOf(16_000_000), Rules.batchDialog(List.of("Do you really want to upgrade the selected", "Walls tor 16000000 Elixir?"), "elixir"));
        assertEquals(Long.valueOf(16_000_000), Rules.batchDialog(List.of("Do you really want to\tupgrade the\nselected Walls for\t16000000 Elixir?"), "elixir"));
        assertNull(Rules.batchDialog(List.of("Do you really want to upgrade the selected", "19 547 941 Elixir"), "elixir")); // a balance, not the Walls line
    }
    @Test public void pocoBuilderCounterAndWallTitlesAreUnderstood() {
        // 23:12–23:16 on the Poco: "5/6" came back as "576" ten times in a row.
        assertEquals(Integer.valueOf(5), Rules.freeBuilders("576"));
        assertEquals(Integer.valueOf(5), Rules.freeBuilders("5/6"));
        assertEquals(Integer.valueOf(0), Rules.freeBuilders("0 | 6"));
        assertNull(Rules.freeBuilders("7 6")); assertNull(Rules.freeBuilders("1576"));
        // The panel title and Clash's small tag over the wall are both read: agreeing levels are one wall.
        assertEquals(Integer.valueOf(17), Rules.selectedWall(List.of("Wall", "Level 17", "Info", "Wall (Level 17)", "Upgrade")));
        assertNull(Rules.selectedWall(List.of("Wall (Level 16)", "Wall (Level 17)")));
        // "x" whose thin 1 was dropped is still the title; the count is just unknown.
        assertEquals(Integer.valueOf(15), Rules.selectedWall(List.of("Wall (Level 15)x")));
        assertEquals(Integer.valueOf(6), Rules.selectedWallCount(List.of("Wall", "Level 16", "Wall (Level 16)x 6")));
    }
    @Test public void dialogButtonsAreFoundDespiteTheFont() {
        for (String read : List.of("Okay", "OKAY", "0kay", "Okav", "Ok ay", "Okay."))
            assertTrue(read, Rules.buttonWord(read, "Okay"));
        for (String read : List.of("Cancel", "CANCEL", "Cancei", "Cance1"))
            assertTrue(read, Rules.buttonWord(read, "Cancel"));
        for (String read : List.of("Cancel", "Ok", "Upgrade", "Okay Cancel", ""))
            assertFalse(read, Rules.buttonWord(read, "Okay"));
        assertFalse(Rules.buttonWord("Okay", "Cancel"));
    }
    @Test public void blockyPriceDigitsAreRead() {
        assertEquals("5 040 000", Vision.priceDigits("S 040 D00"));
        assertEquals("5 040 000", Vision.priceDigits("5 040 00O"));
        assertEquals("1 680 000\nUpgrade", Vision.priceDigits("I 68O OOO\nUpgrade"));
        assertEquals("Upgrade", Vision.priceDigits("Upgrade"));
        assertNull(Rules.batchDialog(List.of("Wall (Level 15)x 11", "Upgrade"), "elixir"));
    }
    @Test public void wallPricesReadOnThePocoAreUnderstood() {
        // Exactly as logged on the Poco F9 Pro, where an older build refused to pay for a selected wall.
        for (String read : List.of("I600 000", "I600 O00", "1600 OO0", "I 600 OD0", "I600 OD0"))
            assertEquals(read, Long.valueOf(1_600_000), Rules.number(Vision.onlyNumberLine(Vision.priceDigits(read))));
    }
    @Test public void exactDeductionRequired() {
        assertTrue(Rules.payment(10_000_000L, 8_000_000L, 2_000_000));
        assertFalse(Rules.payment(10_000_000L, 9_000_000L, 2_000_000));
        assertFalse(Rules.payment(10_000_000L, null, 2_000_000));
    }
    @Test public void validatesImageBoundsWithoutOverflow() {
        assertTrue(Rules.rect(new int[]{0,0,100,100},100,100));
        assertFalse(Rules.rect(new int[]{90,90,20,20},100,100));
        assertFalse(Rules.rect(new int[]{Integer.MAX_VALUE,0,100,100},100,100));
        assertFalse(Rules.point(new int[]{100,0},100,100));
    }
}
