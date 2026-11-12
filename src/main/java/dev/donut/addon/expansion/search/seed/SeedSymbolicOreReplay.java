package dev.donut.addon.expansion.search.seed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

/** Replays native ore features from cloned Minecraft RNG states when the source height gate is uncertain. */
final class SeedSymbolicOreReplay {
    static final int MAX_STATES = 1_024;
    static final int MAX_WORK = 30_000;
    static final int MAX_VARIANT_CELLS = 8_192;
    static final int MAX_TOTAL_VARIANT_CELLS = 1_048_576;

    record Fog(long origin, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String reason,
               int stage, int featureIndex, byte possibleKind) {
        Fog(long origin, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String reason) {
            this(origin, minX, minY, minZ, maxX, maxY, maxZ, reason, -1, -1, SeedScanCore.MISSING);
        }
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }
    record Result(List<Fog> fogs, int nativeCalls, int peakStates, int peakOverlayCells) {
        Result { fogs = List.copyOf(fogs); }
    }

    private static final class Variant {
        SeedRandomBranch random;
        final Map<Long, BlockState> overlay;
        final Set<Long> touched = new HashSet<>();
        boolean observed;
        boolean observationForked;

        Variant(SeedRandomBranch random) {
            this.random = random;
            this.overlay = new HashMap<>();
        }
        Variant fork() {
            Variant copy = new Variant(random.branch());
            copy.overlay.putAll(overlay);
            copy.touched.addAll(touched);
            copy.observed = observed;
            copy.observationForked = observationForked;
            return copy;
        }
    }

    private final long seed;
    private final int chunkX;
    private final int chunkZ;
    private final VanillaSeedWorldgen.OfflineWorld baseline;
    private final ChunkGenerator generator;
    private final List<SeedOreRecipes.Recipe> recipes;
    private final SeedGroundGate gate;
    private final SeedFeatureTrace.Builder trace;
    private final SeedFeatureTrace.Trace oracleTrace;
    private final SeedScanCore.Snapshot observed;
    private final SeedWriterOrder writerOrder;
    private final Map<Long, Set<Long>> predictedOreWriters = new HashMap<>();
    private final BooleanSupplier cancelled;
    private final int minChunkX, maxChunkX, minChunkZ, maxChunkZ;
    private final ArrayList<Fog> fogs = new ArrayList<>();
    private int nativeCalls;
    private int peakStates = 1;
    private int peakOverlayCells;
    private String overlayBudgetFailure = "";

    SeedSymbolicOreReplay(long seed, int chunkX, int chunkZ, VanillaSeedWorldgen.OfflineWorld baseline, ChunkGenerator generator,
                          List<SeedOreRecipes.Recipe> recipes, SeedGroundGate gate,
                          SeedFeatureTrace.Builder trace, BooleanSupplier cancelled) {
        this(seed, chunkX, chunkZ, baseline, generator, recipes, gate, trace, cancelled, null, null);
    }

    SeedSymbolicOreReplay(long seed, int chunkX, int chunkZ, VanillaSeedWorldgen.OfflineWorld baseline, ChunkGenerator generator,
                          List<SeedOreRecipes.Recipe> recipes, SeedGroundGate gate, SeedFeatureTrace.Builder trace,
                          BooleanSupplier cancelled, SeedFeatureTrace.Trace oracleTrace, SeedScanCore.Snapshot observed) {
        this(seed, chunkX, chunkZ, baseline, generator, recipes, gate, trace, cancelled, oracleTrace, observed, null);
    }

    SeedSymbolicOreReplay(long seed, int chunkX, int chunkZ, VanillaSeedWorldgen.OfflineWorld baseline, ChunkGenerator generator,
                          List<SeedOreRecipes.Recipe> recipes, SeedGroundGate gate, SeedFeatureTrace.Builder trace,
                          BooleanSupplier cancelled, SeedFeatureTrace.Trace oracleTrace, SeedScanCore.Snapshot observed,
                          SeedWriterOrder writerOrder) {
        this.seed = seed;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.baseline = baseline;
        this.generator = generator;
        this.recipes = recipes;
        this.gate = gate;
        this.trace = trace;
        this.cancelled = cancelled;
        this.oracleTrace = oracleTrace;
        this.observed = observed;
        this.writerOrder = writerOrder;
        if (oracleTrace != null) for (SeedFeatureTrace.Feature feature : oracleTrace.features())
            for (SeedFeatureTrace.Event write : feature.writes()) if (write.kind() == SeedScanCore.ORE)
                this.predictedOreWriters.computeIfAbsent(write.position(), ignored -> new HashSet<>()).add(feature.origin());
        int reach = recipes.stream().mapToInt(recipe -> recipe.horizontalMargin() +
            (recipe.airDiscard() > 0.0F ? 1 : 0)).max().orElse(0);
        this.minChunkX = (chunkX * 16 - reach) >> 4;
        this.maxChunkX = (chunkX * 16 + 15 + reach) >> 4;
        this.minChunkZ = (chunkZ * 16 - reach) >> 4;
        this.maxChunkZ = (chunkZ * 16 + 15 + reach) >> 4;
    }

