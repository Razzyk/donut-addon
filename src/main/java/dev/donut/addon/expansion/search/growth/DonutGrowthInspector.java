package dev.donut.addon.expansion.search.growth;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.mv.compat.events.EventHandler;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.packets.PacketEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.ChunkDataEvent;
import dev.donut.addon.mv.compat.events.world.StorageBlocksChangedEvent;
import dev.donut.addon.mv.compat.events.world.StorageChunkUnloadedEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.SettingGroup;
import dev.donut.addon.mv.compat.systems.modules.Category;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome.Precipitation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Donut Growth Inspector scoring over already loaded chunks. */
public final class DonutGrowthInspector extends Module {
    private static final int BLOCKS_PER_TICK = 8192;
    private static final int HOLE_CELLS_PER_TICK = 4096;
    private static final int MAX_PENDING_CHUNKS = 8192;
    private static final int MAX_INBOUND_UPDATES = 16384;
    private static final int MAX_PACKET_DRAIN = 4096;
    private static final int MAX_RECENT_UPDATES = 8192;
    private static final int MAX_PENDING_APPLIED = 8192;
    private static final int PACKET_UPDATE_TTL = 40;
    private static final int LOADED_MARGIN = 3;
    private static final int REFRESH_TICKS = 20;
    private static final String DONUT_BRAND = "DonutFolia";
    private static final Color PRIMARY_FILL = new Color(255, 0, 0, 100);
    private static final Color PRIMARY_LINE = new Color(255, 0, 0, 230);
    private static volatile DonutGrowthInspector instance;

