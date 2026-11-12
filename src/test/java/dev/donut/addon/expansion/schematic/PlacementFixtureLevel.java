package dev.donut.addon.expansion.schematic;

import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.sounds.*;
import net.minecraft.world.level.saveddata.maps.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.core.particles.*;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;

/** Native Level with a bounded in-memory block view, never connected to a game or server. */
final class PlacementFixtureLevel extends Level {
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    private final Set<Long> unavailableChunks = new HashSet<>();
    private final net.minecraft.world.level.border.WorldBorder border = new net.minecraft.world.level.border.WorldBorder();
    private static HolderLookup.Provider lookup;
    private static RegistryAccess access;
    private final List<Entity> trackedEntities = new ArrayList<>();
    private final EnvironmentAttributeSystem attributes = EnvironmentAttributeSystem.builder().build();
    private final FixtureTicks<net.minecraft.world.level.material.Fluid> fluidTicks = new FixtureTicks<>();
    private final LevelEntityGetter<Entity> fixtureEntities = new LevelEntityGetter<>() {
        @Override public Entity get(int id) { return trackedEntities.stream().filter(e -> e.getId() == id).findFirst().orElse(null); }
        @Override public Entity get(UUID id) { return trackedEntities.stream().filter(e -> e.getUUID().equals(id)).findFirst().orElse(null); }
        @Override public Iterable<Entity> getAll() { return List.copyOf(trackedEntities); }
        @Override public <U extends Entity> void get(net.minecraft.world.level.entity.EntityTypeTest<Entity,U> type,
            net.minecraft.util.AbortableIterationConsumer<U> consumer) {
            for (var entity : trackedEntities) {
                U found = type.tryCast(entity);
                if (found != null && consumer.accept(found) == net.minecraft.util.Continuation.ABORT) return;
            }
        }
        @Override public void get(net.minecraft.world.phys.AABB bounds, java.util.function.Consumer<Entity> consumer) {
            for (var entity : trackedEntities) if (entity.getBoundingBox().intersects(bounds)) consumer.accept(entity);
        }
        @Override public <U extends Entity> void get(net.minecraft.world.level.entity.EntityTypeTest<Entity,U> type,
            net.minecraft.world.phys.AABB bounds, net.minecraft.util.AbortableIterationConsumer<U> consumer) {
            for (var entity : trackedEntities) {
                U found = type.tryCast(entity);
                if (found != null && entity.getBoundingBox().intersects(bounds) &&
                    consumer.accept(found) == net.minecraft.util.Continuation.ABORT) return;
            }
        }
    };

