package dev.donut.addon.expansion.presence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PresenceStateTest {
    @Test
    void waitsForTrustBeforeAcceptingHiddenListEvidence() {
        DonutVanishState state = new DonutVanishState();
        UUID uuid = UUID.randomUUID();
        state.observeListed(List.of(new DonutVanishState.Candidate(uuid, "HelperName", false)), 10_000L);
        assertNull(state.observeHidden(uuid, 11_000L));
        DonutVanishState.Alert alert = state.observeHidden(uuid, 11_600L);
        assertNotNull(alert);
        assertEquals("HelperName", alert.getName());
        assertEquals(100, alert.getWeight());
    }

    @Test
    void leaveMessagesCancelTentativeTabDisappearances() {
        DonutVanishState state = new DonutVanishState();
        UUID uuid = UUID.randomUUID();
        state.observeListed(List.of(new DonutVanishState.Candidate(uuid, "HelperName", false)), 1_000L);
        state.observeRemoval(uuid, 25, 3_000L, 1);
        assertTrue(state.hasTentativeRemoval(uuid));
        assertEquals(Set.of(uuid), state.observeLeaveMessage("HelperName left the game", 3_100L));
        assertFalse(state.hasTentativeRemoval(uuid));
        assertTrue(state.isTrusted(uuid, 3_200L));
    }

    @Test
    void edgeTrackerOnlyReportsNewNamesAndResetsOnWorldChange() {
        NearbyPlayerEdges edges = new NearbyPlayerEdges();
        Object firstWorld = new Object();
        assertEquals(List.of("Alex"), edges.update(firstWorld, List.of("Alex")));
        assertTrue(edges.update(firstWorld, List.of("alex", "Bee" )).contains("Bee"));
        assertTrue(edges.update(firstWorld, List.of()).isEmpty());
        assertEquals(List.of("Alex"), edges.update(new Object(), List.of("Alex")));
    }
}
