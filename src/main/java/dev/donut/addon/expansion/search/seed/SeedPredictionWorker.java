package dev.donut.addon.expansion.search.seed;

import dev.donut.addon.expansion.search.seed.SeedJobQueue.Result;
import dev.donut.addon.expansion.search.seed.SeedScanCore.PredictedCluster;
import dev.donut.addon.expansion.search.seed.SeedScanCore.Token;
import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.locks.LockSupport;

/** Single-owner background runner for immutable coordinate jobs and generated chunk candidates. */
public final class SeedPredictionWorker implements AutoCloseable {
    public record Output(List<PredictedCluster> clusters, String error, int[] terrainHeights,
                         Map<Long, int[]> motionTops, int replayPass, SeedFeatureTrace.Trace featureTrace) {
        public Output(List<PredictedCluster> clusters, String error) { this(clusters, error, null, Map.of(), 0, null); }
        public Output(List<PredictedCluster> clusters, String error, int[] terrainHeights) {
            this(clusters, error, terrainHeights, Map.of(), 0, null);
        }
        public Output(List<PredictedCluster> clusters, String error, int[] terrainHeights, Map<Long, int[]> motionTops) {
            this(clusters, error, terrainHeights, motionTops, 0, null);
        }
        public Output {
            clusters = clusters == null ? List.of() : List.copyOf(clusters);
            terrainHeights = terrainHeights == null ? null : terrainHeights.clone();
            motionTops = copyMotion(motionTops);
        }
        @Override public int[] terrainHeights() { return terrainHeights == null ? null : terrainHeights.clone(); }
        @Override public Map<Long, int[]> motionTops() { return copyMotion(motionTops); }
    }
    public record Inspection(Token token, long chunk, List<PredictedCluster> clusters,
                              SeedScanCore.Snapshot snapshot, Map<Long, Long> revisions, int minimumChanges,
                              SeedGroundEvidence.Comparison ground, List<SeedFeatureTrace.Probe> writerProbes,
                              Map<Long, int[]> motionTops, int replayPass, SeedFeatureTrace.Trace featureTrace) {
        public Inspection(Token token, long chunk, List<PredictedCluster> clusters, SeedScanCore.Snapshot snapshot,
                          Map<Long, Long> revisions, int minimumChanges) {
            this(token, chunk, clusters, snapshot, revisions, minimumChanges, null, List.of(), Map.of(), 2, null);
        }
        public Inspection(Token token, long chunk, List<PredictedCluster> clusters, SeedScanCore.Snapshot snapshot,
                          Map<Long, Long> revisions, int minimumChanges, SeedGroundEvidence.Comparison ground) {
            this(token, chunk, clusters, snapshot, revisions, minimumChanges, ground, List.of(), Map.of(), 2, null);
        }
        public Inspection(Token token, long chunk, List<PredictedCluster> clusters, SeedScanCore.Snapshot snapshot,
                          Map<Long, Long> revisions, int minimumChanges, SeedGroundEvidence.Comparison ground,
                          List<SeedFeatureTrace.Probe> writerProbes, Map<Long, int[]> motionTops) {
            this(token, chunk, clusters, snapshot, revisions, minimumChanges, ground, writerProbes,
                motionTops, 2, null);
        }
        public Inspection {
            java.util.Objects.requireNonNull(token);
            java.util.Objects.requireNonNull(snapshot);
            clusters = List.copyOf(clusters);
            revisions = Map.copyOf(revisions);
            writerProbes = writerProbes == null ? List.of() : List.copyOf(writerProbes);
            motionTops = copyMotion(motionTops);
        }
        @Override public Map<Long, int[]> motionTops() { return copyMotion(motionTops); }
    }
    public record Evidence(Token token, long chunk, List<SeedVerdictLedger.Finding> findings,
                           Map<Long, Long> revisions, SeedGroundEvidence.Comparison ground) {
        public Evidence(Token token, long chunk, List<SeedVerdictLedger.Finding> findings, Map<Long, Long> revisions) {
            this(token, chunk, findings, revisions, null);
        }
        public Evidence { findings = List.copyOf(findings); revisions = Map.copyOf(revisions); }
    }

