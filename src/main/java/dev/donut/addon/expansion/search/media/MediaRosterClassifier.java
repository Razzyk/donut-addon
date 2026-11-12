package dev.donut.addon.expansion.search.media;

import java.util.UUID;

/** Applies the roster visibility rule to a name already found on a media team. */
public final class MediaRosterClassifier {
    private MediaRosterClassifier() {}

    /** Ignore initial profile-list packets; only a later game-mode update is identity evidence. */
    public static boolean shouldQueueGameModeUpdate(boolean updatesGameMode, boolean addsPlayer, UUID profileId) {
        return updatesGameMode && !addsPlayer && profileId != null;
    }

    /**
     * Listed accounts are normally omitted because team membership can be stale. An account
     * confirmed by a player-info game-mode update is authoritative evidence and stays visible.
     */
    public static boolean shouldInclude(String member, String self, boolean listed, boolean proven) {
        if (member == null || member.isBlank()) return false;
        if (self != null && member.equalsIgnoreCase(self)) return false;
        return !listed || proven;
    }
}
