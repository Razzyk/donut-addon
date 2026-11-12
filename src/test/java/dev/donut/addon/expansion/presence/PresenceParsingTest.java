package dev.donut.addon.expansion.presence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PresenceParsingTest {
    @Test
    void normalizesFormattingAndRecognizesLeaveTextWithBoundaries() {
        assertEquals("bob left the game", PresenceText.INSTANCE.normalized("§cBób left the game"));
        assertTrue(PresenceText.INSTANCE.looksLikeDeparture("Bob left the game.", "Bob"));
        assertFalse(PresenceText.INSTANCE.looksLikeDeparture("Bobby left the game.", "Bob"));
        assertFalse(PresenceText.INSTANCE.looksLikeDeparture("Bob might quit later, but Alice left.", "Bob"));
        assertTrue(PresenceText.INSTANCE.isUsernameLike("Helper.Name 2"));
        assertFalse(PresenceText.INSTANCE.isUsernameLike("helper/name"));
    }

    @Test
    void recognizesRanksMarkersKnownNamesAndTeamOrder() {
        DonutStaffMatcher matcher = new DonutStaffMatcher();
        assertEquals("Admin", matcher.classify("Somebody", null, "§cADMIN §rSomebody").getLabel());
        assertEquals("03", matcher.classify("Somebody", "03-Somebody-false").getLabel());
        assertEquals("Staff", matcher.classify("auzzitech", null).getLabel());
        assertNull(matcher.classify("ordinaryPlayer", null, "ordinaryPlayer"));
    }
}