    private final SeedScanCore.GenerationGate gate = new SeedScanCore.GenerationGate();
    private final SeedJobQueue<Output> queue = new SeedJobQueue<>(16, 96);
    private final ArrayDeque<Inspection> inspections = new ArrayDeque<>();
    private final ArrayDeque<Evidence> evidence = new ArrayDeque<>();
    private final Thread thread;
    private boolean started;
    private volatile boolean running = true;
    // Constructed, accessed, and discarded only by the worker thread.
    private VanillaSeedWorldgen backend;
    private long backendSeed;
    private long backendGeneration = -1L;
    private final SeedWriterOrder writerOrder = new SeedWriterOrder();
    private long writerOrderGeneration = -1L;

    public SeedPredictionWorker() {
        this.thread = new Thread(this::work, "Donut seed worldgen");
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.MIN_PRIORITY);
    }

    public synchronized Token begin(long worldSession, long seed) {
        if (!this.running) throw new IllegalStateException("seed worker is closed");
        Token token = this.gate.begin(worldSession, seed);
        this.queue.clear();
        this.inspections.clear();
        this.evidence.clear();
        if (!this.started) {
            this.started = true;
            this.thread.start();
        }
        LockSupport.unpark(this.thread);
        return token;
    }

    public synchronized void invalidate() {
        this.gate.invalidate();
        this.queue.clear();
        this.inspections.clear();
        this.evidence.clear();
        LockSupport.unpark(this.thread);
    }

    public boolean submit(Token token, int chunkX, int chunkZ) {
        return submit(token, chunkX, chunkZ, Map.of());
    }

    public boolean submit(Token token, int chunkX, int chunkZ, Map<Long, int[]> motionTops) {
        boolean accepted = this.gate.accepts(token) && this.queue.offer(token, chunkX, chunkZ, motionTops);
        if (accepted) LockSupport.unpark(this.thread);
        return accepted;
    }

    public List<Result<Output>> drain(int maximum) { return this.queue.drainCurrent(this.gate, maximum); }

    public boolean accepts(Token token) { return this.gate.accepts(token); }

    /** Immutable snapshots are judged off-thread, on the same bounded worker as generation. */
    public synchronized boolean submitInspection(Inspection inspection) {
        if (!this.gate.accepts(inspection.token()) || inspections.size() >= 2) return false;
        inspections.addLast(inspection);
        LockSupport.unpark(thread);
        return true;
    }

    public synchronized List<Evidence> drainEvidence(int maximum) {
        List<Evidence> out = new ArrayList<>();
        while (maximum-- > 0 && !evidence.isEmpty()) {
            Evidence next = evidence.removeFirst();
            if (gate.accepts(next.token())) out.add(next);
        }
        return List.copyOf(out);
    }

    private synchronized Inspection nextInspection() {
        while (!inspections.isEmpty()) {
            Inspection next = inspections.removeFirst();
            if (gate.accepts(next.token())) return next;
        }
        return null;
    }

    private void inspect(Inspection inspection) {
        boolean orderChanged = writerOrder.replace(inspection.chunk(),
            SeedFeatureTrace.observe(inspection.writerProbes(), inspection.snapshot()));
        boolean incompletePrediction = inspection.clusters().stream().anyMatch(cluster -> !cluster.generationComplete());
        if (inspection.replayPass() < 2 && (inspection.replayPass() == 0 || orderChanged || incompletePrediction)) {
            if (!gate.accepts(inspection.token()) || backend == null || backendGeneration != inspection.token().generation()) return;
            List<PredictedCluster> clusters = backend.predict((int)(inspection.chunk() >> 32), (int)inspection.chunk(),
                () -> !gate.accepts(inspection.token()), writerOrder, inspection.motionTops(),
                inspection.featureTrace(), inspection.snapshot());
            if (!gate.accepts(inspection.token())) return;
            queue.publish(new Result<>(inspection.token(), (int)(inspection.chunk() >> 32), (int)inspection.chunk(),
                new Output(clusters, null, backend.recentTerrainHeights(), inspection.motionTops(),
                    inspection.replayPass() + 1, backend.recentFeatureTrace())), gate);
            return;
        }
        List<SeedVerdictLedger.Finding> findings = new ArrayList<>();
        for (PredictedCluster source : inspection.clusters()) {
            if (!gate.accepts(inspection.token())) return;
            PredictedCluster cluster = source;
            if (orderChanged && inspection.replayPass() >= 2) {
                cluster = new PredictedCluster(source.kind(), source.owner(), source.cells(), source.shell(),
                    source.shellModel(), false, source.certainCells(), source.shellFlags(), source.writerProbes());
            }
            findings.add(new SeedVerdictLedger.Finding(cluster.owner(), cluster.kind(),
                SeedScanCore.judge(cluster, inspection.snapshot(), inspection.minimumChanges())));
        }
        synchronized (this) {
            if (!gate.accepts(inspection.token())) return;
            if (evidence.size() >= 8) evidence.removeFirst();
            evidence.addLast(new Evidence(inspection.token(), inspection.chunk(), findings, inspection.revisions(), inspection.ground()));
        }
    }

    private void work() {
        while (this.running) {
            Inspection inspection = nextInspection();
            if (inspection != null) {
                inspect(inspection);
                continue;
            }
            Token current = this.gate.current();
            if (current == null || current.generation() != this.writerOrderGeneration) {
                this.writerOrder.clear();
                this.writerOrderGeneration = current == null ? -1L : current.generation();
            }
            if (this.backend != null && (current == null || current.generation() != this.backendGeneration)) {
                this.backend = null;
                this.backendSeed = 0L;
                this.backendGeneration = -1L;
            }
            SeedJobQueue.Job job = this.queue.pollCurrent(this.gate);
            if (job == null) {
                LockSupport.park();
                continue;
            }
            Token token = job.token();
            try {
                if (!this.gate.accepts(token)) continue;
                if (this.backend == null || this.backendSeed != token.seed() || this.backendGeneration != token.generation()) {
                    this.backend = new VanillaSeedWorldgen(token.seed());
                    this.backendSeed = token.seed();
                    this.backendGeneration = token.generation();
                }
                List<PredictedCluster> clusters = this.backend.predict(job.chunkX(), job.chunkZ(),
                    () -> !this.gate.accepts(token), this.writerOrder, job.motionTops());
                if (this.gate.accepts(token)) {
                    this.queue.publish(new Result<>(token, job.chunkX(), job.chunkZ(),
                        new Output(clusters, null, this.backend.recentTerrainHeights(), job.motionTops(),
                            0, this.backend.recentFeatureTrace())), this.gate);
                }
            } catch (Throwable failure) {
                if (this.gate.accepts(token)) {
                    String summary = failure.getClass().getSimpleName();
                    this.queue.publish(new Result<>(token, job.chunkX(), job.chunkZ(), new Output(List.of(), summary)), this.gate);
                }
            }
        }
        this.backend = null;
        this.backendSeed = 0L;
        this.backendGeneration = -1L;
    }

    @Override public synchronized void close() {
        this.invalidate();
        this.running = false;
        this.thread.interrupt();
    }

    private static Map<Long, int[]> copyMotion(Map<Long, int[]> source) {
        Map<Long, int[]> copy = new HashMap<>();
        if (source != null) source.forEach((key, value) -> {
            if (key != null && value != null && value.length == 256) copy.put(key, value.clone());
        });
        return Map.copyOf(copy);
    }
}
