package dev.donut.addon.anubis.module.impl.combat.crystal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
/** Avoid further attacks briefly after a nearby player dies, giving their drops time to settle. */
public final class LootLatch {
    private static final Set<UUID> seenDead = new HashSet<>();
    private static long until;
    private static boolean killed;
    private static long updatedTick = Long.MIN_VALUE;
    private static Object level;
    private LootLatch() {}
    public static void update(Minecraft mc, LocalPlayer player) {
        if (level != mc.level) { level = mc.level; updatedTick = Long.MIN_VALUE; until = 0; killed = false; seenDead.clear(); }
        long tick = mc.level.getGameTime();
        if (tick == updatedTick) return;
        updatedTick = tick; killed = false;
        for (Player other : mc.level.players()) {
            if (other == player || player.distanceToSqr(other) > 36) continue;
            if (other.isDeadOrDying() && seenDead.add(other.getUUID())) { killed = true; until = tick + 20; }
            else if (!other.isDeadOrDying()) seenDead.remove(other.getUUID());
        }
    }
    public static boolean latched() { var level = Minecraft.getInstance().level; return level != null && level == LootLatch.level && level.getGameTime() < until; }
    public static boolean killedNow() { return killed; }
}
