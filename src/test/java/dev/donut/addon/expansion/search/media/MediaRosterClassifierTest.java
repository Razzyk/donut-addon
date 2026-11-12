package dev.donut.addon.expansion.search.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.UUID;

class MediaRosterClassifierTest {
    @Test
    void onlyQueuesNonInitialGameModeUpdatesWithAProfileId() {
        UUID id = UUID.randomUUID();
        assertTrue(MediaRosterClassifier.shouldQueueGameModeUpdate(true, false, id));
        assertFalse(MediaRosterClassifier.shouldQueueGameModeUpdate(false, false, id));
        assertFalse(MediaRosterClassifier.shouldQueueGameModeUpdate(true, true, id));
        assertFalse(MediaRosterClassifier.shouldQueueGameModeUpdate(true, false, null));
    }

    @Test
    void excludesSelfEvenWhenTheSelfEntryIsProven() {
        assertFalse(MediaRosterClassifier.shouldInclude("Razzy", "rAzZy", true, true));
    }

    @Test
    void omitsVisibleUnprovenTeamMembersButKeepsAuthoritativelyProvenMedia() {
        assertFalse(MediaRosterClassifier.shouldInclude("VisibleGuest", "Local", true, false));
        assertTrue(MediaRosterClassifier.shouldInclude("KnownMedia", "Local", true, true));
    }

    @Test
    void retainsUnlistedTeamMembersAndRejectsMissingNames() {
        assertTrue(MediaRosterClassifier.shouldInclude("HiddenMedia", "Local", false, false));
        assertFalse(MediaRosterClassifier.shouldInclude(null, "Local", false, false));
        assertFalse(MediaRosterClassifier.shouldInclude("  ", "Local", false, false));
    }
}