    private final Setting<Integer> simulationDistance = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("sim-distance").description("Chunk radius used to spread clear and support evidence.")
            .defaultValue(4).range(2, 16).sliderRange(2, 10).build()
    );
    private final Setting<SettingColor> chunkColor = this.settings.getDefaultGroup().add(
        new ColorSetting.Builder().name("growth-inspector-color").description("Color of primary growth inspector flags.")
            .defaultValue(new SettingColor(255, 0, 0, 100)).build()
    );
    private final Setting<Integer> sensitivity = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("min-flag").description("Minimum support weight required to mark a chunk.")
            .defaultValue(40).range(1, 500).sliderRange(1, 100).build()
    );
    private final Setting<Boolean> holeFilter = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("hole-filter").description("Ignore candidates near enclosed one-wide and three-wide shafts.")
            .defaultValue(false).build()
    );
    private final Setting<Integer> holeDistance = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("hole-distance").description("Chunk radius of the enclosed-shaft filter.")
            .defaultValue(2).range(0, 8).sliderRange(0, 8).visible(this.holeFilter::get).build()
    );
    private final Setting<Boolean> customFlags = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("custom-evidence").description("Choose which of the nineteen growth signals are counted.")
            .defaultValue(false).build()
    );
    private final EnumMap<DonutGrowthRules.Kind, Setting<Boolean>> evidence =
        new EnumMap<>(DonutGrowthRules.Kind.class);
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final DonutPacketBlockLedger packetCorrelation = new DonutPacketBlockLedger();
    private final Set<Long> seenKelpPositions = new HashSet<>();
    private final Set<Long> donutKelpChunks = new HashSet<>();
    private final ConcurrentLinkedQueue<PacketObservation> packetInbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger packetInboxSize = new AtomicInteger();
    private final Object packetInboxLock = new Object();
    private final LinkedHashMap<Long, AppliedBlock> pendingApplied = new LinkedHashMap<>();
    private final AtomicBoolean loginResetRequested = new AtomicBoolean();
    private final DonutGrowthIndex index = new DonutGrowthIndex();
    private ClientLevel world;
    private String dimensionId = "";
    private ScanCursor cursor;
    private volatile long generation;
    private volatile int incomingGeneration;
    private volatile DonutWorldFlagSnapshot snapshot = DonutWorldFlagSnapshot.empty(0L);
    private boolean[] counted = new boolean[DonutGrowthRules.Kind.values().length];
    private boolean dirty;
    private int tickCounter;
    private long worldTicks;

    public DonutGrowthInspector() {
        super(new Category(AddonCategories.BASEFINDING), "Donut Growth Inspector",
            "Flags loaded chunks whose surrounding overdue growth rules leave them as primary candidates.");
        instance = this;
        SettingGroup flags = this.settings.createGroup("Flags");
        DonutGrowthRules.Kind[] kinds = DonutGrowthRules.Kind.values();
        String[] labels = {
            "Kelp", "Cave Vines", "Weeping Vines", "Bamboo", "Sugar Cane", "Cactus", "Cocoa", "Sweet Berries",
            "Nether Wart", "Crops", "Stems", "Amethyst", "Vines", "Leaves", "Grass and Dirt", "Mangrove Propagules",
            "Eyeblossoms", "Snow", "Ice"
        };
        for (int i = 0; i < kinds.length; i++) {
            DonutGrowthRules.Kind kind = kinds[i];
            String key = "growth-count-" + kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            Setting<Boolean> setting = flags.add(new BoolSetting.Builder().name(key)
                .description("Include " + labels[i] + " overdue-growth evidence.")
                .defaultValue(true).visible(this.customFlags::get).build());
            this.evidence.put(kind, setting);
        }
        this.simulationDistance.onChanged(ignored -> this.markDirty());
        this.holeFilter.onChanged(ignored -> { if (this.holeFilter.get()) this.markDirty(); });
        this.customFlags.onChanged(ignored -> this.refreshEvidence());
        this.evidence.values().forEach(setting -> setting.onChanged(ignored -> this.refreshEvidence()));
        this.refreshEvidence();
    }

    public static DonutGrowthInspector instance() { return instance; }

    /** Returns immutable flags tied to the exact current world object and dimension. */
    public DonutWorldFlagSnapshot flaggedChunkSnapshot() { return this.snapshot; }

    /** Always-on world/teardown cleanup for cases where native module toggles skip callbacks. */
    public void endInspectorSession() {
        this.resetState();
        this.world = null;
        this.dimensionId = "";
    }

    /** Called by SearchFeatures' always-on listener to catch out-of-world disable paths. */
    public void onInspectorLifecycleTick(boolean clientReady) {
        if ((!this.isActive() || !clientReady)
            && (this.world != null || this.snapshot.worldIdentity() != null || this.index.recordCount() != 0
                || !this.pending.isEmpty() || !this.pendingApplied.isEmpty() || this.packetCorrelation.pendingCount() != 0
                || this.packetInboxSize.get() != 0 || this.loginResetRequested.get())) this.endInspectorSession();
    }

    @Override public void onActivate() {
        this.resetState();
        this.world = this.mc.level;
        this.dimensionId = dimensionId(this.world);
        this.queueLoadedChunks();
    }

    @Override public void onDeactivate() {
        this.endInspectorSession();
    }

    @EventHandler private void onJoined(GameJoinedEvent event) {
        this.resetState();
        this.world = this.mc.level;
        this.dimensionId = dimensionId(this.world);
        this.queueLoadedChunks();
    }

    @EventHandler private void onLeft(GameLeftEvent event) {
        this.endInspectorSession();
    }

    @EventHandler private void onChunkData(ChunkDataEvent event) {
        if (this.isActive() && event.chunk() != null) this.queueScan(event.chunk().getPos().pack());
    }

    @EventHandler private void onChunkUnloaded(StorageChunkUnloadedEvent event) {
        long key = event.pos().pack();
        this.queued.remove(key);
        this.pending.remove(key);
        this.index.remove(key);
        if (this.cursor != null && this.cursor.key == key) this.cursor = null;
        this.publishSnapshot();
    }

    @EventHandler private void onPacket(PacketEvent.Receive event) {
        if (!this.isActive()) return;
        if (event.packet instanceof ClientboundLoginPacket) {
            this.loginResetRequested.set(true);
        } else if (event.packet instanceof ClientboundLevelChunkWithLightPacket packet) {
            this.offer(new ChunkObservation(ChunkPos.pack(packet.x(), packet.z()), 0));
        } else if (event.packet instanceof ClientboundBlockUpdatePacket packet) {
            this.offerBlock(packet.getPos(), packet.getBlockState());
        } else if (event.packet instanceof ClientboundSectionBlocksUpdatePacket packet) {
            packet.runUpdates((pos, state) -> this.offerBlock(pos, state));
        }
    }

    @EventHandler private void onBlocksChanged(StorageBlocksChangedEvent event) {
        this.drainPacketInbox();
        ClientLevel level = this.mc.level;
        if (!this.isActive() || level == null || level != this.world) return;
        for (Map.Entry<BlockPos, BlockState> change : event.changes().entrySet()) {
            this.pendingApplied.put(change.getKey().asLong(), new AppliedBlock(change.getValue(), this.worldTicks));
        }
        this.trimPendingApplied();
        this.processAppliedPackets(level);
    }

    @EventHandler private void onTick(Post event) {
        ClientLevel current = this.mc.level;
        String currentDimension = dimensionId(current);
        boolean resetRequested = this.loginResetRequested.getAndSet(false);
        if (resetRequested || current != this.world || !currentDimension.equals(this.dimensionId)) {
            this.resetState();
            this.world = current;
            this.dimensionId = currentDimension;
            this.queueLoadedChunks();
        }
        this.drainPacketInbox();
        if (current == null || current != this.world || this.mc.player == null) return;
        this.worldTicks++;
        this.expirePacketUpdates();
        this.processAppliedPackets(current);
        if (this.dirty) {
            this.dirty = false;
            this.resetState();
            this.world = current;
            this.dimensionId = currentDimension;
            this.queueLoadedChunks();
        }
        this.pruneDistant();
        if (++this.tickCounter >= REFRESH_TICKS) {
            this.tickCounter = 0;
            this.index.rebuildPrimary(this.sensitivity.get(), this.holeFilter.get(), this.holeDistance.get(),
                this.simulationDistance.get(), key -> current.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null);
            this.publishSnapshot();
        }

        int blockBudget = BLOCKS_PER_TICK;
        while (blockBudget > 0) {
            if (this.cursor == null && !this.startNext(current)) break;
            int visited = this.cursor.advanceBlocks(current, blockBudget);
            blockBudget -= Math.max(1, visited);
            this.cursor.advanceHole(current, HOLE_CELLS_PER_TICK);
            if (this.cursor.complete()) {
                this.finishScan(current, this.cursor);
                this.cursor = null;
            } else if (visited == 0) {
                break;
            }
        }
    }

    @EventHandler private void onRender(Render3DEvent event) {
        ClientLevel level = this.mc.level;
        if (level == null || this.mc.player == null) return;
        SettingColor color = this.chunkColor.get();
        Color line = new Color(color.r, color.g, color.b, Math.max(160, color.a));
        Color fill = new Color(color.r, color.g, color.b, Math.min(100, color.a));
        int playerX = this.mc.player.getBlockX() >> 4;
        int playerZ = this.mc.player.getBlockZ() >> 4;
        int radius = Math.max(1, this.mc.options.getEffectiveRenderDistance());
        double y = this.mc.player.getBlockY() - 0.02;
        for (long key : this.snapshot.chunkKeys()) {
            int x = ChunkPos.getX(key), z = ChunkPos.getZ(key);
            if (Math.abs(x - playerX) > radius || Math.abs(z - playerZ) > radius) continue;
            event.renderer.box(x * 16.0, y, z * 16.0, x * 16.0 + 16.0, y + 0.06, z * 16.0 + 16.0,
                fill, line, ShapeMode.Both, 0);
        }
    }

    private void refreshEvidence() {
        boolean[] next = new boolean[DonutGrowthRules.Kind.values().length];
        for (DonutGrowthRules.Kind kind : DonutGrowthRules.Kind.values()) {
            next[kind.ordinal()] = !this.customFlags.get() || this.evidence.get(kind).get();
        }
        this.counted = next;
        this.markDirty();
    }

    private void markDirty() { this.dirty = true; }

    private void resetState() {
        synchronized (this.packetInboxLock) {
            this.incomingGeneration++;
            while (this.packetInbox.poll() != null) { }
            this.packetInboxSize.set(0);
        }
        this.generation++;
        this.pending.clear();
        this.queued.clear();
        this.packetCorrelation.clear();
        this.pendingApplied.clear();
        this.seenKelpPositions.clear();
        this.donutKelpChunks.clear();
        this.index.clear();
        this.cursor = null;
        this.tickCounter = 0;
        this.worldTicks = 0L;
        this.dirty = false;
        this.loginResetRequested.set(false);
        this.snapshot = DonutWorldFlagSnapshot.empty(this.generation);
    }

    private void publishSnapshot() {
        this.snapshot = new DonutWorldFlagSnapshot(this.dimensionId, this.world, this.generation, this.index.snapshot());
    }

    private void queueLoadedChunks() {
        ClientLevel level = this.mc.level;
        if (!this.isActive() || level == null || this.mc.player == null) return;
        int centerX = this.mc.player.getBlockX() >> 4;
        int centerZ = this.mc.player.getBlockZ() >> 4;
        int radius = this.mc.options.getEffectiveRenderDistance() + LOADED_MARGIN;
        ArrayList<ChunkPos> candidates = new ArrayList<>();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) candidates.add(new ChunkPos(x, z));
        }
        candidates.sort(java.util.Comparator.comparingLong(pos -> Math.abs((long) pos.x() - centerX) + Math.abs((long) pos.z() - centerZ)));
        for (ChunkPos pos : candidates) {
            if (level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false) != null) this.queueScan(pos.pack());
        }
    }

    private void queueScan(long key) {
        if (!this.isActive() || this.queued.contains(key) || this.queued.size() >= MAX_PENDING_CHUNKS) return;
        this.queued.add(key);
        this.pending.addLast(key);
    }

    private boolean startNext(ClientLevel level) {
        while (!this.pending.isEmpty()) {
            long key = this.pending.removeFirst();
            this.queued.remove(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            this.cursor = new ScanCursor(key, chunk, this.counted.clone(), this.donutServer(), this.donutKelpChunks.contains(key), this.holeFilter.get());
            return true;
        }
        return false;
    }

    private void finishScan(ClientLevel level, ScanCursor scan) {
        if (level != this.world || level.getChunkSource().getChunkNow(ChunkPos.getX(scan.key), ChunkPos.getZ(scan.key)) != scan.chunk) return;
        scan.evidenceCount += DonutGrowthRules.eyeblossomCount(scan.eyeSettled, scan.eyeLate);
        this.index.update(scan.key, new DonutGrowthIndex.Source(scan.evidenceCount > 0, scan.holeFound, scan.evidenceCount),
            this.simulationDistance.get());
    }

    private void pruneDistant() {
        if (this.world == null || this.mc.player == null) return;
        int reach = this.mc.options.getEffectiveRenderDistance() + 2 * this.simulationDistance.get() + 2;
        int px = this.mc.player.getBlockX() >> 4, pz = this.mc.player.getBlockZ() >> 4;
        this.index.pruneDistant(px, pz, reach);
        this.donutKelpChunks.removeIf(key -> Math.abs(ChunkPos.getX(key) - px) > reach || Math.abs(ChunkPos.getZ(key) - pz) > reach);
        this.seenKelpPositions.removeIf(key -> Math.abs((BlockPos.getX(key) >> 4) - px) > reach || Math.abs((BlockPos.getZ(key) >> 4) - pz) > reach);
    }

    private void offerBlock(BlockPos pos, BlockState state) {
        this.offer(new BlockObservation(pos.asLong(), state, 0));
    }

    private void offer(PacketObservation observation) {
        synchronized (this.packetInboxLock) {
            PacketObservation sessionObservation = observation instanceof BlockObservation block
                ? new BlockObservation(block.position(), block.state(), this.incomingGeneration)
                : new ChunkObservation(((ChunkObservation) observation).key(), this.incomingGeneration);
            int size = this.packetInboxSize.incrementAndGet();
            if (size > MAX_INBOUND_UPDATES) {
                this.packetInboxSize.decrementAndGet();
                return;
            }
            this.packetInbox.offer(sessionObservation);
        }
    }

    private void drainPacketInbox() {
        PacketObservation observation;
        int drained = 0;
        while (drained++ < MAX_PACKET_DRAIN && (observation = this.packetInbox.poll()) != null) {
            this.packetInboxSize.decrementAndGet();
            if (observation.generation() != this.incomingGeneration) continue;
            if (observation instanceof ChunkObservation chunk) {
                this.queueScan(chunk.key());
            } else if (observation instanceof BlockObservation block) {
                this.packetCorrelation.record(block.position(), block.state(), this.worldTicks);
                this.packetCorrelation.trimToSize(MAX_RECENT_UPDATES);
            }
        }
    }

    private void processAppliedPackets(ClientLevel level) {
        if (!this.isActive() || level != this.world) return;
        boolean donut = this.donutServer();
        int inspected = 0;
        var iterator = this.pendingApplied.entrySet().iterator();
        while (iterator.hasNext() && inspected++ < MAX_PENDING_APPLIED) {
            Map.Entry<Long, AppliedBlock> entry = iterator.next();
            AppliedBlock applied = entry.getValue();
            if (this.worldTicks - applied.tick() > PACKET_UPDATE_TTL) {
                iterator.remove();
                continue;
            }
            if (!this.packetCorrelation.hasPending(entry.getKey())) continue;
            iterator.remove();
            BlockState state = applied.state();
            if (!this.packetCorrelation.consumeApplied(entry.getKey(), state, this.worldTicks, PACKET_UPDATE_TTL)
                || !donut || !state.is(Blocks.KELP)) continue;
            if (!this.seenKelpPositions.add(entry.getKey())) continue;
            int age = state.getValue(BlockStateProperties.AGE_25);
            BlockPos pos = BlockPos.of(entry.getKey());
            if (age <= 0 || !DonutGrowthRules.kelp(age, level.getBlockState(pos.above()).is(Blocks.WATER))) continue;
            long chunkKey = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
            if (this.donutKelpChunks.add(chunkKey)) this.queueScan(chunkKey);
        }
    }

    private void trimPendingApplied() {
        while (this.pendingApplied.size() > MAX_PENDING_APPLIED) {
            Iterator<Long> oldest = this.pendingApplied.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private void expirePacketUpdates() {
        this.packetCorrelation.prune(this.worldTicks, PACKET_UPDATE_TTL);
    }

    private boolean donutServer() {
        ClientPacketListener connection = this.mc.getConnection();
        if (connection == null) return false;
        String brand = connection.serverBrand();
        return brand != null && brand.contains(DONUT_BRAND);
    }

    private static String dimensionId(ClientLevel world) {
        return world == null ? "" : world.dimension().identifier().toString();
    }

    private sealed interface PacketObservation permits BlockObservation, ChunkObservation { int generation(); }
    private record BlockObservation(long position, BlockState state, int generation) implements PacketObservation {}
    private record ChunkObservation(long key, int generation) implements PacketObservation {}
    private record AppliedBlock(BlockState state, long tick) {}

    private static final class ScanCursor {
        private final long key;
        private final LevelChunk chunk;
        private final LevelChunkSection[] sections;
        private final boolean[] counted;
        private final boolean donut;
        private final boolean scanHole;
        private final int minX;
        private final int minZ;
        private final int minY;
        private final int height;
        private final Heightmap surfaceHeightmap;
        private final byte[] holeOpen;
        private final DonutHoleScanCursor holeCursor;
        private int evidenceCount;
        private int eyeLate;
        private int eyeSettled;
        private int section;
        private int block;
        private int surfaceColumn;
        private int holeLoadIndex;
        private boolean holeComplete;
        private boolean holeFound;

        private ScanCursor(long key, LevelChunk chunk, boolean[] counted, boolean donut, boolean kelpSignal, boolean scanHole) {
            this.key = key;
            this.chunk = chunk;
            this.sections = chunk.getSections();
            this.counted = counted;
            this.donut = donut;
            this.scanHole = scanHole;
            this.minX = chunk.getPos().getMinBlockX();
            this.minZ = chunk.getPos().getMinBlockZ();
            this.minY = chunk.getMinY();
            this.height = chunk.getHeight();
            this.surfaceHeightmap = (counted[17] || counted[18]) && chunk.hasPrimedHeightmap(Types.MOTION_BLOCKING)
                ? chunk.getOrCreateHeightmapUnprimed(Types.MOTION_BLOCKING) : null;
            this.holeOpen = scanHole ? new byte[20 * 20 * this.height] : null;
            this.holeCursor = scanHole ? new DonutHoleScanCursor(this.height) : null;
            if (donut && counted[0] && kelpSignal) this.evidenceCount = 1;
            this.holeComplete = !scanHole;
        }

        private int advanceBlocks(ClientLevel level, int budget) {
            int visited = 0;
            BlockReader reader = new BlockReader(level);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            while (visited < budget) {
                if (this.section < this.sections.length) {
                    LevelChunkSection current = this.sections[this.section];
                    if (current == null || current.hasOnlyAir()) {
                        this.section++;
                        this.block = 0;
                        continue;
                    }
                    if (this.block >= 4096) {
                        this.section++;
                        this.block = 0;
                        continue;
                    }
                    int index = this.block++;
                    int x = this.minX + (index & 15);
                    int z = this.minZ + ((index >> 4) & 15);
                    int y = this.minY + (this.section << 4) + ((index >> 8) & 15);
                    pos.set(x, y, z);
                    BlockState state = current.getBlockState(index & 15, (index >> 8) & 15, (index >> 4) & 15);
                    DonutGrowthRules.Kind kind = kindOf(state);
                    if (kind != null && this.counted[kind.ordinal()]) {
                        if (kind == DonutGrowthRules.Kind.PROPAGULES) {
                            if (DonutGrowthRules.propagule(state.getValue(net.minecraft.world.level.block.MangrovePropaguleBlock.HANGING),
                                state.getValue(net.minecraft.world.level.block.MangrovePropaguleBlock.AGE))) this.evidenceCount++;
                        } else if (kind == DonutGrowthRules.Kind.EYEBLOSSOMS) {
                            Boolean open = eyeblossomsOpen(level);
                            if (open != null) {
                                if (state.is(Blocks.OPEN_EYEBLOSSOM) == open) this.eyeSettled++;
                                else this.eyeLate++;
                            }
                        } else if (owed(level, reader, x, y, z, state, kind, this.donut)) {
                            this.evidenceCount++;
                        }
                    }
                    visited++;
                    continue;
                }

                if (this.surfaceColumn < 256) {
                    int column = this.surfaceColumn++;
                    if (this.surfaceHeightmap != null) {
                        int x = this.minX + (column & 15);
                        int z = this.minZ + (column >> 4);
                        int y = this.surfaceHeightmap.getFirstAvailable(column & 15, column >> 4);
                        if (surfaceOwed(level, reader, x, y, z, level.getSeaLevel(), this.donut, this.counted)) this.evidenceCount++;
                    }
                    visited += 256;
                    continue;
                }
                break;
            }
            return visited;
        }

        private int advanceHole(ClientLevel level, int budget) {
            if (this.holeComplete) return 0;
            int visited = 0;
            BlockReader reader = new BlockReader(level);
            int totalOpenCells = this.holeOpen.length;
            while (visited < budget && this.holeLoadIndex < totalOpenCells) {
                int plane = this.height * 20;
                int xOffset = this.holeLoadIndex / plane;
                int inPlane = this.holeLoadIndex % plane;
                int zOffset = inPlane / this.height;
                int yOffset = inPlane % this.height;
                int x = this.minX - 1 + xOffset;
                int z = this.minZ - 1 + zOffset;
                int y = this.minY + yOffset;
                this.holeOpen[this.holeLoadIndex++] = (byte) (reader.state(x, y, z)
                    .getCollisionShape(level, new BlockPos(x, y, z)).isEmpty() ? 1 : 0);
                visited++;
            }
            if (this.holeLoadIndex < totalOpenCells || visited >= budget) return visited;

            DonutHoleSignal.OpenCell open = (x, y, z) -> this.holeCell(level, x, y, z);
            int detected = this.holeCursor.advance(this.minX, this.minY, this.minZ, budget - visited, open);
            this.holeFound = this.holeCursor.found();
            this.holeComplete = this.holeCursor.complete();
            return visited + detected;
        }

        private boolean holeCell(ClientLevel level, int x, int y, int z) {
            if (level.isOutsideBuildHeight(y)) return true;
            int ix = x - this.minX + 1;
            int iz = z - this.minZ + 1;
            if (ix < 0 || ix >= 20 || iz < 0 || iz >= 20) return true;
            int iy = y - this.minY;
            if (iy < 0 || iy >= this.height) return true;
            return this.holeOpen[(ix * 20 + iz) * this.height + iy] != 0;
        }

        private boolean complete() {
            return this.section >= this.sections.length && this.surfaceColumn >= 256 && this.holeComplete;
        }
    }

    private static DonutGrowthRules.Kind kindOf(BlockState state) {
        Block block = state.getBlock();
        var key = BuiltInRegistries.BLOCK.getKey(block);
        if (key == null) return null;
        boolean hanging = block == Blocks.MANGROVE_PROPAGULE
            && state.getValue(net.minecraft.world.level.block.MangrovePropaguleBlock.HANGING);
        return DonutGrowthRules.classify(key.toString(), block instanceof CropBlock, hanging);
    }

    private static boolean owed(ClientLevel level, BlockReader blocks, int x, int y, int z, BlockState state,
                                DonutGrowthRules.Kind kind, boolean donut) {
        Block block = state.getBlock();
        if (kind == DonutGrowthRules.Kind.GRASS_DIRT) return soilOwed(level, blocks, x, y, z, state);
        if (donut && kind == DonutGrowthRules.Kind.CAVE_VINES) return false;
        if (donut && kind == DonutGrowthRules.Kind.AMETHYST) return isBud(state);
        if (donut && kind == DonutGrowthRules.Kind.KELP) {
            boolean water = blocks.state(x, y + 1, z).is(Blocks.WATER);
            return DonutGrowthRules.donutKelpColumn(water, column(blocks, x, y, z, Blocks.KELP_PLANT, 3));
        }
        if (kind == DonutGrowthRules.Kind.KELP) {
            return DonutGrowthRules.kelp(state.getValue(BlockStateProperties.AGE_25), blocks.state(x, y + 1, z).is(Blocks.WATER));
        }
        if (kind == DonutGrowthRules.Kind.CAVE_VINES || kind == DonutGrowthRules.Kind.WEEPING_VINES) {
            return DonutGrowthRules.vines(kind == DonutGrowthRules.Kind.CAVE_VINES, donut,
                state.getValue(BlockStateProperties.AGE_25), blocks.state(x, y - 1, z).isAir());
        }
        if (kind == DonutGrowthRules.Kind.BAMBOO) {
            return DonutGrowthRules.bamboo(state.getValue(BlockStateProperties.STAGE), blocks.state(x, y + 1, z).isAir(),
                bambooBelow(blocks, x, y, z), blocks.light(x, y + 1, z));
        }
        if (kind == DonutGrowthRules.Kind.SUGAR_CANE) {
            return DonutGrowthRules.sugarCane(blocks.state(x, y + 1, z).isAir(), column(blocks, x, y, z, Blocks.SUGAR_CANE, 3));
        }
        if (kind == DonutGrowthRules.Kind.CACTUS) {
            return DonutGrowthRules.cactus(state.getValue(BlockStateProperties.AGE_15), blocks.state(x, y + 1, z).isAir(),
                column(blocks, x, y, z, Blocks.CACTUS, 3));
        }
        if (kind == DonutGrowthRules.Kind.COCOA) return DonutGrowthRules.belowMaxAge(state.getValue(BlockStateProperties.AGE_2), 2);
        if (kind == DonutGrowthRules.Kind.NETHER_WART) return DonutGrowthRules.belowMaxAge(state.getValue(BlockStateProperties.AGE_3), 3);
        if (kind == DonutGrowthRules.Kind.SWEET_BERRIES) {
            return DonutGrowthRules.berry(state.getValue(BlockStateProperties.AGE_3), blocks.light(x, y + 1, z) >= 9);
        }
        if (kind == DonutGrowthRules.Kind.STEMS) {
            return DonutGrowthRules.stem(state.getValue(BlockStateProperties.AGE_7), blocks.light(x, y, z) >= 9);
        }
        if (kind == DonutGrowthRules.Kind.CROPS) {
            CropBlock crop = (CropBlock) block;
            return DonutGrowthRules.cropOpportunity(!crop.isMaxAge(state), blocks.light(x, y, z));
        }
        if (kind == DonutGrowthRules.Kind.VINES) {
            return DonutGrowthRules.vine(blocks.state(x, y - 1, z).isAir(), state.getValue(BlockStateProperties.NORTH)
                || state.getValue(BlockStateProperties.EAST) || state.getValue(BlockStateProperties.SOUTH)
                || state.getValue(BlockStateProperties.WEST));
        }
        if (kind == DonutGrowthRules.Kind.LEAVES) {
            return state.hasProperty(BlockStateProperties.DISTANCE) && state.hasProperty(BlockStateProperties.PERSISTENT)
                && DonutGrowthRules.leaf(state.getValue(BlockStateProperties.DISTANCE), state.getValue(BlockStateProperties.PERSISTENT));
        }
        if (kind == DonutGrowthRules.Kind.AMETHYST) return amethystOwed(blocks, x, y, z, state);
        return false;
    }

    private static boolean amethystOwed(BlockReader blocks, int x, int y, int z, BlockState state) {
        if (state.is(Blocks.BUDDING_AMETHYST)) {
            for (Direction direction : Direction.values()) {
                BlockState near = blocks.state(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
                if (DonutGrowthRules.amethyst(true, false,
                    near.isAir() || near.is(Blocks.WATER) && near.getFluidState().getAmount() == 8, false)) return true;
            }
            return false;
        }
        if (isBud(state) && state.hasProperty(BlockStateProperties.FACING)) {
            Direction facing = state.getValue(BlockStateProperties.FACING);
            return DonutGrowthRules.amethyst(false, true,
                false, blocks.state(x - facing.getStepX(), y - facing.getStepY(), z - facing.getStepZ()).is(Blocks.BUDDING_AMETHYST));
        }
        return false;
    }

    private static int column(BlockReader blocks, int x, int y, int z, Block target, int limit) {
        int length = 1;
        while (length < limit && blocks.state(x, y - length, z).is(target)) length++;
        return length;
    }

    private static int bambooBelow(BlockReader blocks, int x, int y, int z) {
        int below = 0;
        while (below < 16 && blocks.state(x, y - below - 1, z).is(Blocks.BAMBOO)) below++;
        return below;
    }

    private static boolean soilOwed(ClientLevel level, BlockReader blocks, int x, int y, int z, BlockState state) {
        BlockState upper = blocks.state(x, y + 1, z);
        boolean upperSmothers = smothers(level, upper, x, y + 1, z);
        if (isGrass(state)) return DonutGrowthRules.grassDirt(true, false, upperSmothers, false, false);
        if (!state.is(Blocks.DIRT)) return false;
        boolean nearbyGrassCanSpread = false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int ny = y - 1; ny <= y + 3; ny++) {
            if ((dx != 0 || dz != 0 || ny != y) && isGrass(blocks.state(x + dx, ny, z + dz))
                && !smothers(level, blocks.state(x + dx, ny + 1, z + dz), x + dx, ny + 1, z + dz)
                && blocks.light(x + dx, ny + 1, z + dz) >= 9) nearbyGrassCanSpread = true;
        }
        return DonutGrowthRules.grassDirt(false, true, upperSmothers, upper.getFluidState().is(FluidTags.WATER), nearbyGrassCanSpread);
    }

    private static boolean smothers(ClientLevel level, BlockState upper, int x, int y, int z) {
        if (upper.is(Blocks.SNOW) && upper.getValue(SnowLayerBlock.LAYERS) == 1) return false;
        return upper.getFluidState().getAmount() == 8 || LightEngine.getLightDampeningInto(Blocks.GRASS_BLOCK.defaultBlockState(),
            upper, Direction.UP, upper.getLightDampening()) >= 15;
    }

    private static boolean surfaceOwed(ClientLevel level, BlockReader blocks, int x, int y, int z, int seaLevel,
                                       boolean donut, boolean[] counted) {
        boolean snow = counted[DonutGrowthRules.Kind.SNOW.ordinal()];
        return snow && snowOwed(level, blocks, x, y, z, seaLevel, donut, blocks.state(x, y - 1, z).is(Blocks.DEEPSLATE))
            || counted[DonutGrowthRules.Kind.ICE.ordinal()] && iceOwed(level, blocks, x, y, z, seaLevel);
    }

    private static boolean snowOwed(ClientLevel level, BlockReader blocks, int x, int y, int z, int seaLevel,
                                    boolean donut, boolean deepslateBelow) {
        boolean inBuildHeight = !level.isOutsideBuildHeight(y);
        boolean airAtSurface = blocks.state(x, y, z).isAir();
        BlockPos belowPos = new BlockPos(x, y - 1, z);
        BlockState below = blocks.state(x, y - 1, z);
        BlockPos pos = new BlockPos(x, y, z);
        boolean support = holdsSnow(level, below, belowPos);
        boolean snowPrecipitation = level.getBiome(pos).value().getPrecipitationAt(pos, seaLevel) == Precipitation.SNOW;
        boolean calm = inBuildHeight && airAtSurface && support && snowPrecipitation && calmLight(level, blocks, x, y, z);
        return DonutGrowthRules.snow(inBuildHeight, airAtSurface, support, snowPrecipitation, calm, donut, deepslateBelow);
    }

    private static boolean holdsSnow(ClientLevel level, BlockState below, BlockPos pos) {
        if (below.is(BlockTags.CANNOT_SUPPORT_SNOW_LAYER)) return false;
        if (below.is(BlockTags.SUPPORT_OVERRIDE_SNOW_LAYER)) return true;
        if (below.is(Blocks.SNOW) && below.getValue(SnowLayerBlock.LAYERS) == 8) return true;
        return Block.isFaceFull(below.getCollisionShape(level, pos), Direction.UP);
    }

    private static boolean iceOwed(ClientLevel level, BlockReader blocks, int x, int y, int z, int seaLevel) {
        int waterY = y - 1;
        if (level.isOutsideBuildHeight(waterY)) return DonutGrowthRules.ice(false, false, false, false, false);
        BlockState state = blocks.state(x, waterY, z);
        boolean waterSource = state.getBlock() instanceof LiquidBlock && state.getFluidState().getType() == Fluids.WATER;
        if (!waterSource) return DonutGrowthRules.ice(false, false, false, false, false);
        boolean neighborsLoaded = level.hasChunk((x - 1) >> 4, z >> 4) && level.hasChunk((x + 1) >> 4, z >> 4)
            && level.hasChunk(x >> 4, (z - 1) >> 4) && level.hasChunk(x >> 4, (z + 1) >> 4);
        if (!neighborsLoaded) return DonutGrowthRules.ice(true, false, false, false, false);
        boolean allWater = isWater(blocks, x - 1, waterY, z) && isWater(blocks, x + 1, waterY, z)
            && isWater(blocks, x, waterY, z - 1) && isWater(blocks, x, waterY, z + 1);
        BlockPos surfacePos = new BlockPos(x, y, z);
        BlockPos waterPos = new BlockPos(x, waterY, z);
        boolean coldEnough = !level.getBiome(surfacePos).value().warmEnoughToRain(waterPos, seaLevel);
        boolean calm = !allWater && coldEnough && calmLight(level, blocks, x, waterY, z);
        return DonutGrowthRules.ice(waterSource, allWater, neighborsLoaded, coldEnough, calm);
    }

    private static boolean isWater(BlockReader blocks, int x, int y, int z) {
        return blocks.state(x, y, z).getFluidState().is(FluidTags.WATER);
    }

    private static boolean calmLight(ClientLevel level, BlockReader blocks, int x, int y, int z) {
        int radius = 5;
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++) {
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++) if (!level.hasChunk(cx, cz)) return false;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            int restY = radius - Math.abs(dx);
            for (int dy = -restY; dy <= restY; dy++) {
                int restZ = restY - Math.abs(dy);
                for (int dz = -restZ; dz <= restZ; dz++) {
                    int emission = blocks.state(x + dx, y + dy, z + dz).getLightEmission();
                    if (emission != 0 && emission - Math.abs(dx) - Math.abs(dy) - Math.abs(dz) >= 10) return false;
                }
            }
        }
        return true;
    }

    private static Boolean eyeblossomsOpen(ClientLevel level) {
        if (level.dimension() != Level.OVERWORLD || level.dimensionType().hasFixedTime()) return null;
        int time = Math.floorMod((int) level.getOverworldClockTime(), 24000);
        boolean open = time >= 12600 && time < 23401;
        int since = open ? time - 12600 : Math.floorMod(time - 23401, 24000);
        return since >= 4000 ? open : null;
    }

    private static boolean isBud(BlockState state) {
        return state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.LARGE_AMETHYST_BUD);
    }

    private static boolean isGrass(BlockState state) { return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MYCELIUM); }

    private static final class BlockReader {
        private final ClientLevel level;
        private LevelChunk cachedChunk;

        private BlockReader(ClientLevel level) { this.level = level; }

        private BlockState state(int x, int y, int z) {
            if (this.level.isOutsideBuildHeight(y)) return Blocks.VOID_AIR.defaultBlockState();
            LevelChunk chunk = this.chunkAt(x >> 4, z >> 4);
            if (chunk == null) return Blocks.VOID_AIR.defaultBlockState();
            int sectionIndex = chunk.getSectionIndex(y);
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex < 0 || sectionIndex >= sections.length) return Blocks.VOID_AIR.defaultBlockState();
            LevelChunkSection section = sections[sectionIndex];
            return section == null ? Blocks.VOID_AIR.defaultBlockState() : section.getBlockState(x & 15, y & 15, z & 15);
        }

        private LevelChunk chunkAt(int cx, int cz) {
            if (this.cachedChunk != null && this.cachedChunk.getPos().x() == cx && this.cachedChunk.getPos().z() == cz) return this.cachedChunk;
            LevelChunk found = this.level.getChunkSource().getChunkNow(cx, cz);
            if (found != null) this.cachedChunk = found;
            return found;
        }

        private int light(int x, int y, int z) {
            int value = Math.max(this.rawLight(LightLayer.BLOCK, x, y, z), this.rawLight(LightLayer.SKY, x, y, z));
            if (value < 15 && this.level.dimensionType().hasSkyLight()) return this.openSky(x, y, z) ? 15 : value;
            return value;
        }

        private int rawLight(LightLayer layer, int x, int y, int z) {
            DataLayer data = this.level.getLightEngine().getLayerListener(layer).getDataLayerData(SectionPos.of(x >> 4, y >> 4, z >> 4));
            return data != null && !data.isDefinitelyHomogenous() ? data.get(x & 15, y & 15, z & 15) : 0;
        }

        private boolean openSky(int x, int y, int z) {
            for (int scanY = y + 1; scanY <= this.level.getMaxY(); scanY++) {
                LevelChunk chunk = this.chunkAt(x >> 4, z >> 4);
                if (chunk == null) return false;
                int index = chunk.getSectionIndex(scanY);
                LevelChunkSection section = index < 0 || index >= chunk.getSections().length ? null : chunk.getSections()[index];
                if (section == null || section.hasOnlyAir()) scanY |= 15;
                else if (this.state(x, scanY, z).getLightDampening() > 0) return false;
            }
            return true;
        }
    }
}

