package dev.donut.addon.expansion.search.seed;

import dev.donut.addon.expansion.search.seed.SeedScanCore.PredictedCluster;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.WorldCarver;
import net.minecraft.world.level.levelgen.feature.AbstractOreFeature;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickContainerAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Isolated vanilla Overworld predictor. All registries and ProtoChunks are worker-owned; the
 * client level is never passed into this class. It generates noise terrain, surface material,
 * carvers, and placed features through the last vanilla ore step. Structure starts and later
 * decoration steps are outside this candidate model; client-thread verification stays conservative.
 */
final class VanillaSeedWorldgen {
    private static final int DECORATION_MARGIN = 2;
    private static final int REGION_MARGIN = DECORATION_MARGIN + 1;
    private static final int MIN_CLUSTER_Y = 0;
    private static final int MAX_CLUSTER_Y = 30;
    private static final int MAX_CLUSTER_SIZE = 20_000;
    private static final int MAX_PREDICTIONS = 1_024;
    private static final int MAX_BLUEPRINTS = 64;
    private static final int HEIGHT = SeedScanCore.MAX_Y - SeedScanCore.MIN_Y + 1;
    private static final int CELLS_PER_LAYER = 256;
    private static final Set<ResourceKey<Biome>> TALL_TREE_BIOMES = Set.of(Biomes.JUNGLE, Biomes.BAMBOO_JUNGLE,
        Biomes.SPARSE_JUNGLE, Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.OLD_GROWTH_SPRUCE_TAIGA, Biomes.MANGROVE_SWAMP);

    private final long seed;
    private final HolderLookup.Provider registrySnapshot;
    private final RegistryAccess worldRegistryAccess;
    private final Holder<NoiseGeneratorSettings> settings;
    private final NoiseBasedChunkGenerator generator;
    private final RandomState randomState;
    private final Aquifer.FluidPicker fluidPicker;
    private final LevelHeightAccessor height = LevelHeightAccessor.create(SeedScanCore.MIN_Y, HEIGHT);
    private final PalettedContainerFactory palettes;
    private final List<FeatureSorter.StepFeatureData> featureSteps;
    private final List<SeedOreRecipes.Recipe> oreRecipes;
    private final Map<Integer, SeedOreRecipes.Recipe> recipeByFeature = new HashMap<>();
    private Map<Integer, String> unsupportedOreRecipes = Map.of();
    private final Map<Holder<Biome>, Integer> biomeIds = new IdentityHashMap<>();
    private final List<Holder<Biome>> biomeById;
    private final Holder<Biome> defaultBiome;
    private final int lastOreStep;
    private final LinkedHashMap<Long, TerrainBlueprint> terrainCache = new LinkedHashMap<>(64, 0.75F, true);
    private int[] recentTerrainHeights;
    private SeedFeatureTrace.Trace recentFeatureTrace;
    private List<SeedSymbolicOreReplay.Fog> recentFogs = List.of();
    private int recentNativeCalls;
    private int recentPeakReplayStates;
    private int recentPeakOverlayCells;
    private final boolean captureObservationForTest;
    private Map<Long, int[]> recentMotionTops = Map.of();
    private SeedScanCore.Snapshot recentObservedSnapshot;
    private SeedSnapshotCapture.State recentSnapshotState;

    int[] recentTerrainHeights() { return recentTerrainHeights == null ? null : recentTerrainHeights.clone(); }
    SeedFeatureTrace.Trace recentFeatureTrace() { return recentFeatureTrace; }
    List<SeedSymbolicOreReplay.Fog> recentFogs() { return recentFogs; }
    int recentNativeCalls() { return recentNativeCalls; }
    int recentPeakReplayStates() { return recentPeakReplayStates; }
    int recentPeakOverlayCells() { return recentPeakOverlayCells; }
    SeedSnapshotCapture.State recentSnapshotState() { return recentSnapshotState; }
    SeedScanCore.Snapshot recentObservedSnapshot() { return recentObservedSnapshot; }
    Map<Long, int[]> recentMotionTops() {
        Map<Long, int[]> copy = new HashMap<>();
        recentMotionTops.forEach((key, value) -> copy.put(key, value.clone()));
        return Map.copyOf(copy);
    }

    VanillaSeedWorldgen(long seed) {
        this(seed, false);
    }

    /** Enables the exact observation handoff only for isolated integration tests. */
    VanillaSeedWorldgen(long seed, boolean captureObservationForTest) {
        this.captureObservationForTest = captureObservationForTest;
        if (!Blocks.STONE.defaultBlockState().is(BlockTags.BASE_STONE_OVERWORLD) ||
            !Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(Blocks.STONE.defaultBlockState())) {
            throw new IllegalStateException("Required world-generation tags are unavailable or incompatible");
        }
        this.seed = seed;
        this.registrySnapshot = VanillaRegistries.createWorldLookup();
        this.worldRegistryAccess = registryAccessProxy(this.registrySnapshot);
        this.settings = this.registrySnapshot.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        Holder<MultiNoiseBiomeSourceParameterList> preset = this.registrySnapshot.lookupOrThrow(
            Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        MultiNoiseBiomeSource biomeSource = MultiNoiseBiomeSource.createFromPreset(preset);
        this.generator = new NoiseBasedChunkGenerator(biomeSource, this.settings);
        this.randomState = RandomState.create(this.registrySnapshot.lookupOrThrow(Registries.NOISE), seed, this.settings.value());
        this.fluidPicker = fluidPicker(this.settings.value());

        ArrayList<Holder<Biome>> biomes = new ArrayList<>(biomeSource.possibleBiomes());
        biomes.sort(Comparator.comparing(VanillaSeedWorldgen::biomeKey));
        this.defaultBiome = this.registrySnapshot.lookupOrThrow(Registries.BIOME).getOrThrow(
            net.minecraft.world.level.biome.Biomes.PLAINS);
        if (!biomes.contains(this.defaultBiome)) biomes.add(this.defaultBiome);
        this.biomeById = List.copyOf(biomes);
        for (int i = 0; i < this.biomeById.size(); i++) this.biomeIds.put(this.biomeById.get(i), i);

        Strategy<BlockState> blockStrategy = Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY);
        Strategy<Holder<Biome>> biomeStrategy = Strategy.createForBiomes(new BiomeIdMap(this.biomeById, this.biomeIds));
        this.palettes = new PalettedContainerFactory(blockStrategy, Blocks.AIR.defaultBlockState(), null,
            biomeStrategy, this.defaultBiome, null);

        this.featureSteps = FeatureSorter.buildFeaturesPerStep(biomes,
            biome -> this.generator.getBiomeGenerationSettings(biome).features(), true);
        SeedOreRecipes parsedOreRecipes = new SeedOreRecipes(this.generator, this.featureSteps, this.biomeById);
        this.oreRecipes = parsedOreRecipes.values();
        this.unsupportedOreRecipes = parsedOreRecipes.unsupported();
        for (SeedOreRecipes.Recipe recipe : this.oreRecipes)
            this.recipeByFeature.put(featureKey(recipe.stage(), recipe.featureIndex()), recipe);
        int lastOre = -1;
        for (int step = 0; step < this.featureSteps.size(); step++) {
            for (PlacedFeature feature : this.featureSteps.get(step).features()) {
                if (feature.feature().value() instanceof AbstractOreFeature) {
                    lastOre = step;
                    int index = this.featureSteps.get(step).indexMapping().applyAsInt(feature);
                    if (index < 0 || (!this.recipeByFeature.containsKey(featureKey(step, index)) &&
                        !this.unsupportedOreRecipes.containsKey(featureKey(step, index))))
                        throw new IllegalStateException("Native ore feature is not covered by the validated recipe model");
                }
            }
        }
        this.lastOreStep = lastOre;
    }

    List<PredictedCluster> predict(int chunkX, int chunkZ, BooleanSupplier cancelled) {
        return predict(chunkX, chunkZ, cancelled, new SeedWriterOrder(), Map.of());
    }

