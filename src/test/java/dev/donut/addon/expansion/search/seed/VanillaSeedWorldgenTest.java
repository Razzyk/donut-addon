package dev.donut.addon.expansion.search.seed;

import dev.donut.addon.expansion.search.seed.SeedScanCore.PredictedCluster;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.Blocks;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.VanillaPackResourcesBuilder;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.tags.TagLoader;

class VanillaSeedWorldgenTest {
    @BeforeAll static void bootstrapMinecraftData() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Only this isolated test JVM loads official vanilla data. Never rebind client tags in addon code.
        var resources = new VanillaPackResourcesBuilder().pushJarResources().exposeNamespace("minecraft")
            .build(new PackLocationInfo("vanilla", Component.literal("Vanilla test data"), PackSource.BUILT_IN, Optional.empty()));
        try (var data = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(resources.fullResources()))) {
            TagLoader.loadTagsForExistingRegistries(data,
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)).forEach(tags -> tags.apply());
        }
        assertTrue(Heightmap.Types.OCEAN_FLOOR.isOpaque().test(Blocks.STONE.defaultBlockState()),
            "the official 26.3 heightmap block tags must be loaded before generation");
    }

    @Test void isolatedVanillaBackendProducesConclusiveObservedKnownSeedTrace() {
        VanillaSeedWorldgen backend = new VanillaSeedWorldgen(780123456789L, true);
        assertTrue(backend.predict(0, 0, () -> true).isEmpty(), "pre-cancel must skip work");
        AtomicInteger cancellationChecks = new AtomicInteger();
        assertTrue(backend.predict(0, 0, () -> cancellationChecks.incrementAndGet() > 8).isEmpty());
        assertTrue(cancellationChecks.get() > 8);

        long replayStartedNanos = System.nanoTime();
        List<PredictedCluster> first = backend.predict(0, 0, () -> false);
        SeedFeatureTrace.Trace sourceTrace = backend.recentFeatureTrace();
        SeedScanCore.Snapshot observed = backend.recentObservedSnapshot();
        Map<Long, int[]> motionTops = backend.recentMotionTops();
        assertEquals(SeedSnapshotCapture.State.COMPLETE, backend.recentSnapshotState(),
            "the real native trace must fit the actor's bounded observation capture");
        assertFalse(motionTops.isEmpty(), "the known seed produced native motion heights for the neighborhood");
        assertTrue(!first.isEmpty(), "known seed/chunk must produce actual candidate evidence");
        SeedWriterOrder writerOrder = new SeedWriterOrder();
        var probes = first.stream().flatMap(cluster -> cluster.writerProbes().stream()).distinct().toList();
        writerOrder.replace(ChunkPos.pack(0, 0), SeedFeatureTrace.observe(probes, observed));
        List<PredictedCluster> second = backend.predict(0, 0, () -> false, writerOrder, motionTops,
            sourceTrace, observed);
        SeedFeatureTrace.Trace secondTrace = backend.recentFeatureTrace();
        SeedScanCore.Snapshot secondObserved = backend.recentObservedSnapshot();
        Map<Long, int[]> secondMotionTops = backend.recentMotionTops();
        var secondProbes = second.stream().flatMap(cluster -> cluster.writerProbes().stream()).distinct().toList();
        boolean orderChanged = writerOrder.replace(ChunkPos.pack(0, 0), SeedFeatureTrace.observe(secondProbes, secondObserved));
        List<PredictedCluster> finalPass = second;
        if (orderChanged || second.stream().anyMatch(cluster -> !cluster.generationComplete())) {
            finalPass = backend.predict(0, 0, () -> false, writerOrder, secondMotionTops,
                secondTrace, secondObserved);
        }
        SeedScanCore.Snapshot finalObserved = backend.recentObservedSnapshot();
        long nativeUsable = finalPass.stream().filter(cluster -> cluster.generationComplete() &&
            cluster.certainCells().length >= 8).count();
        long falseAlertsOnUnmodifiedWorld = finalPass.stream().filter(cluster ->
            SeedScanCore.judge(cluster, finalObserved, 8) == SeedScanCore.Verdict.ALERT).count();
        List<String> candidateDiagnostics = finalPass.stream().map(cluster -> "kind=" + cluster.kind() +
            ", owner=" + cluster.owner() + ", cells=" + cluster.cells().length + ", shell=" +
            cluster.shell().length + ", certain=" + cluster.certainCells().length + ", complete=" +
            cluster.generationComplete() + ", verdict=" + SeedScanCore.judge(cluster, finalObserved, 8)).toList();
        SeedFeatureTrace.Trace nativeTrace = backend.recentFeatureTrace();
        System.out.println("Known-seed observed replay: candidates=" + finalPass.size() + ", complete=" +
            finalPass.stream().filter(PredictedCluster::generationComplete).count() + ", nativeFeatures=" +
            (nativeTrace == null ? -1 : nativeTrace.features().size()) + ", nativeWrites=" +
            (nativeTrace == null ? -1 : nativeTrace.features().stream().mapToInt(feature -> feature.writes().size()).sum()) +
            ", nativeReadPositions=" + (nativeTrace == null ? -1 : nativeTrace.readPositions().size()) +
            ", fogs=" + backend.recentFogs().size() + ", reasons=" + backend.recentFogs().stream().collect(
                java.util.stream.Collectors.groupingBy(SeedSymbolicOreReplay.Fog::reason, java.util.TreeMap::new,
                    java.util.stream.Collectors.counting())) + ", capture=" + backend.recentSnapshotState() +
            ", nativeCalls=" + backend.recentNativeCalls() + ", peakStates=" + backend.recentPeakReplayStates() +
            ", peakOverlayCells=" + backend.recentPeakOverlayCells() + ", elapsedMs=" +
            ((System.nanoTime() - replayStartedNanos) / 1_000_000L) + ", nativeUsable=" + nativeUsable +
            ", falseAlertsOnUnmodifiedWorld=" + falseAlertsOnUnmodifiedWorld +
            ", candidateDetails=" + candidateDiagnostics);
        assertTrue(finalObserved != null, "native replay must retain the captured input snapshot");
        assertEquals(SeedSnapshotCapture.State.COMPLETE, backend.recentSnapshotState(),
            "final native inputs must be captured completely");
        assertTrue(nativeUsable > 0,
            "actual native evidence must yield a complete, usable candidate with at least eight certain cells");
        assertEquals(0, falseAlertsOnUnmodifiedWorld,
            "an unmodified generated sample must not produce a false alert; OPEN/TERRAIN/TOO_FEW are acceptable");
        assertTrue(first.size() <= 1024 && finalPass.size() <= 1024,
            "the chunk prediction results must remain bounded");
        assertTrue(nativeTrace != null, "native ore replay must publish its per-origin trace");
        assertTrue(nativeTrace.features().stream().anyMatch(feature -> !feature.writes().isEmpty()),
            "known 26.3 seed must produce actual native ore writes");
        assertTrue(nativeTrace.features().stream().anyMatch(feature -> !feature.readPositions().isEmpty()),
            "native target predicates must publish oracle input positions");
    }

    @Test void offlineTickClockPreservesDelayPriorityPositionAndOrder() {
        OfflineTickClock clock = new OfflineTickClock();
        BlockPos firstPos = new BlockPos(2, 14, -3);
        BlockPos secondPos = new BlockPos(3, 14, -3);
        var first = clock.create(firstPos, "water", 7);
        var second = clock.create(secondPos, "lava", 11, TickPriority.HIGH);

        assertEquals(firstPos, first.pos());
        assertEquals("water", first.type());
        assertEquals(7L, first.triggerTick());
        assertEquals(TickPriority.NORMAL, first.priority());
        assertEquals(secondPos, second.pos());
        assertEquals("lava", second.type());
        assertEquals(11L, second.triggerTick());
        assertEquals(TickPriority.HIGH, second.priority());
        assertTrue(first.subTickOrder() < second.subTickOrder());
    }

    @Test void generatedHeightUsesCurrentChunkAndUpdatesAfterFeatureWrites() {
        VanillaSeedWorldgen backend = new VanillaSeedWorldgen(780123456789L);
        ProtoChunk chunk = backend.newChunk(-1, -2);
        chunk.setPersistedStatus(ChunkStatus.TERRAIN);
        var chunks = Map.of(ChunkPos.pack(-1, -2), chunk);
        BlockPos base = new BlockPos(-3, 10, -18);
        chunk.setBlockState(base, Blocks.STONE.defaultBlockState(), 0);
        assertEquals(Blocks.STONE.defaultBlockState(), chunk.getBlockState(base));
        assertEquals(11, VanillaSeedWorldgen.generatedHeight(chunks, Heightmap.Types.OCEAN_FLOOR, -3, -18));

        chunk.setBlockState(base.above(), Blocks.STONE.defaultBlockState(), 0);
        assertEquals(12, VanillaSeedWorldgen.generatedHeight(chunks, Heightmap.Types.OCEAN_FLOOR, -3, -18));
        chunk.setBlockState(base.above(), Blocks.AIR.defaultBlockState(), 0);
        assertEquals(11, VanillaSeedWorldgen.generatedHeight(chunks, Heightmap.Types.OCEAN_FLOOR, -3, -18));
        assertThrows(IllegalStateException.class, () ->
            VanillaSeedWorldgen.generatedHeight(chunks, Heightmap.Types.OCEAN_FLOOR_WG, 0, 0));
    }

    @Test void dungeonBlockEntitiesRemainIsolatedAndAreRemovedWithTheirBlocks() {
        VanillaSeedWorldgen backend = new VanillaSeedWorldgen(780123456789L);
        ProtoChunk chunk = backend.newChunk(0, 0);
        var view = backend.openRegion(Map.of(ChunkPos.pack(0, 0), chunk)).proxy();
        BlockPos pos = new BlockPos(2, 10, 3);
        assertTrue(view.getChunk(pos) == chunk, "position overload resolves the same owned chunk");
        view.setBlock(pos, Blocks.CHEST.defaultBlockState(), 2);
        var chest = view.getBlockEntity(pos);
        assertTrue(chest instanceof net.minecraft.world.level.block.entity.ChestBlockEntity);
        assertTrue(chest.getLevel() == null, "isolated entities must not attach to a live level");
        assertTrue(view.getBlockEntity(pos) == chest, "repeated feature queries reuse the owned object");
        view.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
        assertTrue(view.getBlockEntity(pos) == null);
    }

    @Test void offlineTickQueueIsBoundedAndAcceptsExistingDuplicates() {
        BlockPos pos = new BlockPos(0, 10, 0);
        int limit = OfflineTickClock.MAX_QUEUED_TICKS_PER_CHUNK;
        OfflineTickClock.requireCapacity(limit, true, pos);
        assertThrows(IllegalStateException.class, () -> OfflineTickClock.requireCapacity(limit, false, pos));
        OfflineTickClock.requireCapacity(limit - 1, false, pos);
    }

    private static List<String> signature(List<PredictedCluster> clusters) {
        ArrayList<PredictedCluster> sorted = new ArrayList<>(clusters);
        sorted.sort(Comparator.comparingInt((PredictedCluster cluster) -> cluster.kind())
            .thenComparingLong(PredictedCluster::owner));
        return sorted.stream().map(cluster -> cluster.kind() + ":" + cluster.owner() + ":"
            + cluster.generationComplete() + ":" + Arrays.toString(cluster.cells()) + ":"
            + Arrays.toString(cluster.shell())).toList();
    }
}