    Result run(Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> localBiomes) {
        List<Variant> variants = new ArrayList<>();
        variants.add(new Variant(null));
        for (int recipeIndex = 0; recipeIndex < recipes.size(); recipeIndex++) {
            if (cancelled.getAsBoolean()) return new Result(fogs, nativeCalls, peakStates, peakOverlayCells);
            SeedOreRecipes.Recipe recipe = recipes.get(recipeIndex);
            if (recipe.biomes().stream().noneMatch(localBiomes::contains)) continue;
            retainForRemaining(variants, recipeIndex, localBiomes);
            RecipeResult result = runRecipe(recipe, variants);
            if (result.fogged) {
                for (int rest = recipeIndex; rest < recipes.size(); rest++)
                    fogs.add(fog(recipes.get(rest), chunkX, chunkZ, result.reason + " at step " +
                        recipe.stage() + ", feature " + recipe.featureIndex()));
                return new Result(fogs, nativeCalls, peakStates, peakOverlayCells);
            }
            variants = result.variants;
        }
        return new Result(fogs, nativeCalls, peakStates, peakOverlayCells);
    }

    private record RecipeResult(List<Variant> variants, boolean fogged, String reason) {}

    /** Only earlier writes inside a remaining ore's conservative input envelope can affect it. */
    private void retainForRemaining(List<Variant> states, int recipeIndex,
                                   Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> localBiomes) {
        for (Variant state : states) {
            state.overlay.entrySet().removeIf(entry -> {
                int x = SeedScanCore.unpackX(entry.getKey()), y = SeedScanCore.unpackY(entry.getKey()),
                    z = SeedScanCore.unpackZ(entry.getKey());
                for (int index = recipeIndex; index < recipes.size(); index++) {
                    SeedOreRecipes.Recipe future = recipes.get(index);
                    if (future.biomes().stream().noneMatch(localBiomes::contains)) continue;
                    int halo = future.airDiscard() > 0.0F ? 1 : 0;
                    int horizontal = future.horizontalMargin() + halo;
                    int x0 = chunkX << 4, z0 = chunkZ << 4;
                    if (x < x0 - horizontal || x > x0 + 15 + horizontal ||
                        z < z0 - horizontal || z > z0 + 15 + horizontal) continue;
                    int vertical = future.verticalReach() + 2 + halo;
                    if (y >= Math.max(SeedScanCore.MIN_Y, future.bottom() - vertical) &&
                        y <= Math.min(SeedScanCore.MAX_Y, future.top() + vertical)) return false;
                }
                return true;
            });
        }
    }

