package dev.donut.addon.expansion.search.seed;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Replaceable chunk-local witnesses; three unanimous positions establish a writer order. */
public final class SeedWriterOrder {
    public record Witness(long position, long earlier, long later) {}
    private record Pair(long first, long second) {
        static Pair of(long a, long b) { return a < b ? new Pair(a,b) : new Pair(b,a); }
    }
    private record PositionPair(long position, Pair pair) {}
    private final LinkedHashMap<Long, Map<Pair, int[]>> byChunk = new LinkedHashMap<>();
    private final Map<Pair, int[]> totals = new HashMap<>();

    public boolean replace(long sourceChunk, List<Witness> witnesses) {
        Map<Pair, Integer> before = decisions();
        remove(sourceChunk);
        Map<Pair, int[]> fresh = new HashMap<>();
        Map<PositionPair,Integer> directions = new HashMap<>();
        for (var witness : witnesses) {
            if (witness.earlier() == witness.later()) continue;
            Pair pair = Pair.of(witness.earlier(), witness.later());
            directions.merge(new PositionPair(witness.position(),pair), witness.earlier() == pair.first() ? 1 : 2, (a,b) -> a | b);
            // Truncation could hide opposing evidence, so discard this entire source on overflow.
            if (directions.size() > 20_000) return !before.equals(decisions());
        }
        directions.forEach((positionPair,mask) -> {
            if (mask != 3) fresh.computeIfAbsent(positionPair.pair(), ignored -> new int[2])[mask == 1 ? 0 : 1]++;
        });
        byChunk.put(sourceChunk,fresh);
        adjust(fresh,1);
        while (byChunk.size() > 4096) remove(byChunk.keySet().iterator().next());
        return !before.equals(decisions());
    }

    public boolean precedes(long earlier, long later) {
        if (earlier == later) return false;
        Pair pair = Pair.of(earlier,later);
        int[] votes = totals.get(pair);
        if (votes == null) return false;
        int direction = earlier == pair.first() ? 0 : 1;
        return votes[direction] >= 3 && votes[1-direction] == 0;
    }

    public void remove(long sourceChunk) {
        var previous = byChunk.remove(sourceChunk);
        if (previous != null) adjust(previous,-1);
    }
    public void clear() { byChunk.clear(); totals.clear(); }
    public void retainSources(Set<Long> kept) {
        for (long source : List.copyOf(byChunk.keySet())) if (!kept.contains(source)) remove(source);
    }

    private void adjust(Map<Pair,int[]> changes, int direction) {
        changes.forEach((pair,counts) -> {
            var aggregate = totals.computeIfAbsent(pair, ignored -> new int[2]);
            aggregate[0] += counts[0]*direction;
            aggregate[1] += counts[1]*direction;
            if (aggregate[0] == 0 && aggregate[1] == 0) totals.remove(pair);
        });
    }

    private Map<Pair, Integer> decisions() {
        Map<Pair, Integer> out = new HashMap<>();
        for (Pair pair : totals.keySet()) out.put(pair, compare(pair.first(), pair.second()));
        return out;
    }

    private int compare(long first, long second) {
        if (precedes(first, second)) return 1;
        if (precedes(second, first)) return 2;
        return 0;
    }
}
