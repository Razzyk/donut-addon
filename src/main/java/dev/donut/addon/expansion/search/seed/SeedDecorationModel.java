package dev.donut.addon.expansion.search.seed;

import java.util.List;
import java.util.function.BiPredicate;

/** Resolves possible feature outcomes without assuming an order for neighboring chunk writers. */
public final class SeedDecorationModel {
    public record Write(byte kind, boolean optional) {
        public Write { if (kind < 0 || kind > SeedScanCore.CLAY || kind == SeedScanCore.MISSING)
            throw new IllegalArgumentException("Invalid generated block kind"); }
    }
    public record Writer(long chunk, List<Write> writes) {
        public Writer { writes = List.copyOf(writes); }
    }
    public record Outcome(int kinds, boolean exact) {
        public byte certain() { return Integer.bitCount(kinds) == 1 ? (byte)Integer.numberOfTrailingZeros(kinds) : SeedScanCore.MISSING; }
        public boolean canBe(byte kind) { return (kinds & (1 << kind)) != 0; }
        public int familyMask() {
            int mask = 0;
            if (canBe(SeedScanCore.GRANITE)) mask |= 1;
            if (canBe(SeedScanCore.DIORITE)) mask |= 2;
            if (canBe(SeedScanCore.ANDESITE)) mask |= 4;
            if (canBe(SeedScanCore.GRAVEL)) mask |= 8;
            return mask;
        }
    }

    private SeedDecorationModel() {}

    public static Outcome resolve(byte terrain, List<Writer> writers, BiPredicate<Long, Long> before) {
        if (terrain < 0 || terrain > SeedScanCore.CLAY) throw new IllegalArgumentException("Invalid terrain kind");
        writers = List.copyOf(writers);
        if (writers.stream().map(Writer::chunk).distinct().count() != writers.size())
            throw new IllegalArgumentException("One ordered trace required per writer");
        long optional = writers.stream().flatMap(w -> w.writes().stream()).filter(Write::optional).count();
        if (writers.size() > 4 || optional > 10) {
            int union = 1 << terrain;
            for (var writer : writers) for (var write : writer.writes()) union |= 1 << write.kind();
            return new Outcome(union, false);
        }
        int kinds = explore(writers, before, 0, 1 << terrain);
        // Contradictory ordering witnesses cannot manufacture certainty.
        if (kinds == 0) kinds = explore(writers, (a, b) -> false, 0, 1 << terrain);
        return new Outcome(kinds, true);
    }

    private static int explore(List<Writer> writers, BiPredicate<Long, Long> before, int used, int states) {
        if (used == (1 << writers.size()) - 1) return states;
        int union = 0;
        for (int next = 0; next < writers.size(); next++) {
            if ((used & (1 << next)) != 0) continue;
            boolean blocked = false;
            for (int predecessor = 0; predecessor < writers.size(); predecessor++) {
                if (predecessor != next && (used & (1 << predecessor)) == 0 &&
                    before.test(writers.get(predecessor).chunk(), writers.get(next).chunk())) { blocked = true; break; }
            }
            if (blocked) continue;
            int after = states;
            for (var write : writers.get(next).writes()) {
                int updated = 0;
                for (byte kind = 0; kind <= SeedScanCore.CLAY; kind++) {
                    if ((after & (1 << kind)) == 0) continue;
                    if (write.optional()) updated |= 1 << kind;
                    updated |= 1 << (replaceable(kind) ? write.kind() : kind);
                }
                after = updated;
            }
            union |= explore(writers, before, used | (1 << next), after);
        }
        return union;
    }

    private static boolean replaceable(byte kind) {
        return kind == SeedScanCore.STONE || kind == SeedScanCore.TUFF || kind == SeedScanCore.GRANITE ||
            kind == SeedScanCore.DIORITE || kind == SeedScanCore.ANDESITE;
    }
}
