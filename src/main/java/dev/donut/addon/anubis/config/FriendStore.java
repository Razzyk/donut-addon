package dev.donut.addon.anubis.config;

import net.ccbluex.liquidbounce.features.misc.FriendManager;

/** The macros share LiquidBounce's existing friend list. */
public final class FriendStore {
    private static final FriendStore INSTANCE = new FriendStore();
    public static FriendStore getInstance() { return INSTANCE; }
    public boolean isFriend(String name) {
        if (name == null) return false;
        return FriendManager.INSTANCE.getFriends().stream()
            .anyMatch(friend -> friend.getName().equalsIgnoreCase(name));
    }
}
