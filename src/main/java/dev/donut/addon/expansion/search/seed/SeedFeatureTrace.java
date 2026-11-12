package dev.donut.addon.expansion.search.seed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded immutable write traces captured from native placed-feature execution. */
final class SeedFeatureTrace {
    static final int MAX_WRITES = 180_000;

    record Probe(long position, long firstOrigin, byte firstKind, long secondOrigin, byte secondKind) {}
    record Event(long position, byte kind, boolean optional) {}
    record Feature(long origin, int stage, int index, SeedRandomBranch.State randomStart,
                   List<Event> writes, List<Long> readPositions) {
        Feature(long origin, int stage, int index, SeedRandomBranch.State randomStart, List<Event> writes) {
            this(origin, stage, index, randomStart, writes, List.of());
        }
        Feature { writes = List.copyOf(writes); readPositions = List.copyOf(readPositions); }
    }
    record Writer(long origin, List<SeedDecorationModel.Write> writes) {
        Writer { writes = List.copyOf(writes); }
    }

    private record FeatureKey(long origin, int stage, int index) {}
    private static final class MutableFeature {
        final FeatureKey key;
        final SeedRandomBranch.State randomStart;
        final ArrayList<Event> writes = new ArrayList<>();
        final Set<Long> reads = new HashSet<>();
        MutableFeature(FeatureKey key, SeedRandomBranch.State randomStart) {
            this.key = key;
            this.randomStart = randomStart;
        }
    }

    static final class Builder {
        private final int writeLimit;
        private final LinkedHashMap<FeatureKey, MutableFeature> features = new LinkedHashMap<>();
        private final Map<Long, Byte> terrain = new HashMap<>();
        private MutableFeature active;
        private int writeCount;

        Builder() { this(MAX_WRITES); }
        Builder(int writeLimit) {
            if (writeLimit < 1) throw new IllegalArgumentException("Positive trace limit required");
            this.writeLimit = writeLimit;
        }

        void begin(long origin, int stage, int index, SeedRandomBranch.State randomStart) {
            if (active != null) throw new IllegalStateException("Nested placed-feature trace");
            FeatureKey key = new FeatureKey(origin, stage, index);
            active = features.computeIfAbsent(key, ignored -> new MutableFeature(key, randomStart));
        }

        void write(long position, byte beforeKind, byte outputKind, boolean optional) {
            if (active == null) throw new IllegalStateException("Feature write outside trace scope");
            if (outputKind < SeedScanCore.GRANITE || outputKind > SeedScanCore.CLAY || outputKind == SeedScanCore.STRUCTURE ||
                outputKind == SeedScanCore.MODIFIED || outputKind == SeedScanCore.MISSING) return;
            if (++writeCount > writeLimit) throw new IllegalStateException("Native feature trace exceeded its safety limit");
            terrain.putIfAbsent(position, beforeKind);
            active.writes.add(new Event(position, outputKind, optional));
        }

        void reads(Set<Long> positions) {
            if (active == null) throw new IllegalStateException("Native read outside trace scope");
            active.reads.addAll(positions);
        }

        void end() { active = null; }

        Trace build() {
            if (active != null) throw new IllegalStateException("Unclosed placed-feature trace");
            List<Feature> frozen = features.values().stream()
                .filter(feature -> !feature.writes.isEmpty() || !feature.reads.isEmpty())
                .sorted(Comparator.comparingLong((MutableFeature feature) -> feature.key.origin())
                    .thenComparingInt(feature -> feature.key.stage()).thenComparingInt(feature -> feature.key.index()))
                .map(feature -> new Feature(feature.key.origin(), feature.key.stage(), feature.key.index(),
                    feature.randomStart, feature.writes, feature.reads.stream().sorted().toList()))
                .toList();
            return new Trace(frozen, terrain);
        }
    }

    static final class Trace {
        private final List<Feature> features;
        private final Map<Long, Byte> terrain;
        private final Map<Long, List<Writer>> writersAt;

        private Trace(List<Feature> features, Map<Long, Byte> terrain) {
            this.features = List.copyOf(features);
            this.terrain = Map.copyOf(terrain);
            Map<Long, Map<Long, List<SeedDecorationModel.Write>>> mutable = new HashMap<>();
            for (Feature feature : features) {
                for (Event event : feature.writes()) {
                    mutable.computeIfAbsent(event.position(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(feature.origin(), ignored -> new ArrayList<>())
                        .add(new SeedDecorationModel.Write(event.kind(), event.optional()));
                }
            }
            Map<Long, List<Writer>> frozen = new HashMap<>();
            mutable.forEach((position, byOrigin) -> {
                List<Writer> writers = byOrigin.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new Writer(entry.getKey(), entry.getValue())).toList();
                frozen.put(position, writers);
            });
            this.writersAt = Map.copyOf(frozen);
        }

        List<Feature> features() { return features; }
        Set<Long> positions() { return terrain.keySet(); }
        List<Long> readPositions() { return features.stream().flatMap(feature -> feature.readPositions().stream()).distinct().toList(); }
        Feature feature(long origin, int stage, int index) {
            return features.stream().filter(item -> item.origin() == origin && item.stage() == stage && item.index() == index)
                .findFirst().orElse(null);
        }
        byte terrain(long position) { return terrain.getOrDefault(position, SeedScanCore.MISSING); }
        List<Writer> writers(long position) { return writersAt.getOrDefault(position, List.of()); }

        SeedDecorationModel.Outcome outcome(long position, SeedWriterOrder order) {
            byte base = terrain(position);
            if (base == SeedScanCore.MISSING) return new SeedDecorationModel.Outcome(1 << SeedScanCore.MISSING, false);
            List<Writer> writers = writers(position);
            return SeedDecorationModel.resolve(base,
                writers.stream().map(writer -> new SeedDecorationModel.Writer(writer.origin(), writer.writes())).toList(),
                order::precedes);
        }

        Probe probe(long position) {
            List<Writer> writers = writers(position);
            if (writers.size() != 2) return null;
            Writer first = writers.get(0), second = writers.get(1);
            if (first.writes().isEmpty() || second.writes().isEmpty()) return null;
            SeedDecorationModel.Write a = first.writes().get(first.writes().size() - 1);
            SeedDecorationModel.Write b = second.writes().get(second.writes().size() - 1);
            if (a.optional() || b.optional() || a.kind() == b.kind() || !family(a.kind()) || !family(b.kind())) return null;
            return new Probe(position, first.origin(), a.kind(), second.origin(), b.kind());
        }
    }

    private static boolean family(byte kind) {
        return kind == SeedScanCore.GRANITE || kind == SeedScanCore.DIORITE ||
            kind == SeedScanCore.ANDESITE || kind == SeedScanCore.GRAVEL;
    }

    static List<SeedWriterOrder.Witness> observe(List<Probe> probes, SeedScanCore.Snapshot snapshot) {
        ArrayList<SeedWriterOrder.Witness> witnesses = new ArrayList<>();
        for (Probe probe : probes) {
            byte visible = snapshot.at(SeedScanCore.unpackX(probe.position()), SeedScanCore.unpackY(probe.position()),
                SeedScanCore.unpackZ(probe.position()));
            if (visible == probe.firstKind()) {
                witnesses.add(new SeedWriterOrder.Witness(probe.position(), probe.secondOrigin(), probe.firstOrigin()));
            } else if (visible == probe.secondKind()) {
                witnesses.add(new SeedWriterOrder.Witness(probe.position(), probe.firstOrigin(), probe.secondOrigin()));
            }
        }
        return List.copyOf(witnesses);
    }

    private SeedFeatureTrace() {}
}
