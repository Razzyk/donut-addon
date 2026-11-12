package dev.donut.addon.expansion.search.seed;

import dev.donut.addon.expansion.search.seed.SeedScanCore.Token;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Bounded work/result handoff. It never stores a live world, chunk, or level reference. */
public final class SeedJobQueue<T> {
    public record Job(Token token, int chunkX, int chunkZ, Map<Long, int[]> motionTops) {
        public Job(Token token, int chunkX, int chunkZ) { this(token, chunkX, chunkZ, Map.of()); }
        public Job {
            Map<Long, int[]> copy = new HashMap<>();
            if (motionTops != null) motionTops.forEach((key, value) -> {
                if (key != null && value != null && value.length == 256) copy.put(key, value.clone());
            });
            motionTops = Map.copyOf(copy);
        }
        @Override public Map<Long, int[]> motionTops() {
            Map<Long, int[]> copy = new HashMap<>();
            motionTops.forEach((key, value) -> copy.put(key, value.clone()));
            return Map.copyOf(copy);
        }
    }
    public record Result<T>(Token token, int chunkX, int chunkZ, T value) {}

    private final int jobCapacity;
    private final int resultCapacity;
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private final ArrayDeque<Result<T>> results = new ArrayDeque<>();

    public SeedJobQueue(int jobCapacity, int resultCapacity) {
        if (jobCapacity < 1 || resultCapacity < 1) throw new IllegalArgumentException("queue capacity must be positive");
        this.jobCapacity = jobCapacity;
        this.resultCapacity = resultCapacity;
    }

    public synchronized boolean offer(Token token, int chunkX, int chunkZ) {
        return offer(token, chunkX, chunkZ, Map.of());
    }

    public synchronized boolean offer(Token token, int chunkX, int chunkZ, Map<Long, int[]> motionTops) {
        if (token == null || this.jobs.size() >= this.jobCapacity) return false;
        this.jobs.addLast(new Job(token, chunkX, chunkZ, motionTops));
        return true;
    }

    /** Removes stale work while searching for one current item. */
    public synchronized Job pollCurrent(SeedScanCore.GenerationGate gate) {
        while (!this.jobs.isEmpty()) {
            Job job = this.jobs.removeFirst();
            if (gate.accepts(job.token())) return job;
        }
        return null;
    }

    public synchronized void publish(Result<T> result, SeedScanCore.GenerationGate gate) {
        if (result == null || !gate.accepts(result.token())) return;
        if (this.results.size() >= this.resultCapacity) this.results.removeFirst();
        this.results.addLast(result);
    }

    public synchronized List<Result<T>> drainCurrent(SeedScanCore.GenerationGate gate, int maximum) {
        List<Result<T>> current = new ArrayList<>();
        while (maximum-- > 0 && !this.results.isEmpty()) {
            Result<T> result = this.results.removeFirst();
            if (gate.accepts(result.token())) current.add(result);
        }
        return List.copyOf(current);
    }

    public synchronized void clear() { this.jobs.clear(); this.results.clear(); }
    public synchronized int jobCount() { return this.jobs.size(); }
    public synchronized int resultCount() { return this.results.size(); }
}
