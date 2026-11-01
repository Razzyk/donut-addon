package dev.donut.addon.anubis.module.impl.combat.crystal;
public final class CombatHelperMath {
    private CombatHelperMath() {}
    /** Round trip in ticks plus two processing ticks, bounded to avoid indefinite stalls. */
    public static int confirmTicks(int latencyMs) {
        long latency = Math.max(0L, (long) latencyMs);
        return (int) Math.max(4, Math.min(40, (latency + 49) / 50 + 2));
    }
}
