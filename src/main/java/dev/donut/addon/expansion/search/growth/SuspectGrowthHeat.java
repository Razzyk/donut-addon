package dev.donut.addon.expansion.search.growth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maintains per-signal packet heat and per-signal vetoes around scanned chunks. */
public final class SuspectGrowthHeat {
    public static final int KIND_COUNT = 7;
    public record Key(int x, int z) {}
    public record Score(Key key, int value) {}
    private final int capacity;
    private final LinkedHashMap<Key, Profile> profiles = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Key, int[]> heat = new HashMap<>();
    private final Map<Key, int[]> veto = new HashMap<>();
    private int spreadRadius;

    public SuspectGrowthHeat(int capacity, int spreadRadius) {
        if (capacity < 1 || spreadRadius < 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.spreadRadius = spreadRadius;
    }

    public void setSpreadRadius(int radius) {
        if (radius < 0) throw new IllegalArgumentException("radius must be non-negative");
        if (this.spreadRadius == radius) return;
        for (Map.Entry<Key, Profile> entry : this.profiles.entrySet()) {
            this.apply(entry.getKey(), entry.getValue(), -1);
        }
        this.spreadRadius = radius;
        for (Map.Entry<Key, Profile> entry : this.profiles.entrySet()) {
            this.apply(entry.getKey(), entry.getValue(), 1);
        }
    }

    public void replace(Key key, int[] grown, int vetoMask) {
        if (grown == null || grown.length != KIND_COUNT) throw new IllegalArgumentException("expected seven signal counts");
        Profile next = new Profile(grown.clone(), vetoMask);
        Profile previous = this.profiles.put(key, next);
        if (previous != null) this.apply(key, previous, -1);
        this.apply(key, next, 1);
        while (this.profiles.size() > this.capacity) {
            Map.Entry<Key, Profile> eldest = this.profiles.entrySet().iterator().next();
            this.apply(eldest.getKey(), eldest.getValue(), -1);
            this.profiles.remove(eldest.getKey());
        }
    }

    public void remove(Key key) {
        Profile previous = this.profiles.remove(key);
        if (previous != null) this.apply(key, previous, -1);
    }
    public void clear() { this.profiles.clear(); this.heat.clear(); this.veto.clear(); }
    public int size() { return this.profiles.size(); }

    public List<Score> scores() {
        ArrayList<Score> result = new ArrayList<>();
        for (Map.Entry<Key, int[]> entry : this.heat.entrySet()) {
            int[] blocked = this.veto.get(entry.getKey());
            int score = 0;
            for (int kind = 0; kind < KIND_COUNT; kind++) {
                if (blocked == null || blocked[kind] == 0) score += entry.getValue()[kind];
            }
            if (score > 0) result.add(new Score(entry.getKey(), score));
        }
        return List.copyOf(result);
    }

    public boolean vetoed(Key key, int kind) {
        int[] values = this.veto.get(key);
        return values != null && kind >= 0 && kind < KIND_COUNT && values[kind] > 0;
    }

    /** Signal-specific heat after spread, or zero when that kind is vetoed at the requested chunk. */
    public int heat(Key key, int kind) {
        if (kind < 0 || kind >= KIND_COUNT || this.vetoed(key, kind)) return 0;
        int[] values = this.heat.get(key);
        return values == null ? 0 : values[kind];
    }

    private void apply(Key center, Profile profile, int direction) {
        for (int dx = -this.spreadRadius; dx <= this.spreadRadius; dx++) for (int dz = -this.spreadRadius; dz <= this.spreadRadius; dz++) {
            Key area = new Key(center.x() + dx, center.z() + dz);
            int[] values = this.heat.get(area);
            int[] denied = this.veto.get(area);
            if (direction > 0) {
                if (values == null) this.heat.put(area, values = new int[KIND_COUNT]);
                if (denied == null) this.veto.put(area, denied = new int[KIND_COUNT]);
            }
            if (values != null) for (int kind = 0; kind < KIND_COUNT; kind++) {
                values[kind] += direction * Math.max(0, profile.grown[kind]);
            }
            if (denied != null) for (int kind = 0; kind < KIND_COUNT; kind++) {
                if ((profile.vetoMask & 1 << kind) != 0) denied[kind] += direction;
            }
            if (direction < 0) {
                if (values != null && empty(values)) this.heat.remove(area);
                if (denied != null && empty(denied)) this.veto.remove(area);
            }
        }
    }

    private static boolean empty(int[] values) {
        for (int value : values) if (value != 0) return false;
        return true;
    }

    private record Profile(int[] grown, int vetoMask) {}
}
