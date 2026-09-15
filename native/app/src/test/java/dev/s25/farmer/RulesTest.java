package dev.s25.farmer;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class RulesTest {
    @Test public void groupedNumbers() {
        for (String n : List.of("1234567", "1,234,567", "1 234 567", "1.234.567", "1\u202f234\u202f567")) assertEquals(Long.valueOf(1234567), Rules.number(n));
    }
    @Test public void troopCounterRejectsOtherTextAndMultipleRows() {
        assertEquals(Integer.valueOf(18), Rules.troopCount("x18"));
        assertEquals(Integer.valueOf(2), Rules.troopCount("× 2"));
        assertEquals(Integer.valueOf(0), Rules.troopCount("0"));
        for (String s : List.of("x1O", "18\n12", "101", "", "Level 18")) assertNull(Rules.troopCount(s));
    }
    @Test public void ambiguousNumbersCannotAuthorizeSpending() {
        for (String n : List.of("", "1O00000", "1.5M", "12\n34", "1 23 456", "-1", "200 gems", "1000 000", "1,234.567", "1 234\n567")) assertNull(n, Rules.number(n));
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