    List<PredictedCluster> predict(int chunkX, int chunkZ, BooleanSupplier cancelled,
                                   SeedWriterOrder writerOrder, Map<Long, int[]> observedMotion) {
        return predict(chunkX, chunkZ, cancelled, writerOrder, observedMotion, null, null);
    }

    List<PredictedCluster> predict(int chunkX, int chunkZ, BooleanSupplier cancelled,
                                   SeedWriterOrder writerOrder, Map<Long, int[]> observedMotion,
                                   SeedFeatureTrace.Trace oracleTrace, SeedScanCore.Snapshot observed) {
        this.recentTerrainHeights = null;
        this.recentFeatureTrace = null;
        this.recentFogs = List.of();
        this.recentNativeCalls = 0;
        this.recentPeakReplayStates = 0;
        this.recentPeakOverlayCells = 0;
        this.recentMotionTops = Map.of();
        this.recentObservedSnapshot = null;
        this.recentSnapshotState = null;
        if (cancelled.getAsBoolean()) return List.of();
        Map<Long, ProtoChunk> neighborhood = new HashMap<>();
        for (int dx = -REGION_MARGIN; dx <= REGION_MARGIN; dx++) {
            for (int dz = -REGION_MARGIN; dz <= REGION_MARGIN; dz++) {
                if (cancelled.getAsBoolean()) return List.of();
                int x = chunkX + dx, z = chunkZ + dz;
                TerrainBlueprint terrain = this.blueprint(x, z, cancelled);
                if (cancelled.getAsBoolean() || terrain.cancelled) return List.of();
                neighborhood.put(ChunkPos.pack(x, z), this.materialize(terrain));
            }
        }
        if (cancelled.getAsBoolean()) return List.of();

        OfflineWorld world = this.openRegion(neighborhood);
        if (!this.buildSurface(neighborhood, world.biomeManager(), cancelled)) return List.of();
        if (!this.carve(neighborhood, world.biomeManager(), cancelled)) return List.of();
        // Match the 26.3 stage boundary: preserve worldgen height snapshots, then maintain final maps.
        for (ProtoChunk chunk : neighborhood.values()) {
            Heightmap.primeHeightmaps(chunk, ChunkStatus.FINAL_HEIGHTMAPS);
            chunk.setPersistedStatus(ChunkStatus.TERRAIN);
        }
        int[] surface = new int[256];
        ProtoChunk center = neighborhood.get(ChunkPos.pack(chunkX, chunkZ));
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            surface[z * 16 + x] = center.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) + 1;
        }
        this.recentTerrainHeights = surface;
        WorldGenLevel level = world.proxy();
        if (SharedConstants.DEBUG_DISABLE_FEATURES) return List.of();
        SeedGroundGate gate = new SeedGroundGate(observedMotion,
            (x, z) -> neighborhood.get(ChunkPos.pack(x >> 4, z >> 4))
                .getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15) + 1,
            (x, z) -> this.tallTreesAt(neighborhood, x, z));
        SeedFeatureTrace.Builder traceBuilder = new SeedFeatureTrace.Builder();
        ArrayList<SeedSymbolicOreReplay.Fog> fogs = new ArrayList<>();
        if (!this.unsupportedOreRecipes.isEmpty()) {
            int x0 = chunkX << 4, z0 = chunkZ << 4;
            for (var unsupported : this.unsupportedOreRecipes.entrySet()) {
                int key = unsupported.getKey();
                fogs.add(new SeedSymbolicOreReplay.Fog(SeedScanCore.packChunk(chunkX, chunkZ), x0,
                    SeedScanCore.MIN_Y, z0, x0 + 15, SeedScanCore.MAX_Y, z0 + 15,
                    "unsupported native ore configuration: " + unsupported.getValue(),
                    key >>> 16, key & 0xffff, SeedScanCore.MISSING));
            }
        }
        for (int x = chunkX - DECORATION_MARGIN; x <= chunkX + DECORATION_MARGIN; x++) {
            for (int z = chunkZ - DECORATION_MARGIN; z <= chunkZ + DECORATION_MARGIN; z++) {
                if (cancelled.getAsBoolean()) return List.of();
                Set<Holder<Biome>> localBiomes = localBiomes(neighborhood, x, z);
                if (localBiomes.isEmpty()) continue;
                var replay = new SeedSymbolicOreReplay(this.seed, x, z, world, this.generator, this.oreRecipes,
                    gate, traceBuilder, cancelled, oracleTrace, observed, writerOrder).run(localBiomes);
                fogs.addAll(replay.fogs());
                this.recentNativeCalls += replay.nativeCalls();
                this.recentPeakReplayStates = Math.max(this.recentPeakReplayStates, replay.peakStates());
                this.recentPeakOverlayCells = Math.max(this.recentPeakOverlayCells, replay.peakOverlayCells());
            }
        }
        this.recentFogs = List.copyOf(fogs);
        SeedFeatureTrace.Trace traces = traceBuilder.build();
        this.recentFeatureTrace = traces;
        for (int x = chunkX - DECORATION_MARGIN; x <= chunkX + DECORATION_MARGIN; x++) {
            for (int z = chunkZ - DECORATION_MARGIN; z <= chunkZ + DECORATION_MARGIN; z++) {
                if (cancelled.getAsBoolean()) return List.of();
                this.decorateOrigin(level, neighborhood, x, z, cancelled);
            }
        }
        if (cancelled.getAsBoolean()) return List.of();
        List<PredictedCluster> clusters = this.findClusters(neighborhood, chunkX, chunkZ, traces, fogs, writerOrder, cancelled);
        if (this.captureObservationForTest && !cancelled.getAsBoolean()) {
            this.recentMotionTops = motionTops(neighborhood);
            this.captureObservedSnapshot(world, clusters, traces);
        }
        return clusters;
    }

    private static Map<Long, int[]> motionTops(Map<Long, ProtoChunk> chunks) {
        Map<Long, int[]> out = new HashMap<>();
        chunks.forEach((key, chunk) -> {
            int[] heights = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                heights[z * 16 + x] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            out.put(key, heights);
        });
        return Map.copyOf(out);
    }

    private void captureObservedSnapshot(OfflineWorld world, List<PredictedCluster> clusters,
                                         SeedFeatureTrace.Trace trace) {
        SeedScanCore.Token token = new SeedScanCore.Token(1, 1, this.seed);
        SeedSnapshotCapture capture = new SeedSnapshotCapture(token, clusters, trace.readPositions());
        SeedSnapshotCapture.State state;
        do {
            state = capture.stepNative(token, 300_000, Long.MAX_VALUE, System::nanoTime, packed -> {
                BlockPos pos = new BlockPos(SeedScanCore.unpackX(packed), SeedScanCore.unpackY(packed),
                    SeedScanCore.unpackZ(packed));
                BlockState block = world.blockState(pos);
                return new SeedSnapshotCapture.CapturedBlock(SeedBlockKinds.classify(block),
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(block.getBlock()));
            }, ignored -> 1L);
        } while (state == SeedSnapshotCapture.State.EXPANDING || state == SeedSnapshotCapture.State.READING ||
            state == SeedSnapshotCapture.State.VALIDATING);
        this.recentSnapshotState = state;
        this.recentObservedSnapshot = capture.snapshot();
    }

    private static int featureKey(int step, int index) { return step << 16 | index & 0xffff; }

    private Set<Holder<Biome>> localBiomes(Map<Long, ProtoChunk> region, int chunkX, int chunkZ) {
        Set<Holder<Biome>> local = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            ProtoChunk chunk = region.get(ChunkPos.pack(chunkX + dx, chunkZ + dz));
            if (chunk != null) chunk.collectBiomesInPalette(local);
        }
        local.retainAll(this.generator.getBiomeSource().possibleBiomes());
        return local;
    }

    private boolean tallTreesAt(Map<Long, ProtoChunk> region, int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int x = ((chunkX + dx) << 4) + 8, z = ((chunkZ + dz) << 4) + 8;
            for (int y = 60; y <= 130; y += 10) {
                int qx = QuartPos.fromBlock(x), qy = QuartPos.fromBlock(y), qz = QuartPos.fromBlock(z);
                ProtoChunk chunk = region.get(ChunkPos.pack(QuartPos.toBlock(qx) >> 4, QuartPos.toBlock(qz) >> 4));
                if (chunk == null) continue;
                Holder<Biome> biome = chunk.getNoiseBiome(qx, qy, qz);
                if (biome.unwrapKey().filter(TALL_TREE_BIOMES::contains).isPresent()) return true;
            }
        }
        return false;
    }

    private TerrainBlueprint blueprint(int chunkX, int chunkZ, BooleanSupplier cancelled) {
        long key = ChunkPos.pack(chunkX, chunkZ);
        TerrainBlueprint known = this.terrainCache.get(key);
        if (known != null) return known;
        ProtoChunk chunk = this.newChunk(chunkX, chunkZ);
        this.generator.createBiomes(this.randomState, Blender.empty(), null, chunk).join();
        // ProtoChunk.getNoiseBiome guards access on its generated status, even after the
        // generator has populated the biome palette.
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        Holder<Biome>[] biomes = this.biomeGrid(chunk);
        BlockState[] states = new BlockState[HEIGHT * CELLS_PER_LAYER];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                if (cancelled.getAsBoolean()) return TerrainBlueprint.cancelled();
                int wx = (chunkX << 4) + x;
                int wz = (chunkZ << 4) + z;
                NoiseColumn column = this.generator.getBaseColumn(wx, wz, this.height, this.randomState);
                int offset = z * 16 + x;
                for (int y = SeedScanCore.MIN_Y; y <= SeedScanCore.MAX_Y; y++) {
                    pos.set(wx, y, wz);
                    states[(y - SeedScanCore.MIN_Y) * CELLS_PER_LAYER + offset] = column.getBlock(y);
                }
            }
        }
        TerrainBlueprint result = new TerrainBlueprint(chunkX, chunkZ, states, biomes, false);
        this.terrainCache.put(key, result);
        while (this.terrainCache.size() > MAX_BLUEPRINTS) this.terrainCache.remove(this.terrainCache.keySet().iterator().next());
        return result;
    }

    private Holder<Biome>[] biomeGrid(ProtoChunk chunk) {
        @SuppressWarnings("unchecked") Holder<Biome>[] values = new Holder[1536];
        int quartX = QuartPos.fromBlock(chunk.getPos().getMinBlockX());
        int quartZ = QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
        int quartY = QuartPos.fromBlock(SeedScanCore.MIN_Y);
        for (int y = 0; y < 96; y++) {
            for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
                values[(y * 4 + z) * 4 + x] = chunk.getNoiseBiome(quartX + x, quartY + y, quartZ + z);
            }
        }
        return values;
    }

    private ProtoChunk materialize(TerrainBlueprint blueprint) {
        ProtoChunk chunk = this.newChunk(blueprint.chunkX, blueprint.chunkZ);
        int quartX = QuartPos.fromBlock(chunk.getPos().getMinBlockX());
        int quartZ = QuartPos.fromBlock(chunk.getPos().getMinBlockZ());
        int quartY = QuartPos.fromBlock(SeedScanCore.MIN_Y);
        chunk.fillBiomesFromNoise((qx, qy, qz) -> {
            int x = qx - quartX, y = qy - quartY, z = qz - quartZ;
            if (x < 0 || x >= 4 || z < 0 || z >= 4 || y < 0 || y >= 96) return this.defaultBiome;
            return blueprint.biomes[(y * 4 + z) * 4 + x];
        });
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = SeedScanCore.MIN_Y; y <= SeedScanCore.MAX_Y; y++) {
            int layer = (y - SeedScanCore.MIN_Y) * CELLS_PER_LAYER;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                pos.set((blueprint.chunkX << 4) + x, y, (blueprint.chunkZ << 4) + z);
                chunk.setBlockState(pos, blueprint.states[layer + z * 16 + x], 0);
            }
        }
        return chunk;
    }

    private ProtoChunk copyChunk(ProtoChunk source) {
        ProtoChunk copy = this.newChunk(source.getPos().x(), source.getPos().z());
        var sections = source.getSections();
        var copySections = copy.getSections();
        System.arraycopy(sections, 0, copySections, 0, sections.length);
        copy.setPersistedStatus(ChunkStatus.BIOMES);
        copy.setPersistedStatus(ChunkStatus.TERRAIN);
        return copy;
    }

    ProtoChunk newChunk(int chunkX, int chunkZ) {
        return new ProtoChunk(new ChunkPos(chunkX, chunkZ), UpgradeData.EMPTY, this.height, this.palettes, null);
    }

    OfflineWorld openRegion(Map<Long, ProtoChunk> chunks) {
        return new OfflineWorld(this.seed, chunks, this.generator, this.randomState,
            this.height, this.registrySnapshot, this.worldRegistryAccess, this.defaultBiome, this::copyChunk);
    }

    static int generatedHeight(Map<Long, ProtoChunk> chunks, Heightmap.Types type, int worldX, int worldZ) {
        ProtoChunk chunk = chunks.get(ChunkPos.pack(worldX >> 4, worldZ >> 4));
        if (chunk == null) throw new IllegalStateException("offline height query crossed generated-region boundary");
        // Match WorldGenRegion: ChunkAccess stores the highest occupied Y; the level returns Y+1.
        return chunk.getHeight(type, worldX & 15, worldZ & 15) + 1;
    }

    private void decorateOrigin(WorldGenLevel level, Map<Long, ProtoChunk> region, int chunkX, int chunkZ,
                                 BooleanSupplier cancelled) {
        Set<Holder<Biome>> localBiomes = localBiomes(region, chunkX, chunkZ);
        if (localBiomes.isEmpty()) return;

        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(this.seed));
        int blockX = chunkX << 4;
        int blockZ = chunkZ << 4;
        long decorationSeed = random.setDecorationSeed(this.seed, blockX, blockZ);
        BlockPos origin = new BlockPos(blockX, SeedScanCore.MIN_Y, blockZ);
        FeaturePlacer placer = new FeaturePlacer(level, this.generator);
        for (int step = 0; step <= this.lastOreStep; step++) {
            if (cancelled.getAsBoolean()) return;
            FeatureSorter.StepFeatureData stepFeatures = this.featureSteps.get(step);
            IntSet indices = new IntArraySet();
            for (Holder<Biome> biome : localBiomes) {
                List<net.minecraft.core.HolderSet<PlacedFeature>> perStep = this.generator.getBiomeGenerationSettings(biome).features();
                if (step >= perStep.size()) continue;
                for (Holder<PlacedFeature> feature : perStep.get(step)) {
                    int index = stepFeatures.indexMapping().applyAsInt(feature.value());
                    if (index >= 0) indices.add(index);
                }
            }
            int[] order = indices.toIntArray();
            Arrays.sort(order);
            for (int index : order) {
                if (cancelled.getAsBoolean()) return;
                if (index < 0 || index >= stepFeatures.features().size()) continue;
                PlacedFeature feature = stepFeatures.features().get(index);
                if (this.recipeByFeature.containsKey(featureKey(step, index))) continue;
                random.setFeatureSeed(decorationSeed, index, step);
                placer.placeWithBiomeCheck(feature, random, origin);
            }
        }
    }

    private List<PredictedCluster> findClusters(Map<Long, ProtoChunk> region, int ownerChunkX, int ownerChunkZ,
                                                SeedFeatureTrace.Trace traces, List<SeedSymbolicOreReplay.Fog> fogs,
                                                SeedWriterOrder writerOrder, BooleanSupplier cancelled) {
        Map<Long, SeedDecorationModel.Outcome> outcomes = new HashMap<>();
        for (long position : traces.positions()) outcomes.put(position, traces.outcome(position, writerOrder));
        Map<Long, List<CellWrite>> traceWrites = traceWritesAt(traces);
        Map<Long, List<SeedSymbolicOreReplay.Fog>> fogsByChunk = indexFogsByChunk(fogs);
        Set<Long> resolvedFogPositions = new HashSet<>();
        ArrayList<PredictedCluster> found = new ArrayList<>();
        boolean overflow = false;
        for (byte kind : new byte[]{SeedScanCore.GRANITE, SeedScanCore.DIORITE, SeedScanCore.ANDESITE, SeedScanCore.GRAVEL}) {
            HashSet<Long> possible = new HashSet<>();
            int familyBit = familyBit(kind);
            for (int y = 0; y <= 30; y++) {
                for (int z = ownerChunkZ << 4; z < (ownerChunkZ << 4) + 16; z++) {
                    for (int x = ownerChunkX << 4; x < (ownerChunkX << 4) + 16; x++) {
                        long position = SeedScanCore.packBlock(x, y, z);
                        if (possibleAt(region, outcomes, resolvedFogPositions, traceWrites, fogsByChunk,
                            traces, writerOrder, position, kind, familyBit)) possible.add(position);
                    }
                }
            }
            HashSet<Long> seen = new HashSet<>();
            for (int y = 0; y <= 30 && !overflow; y++) {
                for (int z = ownerChunkZ << 4; z < (ownerChunkZ << 4) + 16 && !overflow; z++) {
                    for (int x = ownerChunkX << 4; x < (ownerChunkX << 4) + 16; x++) {
                        if (cancelled.getAsBoolean()) return List.of();
                        long start = SeedScanCore.packBlock(x, y, z);
                        if (!possible.contains(start) || !seen.add(start)) continue;
                        ArrayDeque<Long> open = new ArrayDeque<>();
                        ArrayList<Long> cells = new ArrayList<>();
                        open.addLast(start);
                        long owner = start;
                        boolean outOfBand = false;
                        while (!open.isEmpty()) {
                            long current = open.removeFirst();
                            cells.add(current);
                            int cy = SeedScanCore.unpackY(current);
                            if (cy >= 0 && cy <= 30 && before(current, owner)) owner = current;
                            if (cells.size() > MAX_CLUSTER_SIZE) { outOfBand = true; overflow = true; break; }
                            int cx = SeedScanCore.unpackX(current), cz = SeedScanCore.unpackZ(current);
                            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0) continue;
                                int nx = cx + dx, ny = cy + dy, nz = cz + dz;
                                long neighbor = SeedScanCore.packBlock(nx, ny, nz);
                                if (ny < -16 || ny > 63) {
                                    outOfBand = true;
                                    continue;
                                }
                                if (possibleAt(region, outcomes, resolvedFogPositions, traceWrites, fogsByChunk,
                                    traces, writerOrder, neighbor, kind, familyBit)) {
                                    possible.add(neighbor);
                                    if (seen.add(neighbor)) open.addLast(neighbor);
                                }
                            }
                        }
                        if (overflow) break;
                        if (outOfBand) continue;
                        if ((SeedScanCore.unpackX(owner) >> 4) != ownerChunkX || (SeedScanCore.unpackZ(owner) >> 4) != ownerChunkZ) continue;
                        HashSet<Long> inside = new HashSet<>(cells);
                        TreeSet<Long> shell = new TreeSet<>();
                        for (long cell : cells) {
                            int cx = SeedScanCore.unpackX(cell), cy = SeedScanCore.unpackY(cell), cz = SeedScanCore.unpackZ(cell);
                            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0) continue;
                                int ny = cy + dy;
                                if (ny < SeedScanCore.MIN_Y || ny > SeedScanCore.MAX_Y) continue;
                                long neighbor = SeedScanCore.packBlock(cx + dx, ny, cz + dz);
                                if (!inside.contains(neighbor)) shell.add(neighbor);
                            }
                        }
                        long[] cellArray = cells.stream().mapToLong(Long::longValue).sorted().toArray();
                        long[] certainArray = cells.stream().filter(pos -> {
                            var outcome = cellOutcome(region, outcomes, resolvedFogPositions, traceWrites,
                                fogsByChunk, traces, writerOrder, pos);
                            int cx = SeedScanCore.unpackX(pos), cy = SeedScanCore.unpackY(pos), cz = SeedScanCore.unpackZ(pos);
                            return outcome == null ? modelKindAt(region, cx, cy, cz) == kind : outcome.certain() == kind;
                        }).mapToLong(Long::longValue).sorted().toArray();
                        long[] shellArray = shell.stream().mapToLong(Long::longValue).toArray();
                        byte[] shellModel = new byte[shellArray.length];
                        byte[] shellFlags = new byte[shellArray.length];
                        for (int i = 0; i < shellArray.length; i++) {
                            int sx = SeedScanCore.unpackX(shellArray[i]), sy = SeedScanCore.unpackY(shellArray[i]), sz = SeedScanCore.unpackZ(shellArray[i]);
                            var outcome = cellOutcome(region, outcomes, resolvedFogPositions, traceWrites,
                                fogsByChunk, traces, writerOrder, shellArray[i]);
                            byte certain = outcome == null ? modelKindAt(region, sx, sy, sz) : outcome.certain();
                            int families = outcome == null ? familyBit(certain) : outcome.familyMask();
                            shellModel[i] = certain;
                            int flags = families;
                            if (outcome == null ? certain == SeedScanCore.AIR : outcome.canBe(SeedScanCore.AIR)) flags |= 16;
                            if (sy >= 0 && sy <= 30) flags |= 32;
                            if (certain == SeedScanCore.STONE) flags |= 64;
                            shellFlags[i] = (byte)flags;
                        }
                        boolean complete = !candidateHasUnknownFog(fogs, cellArray, shellArray);
                        ArrayList<SeedFeatureTrace.Probe> probes = new ArrayList<>();
                        for (long cell : cellArray) {
                            var probe = traces.probe(cell);
                            if (probe != null) probes.add(probe);
                        }
                        found.add(new PredictedCluster(kind, owner, cellArray, shellArray, shellModel, complete,
                            certainArray, shellFlags, probes));
                        if (found.size() >= MAX_PREDICTIONS) { overflow = true; break; }
                    }
                }
            }
        }
        boolean unknownFogAtTarget = fogs.stream().anyMatch(fog -> unknownFog(fog) &&
            intersectsChunk(fog, ownerChunkX, ownerChunkZ, 0, 30));
        if (overflow || unknownFogAtTarget) found.add(incompleteSentinel(ownerChunkX, ownerChunkZ));
        return List.copyOf(found);
    }

    record CellWrite(long origin, int stage, int featureIndex, int sequence,
                     SeedDecorationModel.Write write) {}

    private static Map<Long, List<CellWrite>> traceWritesAt(SeedFeatureTrace.Trace traces) {
        Map<Long, List<CellWrite>> byPosition = new HashMap<>();
        for (SeedFeatureTrace.Feature feature : traces.features()) {
            for (int i = 0; i < feature.writes().size(); i++) {
                SeedFeatureTrace.Event event = feature.writes().get(i);
                byPosition.computeIfAbsent(event.position(), ignored -> new ArrayList<>()).add(
                    new CellWrite(feature.origin(), feature.stage(), feature.index(), i,
                        new SeedDecorationModel.Write(event.kind(), event.optional())));
            }
        }
        return byPosition;
    }

    private static Map<Long, List<SeedSymbolicOreReplay.Fog>> indexFogsByChunk(
        List<SeedSymbolicOreReplay.Fog> fogs) {
        Map<Long, List<SeedSymbolicOreReplay.Fog>> byChunk = new HashMap<>();
        for (SeedSymbolicOreReplay.Fog fog : fogs) {
            for (int chunkX = fog.minX() >> 4; chunkX <= fog.maxX() >> 4; chunkX++) {
                for (int chunkZ = fog.minZ() >> 4; chunkZ <= fog.maxZ() >> 4; chunkZ++) {
                    byChunk.computeIfAbsent(SeedScanCore.packChunk(chunkX, chunkZ), ignored -> new ArrayList<>()).add(fog);
                }
            }
        }
        byChunk.replaceAll((ignored, value) -> List.copyOf(value));
        return byChunk;
    }

    private static boolean possibleAt(Map<Long, ProtoChunk> region, Map<Long, SeedDecorationModel.Outcome> outcomes,
                                      Set<Long> resolvedFogPositions, Map<Long, List<CellWrite>> traceWrites,
                                      Map<Long, List<SeedSymbolicOreReplay.Fog>> fogsByChunk,
                                      SeedFeatureTrace.Trace traces, SeedWriterOrder writerOrder,
                                      long position, byte kind, int familyBit) {
        var outcome = cellOutcome(region, outcomes, resolvedFogPositions, traceWrites, fogsByChunk,
            traces, writerOrder, position);
        if (outcome != null) return (outcome.familyMask() & familyBit) != 0;
        return modelKindAt(region, SeedScanCore.unpackX(position), SeedScanCore.unpackY(position),
            SeedScanCore.unpackZ(position)) == kind;
    }

    private static SeedDecorationModel.Outcome cellOutcome(
        Map<Long, ProtoChunk> region, Map<Long, SeedDecorationModel.Outcome> outcomes,
        Set<Long> resolvedFogPositions, Map<Long, List<CellWrite>> traceWrites,
        Map<Long, List<SeedSymbolicOreReplay.Fog>> fogsByChunk, SeedFeatureTrace.Trace traces,
        SeedWriterOrder writerOrder, long position) {
        int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
        List<SeedSymbolicOreReplay.Fog> indexed = fogsByChunk.get(SeedScanCore.packChunk(x >> 4, z >> 4));
        if (indexed == null) return outcomes.get(position);
        ArrayList<SeedSymbolicOreReplay.Fog> matching = new ArrayList<>();
        for (SeedSymbolicOreReplay.Fog fog : indexed) if (fog.contains(x, y, z)) matching.add(fog);
        if (matching.isEmpty()) return outcomes.get(position);
        if (resolvedFogPositions.contains(position)) return outcomes.get(position);

        byte terrain = traces.terrain(position);
        if (terrain == SeedScanCore.MISSING) terrain = modelKindAt(region, x, y, z);
        SeedDecorationModel.Outcome resolved = resolveFogOutcome(terrain,
            traceWrites.getOrDefault(position, List.of()), matching, position, writerOrder);
        outcomes.put(position, resolved);
        resolvedFogPositions.add(position);
        return resolved;
    }

    /** Resolves all writes and possible fog writes in their same-origin feature order. */
    static SeedDecorationModel.Outcome resolveFogOutcome(byte terrain, List<CellWrite> traceWrites,
                                                          List<SeedSymbolicOreReplay.Fog> fogs,
                                                          long position, SeedWriterOrder writerOrder) {
        if (fogs.stream().anyMatch(VanillaSeedWorldgen::unknownFog)) {
            return new SeedDecorationModel.Outcome((1 << (SeedScanCore.CLAY + 1)) - 1, false);
        }
        if (terrain < 0 || terrain > SeedScanCore.CLAY || terrain == SeedScanCore.MISSING) {
            return new SeedDecorationModel.Outcome((1 << (SeedScanCore.CLAY + 1)) - 1, false);
        }
        Map<Long, List<CellWrite>> byOrigin = new HashMap<>();
        for (CellWrite write : traceWrites) byOrigin.computeIfAbsent(write.origin(), ignored -> new ArrayList<>()).add(write);
        for (SeedSymbolicOreReplay.Fog fog : fogs) {
            if (fog.stage() < 0 || fog.featureIndex() < 0 || fog.possibleKind() < 0 ||
                fog.possibleKind() > SeedScanCore.CLAY || fog.possibleKind() == SeedScanCore.MISSING) {
                return new SeedDecorationModel.Outcome((1 << (SeedScanCore.CLAY + 1)) - 1, false);
            }
            byOrigin.computeIfAbsent(fog.origin(), ignored -> new ArrayList<>()).add(
                new CellWrite(fog.origin(), fog.stage(), fog.featureIndex(), Integer.MAX_VALUE,
                    new SeedDecorationModel.Write(fog.possibleKind(), true)));
        }
        ArrayList<SeedDecorationModel.Writer> writers = new ArrayList<>(byOrigin.size());
        for (var entry : byOrigin.entrySet()) {
            List<CellWrite> sequence = entry.getValue();
            sequence.sort(Comparator.comparingInt(CellWrite::stage).thenComparingInt(CellWrite::featureIndex)
                .thenComparingInt(CellWrite::sequence));
            writers.add(new SeedDecorationModel.Writer(entry.getKey(), sequence.stream().map(CellWrite::write).toList()));
        }
        return SeedDecorationModel.resolve(terrain, writers, writerOrder::precedes);
    }

    private static boolean unknownFog(SeedSymbolicOreReplay.Fog fog) {
        return fog.stage() < 0 || fog.featureIndex() < 0 || fog.possibleKind() < 0 ||
            fog.possibleKind() > SeedScanCore.CLAY || fog.possibleKind() == SeedScanCore.MISSING;
    }

    private static boolean intersects(SeedSymbolicOreReplay.Fog fog, long[] positions) {
        for (long position : positions) if (fog.contains(SeedScanCore.unpackX(position), SeedScanCore.unpackY(position), SeedScanCore.unpackZ(position))) return true;
        return false;
    }

    static boolean candidateHasUnknownFog(List<SeedSymbolicOreReplay.Fog> fogs, long[] cells, long[] shell) {
        for (SeedSymbolicOreReplay.Fog fog : fogs)
            if (unknownFog(fog) && (intersects(fog, cells) || intersects(fog, shell))) return true;
        return false;
    }

    private static boolean intersectsChunk(SeedSymbolicOreReplay.Fog fog, int chunkX, int chunkZ, int minY, int maxY) {
        int minX = chunkX << 4, minZ = chunkZ << 4;
        return fog.minX() <= minX + 15 && fog.maxX() >= minX && fog.minZ() <= minZ + 15 && fog.maxZ() >= minZ &&
            fog.minY() <= maxY && fog.maxY() >= minY;
    }

    private static PredictedCluster incompleteSentinel(int chunkX, int chunkZ) {
        long position = SeedScanCore.packBlock((chunkX << 4) + 8, 0, (chunkZ << 4) + 8);
        return new PredictedCluster(SeedScanCore.GRANITE, position, new long[]{position}, new long[0],
            new byte[0], false, new long[0], new byte[0], List.of());
    }

    private static int familyBit(byte kind) {
        return switch (kind) {
            case SeedScanCore.GRANITE -> 1;
            case SeedScanCore.DIORITE -> 2;
            case SeedScanCore.ANDESITE -> 4;
            case SeedScanCore.GRAVEL -> 8;
            default -> 0;
        };
    }

    private boolean buildSurface(Map<Long, ProtoChunk> region, BiomeManager biomeManager, BooleanSupplier cancelled) {
        if (SharedConstants.DEBUG_DISABLE_SURFACE) return true;
        MaterialRule rule = this.settings.value().materialRule().value();
        WorldGenerationContext context = new WorldGenerationContext(this.generator, this.height);
        NoiseSettings noiseSettings = this.settings.value().noiseSettings().clampToHeightAccessor(this.height);
        for (ProtoChunk chunk : region.values()) {
            if (cancelled.getAsBoolean()) return false;
            if (SharedConstants.debugVoidTerrain(chunk.getPos())) continue;
            NoiseChunk noise = this.newNoiseChunk(chunk, noiseSettings);
            try {
                Set<Holder<Biome>> chunkBiomes = new HashSet<>();
                chunk.collectBiomesInPalette(chunkBiomes);
                chunkBiomes.retainAll(this.generator.getBiomeSource().possibleBiomes());
                this.randomState.surfaceSystem().buildSurface(this.randomState, biomeManager, context, chunk,
                    noise, rule, chunkBiomes);
            } finally {
                noise.close();
            }
        }
        return true;
    }

    private boolean carve(Map<Long, ProtoChunk> region, BiomeManager biomeManager, BooleanSupplier cancelled) {
        Blender blender = Blender.empty();
        CarvingMask.Filter filter = blender.getCarvingFilter();
        BiomeSource source = this.generator.getBiomeSource();
        var resolver = source.createUncachedResolver(this.randomState);
        WorldGenerationContext context = new WorldGenerationContext(this.generator, this.height);
        NoiseSettings noiseSettings = this.settings.value().noiseSettings().clampToHeightAccessor(this.height);
        MaterialRule materialRule = this.settings.value().materialRule().value();
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        for (ProtoChunk chunk : region.values()) {
            if (cancelled.getAsBoolean()) return false;
            if (SharedConstants.DEBUG_DISABLE_CARVERS || SharedConstants.debugVoidTerrain(chunk.getPos())) continue;
            NoiseChunk noise = this.newNoiseChunk(chunk, noiseSettings);
            try {
                CarvingMask mask = new CarvingMask(context.getMinGenY() + 1,
                    context.getMinGenY() + context.getGenDepth() - 1 - 7);
                ChunkPos target = chunk.getPos();
                for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) {
                    if (cancelled.getAsBoolean()) return false;
                    ChunkPos origin = new ChunkPos(target.x() + dx, target.z() + dz);
                    int quartX = QuartPos.fromBlock(origin.getMinBlockX());
                    int quartZ = QuartPos.fromBlock(origin.getMinBlockZ());
                    Holder<Biome> biome = resolver.getNoiseBiome(quartX, 0, quartZ);
                    Iterable<Holder<WorldCarver>> carvers = this.generator.getBiomeGenerationSettings(biome).getCarvers();
                    int index = 0;
                    for (Holder<WorldCarver> holder : carvers) {
                        if (cancelled.getAsBoolean()) return false;
                        WorldCarver carver = holder.value();
                        random.setLargeFeatureSeed(this.randomState.seed() + index, origin.x(), origin.z());
                        if (carver.isStartChunk(random)) carver.carve(context, random, target, origin, mask);
                        index++;
                    }
                }
                if (!mask.isEmpty() && !this.applyCarvingMask(chunk, mask, noise, biomeManager, context,
                    materialRule, filter, cancelled)) return false;
            } finally {
                noise.close();
            }
        }
        return true;
    }

    private boolean applyCarvingMask(ProtoChunk chunk, CarvingMask mask, NoiseChunk noise, BiomeManager biomeManager,
                                     WorldGenerationContext context, MaterialRule materialRule,
                                     CarvingMask.Filter filter, BooleanSupplier cancelled) {
        ChunkPos chunkPos = chunk.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();
        Aquifer aquifer = noise.aquifer();
        MaterialSystem surface = this.randomState.surfaceSystem();
        mask.visit((localX, localZ, minY, maxY) -> {
            if (cancelled.getAsBoolean()) return;
            int x = chunkPos.getBlockX(localX);
            int z = chunkPos.getBlockZ(localZ);
            for (int y = maxY; y >= minY; y--) {
                if (cancelled.getAsBoolean() || filter != null && !filter.test(localX, y, localZ)) continue;
                pos.set(x, y, z);
                BlockState old = chunk.getBlockState(pos);
                if (old.is(BlockTags.UNCARVABLE)) continue;
                boolean restoreSurface = old.is(Blocks.GRASS_BLOCK) || old.is(Blocks.MYCELIUM);
                BlockState carved = aquifer.computeSubstance(x, y, z, 0.0);
                if (carved == null) continue;
                chunk.setBlockState(pos, carved);
                if (aquifer.shouldScheduleFluidUpdate() && !carved.getFluidState().isEmpty()) {
                    chunk.markPosForPostProcessing(pos);
                }
                if (restoreSurface) {
                    below.setWithOffset(pos, net.minecraft.core.Direction.DOWN);
                    if (chunk.getBlockState(below).is(Blocks.DIRT)) {
                        surface.topMaterial(materialRule, this.randomState, context, biomeManager::getBiome, chunk,
                            noise.cachingSamplers(), below, !carved.getFluidState().isEmpty())
                            .ifPresent(state -> chunk.setBlockState(below, state));
                    }
                }
            }
        });
        return !cancelled.getAsBoolean();
    }

    private NoiseChunk newNoiseChunk(ProtoChunk chunk, NoiseSettings noiseSettings) {
        ChunkPos pos = chunk.getPos();
        DensityVolume volume = new DensityVolume(16, noiseSettings.height(), 16, pos.getMinBlockX(),
            noiseSettings.minY(), pos.getMinBlockZ());
        return new NoiseChunk(this.randomState, Beardifier.EMPTY, this.settings.value(), this.fluidPicker,
            Blender.empty(), volume);
    }

    private static Aquifer.FluidPicker fluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus water = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        Aquifer.FluidStatus air = new Aquifer.FluidStatus(DimensionType.MIN_Y * 2, Blocks.AIR.defaultBlockState());
        return (x, y, z) -> {
            if (SharedConstants.DEBUG_DISABLE_FLUID_GENERATION) return air;
            return y < Math.min(-54, water.fluidLevel()) ? lava : water;
        };
    }

    private static boolean before(long left, long right) {
        int leftY = SeedScanCore.unpackY(left), rightY = SeedScanCore.unpackY(right);
        if (leftY != rightY) return leftY < rightY;
        int leftZ = SeedScanCore.unpackZ(left), rightZ = SeedScanCore.unpackZ(right);
        return leftZ != rightZ ? leftZ < rightZ : SeedScanCore.unpackX(left) < SeedScanCore.unpackX(right);
    }

    private static byte kindAt(Map<Long, ProtoChunk> region, int x, int y, int z) {
        ProtoChunk chunk = region.get(ChunkPos.pack(x >> 4, z >> 4));
        if (chunk == null) return SeedScanCore.MISSING;
        Block block = chunk.getBlockState(new BlockPos(x, y, z)).getBlock();
        if (block == Blocks.GRANITE) return SeedScanCore.GRANITE;
        if (block == Blocks.DIORITE) return SeedScanCore.DIORITE;
        if (block == Blocks.ANDESITE) return SeedScanCore.ANDESITE;
        if (block == Blocks.GRAVEL) return SeedScanCore.GRAVEL;
        return SeedScanCore.NATURAL;
    }

    private static byte modelKindAt(Map<Long, ProtoChunk> region, int x, int y, int z) {
        ProtoChunk chunk = region.get(ChunkPos.pack(x >> 4, z >> 4));
        if (chunk == null) return SeedScanCore.MISSING;
        BlockState state = chunk.getBlockState(new BlockPos(x, y, z));
        if (state.isAir() || !state.getFluidState().isEmpty()) return SeedScanCore.AIR;
        Block block = state.getBlock();
        if (block == Blocks.GRANITE) return SeedScanCore.GRANITE;
        if (block == Blocks.DIORITE) return SeedScanCore.DIORITE;
        if (block == Blocks.ANDESITE) return SeedScanCore.ANDESITE;
        if (block == Blocks.GRAVEL) return SeedScanCore.GRAVEL;
        if (block == Blocks.STONE || block == Blocks.DEEPSLATE) return SeedScanCore.STONE;
        if (block == Blocks.TUFF) return SeedScanCore.TUFF;
        if (block == Blocks.DIRT) return SeedScanCore.DIRT;
        if (block == Blocks.CLAY) return SeedScanCore.CLAY;
        return SeedScanCore.MODIFIED;
    }

    private static String biomeKey(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.identifier()).map(Object::toString).orElse("~direct");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object registryLookup(HolderLookup.Provider provider, Object key) {
        return provider.lookup((ResourceKey)key).map(value -> registryProxy((HolderLookup.RegistryLookup<?>)value));
    }

    private static Registry<?> registryProxy(HolderLookup.RegistryLookup<?> lookup) {
        return (Registry<?>)Proxy.newProxyInstance(Registry.class.getClassLoader(), new Class<?>[]{Registry.class},
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                Method delegated;
                try {
                    delegated = HolderLookup.RegistryLookup.class.getMethod(method.getName(), method.getParameterTypes());
                } catch (NoSuchMethodException unavailable) {
                    throw new UnsupportedOperationException("offline registry lookup does not expose " + method.getName(), unavailable);
                }
                try {
                    return delegated.invoke(lookup, args);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            });
    }

    private static RegistryAccess registryAccessProxy(HolderLookup.Provider provider) {
        return (RegistryAccess)Proxy.newProxyInstance(RegistryAccess.class.getClassLoader(), new Class<?>[]{RegistryAccess.class},
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                if (method.getName().equals("lookup")) return registryLookup(provider, args[0]);
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                throw new UnsupportedOperationException("offline registry view does not expose " + method.getName());
            });
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "DonutOfflineRegistryView";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
    }

    private static final class TerrainBlueprint {
        private final int chunkX;
        private final int chunkZ;
        private final BlockState[] states;
        private final Holder<Biome>[] biomes;
        private final boolean cancelled;
        private TerrainBlueprint(int chunkX, int chunkZ, BlockState[] states, Holder<Biome>[] biomes, boolean cancelled) {
            this.chunkX = chunkX; this.chunkZ = chunkZ; this.states = states; this.biomes = biomes; this.cancelled = cancelled;
        }
        private static TerrainBlueprint cancelled() { return new TerrainBlueprint(0, 0, new BlockState[0], new Holder[0], true); }
    }

    private static final class BiomeIdMap implements IdMap<Holder<Biome>> {
        private final List<Holder<Biome>> values;
        private final Map<Holder<Biome>, Integer> ids;
        private BiomeIdMap(List<Holder<Biome>> values, Map<Holder<Biome>, Integer> ids) { this.values = values; this.ids = ids; }
        @Override public int getId(Holder<Biome> value) { return this.ids.getOrDefault(value, -1); }
        @Override public Holder<Biome> byId(int id) { return id >= 0 && id < this.values.size() ? this.values.get(id) : null; }
        @Override public int size() { return this.values.size(); }
        @Override public java.util.Iterator<Holder<Biome>> iterator() { return this.values.iterator(); }
    }

    static final class OfflineWorld implements InvocationHandler {
        private final long seed;
        private final Map<Long, ProtoChunk> chunks;
        private final NoiseBasedChunkGenerator generator;
        private final RandomState randomState;
        private final LevelHeightAccessor height;
        private final HolderLookup.Provider lookups;
        private final Holder<Biome> fallbackBiome;
        private final BiomeManager biomeManager;
        private final WorldGenLevel proxy;
        private final RegistryAccess registryAccess;
        private final java.util.function.Function<ProtoChunk, ProtoChunk> chunkCopier;
        private final LevelTickAccess<Block> blockTicks = new IsolatedTickAccess<>(false);
        private final LevelTickAccess<net.minecraft.world.level.material.Fluid> fluidTicks = new IsolatedTickAccess<>(true);
        private final OfflineTickClock tickClock = new OfflineTickClock();

        private OfflineWorld(long seed, Map<Long, ProtoChunk> chunks, NoiseBasedChunkGenerator generator,
                             RandomState randomState, LevelHeightAccessor height, HolderLookup.Provider lookups,
                             RegistryAccess registryAccess, Holder<Biome> fallbackBiome,
                             java.util.function.Function<ProtoChunk, ProtoChunk> chunkCopier) {
            this.seed = seed; this.chunks = chunks; this.generator = generator; this.randomState = randomState;
            this.height = height; this.lookups = lookups; this.registryAccess = registryAccess; this.fallbackBiome = fallbackBiome;
            this.chunkCopier = chunkCopier;
            this.biomeManager = new BiomeManager(this::noiseBiome, BiomeManager.obfuscateSeed(seed));
            this.proxy = (WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, this);
        }

        WorldGenLevel proxy() { return this.proxy; }

        OfflineWorld scratch(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ,
                             int minY, int maxY, Map<Long, BlockState> overlay,
                             Map<Long, BlockState> observed) {
            Map<Long, ProtoChunk> scratch = new HashMap<>(this.chunks);
            Map<Long, Set<Integer>> mutable = new HashMap<>();
            Set<Long> cloned = new HashSet<>();
            for (int x = minChunkX; x <= maxChunkX; x++) for (int z = minChunkZ; z <= maxChunkZ; z++) {
                long key = ChunkPos.pack(x, z);
                ProtoChunk source = this.chunks.get(key);
                if (source == null) return null;
                scratch.put(key, this.chunkCopier.apply(source));
                cloned.add(key);
                Set<Integer> sections = mutable.computeIfAbsent(key, ignored -> new HashSet<>());
                int minSection = this.height.getSectionIndex(minY);
                int maxSection = this.height.getSectionIndex(maxY);
                for (int section = minSection; section <= maxSection; section++) sections.add(section);
            }
            for (var entry : overlay.entrySet()) {
                long position = entry.getKey();
                int x = SeedScanCore.unpackX(position), z = SeedScanCore.unpackZ(position);
                long chunkKey = ChunkPos.pack(x >> 4, z >> 4);
                ProtoChunk source = this.chunks.get(chunkKey);
                if (source == null) return null;
                if (cloned.add(chunkKey)) scratch.put(chunkKey, this.chunkCopier.apply(source));
                mutable.computeIfAbsent(chunkKey, ignored -> new HashSet<>())
                    .add(this.height.getSectionIndex(SeedScanCore.unpackY(position)));
            }
            for (var entry : observed.entrySet()) {
                long position = entry.getKey();
                int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
                long chunkKey = ChunkPos.pack(x >> 4, z >> 4);
                ProtoChunk source = this.chunks.get(chunkKey);
                if (source == null) return null;
                if (cloned.add(chunkKey)) scratch.put(chunkKey, this.chunkCopier.apply(source));
                mutable.computeIfAbsent(chunkKey, ignored -> new HashSet<>()).add(this.height.getSectionIndex(y));
            }
            for (var entry : mutable.entrySet()) {
                ProtoChunk target = scratch.get(entry.getKey());
                ProtoChunk source = this.chunks.get(entry.getKey());
                for (int section : entry.getValue()) {
                    if (section < 0 || section >= target.getSections().length) return null;
                    target.getSections()[section] = source.getSections()[section].copy();
                }
            }
            for (var entry : overlay.entrySet()) {
                long position = entry.getKey();
                int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
                ProtoChunk target = scratch.get(ChunkPos.pack(x >> 4, z >> 4));
                target.setBlockState(new BlockPos(x, y, z), entry.getValue(), 0);
            }
            for (var entry : observed.entrySet()) {
                long position = entry.getKey();
                int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
                ProtoChunk target = scratch.get(ChunkPos.pack(x >> 4, z >> 4));
                target.setBlockState(new BlockPos(x, y, z), entry.getValue(), 0);
            }
            return new OfflineWorld(this.seed, scratch, this.generator, this.randomState, this.height,
                this.lookups, this.registryAccess, this.fallbackBiome, this.chunkCopier);
        }

        BlockState blockState(BlockPos pos) { return this.chunkAt(pos).getBlockState(pos); }

        Map<Long, BlockState> snapshot(BlockPos center, SeedOreRecipes.Recipe recipe) {
            int horizontal = recipe.horizontalMargin();
            int vertical = recipe.verticalReach() + 2;
            long volume = (long)(horizontal * 2 + 1) * (horizontal * 2 + 1) * (vertical * 2 + 1);
            if (volume > SeedFeatureTrace.MAX_WRITES)
                throw new IllegalStateException("Native ore snapshot exceeded its safety limit");
            Map<Long, BlockState> states = new HashMap<>((int)Math.min(volume * 4 / 3 + 1, Integer.MAX_VALUE));
            int minY = Math.max(SeedScanCore.MIN_Y, center.getY() - vertical);
            int maxY = Math.min(SeedScanCore.MAX_Y, center.getY() + vertical);
            for (int y = minY; y <= maxY; y++) for (int z = center.getZ() - horizontal; z <= center.getZ() + horizontal; z++)
                for (int x = center.getX() - horizontal; x <= center.getX() + horizontal; x++) {
                    long key = SeedScanCore.packBlock(x, y, z);
                    states.put(key, this.blockState(new BlockPos(x, y, z)));
                }
            return states;
        }

        private BiomeManager biomeManager() { return this.biomeManager; }

        private Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
            int x = QuartPos.toBlock(quartX) >> 4, z = QuartPos.toBlock(quartZ) >> 4;
            ProtoChunk chunk = this.chunks.get(ChunkPos.pack(x, z));
            return chunk == null ? this.fallbackBiome : chunk.getNoiseBiome(quartX, quartY, quartZ);
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
            String name = method.getName();
            if (name.equals("getSeed")) return this.seed;
            if (name.equals("isClientSide")) return false;
            if (name.equals("getMinY")) return this.height.getMinY();
            if (name.equals("getHeight")) {
                if (args == null || args.length == 0) return this.height.getHeight();
                return generatedHeight(this.chunks, (Heightmap.Types)args[0], (int)args[1], (int)args[2]);
            }
            if (name.equals("getSeaLevel")) return this.generator.getSeaLevel();
            if (name.equals("registryAccess")) return this.registryAccess;
            if (name.equals("getBiomeManager")) return this.biomeManager;
            if (name.equals("getBlockTicks")) return this.blockTicks;
            if (name.equals("getFluidTicks")) return this.fluidTicks;
            if (name.equals("createTick")) {
                BlockPos pos = (BlockPos)args[0];
                if (args.length == 4) {
                    return this.tickClock.create(pos, (Object)args[1], (int)args[2], (net.minecraft.world.ticks.TickPriority)args[3]);
                }
                return this.tickClock.create(pos, (Object)args[1], (int)args[2]);
            }
            if (name.equals("getNoiseBiome") || name.equals("getUncachedNoiseBiome")) return this.noiseBiome((int)args[0], (int)args[1], (int)args[2]);
            if (name.equals("getChunk")) {
                if (args[0] instanceof BlockPos pos) return this.chunkAt(pos);
                ProtoChunk chunk = this.chunks.get(ChunkPos.pack((int)args[0], (int)args[1]));
                if (chunk == null && args.length == 4 && Boolean.TRUE.equals(args[3])) {
                    throw new IllegalStateException("offline feature requested an ungenerated chunk");
                }
                return chunk;
            }
            if (name.equals("hasChunk")) return this.chunks.containsKey(ChunkPos.pack((int)args[0], (int)args[1]));
            if (name.equals("getBlockState")) return this.chunkAt((BlockPos)args[0]).getBlockState((BlockPos)args[0]);
            if (name.equals("getFluidState")) return this.chunkAt((BlockPos)args[0]).getFluidState((BlockPos)args[0]);
            if (name.equals("isStateAtPosition")) {
                @SuppressWarnings("unchecked")
                var predicate = (java.util.function.Predicate<BlockState>)args[1];
                return predicate.test(this.chunkAt((BlockPos)args[0]).getBlockState((BlockPos)args[0]));
            }
            if (name.equals("isFluidAtPosition")) {
                @SuppressWarnings("unchecked")
                var predicate = (java.util.function.Predicate<net.minecraft.world.level.material.FluidState>)args[1];
                return predicate.test(this.chunkAt((BlockPos)args[0]).getFluidState((BlockPos)args[0]));
            }
            if (name.equals("getBlockEntity")) return this.blockEntityAt((BlockPos)args[0]);
            if (name.equals("setBlock") && args != null && args.length >= 3) {
                BlockPos pos = (BlockPos)args[0];
                ProtoChunk chunk = this.chunkAt(pos);
                BlockState next = (BlockState)args[1];
                chunk.setBlockState(pos, next, (int)args[2]);
                BlockEntity previous = chunk.getBlockEntity(pos);
                if (previous != null && !previous.isValidBlockState(next)) chunk.removeBlockEntity(pos);
                return true;
            }
            // Level metadata belongs to a live server and is not fabricated for offline worldgen.
            // A feature that requires it fails the prediction instead of receiving plausible defaults.
            if (name.equals("getServer") || name.equals("getLevelData") || name.equals("environmentAttributes") || name.equals("dimensionType")) {
                throw new UnsupportedOperationException("offline ore generation does not expose " + name);
            }
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            throw new UnsupportedOperationException("offline ore generation requested " + name);
        }

        private ProtoChunk chunkAt(BlockPos pos) {
            ProtoChunk chunk = this.chunks.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
            if (chunk == null) throw new IllegalStateException("offline feature crossed generated-region boundary at " + pos);
            return chunk;
        }

        private BlockEntity blockEntityAt(BlockPos pos) {
            ProtoChunk chunk = this.chunkAt(pos);
            BlockEntity existing = chunk.getBlockEntity(pos);
            if (existing != null) return existing;
            BlockState state = chunk.getBlockState(pos);
            if (!(state.getBlock() instanceof EntityBlock factory)) return null;
            if (chunk.getBlockEntitiesPos().size() >= 4_096) {
                throw new IllegalStateException("offline feature exceeded the block entity limit");
            }
            BlockEntity created = factory.newBlockEntity(pos.immutable(), state);
            // These objects belong only to the isolated region and never receive a client/server Level.
            if (created != null) chunk.setBlockEntity(created);
            return created;
        }

        private final class IsolatedTickAccess<T> implements LevelTickAccess<T> {
            private final boolean fluid;

            private IsolatedTickAccess(boolean fluid) { this.fluid = fluid; }

            @Override public void schedule(ScheduledTick<T> tick) {
                ProtoChunk chunk = OfflineWorld.this.chunkAt(tick.pos());
                TickContainerAccess<T> target = this.container(chunk);
                boolean duplicate = target.hasScheduledTick(tick.pos(), tick.type());
                int total = chunk.getBlockTicks().count() + chunk.getFluidTicks().count();
                OfflineTickClock.requireCapacity(total, duplicate, tick.pos());
                target.schedule(tick);
            }

            @Override public boolean hasScheduledTick(BlockPos pos, T type) {
                return this.container(OfflineWorld.this.chunkAt(pos)).hasScheduledTick(pos, type);
            }

            @Override public int count() {
                int total = 0;
                for (ProtoChunk chunk : OfflineWorld.this.chunks.values()) total += this.container(chunk).count();
                return total;
            }

            @Override public boolean willTickThisTick(BlockPos pos, T type) {
                throw new UnsupportedOperationException("offline worldgen does not advance scheduled ticks");
            }

            @SuppressWarnings("unchecked")
            private TickContainerAccess<T> container(ProtoChunk chunk) {
                return (TickContainerAccess<T>)(this.fluid ? chunk.getFluidTicks() : chunk.getBlockTicks());
            }
        }
    }
}
