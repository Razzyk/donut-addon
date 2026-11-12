package dev.donut.addon.expansion.utility;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DonutAliasRulesTest {
    @Test
    void protectsWholeNamesWithoutChangingLargerWords() {
        assertEquals("You met Player; You isn't the same as Razz2.",
            DonutAliasRules.INSTANCE.replaceText("Razz met PlayerOne; Razz isn't the same as Razz2.",
                Map.of("Razz", "You", "PlayerOne", "Player")));
    }

    @Test
    void parsesAndDeduplicatesExplicitAccountNames() {
        assertEquals(java.util.List.of("First_Name", "second", "Third"),
            DonutAliasRules.INSTANCE.parseNames("First_Name, second; FIRST_NAME\nThird"));
        assertTrue(DonutAliasRules.INSTANCE.parseNames("ab, bad-name, overlylongaccountname").isEmpty());
    }
}