    private RecipeResult runRecipe(SeedOreRecipes.Recipe recipe, List<Variant> incoming) {
        int blockX = chunkX << 4, blockZ = chunkZ << 4;
        long half = recipe.horizontalMargin(), halfY = recipe.verticalReach() + 2L;
        if ((half * 2 + 1) * (half * 2 + 1) * (halfY * 2 + 1) > SeedFeatureTrace.MAX_WRITES)
            return new RecipeResult(List.of(), true, "ore snapshot volume cap");
        SeedRandomBranch countRandom = SeedRandomBranch.feature(seed, blockX, blockZ, recipe.featureIndex(), recipe.stage());
        List<PlacementModifier> modifiers = recipe.placed().placement();
        PlacementModifier first = modifiers.getFirst();
        int attempts = first instanceof CountPlacement count
            ? count.count(countRandom.nativeSource(), new BlockPos(blockX, SeedScanCore.MIN_Y, blockZ)) : 1;
        SeedRandomBranch.State afterCount = countRandom.freeze();
        boolean observedTerrainCanAffectFeature = recipe.airDiscard() > 0.0F;
        FeatureObservation observation = observedTerrainCanAffectFeature
            ? observation(recipe) : new FeatureObservation(Map.of(), false);
        List<Variant> states = new ArrayList<>(incoming.size() + 1);
        for (Variant prior : incoming) {
            Variant state = new Variant(SeedRandomBranch.resume(afterCount));
            state.overlay.putAll(prior.overlay);
            if (prior.observed) {
                state.observed = true;
                states.add(state);
            } else {
                states.add(state);
                // A partial observation still needs a bounded probe: its first native read
                // positions are what let the next main-thread capture fill the missing cells.
                // Compare against the model variant's cumulative writes, not terrain alone.
                if (observedTerrainCanAffectFeature && (observation.incomplete() ||
                    observationDiffers(recipe, observation.states(), prior.overlay))) {
                    Variant oracle = state.fork();
                    oracle.observed = true;
                    states.add(oracle);
                }
            }
        }
        if (states.size() > MAX_STATES) return new RecipeResult(List.of(), true, "state cap");
        if (!withinOverlayBudget(states)) return new RecipeResult(List.of(), true, "variant overlay cap: " + overlayBudgetFailure);
        int firstModifier = first instanceof CountPlacement ? 1 : 0;
        int work = 0;
        Set<Long> recipeReads = new HashSet<>();
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (cancelled.getAsBoolean()) return new RecipeResult(List.of(), true, "cancelled");
            ArrayList<Variant> next = new ArrayList<>(states.size() + 2);
            for (Variant state : states) {
                List<BlockPos> positions = List.of(new BlockPos(blockX, SeedScanCore.MIN_Y, blockZ));
                PlacementContext placement = new PlacementContext(baseline.proxy(), generator, java.util.Optional.of(recipe.placed()));
                for (int modifierIndex = firstModifier; modifierIndex < modifiers.size() && !positions.isEmpty(); modifierIndex++) {
                    PlacementModifier modifier = modifiers.get(modifierIndex);
                    ArrayList<BlockPos> modified = new ArrayList<>(positions.size());
                    for (BlockPos position : positions)
                        modifier.modify(placement, state.random.nativeSource(), position, modified::add);
                    positions = modified;
                    // Validated native ore recipes have one candidate per attempt after their count/rareness filter.
                    if (positions.size() > 1) return new RecipeResult(List.of(), true, "placement modifier multiplicity");
                }
                if (positions.isEmpty()) {
                    next.add(state);
                    continue;
                }
                BlockPos position = positions.getFirst();
                var decision = gate.placement(recipe, position.getX(), position.getY(), position.getZ());
                if (decision == SeedGroundGate.Decision.SKIP) {
                    state.random.skipOreGatePrefix();
                    next.add(state);
                    continue;
                }
                if (decision == SeedGroundGate.Decision.BRANCH) {
                    Variant skipped = state.fork();
                    skipped.random.skipOreGatePrefix();
                    next.add(skipped);
                }
                int vertical = recipe.verticalReach() + 2;
                int minY = Math.max(SeedScanCore.MIN_Y, position.getY() - vertical);
                int maxY = Math.min(SeedScanCore.MAX_Y, position.getY() + vertical);
                Map<Long, BlockState> observedStates = state.observed && observedTerrainCanAffectFeature
                    ? observation.states() : Map.of();
                var scratch = baseline.scratch(minChunkX, maxChunkX, minChunkZ, maxChunkZ, minY, maxY,
                    state.overlay, observedStates);
                if (scratch == null) return new RecipeResult(List.of(), true, "missing scratch neighborhood");
                Map<Long, BlockState> before = scratch.snapshot(position, recipe);
                Set<Long> reads;
                try (SeedOreRecipes.ReadCapture capture = SeedOreRecipes.captureReads()) {
                    recipe.feature().place(scratch.proxy(), generator, state.random.nativeSource(), position);
                    reads = capture.finish();
                    nativeCalls++;
                } catch (SeedOreRecipes.ReadLimitException capped) {
                    return new RecipeResult(List.of(), true, "native target read cap");
                }
                recipeReads.addAll(reads);
                if (state.observed && observedTerrainCanAffectFeature && !observationCovers(recipe, reads)) {
                    emitReadOnly(recipe, recipeReads);
                    return new RecipeResult(List.of(), true, "observed read coverage incomplete");
                }
                for (var entry : before.entrySet()) {
                    BlockState after = scratch.blockState(new BlockPos(SeedScanCore.unpackX(entry.getKey()),
                        SeedScanCore.unpackY(entry.getKey()), SeedScanCore.unpackZ(entry.getKey())));
                    if (!after.equals(entry.getValue())) {
                        if (!state.overlay.containsKey(entry.getKey()) && state.overlay.size() >= SeedFeatureTrace.MAX_WRITES)
                        {
                            emitReadOnly(recipe, recipeReads);
                            return new RecipeResult(List.of(), true, "per-feature overlay write cap");
                        }
                        state.overlay.put(entry.getKey(), after);
                        state.touched.add(entry.getKey());
                    }
                }
                next.add(state);
            }
            states = distinct(next);
            peakStates = Math.max(peakStates, states.size());
            work += states.size();
            if (states.size() > MAX_STATES) return new RecipeResult(List.of(), true, "state cap");
            if (work > MAX_WORK && attempt + 1 < attempts) {
                emitReadOnly(recipe, recipeReads);
                return new RecipeResult(List.of(), true, "work cap");
            }
            if (!withinOverlayBudget(states)) {
                emitReadOnly(recipe, recipeReads);
                return new RecipeResult(List.of(), true, "variant overlay cap: " + overlayBudgetFailure);
            }
        }
        emitFeature(recipe, states, recipeReads);
        return new RecipeResult(states, false, "");
    }

    private void emitFeature(SeedOreRecipes.Recipe recipe, List<Variant> states, Set<Long> reads) {
        long origin = SeedScanCore.packChunk(chunkX, chunkZ);
        SeedRandomBranch start = SeedRandomBranch.feature(seed, chunkX << 4, chunkZ << 4,
            recipe.featureIndex(), recipe.stage());
        trace.begin(origin, recipe.stage(), recipe.featureIndex(), start.freeze());
        trace.reads(reads);
        Set<Long> touched = new HashSet<>();
        for (Variant state : states) touched.addAll(state.touched);
        ArrayList<Long> ordered = new ArrayList<>(touched);
        ordered.sort(Long::compare);
        byte expected = recipe.material();
        for (long position : ordered) {
            boolean any = false, every = true;
            byte before = kind(baseState(position));
            for (Variant state : states) {
                BlockState after = state.overlay.getOrDefault(position, baseState(position));
                boolean wrote = state.touched.contains(position) && kind(after) == expected;
                any |= wrote;
                every &= wrote;
            }
            if (any) trace.write(position, before, expected, !every);
        }
        trace.end();
        for (Variant state : states) {
            state.touched.clear();
        }
    }

    private BlockState baseState(long position) {
        return baseline.blockState(new BlockPos(SeedScanCore.unpackX(position), SeedScanCore.unpackY(position),
            SeedScanCore.unpackZ(position)));
    }

    private static byte kind(BlockState state) {
        return SeedBlockKinds.classify(state);
    }

    private static List<Variant> distinct(List<Variant> states) {
        if (states.size() < 2) return states;
        LinkedHashMap<Integer, List<Variant>> byHash = new LinkedHashMap<>();
        ArrayList<Variant> unique = new ArrayList<>(states.size());
        for (Variant state : states) {
            int hash = state.overlay.hashCode();
            List<Variant> bucket = byHash.computeIfAbsent(hash, ignored -> new ArrayList<>());
            boolean duplicate = bucket.stream().anyMatch(other -> other.overlay.equals(state.overlay) &&
                other.random.freeze().equals(state.random.freeze()) && other.observed == state.observed);
            if (!duplicate) { bucket.add(state); unique.add(state); }
        }
        return unique;
    }

    private record FeatureObservation(Map<Long, BlockState> states, boolean incomplete) {}

    private void emitReadOnly(SeedOreRecipes.Recipe recipe, Set<Long> reads) {
        long origin = SeedScanCore.packChunk(chunkX, chunkZ);
        SeedRandomBranch start = SeedRandomBranch.feature(seed, chunkX << 4, chunkZ << 4,
            recipe.featureIndex(), recipe.stage());
        trace.begin(origin, recipe.stage(), recipe.featureIndex(), start.freeze());
        trace.reads(reads);
        trace.end();
    }

    private FeatureObservation observation(SeedOreRecipes.Recipe recipe) {
        if (recipe.airDiscard() <= 0.0F || observed == null || oracleTrace == null)
            return new FeatureObservation(Map.of(), false);
        SeedFeatureTrace.Feature feature = oracleTrace.feature(SeedScanCore.packChunk(chunkX, chunkZ), recipe.stage(), recipe.featureIndex());
        if (feature == null || feature.readPositions().isEmpty()) return new FeatureObservation(Map.of(), false);
        Set<Long> candidates = new HashSet<>(feature.readPositions());
        Set<Long> neighbors = new HashSet<>();
        boolean incomplete = false;
        if (recipe.airDiscard() > 0.0F) for (long position : feature.readPositions()) {
            int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
            if (y < SeedScanCore.MIN_Y || y > SeedScanCore.MAX_Y) continue;
            int id = observed.nativeBlockIdAt(x, y, z);
            if (id < 0) { incomplete = true; continue; }
            BlockState state = BuiltInRegistries.BLOCK.byId(id).defaultBlockState();
            if (matches(recipe, state, new BlockPos(x, y, z))) addAirNeighbors(neighbors, position);
        }
        candidates.addAll(neighbors);
        if (candidates.size() > SeedOreRecipes.MAX_NATIVE_READS * 7L) return new FeatureObservation(Map.of(), true);
        Map<Long, BlockState> overrides = new HashMap<>();
        Set<Long> ruleInputs = new HashSet<>(feature.readPositions());
        for (long position : candidates) {
            int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
            if (y < SeedScanCore.MIN_Y || y > SeedScanCore.MAX_Y) {
                BlockState state = Blocks.AIR.defaultBlockState();
                overrides.put(position, state);
                continue;
            }
            int id = observed.nativeBlockIdAt(x, y, z);
            if (id < 0) { incomplete = true; continue; }
            BlockState state = BuiltInRegistries.BLOCK.byId(id).defaultBlockState();
            if (ruleInputs.contains(position) && hasPredictedOre(position) && isOreOutput(state) &&
                !mayBePriorOreWriter(position, recipe)) continue;
            overrides.put(position, state);
        }
        return new FeatureObservation(Map.copyOf(overrides), incomplete);
    }

    private static boolean matches(SeedOreRecipes.Recipe recipe, BlockState state, BlockPos pos) {
        SeedRandomBranch random = SeedRandomBranch.feature(0L, 0, 0, 0, 0);
        for (var target : recipe.targets()) if (target.target().test(state, pos, random.nativeSource())) return true;
        return false;
    }

    private static boolean isOreOutput(BlockState state) {
        if (state == null) return false;
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.endsWith("_ore") || state.is(Blocks.RAW_IRON_BLOCK) || state.is(Blocks.RAW_COPPER_BLOCK) ||
            state.is(Blocks.INFESTED_STONE) || state.is(Blocks.INFESTED_DEEPSLATE);
    }

    private boolean hasPredictedOre(long position) {
        return this.predictedOreWriters.containsKey(position);
    }

    private boolean mayBePriorOreWriter(long position, SeedOreRecipes.Recipe recipe) {
        Set<Long> writers = this.predictedOreWriters.get(position);
        if (writers == null || writers.isEmpty()) return false;
        long current = SeedScanCore.packChunk(chunkX, chunkZ);
        for (long writer : writers) {
            if (writer == current) continue; // Same-origin order is already represented by this variant's overlay.
            if (this.writerOrder == null) return true;
            if (this.writerOrder.precedes(writer, current)) return true;
            if (!this.writerOrder.precedes(current, writer)) return true; // Unordered writers keep both branches.
        }
        return false;
    }

    private static void addAirNeighbors(Set<Long> positions, long position) {
        int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
        positions.add(SeedScanCore.packBlock(x + 1, y, z));
        positions.add(SeedScanCore.packBlock(x - 1, y, z));
        positions.add(SeedScanCore.packBlock(x, y + 1, z));
        positions.add(SeedScanCore.packBlock(x, y - 1, z));
        positions.add(SeedScanCore.packBlock(x, y, z + 1));
        positions.add(SeedScanCore.packBlock(x, y, z - 1));
    }

    private boolean observationDiffers(SeedOreRecipes.Recipe recipe, Map<Long, BlockState> inputs,
                                      Map<Long, BlockState> modelOverlay) {
        if (inputs.isEmpty() || this.oracleTrace == null) return false;
        SeedFeatureTrace.Feature feature = this.oracleTrace.feature(SeedScanCore.packChunk(chunkX, chunkZ), recipe.stage(), recipe.featureIndex());
        if (feature == null) return false;
        for (long position : feature.readPositions()) {
            BlockState live = inputs.get(position);
            if (live == null) continue;
            if (hasPredictedOre(position) && isOreOutput(live) && !mayBePriorOreWriter(position, recipe)) continue;
            int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
            BlockPos pos = new BlockPos(x, y, z);
            BlockState modeled = modelOverlay.getOrDefault(position, baseline.blockState(pos));
            boolean liveMatches = matches(recipe, live, pos);
            boolean modelMatches = matches(recipe, modeled, pos);
            if (liveMatches != modelMatches) return true;
            if (recipe.airDiscard() > 0.0F && (liveMatches || modelMatches)) {
                for (long neighbor : airNeighbors(position)) {
                    BlockState observedNeighbor = inputs.get(neighbor);
                    if (observedNeighbor == null) continue;
                    int nx = SeedScanCore.unpackX(neighbor), ny = SeedScanCore.unpackY(neighbor), nz = SeedScanCore.unpackZ(neighbor);
                    BlockState modelNeighbor = modelOverlay.getOrDefault(neighbor,
                        baseline.blockState(new BlockPos(nx, ny, nz)));
                    if (observedNeighbor.isAir() != modelNeighbor.isAir()) return true;
                }
            }
        }
        return false;
    }

    private static List<Long> airNeighbors(long position) {
        ArrayList<Long> neighbors = new ArrayList<>(6);
        int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
        neighbors.add(SeedScanCore.packBlock(x + 1, y, z));
        neighbors.add(SeedScanCore.packBlock(x - 1, y, z));
        neighbors.add(SeedScanCore.packBlock(x, y + 1, z));
        neighbors.add(SeedScanCore.packBlock(x, y - 1, z));
        neighbors.add(SeedScanCore.packBlock(x, y, z + 1));
        neighbors.add(SeedScanCore.packBlock(x, y, z - 1));
        return neighbors;
    }

    private boolean observationCovers(SeedOreRecipes.Recipe recipe, Set<Long> reads) {
        if (observed == null) return false;
        for (long position : reads) {
            int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
            if (y < SeedScanCore.MIN_Y || y > SeedScanCore.MAX_Y) continue;
            int id = observed.nativeBlockIdAt(x, y, z);
            if (id < 0) return false;
            BlockState state = BuiltInRegistries.BLOCK.byId(id).defaultBlockState();
            if (recipe.airDiscard() > 0.0F && matches(recipe, state, new BlockPos(x, y, z))) for (long neighbor : airNeighbors(position)) {
                int nx = SeedScanCore.unpackX(neighbor), ny = SeedScanCore.unpackY(neighbor), nz = SeedScanCore.unpackZ(neighbor);
                if (ny >= SeedScanCore.MIN_Y && ny <= SeedScanCore.MAX_Y && observed.nativeBlockIdAt(nx, ny, nz) < 0) return false;
            }
        }
        return true;
    }

    private boolean withinOverlayBudget(List<Variant> states) {
        long cells = 0;
        for (Variant state : states) {
            if (state.overlay.size() > MAX_VARIANT_CELLS) {
                this.peakOverlayCells = Math.max(this.peakOverlayCells,
                    (int)Math.min((long)state.overlay.size(), Integer.MAX_VALUE));
                this.overlayBudgetFailure = "single variant " + state.overlay.size() + " / " + MAX_VARIANT_CELLS +
                    " across " + states.size() + " states";
                return false;
            }
            cells += state.overlay.size();
        }
        peakOverlayCells = Math.max(peakOverlayCells, (int) cells);
        if (cells > MAX_TOTAL_VARIANT_CELLS) {
            this.overlayBudgetFailure = "aggregate " + cells + " / " + MAX_TOTAL_VARIANT_CELLS +
                " across " + states.size() + " states";
            return false;
        }
        return true;
    }

    static Fog fog(SeedOreRecipes.Recipe recipe, int chunkX, int chunkZ, String reason) {
        int half = recipe.horizontalMargin(), reach = recipe.verticalReach();
        int x0 = chunkX << 4, z0 = chunkZ << 4;
        return new Fog(SeedScanCore.packChunk(chunkX, chunkZ), Math.max(x0 - half, x0 - 16),
            Math.max(recipe.bottom() - 2 - reach, SeedScanCore.MIN_Y), Math.max(z0 - half, z0 - 16),
            Math.min(x0 + 15 + half, x0 + 31), Math.min(recipe.top() + 2 + reach, SeedScanCore.MAX_Y),
            Math.min(z0 + 15 + half, z0 + 31), reason, recipe.stage(), recipe.featureIndex(), recipe.material());
    }

}
