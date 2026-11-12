package dev.donut.addon.expansion.utility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DonutPrivacyMathTest {
    @Test
    void emptyStatusValuesBecomeStablePseudodataWithoutLeakingSessionNumbers() {
        String first = DonutPrivacyMath.pseudoValue("money", 73L);
        assertEquals(first, DonutPrivacyMath.pseudoValue("money", 73L));
        assertNotEquals(first, DonutPrivacyMath.pseudoValue("money", 74L));
        assertTrue(first.matches("[1-9][0-9]{2,5}"));
        assertTrue(DonutPrivacyMath.pseudoName(73L).matches("Player-[0-9]{3}"));
    }

    @Test
    void numberScramblingPreservesPunctuationAndDigitWidthsDeterministically() {
        String original = "Balance $12,345.67; kills 009";
        String shown = DonutPrivacyMath.obscureNumbers(original, 19L);
        assertEquals(shown, DonutPrivacyMath.obscureNumbers(original, 19L));
        assertNotEquals(original, shown);
        assertEquals(original.replaceAll("[0-9]", "#"), shown.replaceAll("[0-9]", "#"));
        assertTrue(shown.startsWith("Balance $") && shown.contains(",") && shown.contains("."));
    }
}
