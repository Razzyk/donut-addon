package dev.donut.addon.expansion.search.seed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps established evidence through partial unloads and remembers flagged chunks outside view. */
public final class SeedVerdictLedger {
    private record Identity(long owner, byte kind) {}
    public record Finding(long owner, byte kind, SeedScanCore.Verdict verdict) {}
    public record ChunkEvidence(long chunk, List<Finding> findings) {
        public ChunkEvidence { findings = List.copyOf(findings); }
        public boolean flagged() { return findings.stream().anyMatch(f -> f.verdict() == SeedScanCore.Verdict.ALERT); }
    }
    private final int capacity;
    private final LinkedHashMap<Long, ChunkEvidence> results = new LinkedHashMap<>();

    public SeedVerdictLedger(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive capacity required");
        this.capacity = capacity;
    }

    public ChunkEvidence get(long chunk) { return results.get(chunk); }
    public List<ChunkEvidence> flagged() { return results.values().stream().filter(ChunkEvidence::flagged).toList(); }
    public void clear() { results.clear(); }

    /** Null means unknown evidence cannot replace the previous result yet. */
    public ChunkEvidence reconcile(long chunk, List<Finding> observed) {
        ChunkEvidence previous = results.get(chunk);
        Map<Identity, Finding> old = new HashMap<>();
        if (previous != null) for (Finding finding : previous.findings()) {
            if (finding.verdict() != SeedScanCore.Verdict.INCOMPLETE) old.put(new Identity(finding.owner(), finding.kind()), finding);
        }
        List<Finding> merged = new ArrayList<>(observed.size());
        boolean unknown = false;
        boolean alert = false;
        for (Finding finding : observed) {
            if (finding.verdict() == SeedScanCore.Verdict.INCOMPLETE) {
                Finding known = old.get(new Identity(finding.owner(), finding.kind()));
                if (known == null) unknown = true;
                else finding = known;
            }
            merged.add(finding);
            alert |= finding.verdict() == SeedScanCore.Verdict.ALERT;
        }
        if (unknown && !alert) return null;
        ChunkEvidence result = new ChunkEvidence(chunk, merged);
        results.put(chunk, result);
        while (results.size() > capacity) {
            Long discard = results.entrySet().stream().filter(e -> !e.getValue().flagged())
                .map(Map.Entry::getKey).findFirst().orElse(results.keySet().iterator().next());
            results.remove(discard);
        }
        return result;
    }

    public void retainArea(Set<Long> area) {
        results.entrySet().removeIf(entry -> !area.contains(entry.getKey()) && !entry.getValue().flagged());
    }
}