    static void prepareRegistries() throws Exception {
        if (access != null) return;
        lookup = VanillaRegistries.createWorldLookup();
        // This isolated test JVM binds official defaults; addon runtime never mutates registry components.
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup)
            .forEach(components -> components.apply());
        List<Registry<?>> registries = new ArrayList<>();
        net.minecraft.core.registries.BuiltInRegistries.REGISTRY.forEach(registries::add);
        registries.add(copyRegistry(lookup.lookupOrThrow(Registries.BIOME)));
        registries.add(copyRegistry(lookup.lookupOrThrow(Registries.DIMENSION_TYPE)));
        registries.add(copyRegistry(lookup.lookupOrThrow(Registries.DAMAGE_TYPE)));
        access = new RegistryAccess.ImmutableRegistryAccess(registries).freeze();
    }

    @SuppressWarnings("unchecked")
    private static <T> Registry<T> copyRegistry(HolderLookup.RegistryLookup<T> original) {
        var key = (net.minecraft.resources.ResourceKey<? extends Registry<T>>)(net.minecraft.resources.ResourceKey<?>)original.key();
        var copy = new MappedRegistry<T>(key, com.mojang.serialization.Lifecycle.stable());
        original.listElements().forEach(holder -> copy.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        return copy.freeze();
    }

    private static WritableLevelData data() {
        return (WritableLevelData)Proxy.newProxyInstance(WritableLevelData.class.getClassLoader(),
            new Class<?>[]{WritableLevelData.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getGameTime" -> 0L;
                case "isHardcore", "isDifficultyLocked" -> false;
                case "getDifficulty" -> net.minecraft.world.Difficulty.NORMAL;
                case "setSpawn" -> null;
                case "toString" -> "DonutPlacementFixtureData";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    PlacementFixtureLevel() {
        this(false);
    }

    PlacementFixtureLevel(boolean clientSide) {
        super(data(), Level.OVERWORLD, access,
            access.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD),
            clientSide, false, 0L, 1000);
    }

    void put(BlockPos pos, BlockState state) { blocks.put(pos.immutable(), state); }
    void track(Entity entity) { trackedEntities.add(entity); }
    void unloadChunk(int x, int z) { unavailableChunks.add(ChunkPos.pack(x, z)); }
    @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
        put(pos,state);
        return true;
    }
    @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public boolean hasChunk(int x, int z) { return !unavailableChunks.contains(ChunkPos.pack(x, z)); }
    @Override public boolean hasChunkAt(BlockPos pos) { return hasChunk(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getZ(), 16)); }
    @Override public BlockGetter getChunkForCollisions(int x, int z) { return hasChunk(x, z) ? this : null; }
    @Override public net.minecraft.world.level.border.WorldBorder getWorldBorder() { return border; }
    @Override public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() { return net.minecraft.world.flag.FeatureFlags.VANILLA_SET; }
    @Override public java.util.List<? extends net.minecraft.world.entity.player.Player> players() {
        return trackedEntities.stream().filter(net.minecraft.world.entity.player.Player.class::isInstance)
            .map(net.minecraft.world.entity.player.Player.class::cast).toList();
    }
    @Override public ChunkSource getChunkSource() { throw new UnsupportedOperationException("fixture chunks"); }
    @Override public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) { return null; }
    @Override public void sendBlockUpdated(BlockPos p, BlockState before, BlockState after, int flags) { }
    @Override public void playSeededSound(Entity e, double x, double y, double z, Holder<SoundEvent> s, SoundSource c, float v, float p, long seed) { }
    @Override public void playSeededSound(Entity source, Entity at, Holder<SoundEvent> s, SoundSource c, float v, float p, long seed) { }
    @Override public void explode(Entity source, DamageSource damage, ExplosionDamageCalculator calculator,
        double x, double y, double z, float radius, boolean fire, ExplosionInteraction interaction,
        ParticleOptions small, ParticleOptions large, WeightedList<ExplosionParticleInfo> particles, Holder<SoundEvent> sound) {
        throw new UnsupportedOperationException("fixture explosion");
    }
    @Override public String gatherChunkSourceStats() { return "Donut placement fixture"; }
    @Override public void setRespawnData(LevelData.RespawnData data) { throw new UnsupportedOperationException("fixture respawn"); }
    @Override public LevelData.RespawnData getRespawnData() { throw new UnsupportedOperationException("fixture respawn"); }
    @Override public Entity getEntity(int id) { return fixtureEntities.get(id); }
    @Override public Collection<EnderDragonPart> dragonParts() { return List.of(); }
    @Override public TickRateManager tickRateManager() { throw new UnsupportedOperationException("fixture ticks"); }
    @Override public MapItemSavedData getMapData(MapId id) { return null; }
    @Override public void destroyBlockProgress(int id, BlockPos pos, int progress) { }
    @Override public Scoreboard getScoreboard() { throw new UnsupportedOperationException("fixture scoreboard"); }
    @Override public RecipeAccess recipeAccess() { throw new UnsupportedOperationException("fixture recipes"); }
    @Override protected LevelEntityGetter<Entity> getEntities() { return fixtureEntities; }
    @Override public ClockManager clockManager() { throw new UnsupportedOperationException("fixture clock"); }
    @Override public EnvironmentAttributeSystem environmentAttributes() { return attributes; }
    @Override public void levelEvent(Entity entity, int type, BlockPos pos, int data) { }
    @Override public void gameEvent(Holder<net.minecraft.world.level.gameevent.GameEvent> event,
        net.minecraft.world.phys.Vec3 pos, net.minecraft.world.level.gameevent.GameEvent.Context context) { }
    @Override public int getSeaLevel() { return 63; }
    @Override public Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return lookup.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
    }
    @Override public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() {
        throw new UnsupportedOperationException("fixture block ticks");
    }
    @Override public net.minecraft.world.ticks.LevelTickAccess<net.minecraft.world.level.material.Fluid> getFluidTicks() {
        return fluidTicks;
    }

    /** Retains scheduled native effects without running a simulation or connecting to a server. */
    private static final class FixtureTicks<T> implements net.minecraft.world.ticks.LevelTickAccess<T> {
        private final List<net.minecraft.world.ticks.ScheduledTick<T>> pending = new ArrayList<>();
        @Override public void schedule(net.minecraft.world.ticks.ScheduledTick<T> tick) {
            if (!hasScheduledTick(tick.pos(), tick.type())) pending.add(tick);
        }
        @Override public boolean hasScheduledTick(BlockPos pos, T type) {
            return pending.stream().anyMatch(tick -> tick.pos().equals(pos) && tick.type() == type);
        }
        @Override public int count() { return pending.size(); }
        @Override public boolean willTickThisTick(BlockPos pos, T type) { return false; }
    }
}
