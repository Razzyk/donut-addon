package dev.donut.addon.expansion.search.seed;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTestType;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedSymbolicOreReplayTest {
    @BeforeAll static void bootstrap() { VanillaSeedWorldgenTest.bootstrapMinecraftData(); }

    @Test void nativeUncertainGateAddsLaterGeometryAndKeepsCertainWritesAndCapFog() throws Exception {
        VanillaSeedWorldgen backend = new VanillaSeedWorldgen(88);
        ChunkGenerator generator = (ChunkGenerator)field(backend, "generator");
        Map<Long, ProtoChunk> chunks = new HashMap<>();
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) {
            ProtoChunk chunk = backend.newChunk(cx, cz);
            chunk.setPersistedStatus(ChunkStatus.BIOMES);
            for (int y : new int[]{6, 7, 8, 9, 10, 11, 12, 13, 14, 116, 117, 118, 119, 120, 121, 122, 123, 124})
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                    chunk.setBlockState(new BlockPos((cx << 4) + x, y, (cz << 4) + z), Blocks.STONE.defaultBlockState(), 0);
            chunk.setPersistedStatus(ChunkStatus.TERRAIN);
            chunks.put(ChunkPos.pack(cx, cz), chunk);
        }
        VanillaSeedWorldgen.OfflineWorld baseline = backend.openRegion(chunks);
        Set<Holder<Biome>> biomes = new HashSet<>();
        chunks.get(ChunkPos.pack(0, 0)).collectBiomesInPalette(biomes);
        assertFalse(biomes.isEmpty());

        var early = recipe(0, 2, 120, 12, SeedScanCore.GRANITE, biomes);
        var certainLater = recipe(1, 1, 10, 12, SeedScanCore.DIORITE, biomes);
        var uncertain = replay(backend, generator, baseline, List.of(early, certainLater), biomes, 100);
        var deterministic = replay(backend, generator, baseline, List.of(early, certainLater), biomes, 1_000);
        var uncertainTrace = uncertain.trace();
        var deterministicTrace = deterministic.trace();
        assertTrue(uncertain.result().nativeCalls() > 0,
            "uncertain native OreFeature calls=" + uncertain.result().nativeCalls() + ", traceFeatures=" + uncertainTrace.features().size());

        var uncertainEarly = feature(uncertainTrace, 0);
        var certainLaterTrace = feature(uncertainTrace, 1);
        var deterministicEarly = feature(deterministicTrace, 0);
        assertFalse(uncertainEarly.writes().isEmpty());
        assertTrue(uncertainEarly.writes().stream().anyMatch(SeedFeatureTrace.Event::optional),
            "uncertain source gate must retain optional native writes");
        assertFalse(certainLaterTrace.writes().isEmpty());
        assertTrue(certainLaterTrace.writes().stream().noneMatch(SeedFeatureTrace.Event::optional),
            "a later certain feature keeps its intersection writes");
        Set<Long> possible = positions(uncertainEarly);
        Set<Long> nativeCertainPath = positions(deterministicEarly);
        Set<Long> extraLaterGeometry = new HashSet<>(possible);
        extraLaterGeometry.removeAll(nativeCertainPath);
        assertFalse(extraLaterGeometry.isEmpty(),
            "the skipped early gate must shift the later native feature geometry, and the union must keep it");
        assertTrue(uncertain.result().nativeCalls() > deterministic.result().nativeCalls());

        // Native replay writes only to a section-cloned scratch region.
        BlockState baselineStone = Blocks.STONE.defaultBlockState();
        for (int y : new int[]{6, 10, 14, 116, 120, 124}) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            assertEquals(baselineStone, baseline.blockState(new BlockPos(x, y, z)));

        var capped = replay(backend, generator, baseline,
            List.of(recipe(0, 12, 120, 12, SeedScanCore.GRANITE, biomes), certainLater), biomes, 100);
        assertEquals(2, capped.result().fogs().size(), "state-cap fog covers this and later native recipes");
        assertTrue(capped.result().fogs().stream().anyMatch(fog -> fog.contains(8, 120, 8)));
        assertTrue(capped.result().fogs().stream().anyMatch(fog -> fog.contains(8, 10, 8)));
        assertTrue(capped.trace().features().isEmpty(), "a capped partial trace is discarded");
    }

    @Test void fractionalAirDiscardObservedBasePredicateReplaysThroughNativeScratchAndKeepsBothWrites() throws Exception {
        VanillaSeedWorldgen backend = new VanillaSeedWorldgen(88);
        ChunkGenerator generator = (ChunkGenerator)field(backend, "generator");
        Map<Long, ProtoChunk> chunks = new HashMap<>();
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) {
            ProtoChunk chunk = backend.newChunk(cx, cz);
            chunk.setPersistedStatus(ChunkStatus.BIOMES);
            for (int y = 6; y <= 14; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                chunk.setBlockState(new BlockPos((cx << 4) + x, y, (cz << 4) + z), Blocks.STONE.defaultBlockState(), 0);
            chunk.setPersistedStatus(ChunkStatus.TERRAIN);
            chunks.put(ChunkPos.pack(cx, cz), chunk);
        }
        VanillaSeedWorldgen.OfflineWorld baseline = backend.openRegion(chunks);
        Set<Holder<Biome>> biomes = new HashSet<>();
        chunks.get(ChunkPos.pack(0, 0)).collectBiomesInPalette(biomes);
        var recipe = recipe(0, 3, 10, 12, SeedScanCore.GRANITE, biomes, 0.5F);
        var initial = replay(backend, generator, baseline, List.of(recipe), biomes, 1_000);
        var originalFeature = feature(initial.trace(), 0);
        assertFalse(originalFeature.readPositions().isEmpty(), "native target predicate reads are captured");
        Set<Long> originallyWritten = positions(originalFeature);
        long changed = originalFeature.readPositions().stream().filter(originallyWritten::contains).filter(position ->
            baseline.blockState(new BlockPos(SeedScanCore.unpackX(position), SeedScanCore.unpackY(position),
                SeedScanCore.unpackZ(position))).is(Blocks.STONE)).findFirst().orElseThrow();

        SeedScanCore.Token token = new SeedScanCore.Token(1, 1, 88);
        List<Long> capturedInputs = new java.util.ArrayList<>(originalFeature.readPositions());
        for (int y = 5; y <= 16; y++) for (int z = -8; z <= 23; z++) for (int x = -8; x <= 23; x++)
            capturedInputs.add(SeedScanCore.packBlock(x, y, z));
        SeedSnapshotCapture capture = new SeedSnapshotCapture(token, List.of(), capturedInputs);
        capture.stepNative(token, 300_000, System.nanoTime() + 2_000_000_000L, System::nanoTime, position -> {
            int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
            BlockState state = position == changed ? Blocks.DIRT.defaultBlockState() : baseline.blockState(new BlockPos(x, y, z));
            return new SeedSnapshotCapture.CapturedBlock(SeedBlockKinds.classify(state),
                BuiltInRegistries.BLOCK.getId(state.getBlock()));
        }, chunk -> 1L);
        assertEquals(SeedSnapshotCapture.State.COMPLETE, capture.state());

        SeedFeatureTrace.Builder observedBuilder = new SeedFeatureTrace.Builder();
        SeedGroundGate gate = new SeedGroundGate(Map.of(), (x, z) -> 1_000, (x, z) -> false);
        var replay = new SeedSymbolicOreReplay(88, 0, 0, baseline, generator, List.of(recipe), gate,
            observedBuilder, () -> false, initial.trace(), capture.snapshot()).run(biomes);
        var replayed = observedBuilder.build();
        var output = feature(replayed, 0);
        assertTrue(replay.nativeCalls() > initial.result().nativeCalls(), "model and observed native branches both execute");
        assertTrue(output.writes().stream().anyMatch(SeedFeatureTrace.Event::optional),
            "a raw observed base mismatch makes the model/oracle union optional");
        assertTrue(output.writes().stream().anyMatch(event -> event.position() == changed),
            "the possible model write remains in the union when the observed branch rejects its base");
        assertTrue(output.writes().stream().anyMatch(event -> !event.optional()),
            "writes shared by both native branches remain certain");
        assertTrue(baseline.blockState(new BlockPos(SeedScanCore.unpackX(changed), SeedScanCore.unpackY(changed),
            SeedScanCore.unpackZ(changed))).is(Blocks.STONE), "scratch replay leaves its baseline unchanged");
    }

    @Test void zeroAirDiscardDoesNotForkOnObservedBaseMismatch() throws Exception {
        VanillaSeedWorldgen backend = new VanillaSeedWorldgen(88);
        ChunkGenerator generator = (ChunkGenerator)field(backend, "generator");
        Map<Long, ProtoChunk> chunks = new HashMap<>();
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) {
            ProtoChunk chunk = backend.newChunk(cx, cz);
            chunk.setPersistedStatus(ChunkStatus.BIOMES);
            for (int y = 6; y <= 14; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                chunk.setBlockState(new BlockPos((cx << 4) + x, y, (cz << 4) + z), Blocks.STONE.defaultBlockState(), 0);
            chunk.setPersistedStatus(ChunkStatus.TERRAIN);
            chunks.put(ChunkPos.pack(cx, cz), chunk);
        }
        VanillaSeedWorldgen.OfflineWorld baseline = backend.openRegion(chunks);
        Set<Holder<Biome>> biomes = new HashSet<>();
        chunks.get(ChunkPos.pack(0, 0)).collectBiomesInPalette(biomes);
        var recipe = recipe(0, 3, 10, 12, SeedScanCore.GRANITE, biomes);
        var initial = replay(backend, generator, baseline, List.of(recipe), biomes, 1_000);
        var originalFeature = feature(initial.trace(), 0);
        Set<Long> originallyWritten = positions(originalFeature);
        long changed = originalFeature.readPositions().stream().filter(originallyWritten::contains).filter(position ->
            baseline.blockState(new BlockPos(SeedScanCore.unpackX(position), SeedScanCore.unpackY(position),
                SeedScanCore.unpackZ(position))).is(Blocks.STONE)).findFirst().orElseThrow();

        SeedScanCore.Token token = new SeedScanCore.Token(1, 1, 88);
        SeedSnapshotCapture capture = new SeedSnapshotCapture(token, List.of(), originalFeature.readPositions());
        capture.stepNative(token, 300_000, System.nanoTime() + 2_000_000_000L, System::nanoTime, position -> {
            int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
            BlockState state = position == changed ? Blocks.DIRT.defaultBlockState() : baseline.blockState(new BlockPos(x, y, z));
            return new SeedSnapshotCapture.CapturedBlock(SeedBlockKinds.classify(state),
                BuiltInRegistries.BLOCK.getId(state.getBlock()));
        }, chunk -> 1L);
        assertEquals(SeedSnapshotCapture.State.COMPLETE, capture.state());

        SeedFeatureTrace.Builder observedBuilder = new SeedFeatureTrace.Builder();
        SeedGroundGate gate = new SeedGroundGate(Map.of(), (x, z) -> 1_000, (x, z) -> false);
        var replay = new SeedSymbolicOreReplay(88, 0, 0, baseline, generator, List.of(recipe), gate,
            observedBuilder, () -> false, initial.trace(), capture.snapshot()).run(biomes);
        var output = feature(observedBuilder.build(), 0);
        assertEquals(initial.result().nativeCalls(), replay.nativeCalls(),
            "observed base mismatch must not create another native path when airDiscard is zero");
        assertTrue(output.writes().stream().noneMatch(SeedFeatureTrace.Event::optional));
        assertTrue(output.writes().stream().anyMatch(event -> event.position() == changed));
    }

    @Test void observedCrossOriginWritersResolveCertainCellsBeforeAConclusiveJudge() {
        long firstOrigin = SeedScanCore.packChunk(0, 0);
        long secondOrigin = SeedScanCore.packChunk(1, 0);
        long[] cells = new long[8];
        long[] probes = new long[3];
        for (int i = 0; i < cells.length; i++) cells[i] = SeedScanCore.packBlock(i, 10, 4);
        System.arraycopy(cells, 0, probes, 0, probes.length);
        SeedFeatureTrace.Builder builder = new SeedFeatureTrace.Builder();
        builder.begin(firstOrigin, 10, 0, SeedRandomBranch.feature(5, 0, 0, 0, 10).freeze());
        for (long position : probes) builder.write(position, SeedScanCore.STONE, SeedScanCore.GRANITE, false);
        builder.end();
        builder.begin(secondOrigin, 10, 1, SeedRandomBranch.feature(5, 16, 0, 1, 10).freeze());
        for (long position : probes) builder.write(position, SeedScanCore.GRANITE, SeedScanCore.DIORITE, false);
        builder.end();
        var trace = builder.build();
        var writerProbes = probesToList(trace, probes);
        assertEquals(3, writerProbes.size());

        byte[] chunk = new byte[(SeedScanCore.JUDGE_MAX_Y - SeedScanCore.JUDGE_MIN_Y + 1) * 256];
        java.util.Arrays.fill(chunk, SeedScanCore.STONE);
        for (long cell : cells) {
            int index = (SeedScanCore.unpackY(cell) - SeedScanCore.JUDGE_MIN_Y) * 256 +
                (SeedScanCore.unpackZ(cell) & 15) * 16 + (SeedScanCore.unpackX(cell) & 15);
            chunk[index] = SeedScanCore.GRANITE;
        }
        var snapshot = new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), chunk));
        var observed = SeedFeatureTrace.observe(writerProbes, snapshot);
        SeedWriterOrder order = new SeedWriterOrder();
        assertTrue(order.replace(SeedScanCore.packChunk(0, 0), observed));
        assertTrue(order.precedes(secondOrigin, firstOrigin));
        assertEquals(SeedScanCore.GRANITE, trace.outcome(probes[0], order).certain());

        var cluster = new SeedScanCore.PredictedCluster(SeedScanCore.GRANITE, cells[0], cells, new long[0],
            new byte[0], true, cells, new byte[0], writerProbes);
        assertEquals(SeedScanCore.Verdict.CLEAN, SeedScanCore.judge(cluster, snapshot, 1),
            "observed writer order leaves a fully matching candidate conclusive");
        var unknown = new SeedScanCore.PredictedCluster(SeedScanCore.GRANITE, cells[0], cells, new long[0],
            new byte[0], false, cells, new byte[0], writerProbes);
        assertEquals(SeedScanCore.Verdict.INCOMPLETE, SeedScanCore.judge(unknown, snapshot, 1));
    }

    private record Replay(SeedSymbolicOreReplay.Result result, SeedFeatureTrace.Trace trace) {}

    private static Replay replay(VanillaSeedWorldgen backend, ChunkGenerator generator,
                                 VanillaSeedWorldgen.OfflineWorld baseline,
                                 List<SeedOreRecipes.Recipe> recipes, Set<Holder<Biome>> biomes, int modelFloor) {
        SeedFeatureTrace.Builder builder = new SeedFeatureTrace.Builder();
        SeedGroundGate gate = new SeedGroundGate(Map.of(), (x, z) -> modelFloor, (x, z) -> false);
        var result = new SeedSymbolicOreReplay(88, 0, 0, baseline, generator, recipes, gate, builder, () -> false).run(biomes);
        return new Replay(result, builder.build());
    }

    private static SeedOreRecipes.Recipe recipe(int index, int count, int y, int size, byte material,
                                                 Set<Holder<Biome>> biomes) {
        return recipe(index, count, y, size, material, biomes, 0.0F);
    }

    private static SeedOreRecipes.Recipe recipe(int index, int count, int y, int size, byte material,
                                                 Set<Holder<Biome>> biomes, float airDiscard) {
        RuleTest stone = new RuleTest() {
            @Override public boolean test(BlockState state, BlockPos at, RandomSource random) { return state.is(Blocks.STONE); }
            @Override protected RuleTestType<?> getType() { return null; }
        };
        List<BlockReplacement> targets = List.of(new BlockReplacement(stone, materialBlock(material).defaultBlockState()));
        OreFeature feature = SeedOreRecipes.instrumented(new OreFeature(targets, size, airDiscard));
        PlacedFeature placed = new PlacedFeature(Holder.direct(feature), List.of(
            CountPlacement.of(count), InSquarePlacement.spread(),
            HeightRangePlacement.uniform(VerticalAnchor.absolute(y), VerticalAnchor.absolute(y)), new PlacementModifier() {
                // The feature is a local replay fixture, not a biome-registered placed feature.
                // Keep the vanilla ore placer, placement RNG, and height modifiers, while avoiding
                // BiomeFilter's registry-membership check on this synthetic fixture.
                @Override public void modify(PlacementContext context, RandomSource random, BlockPos pos,
                                             java.util.function.Consumer<BlockPos> output) {
                    output.accept(pos);
                }
                @Override public com.mojang.serialization.MapCodec<? extends PlacementModifier> codec() { return null; }
            }));
        return new SeedOreRecipes.Recipe(0, index, index, count, count, 0, y, y, 0, false,
            size, airDiscard, material, biomes, targets, feature, placed);
    }

    private static net.minecraft.world.level.block.Block materialBlock(byte material) {
        return switch (material) {
            case SeedScanCore.GRANITE -> Blocks.GRANITE;
            case SeedScanCore.DIORITE -> Blocks.DIORITE;
            default -> throw new IllegalArgumentException("test material");
        };
    }

    private static SeedFeatureTrace.Feature feature(SeedFeatureTrace.Trace trace, int index) {
        return trace.features().stream().filter(feature -> feature.index() == index).findFirst().orElseThrow();
    }

    private static Set<Long> positions(SeedFeatureTrace.Feature feature) {
        Set<Long> positions = new HashSet<>();
        feature.writes().forEach(event -> positions.add(event.position()));
        return positions;
    }

    private static List<SeedFeatureTrace.Probe> probesToList(SeedFeatureTrace.Trace trace, long[] positions) {
        return java.util.Arrays.stream(positions).mapToObj(trace::probe).filter(java.util.Objects::nonNull).toList();
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
