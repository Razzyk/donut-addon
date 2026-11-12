package dev.donut.addon.expansion.search.growth;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.expansion.search.growth.NaturalGrowthRules.Fact;
import dev.donut.addon.expansion.search.growth.NaturalGrowthRules.Kind;
import dev.donut.addon.expansion.search.growth.NaturalGrowthRules.Sample;
import dev.donut.addon.expansion.search.amethyst.GeodePaletteEvidence;
import dev.donut.addon.mv.compat.events.EventHandler;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.ChunkDataEvent;
import dev.donut.addon.mv.compat.events.world.StorageBlocksChangedEvent;
import dev.donut.addon.mv.compat.events.world.StorageChunkUnloadedEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.events.packets.PacketEvent;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.EnumSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.SettingGroup;
import dev.donut.addon.mv.compat.systems.modules.Category;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.ChunkPos;

/** Incremental, client-thread survey of loaded plant and surface-growth opportunities. */
public final class GrowthSurveyModule extends Module {
    private static final int BLOCKS_PER_TICK = 8192;
    private static final int MAX_PENDING_CHUNKS = 8192;
    private static final int MAX_RECORDED_CHUNKS = 8192;
    private static final int MAX_RENDERED_PLATES = 512;
    private static final int MAX_PENDING_PACKETS = 1024;
    private static final int MAX_PACKETS_PER_TICK = 64;
    private static final int SOURCE_MIN_Y = -16;
    private static final int SOURCE_MAX_Y = 30;
    private static final List<Kind> EVIDENCE_KINDS = List.of(Kind.values());
    private static final Color PLATE_FILL = new Color(65, 195, 164, 36);
    private static final Color PLATE_LINE = new Color(65, 225, 192, 220);

    private final Setting<SurveyMethod> method = this.settings.getDefaultGroup().add(
        new EnumSetting.Builder<SurveyMethod>().name("method")
            .description("Choose natural growth scoring, classic Sus signals, or Donut geode-light scanning.")
            .defaultValue(SurveyMethod.Classic).build()
    );
    private final Setting<Integer> scanRadius = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("scan-radius")
            .description("Radius of already loaded chunks included in a survey.")
            .defaultValue(4).range(1, 12).sliderRange(1, 8)
            .visible(() -> this.method.get() == SurveyMethod.NaturalGrowth).build()
    );
    private final Setting<Integer> minimumEvidence = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("minimum-evidence")
            .description("Minimum valid growth opportunities before a chunk is marked.")
            .defaultValue(12).range(1, 500).sliderRange(1, 100)
            .visible(() -> this.method.get() == SurveyMethod.NaturalGrowth && !this.packetHeat.get()).build()
    );
    private final Setting<Integer> markerY = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("marker-y")
            .description("Height of the chunk marker plate.")
            .defaultValue(62).range(-64, 320).sliderRange(-64, 200)
            .build()
    );
    private final Setting<Boolean> customEvidence = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("custom-evidence")
            .description("Choose which growth and surface clues contribute to chunk scores.")
            .defaultValue(false).visible(() -> this.method.get() == SurveyMethod.NaturalGrowth).build()
    );
    private final Setting<SettingColor> markerColor = this.settings.getDefaultGroup().add(
        new ColorSetting.Builder().name("chunk-color")
            .description("Color for flagged chunks.")
            .defaultValue(new SettingColor(65, 225, 192, 130))
            .build()
    );
    private final Setting<Boolean> packetHeat = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("packet-heat")
            .description("Spread per-kind growth heat and vetoes across the configured simulation neighborhood.")
            .defaultValue(false).visible(() -> this.method.get() == SurveyMethod.NaturalGrowth).build()
    );
    private final Setting<Integer> simulationRadius = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("spread-radius").description("Chunk radius used by Sus signal heat and local-peak checks.")
            .defaultValue(5).range(2, 16).sliderRange(2, 10)
            .visible(() -> this.method.get() != SurveyMethod.NaturalGrowth || this.packetHeat.get()).build()
    );
    private final Setting<Integer> packetSensitivity = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("packet-sensitivity").description("Minimum non-vetoed signal heat required for a plate.")
            .defaultValue(6).range(1, 20).sliderRange(1, 20)
            .visible(() -> this.method.get() != SurveyMethod.NaturalGrowth || this.packetHeat.get()).build()
    );
    private final Setting<Boolean> tracers = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("tracers").description("Draw lines to Sus and mined-geode chunk plates.")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> beeNestSignals = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("natural-bee-nests").description("Include full bee nests as a growth signal in packet heat.")
            .defaultValue(true).visible(this.packetHeat::get).build()
    );
    private final Setting<Boolean> detectMinedClusters = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("detect-mined-clusters").description("Also mark large, unlit amethyst geode shells without visible exposed buds.")
            .defaultValue(false).build()
    );
    private final Setting<SettingColor> minedColor = this.settings.getDefaultGroup().add(
        new ColorSetting.Builder().name("mined-color").description("Color for candidate mined geode chunks.")
            .defaultValue(new SettingColor(255, 160, 70, 115)).visible(this.detectMinedClusters::get).build()
    );

    private final EnumMap<Kind, Setting<Boolean>> enabledEvidence = new EnumMap<>(Kind.class);
    private final EnumMap<SourceSignal, Setting<Boolean>> enabledSourceSignals = new EnumMap<>(SourceSignal.class);
    private final ArrayDeque<Long> pending = new ArrayDeque<>();
    private final Map<Long, LevelChunk> pendingChunks = new HashMap<>();
    private final LinkedHashMap<Long, ChunkSummary> summaries = new LinkedHashMap<>(256, 0.75f, true);
    private final SuspectGrowthHeat signalHeat = new SuspectGrowthHeat(MAX_RECORDED_CHUNKS, 5);
    private final SuspectServerLight serverLight = new SuspectServerLight();
    private final SuspectGlowScanner glowScanner = new SuspectGlowScanner();
    private final MinedGeodeSurvey minedSurvey = new MinedGeodeSurvey();
    private final ArrayDeque<PendingSignalPacket> pendingPackets = new ArrayDeque<>();
    private final Object packetBoundaryLock = new Object();
    private final AtomicLong packetGeneration = new AtomicLong(1L);
    private final Set<Long> minedChunks = new HashSet<>();
    private boolean packetOverflowed;
    private int darkLightVotes;
    private int glowLightVotes;
    private long surveyTick;
    private int minedAge;
    private ClientLevel lastWorld;
    private ScanCursor cursor;

    public GrowthSurveyModule() {
        super(new Category(AddonCategories.BASEFINDING), "Donut Suspect Chunks",
            "Combines classic growth signs, Donut geode-light evidence, and optional natural-growth scoring.");
        SettingGroup evidenceGroup = this.settings.createGroup("Evidence");
        for (Kind kind : EVIDENCE_KINDS) {
            String key = kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            Setting<Boolean> setting = evidenceGroup.add(
                new BoolSetting.Builder().name(key)
                    .description("Count " + key.replace('-', ' ') + " growth evidence.")
                    .defaultValue(true).visible(this.customEvidence::get).build()
            );
            this.enabledEvidence.put(kind, setting);
        }
        SettingGroup sourceSignalGroup = this.settings.createGroup("Sus Signals");
        for (SourceSignal signal : SourceSignal.values()) {
            Setting<Boolean> setting = sourceSignalGroup.add(
                new BoolSetting.Builder().name(signal.settingName)
                    .description("Include " + signal.label + " in per-kind heat and veto scoring.")
                    .defaultValue(true).visible(() -> this.method.get() != SurveyMethod.NaturalGrowth).build()
            );
            this.enabledSourceSignals.put(signal, setting);
            setting.onChanged(ignored -> {
                this.reset();
                this.queueLoadedChunks();
            });
        }
        this.method.onChanged(ignored -> {
            this.reset();
            this.queueLoadedChunks();
        });
        this.scanRadius.onChanged(ignored -> this.queueLoadedChunks());
        this.customEvidence.onChanged(ignored -> this.queueLoadedChunks());
        this.minimumEvidence.onChanged(ignored -> this.queueLoadedChunks());
        this.enabledEvidence.values().forEach(setting -> setting.onChanged(ignored -> this.queueLoadedChunks()));
        this.simulationRadius.onChanged(ignored -> {
            this.reset();
            this.signalHeat.setSpreadRadius(this.simulationRadius.get());
            this.queueLoadedChunks();
        });
        this.packetHeat.onChanged(ignored -> this.queueLoadedChunks());
        this.detectMinedClusters.onChanged(ignored -> {
            this.minedChunks.clear();
            this.minedSurvey.clear();
            this.queueLoadedChunks();
        });
    }

    @Override
    public void onActivate() {
        this.reset();
        this.resetPacketBoundary(true);
        this.serverLight.clear();
        this.darkLightVotes = 0;
        this.glowLightVotes = 0;
        this.lastWorld = this.mc.level;
        this.queueLoadedChunks();
    }

    @Override
    public void onDeactivate() {
        this.reset();
        this.resetPacketBoundary(true);
        this.serverLight.clear();
        this.lastWorld = null;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        this.reset();
        this.resetPacketBoundary(true);
        this.serverLight.clear();
        this.darkLightVotes = 0;
        this.glowLightVotes = 0;
        this.lastWorld = this.mc.level;
        this.queueLoadedChunks();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        this.reset();
        this.resetPacketBoundary(true);
        this.serverLight.clear();
        this.darkLightVotes = 0;
        this.glowLightVotes = 0;
        this.lastWorld = null;
    }

    /** Clears source Sus state at a session boundary without toggling the module. */
    public void endSearchSession() {
        this.reset();
        this.resetPacketBoundary(true);
        this.serverLight.clear();
        this.darkLightVotes = 0;
        this.glowLightVotes = 0;
        this.lastWorld = null;
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        LevelChunk chunk = event.chunk();
        if (chunk == null) return;
        this.queueChunk(chunk);
        if (this.detectMinedClusters.get()) {
            this.queueMinedNeighbors(this.mc.level, chunk.getPos().x(), chunk.getPos().z());
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (!this.isActive()) return;
        Object packet = event.packet;
        if (!(packet instanceof ClientboundLevelChunkWithLightPacket)
            && !(packet instanceof ClientboundLightUpdatePacket)
            && !(packet instanceof ClientboundBlockUpdatePacket)
            && !(packet instanceof ClientboundSectionBlocksUpdatePacket)) return;
        long generation = this.packetGeneration.get();
        synchronized (this.packetBoundaryLock) {
            if (!this.isActive() || generation != this.packetGeneration.get()) return;
            if (this.pendingPackets.size() >= MAX_PENDING_PACKETS) {
                this.packetOverflowed = true;
                return;
            }
            this.pendingPackets.addLast(new PendingSignalPacket(packet, generation));
        }
    }

    @EventHandler
    private void onBlocksChanged(StorageBlocksChangedEvent event) {
        ClientLevel world = this.mc.level;
        if (world == null) return;
        for (BlockPos changed : event.changes().keySet()) {
            this.queueChangedBlock(world, changed);
        }
    }

    @EventHandler
    private void onChunkUnloaded(StorageChunkUnloadedEvent event) {
        long key = event.pos().pack();
        this.serverLight.forgetChunk(event.pos().x(), event.pos().z());
        this.pendingChunks.remove(key);
        this.pending.remove(key);
        this.summaries.remove(key);
        this.signalHeat.remove(new SuspectGrowthHeat.Key(event.pos().x(), event.pos().z()));
        this.minedChunks.remove(key);
        this.minedSurvey.forgetChunk(event.pos().x(), event.pos().z());
        if (this.cursor != null && this.cursor.key == key) this.cursor = null;
    }

    @EventHandler
    private void onTick(Post event) {
        ClientLevel world = this.mc.level;
        if (world != this.lastWorld) {
            this.resetPacketBoundary(true);
            this.serverLight.clear();
            this.reset();
            this.lastWorld = world;
            this.queueLoadedChunks();
        }
        if (world == null || this.mc.player == null || this.mc.player.level() != world || this.mc.getConnection() == null) return;
        this.surveyTick++;
        if (this.drainPacketSignals(world)) {
            this.serverLight.clear();
            this.reset();
            this.lastWorld = world;
            this.queueLoadedChunks();
        }
        this.serverLight.forgetUnloaded(world);

        int budget = BLOCKS_PER_TICK;
        while (budget > 0) {
            if (this.cursor == null && !this.startNext(world)) break;
            boolean classicSource = this.method.get() == SurveyMethod.Classic;
            boolean sourceScan = this.method.get() != SurveyMethod.NaturalGrowth;
            int visited = this.cursor.advance(world, budget, this::enabled, this::evaluate,
                classicSource ? this::sourceSign : null, sourceScan);
            budget -= Math.max(1, visited);
            if (this.cursor.complete()) {
                if (this.method.get() == SurveyMethod.DonutScan) {
                    this.cursor.sourceGrown[SourceSignal.AMETHYST.ordinal()] = this.scanDonutGlow(world, this.cursor.chunk) / 12;
                    if (this.cursor.sourceGrown[SourceSignal.AMETHYST.ordinal()] > 0) {
                        this.cursor.sourceObserved[SourceSignal.AMETHYST.ordinal()] = 1;
                    }
                } else if (this.method.get() == SurveyMethod.Classic) {
                    LightReading hidden = this.scanHiddenAmethyst(world, this.cursor.chunk);
                    this.darkLightVotes += hidden.darkVotes();
                    this.glowLightVotes += hidden.glowVotes();
                    this.cursor.sourceGrown[SourceSignal.AMETHYST.ordinal()] += hidden.clusters();
                    if (hidden.buds() > 0) this.cursor.sourceVetoMask |= 1 << SourceSignal.AMETHYST.ordinal();
                }
                this.summaries.put(this.cursor.key, new ChunkSummary(this.cursor.key, this.cursor.counts.clone(),
                    this.cursor.sourcePacketData));
                this.updateSignalHeat(this.cursor);
                if (this.detectMinedClusters.get()) this.minedSurvey.scanChunk(world, this.cursor.chunk, this.surveyTick);
                while (this.summaries.size() > MAX_RECORDED_CHUNKS) {
                    Iterator<Long> oldest = this.summaries.keySet().iterator();
                    long removed = oldest.next();
                    oldest.remove();
                    this.signalHeat.remove(new SuspectGrowthHeat.Key((int) removed, (int) (removed >> 32)));
                    this.minedChunks.remove(removed);
                }
                this.cursor = null;
            }
        }
        if (this.detectMinedClusters.get()) {
            if (++this.minedAge >= 20) {
                this.minedChunks.clear();
                this.minedChunks.addAll(this.minedSurvey.candidates(world, this.surveyTick, this.serverLight));
                this.minedAge = 0;
            }
        } else {
            this.minedChunks.clear();
        }
        this.publishFlaggedSnapshot(world);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        ClientLevel world = this.mc.level;
        if (world == null || this.mc.player == null) return;
        int cx = this.mc.player.getBlockX() >> 4;
        int cz = this.mc.player.getBlockZ() >> 4;
        boolean sourceSus = this.method.get() != SurveyMethod.NaturalGrowth;
        int range = sourceSus ? this.sourceChunkRadius() : this.scanRadius.get();
        SettingColor selected = this.markerColor.get();
        Color line = new Color(selected.r, selected.g, selected.b, Math.max(80, selected.a));
        Color fill = new Color(selected.r, selected.g, selected.b, Math.min(70, selected.a));
        boolean[] enabled = enabledMask();
        Map<SuspectGrowthHeat.Key, Integer> heatScores = new HashMap<>();
        boolean heatMode = sourceSus || this.packetHeat.get();
        if (heatMode) for (SuspectGrowthHeat.Score score : this.signalHeat.scores()) heatScores.put(score.key(), score.value());
        Map<SuspectGrowthHeat.Key, ChunkSummary> summariesByChunk = new HashMap<>();
        for (ChunkSummary summary : this.summaries.values()) {
            summariesByChunk.put(new SuspectGrowthHeat.Key((int) summary.key, (int) (summary.key >> 32)), summary);
        }
        int drawn = 0;
        for (ChunkSummary summary : this.summaries.values()) {
            if (drawn >= MAX_RENDERED_PLATES) break;
            int chunkX = (int)summary.key;
            int chunkZ = (int)(summary.key >> 32);
            if (Math.abs(chunkX - cx) > range || Math.abs(chunkZ - cz) > range) continue;
            SuspectGrowthHeat.Key key = new SuspectGrowthHeat.Key(chunkX, chunkZ);
            boolean flagged = sourceSus
                ? this.isSourceSuspect(world, summariesByChunk, heatScores, summary, key)
                : this.packetHeat.get()
                    ? this.isHeatPlate(heatScores, chunkX, chunkZ)
                    : NaturalGrowthScore.meetsThreshold(summary.counts, enabled, this.minimumEvidence.get());
            if (!flagged) continue;
            double x = chunkX * 16.0;
            double z = chunkZ * 16.0;
            double y = this.markerY.get() + 0.02;
            event.renderer.box(x, y, z, x + 16.0, y + 0.08, z + 16.0, fill, line, ShapeMode.Both, 0);
            if (this.tracers.get()) event.renderer.line(this.mc.player.getX(), this.mc.player.getEyeY(), this.mc.player.getZ(),
                x + 8.0, 63.0, z + 8.0, line);
            drawn++;
        }
        if (this.detectMinedClusters.get()) {
            SettingColor mined = this.minedColor.get();
            Color minedLine = new Color(mined.r, mined.g, mined.b, Math.max(110, mined.a));
            Color minedFill = new Color(mined.r, mined.g, mined.b, Math.min(70, mined.a));
            for (long key : this.minedChunks) {
                int chunkX = (int) key, chunkZ = (int) (key >> 32);
                if (Math.abs(chunkX - cx) > range || Math.abs(chunkZ - cz) > range) continue;
                double y = this.markerY.get() + 0.1;
                event.renderer.box(chunkX * 16.0, y, chunkZ * 16.0, chunkX * 16.0 + 16.0, y + 0.07,
                    chunkZ * 16.0 + 16.0, minedFill, minedLine, ShapeMode.Both, 0);
                if (this.tracers.get()) event.renderer.line(this.mc.player.getX(), this.mc.player.getEyeY(), this.mc.player.getZ(),
                    chunkX * 16.0 + 8.0, 63.0, chunkZ * 16.0 + 8.0, minedLine);
            }
        }
    }

    private DonutWorldFlagSnapshot snapshot = DonutWorldFlagSnapshot.empty(0L);
    private long snapshotGeneration;

    /** Immutable current-world candidate set for consumers such as the tunnel planner. */
    public DonutWorldFlagSnapshot flaggedChunkSnapshot() { return this.snapshot; }

    private void publishFlaggedSnapshot(ClientLevel world) {
        if (this.method.get() == SurveyMethod.NaturalGrowth || world == null || this.mc.player == null
            || this.mc.player.level() != world) {
            this.snapshot = DonutWorldFlagSnapshot.empty(++this.snapshotGeneration);
            return;
        }
        int centerX = this.mc.player.getBlockX() >> 4;
        int centerZ = this.mc.player.getBlockZ() >> 4;
        int radius = this.sourceChunkRadius();
        Map<SuspectGrowthHeat.Key, ChunkSummary> byChunk = new HashMap<>();
        for (ChunkSummary summary : this.summaries.values()) {
            byChunk.put(new SuspectGrowthHeat.Key((int) summary.key, (int) (summary.key >> 32)), summary);
        }
        Map<SuspectGrowthHeat.Key, Integer> scores = new HashMap<>();
        for (SuspectGrowthHeat.Score score : this.signalHeat.scores()) scores.put(score.key(), score.value());
        Set<Long> keys = new HashSet<>();
        for (ChunkSummary summary : this.summaries.values()) {
            int x = (int) summary.key, z = (int) (summary.key >> 32);
            if (Math.abs(x - centerX) > radius || Math.abs(z - centerZ) > radius) continue;
            SuspectGrowthHeat.Key key = new SuspectGrowthHeat.Key(x, z);
            if (this.isSourceSuspect(world, byChunk, scores, summary, key)) keys.add(summary.key);
        }
        this.snapshot = new DonutWorldFlagSnapshot(world.dimension().identifier().toString(), world,
            ++this.snapshotGeneration, keys);
    }

    private boolean enabled(Kind kind) {
        return !this.customEvidence.get() || this.enabledEvidence.get(kind).get();
    }

    private boolean[] enabledMask() {
        boolean[] selected = new boolean[Kind.values().length];
        for (Kind kind : Kind.values()) selected[kind.ordinal()] = this.enabled(kind);
        return selected;
    }

    private void updateSignalHeat(ScanCursor cursor) {
        int[] signals = new int[SuspectGrowthHeat.KIND_COUNT];
        if (this.method.get() != SurveyMethod.NaturalGrowth) {
            for (SourceSignal signal : SourceSignal.values()) {
                int index = signal.ordinal();
                if (this.sourceSignalEnabled(signal)) signals[index] = cursor.sourceGrown[index];
            }
            int vetoMask = 0;
            for (SourceSignal signal : SourceSignal.values()) {
                int index = signal.ordinal();
                if (this.sourceSignalEnabled(signal)) vetoMask |= cursor.sourceVetoMask & (1 << index);
            }
            this.signalHeat.replace(new SuspectGrowthHeat.Key((int) cursor.key, (int) (cursor.key >> 32)), signals, vetoMask);
            return;
        }
        Kind[] kinds = {Kind.AMETHYST_BUD, Kind.KELP, Kind.CAVE_VINE, Kind.VINE, Kind.BAMBOO, Kind.COCOA};
        for (int i = 0; i < kinds.length; i++) if (this.enabled(kinds[i])) signals[i] = cursor.counts[kinds[i].ordinal()];
        if (this.beeNestSignals.get()) signals[6] = cursor.beeNestFull;
        int vetoMask = 0;
        for (int i = 0; i < kinds.length; i++) {
            if (this.enabled(kinds[i]) && cursor.observed[kinds[i].ordinal()] > 0 && signals[i] == 0) vetoMask |= 1 << i;
        }
        if (this.beeNestSignals.get() && cursor.beeNestObserved > 0 && signals[6] == 0) vetoMask |= 1 << 6;
        this.signalHeat.replace(new SuspectGrowthHeat.Key((int) cursor.key, (int) (cursor.key >> 32)), signals, vetoMask);
    }

    private boolean sourceSignalEnabled(SourceSignal signal) {
        Setting<Boolean> setting = this.enabledSourceSignals.get(signal);
        return setting == null || setting.get();
    }

    private int scanDonutGlow(ClientLevel world, LevelChunk chunk) {
        return this.glowScanner.scan(world, chunk, this.serverLight);
    }

    private LightReading scanHiddenAmethyst(ClientLevel world, LevelChunk chunk) {
        return SuspectHiddenAmethyst.scan(world, chunk, this.serverLight, this.hiddenSolidDecision());
    }

    private Boolean hiddenSolidDecision() {
        var connection = this.mc.getConnection();
        String brand = connection == null ? null : connection.serverBrand();
        if (brand != null && brand.contains("DonutFolia")) return Boolean.TRUE;
        if (this.darkLightVotes >= 3 && this.darkLightVotes > this.glowLightVotes) return Boolean.TRUE;
        if (this.glowLightVotes >= 3 && this.glowLightVotes > this.darkLightVotes) return Boolean.FALSE;
        return null;
    }

    private int sourceSign(ClientLevel world, BlockPos.MutableBlockPos pos, BlockState state,
                           SourceSignal signal) {
        int stage = integerProperty(state, "age", -1);
        BlockState above = world.getBlockState(pos.above());
        BlockState below = world.getBlockState(pos.below());
        return switch (signal) {
            case AMETHYST -> state.is(Blocks.AMETHYST_CLUSTER) ? 1
                : isAmethystBud(state) && hasFacing(state)
                    ? world.getBlockState(pos.relative(facing(state).getOpposite())).is(Blocks.BUDDING_AMETHYST) ? -1 : 1
                    : 0;
            case KELP -> state.is(Blocks.KELP) ? stage >= 0 && stage < 25 && above.is(Blocks.WATER) ? -1 : 1 : 0;
            case CAVE_VINES -> state.is(Blocks.CAVE_VINES)
                ? stage >= 0 && stage < 25 && below.isAir() ? -1 : 1 : 0;
            case VINES -> {
                if (!state.is(Blocks.VINE) || below.is(Blocks.VINE)) yield 0;
                if (!below.isAir()) yield 1;
                yield hasSideAttachment(state) ? -1 : 1;
            }
            case BAMBOO -> {
                if (!state.is(Blocks.BAMBOO) || above.is(Blocks.BAMBOO)) yield 0;
                if (integerProperty(state, "stage", 0) == 1) yield 1;
                if (above.isAir() && world.getRawBrightness(pos, 0) >= 9) {
                    int length = 1;
                    int x = pos.getX(), y = pos.getY(), z = pos.getZ();
                    while (length < 16 && world.getBlockState(pos.set(x, y - length, z)).is(Blocks.BAMBOO)) length++;
                    yield length < 16 ? -1 : 0;
                }
                yield 0;
            }
            case COCOA -> state.is(Blocks.COCOA) ? integerProperty(state, "age", 0) == 2 ? 1 : -1 : 0;
            case BEE_NEST -> state.is(Blocks.BEE_NEST)
                ? integerProperty(state, "honey_level", 0) == 5 ? 1 : -1 : 0;
        };
    }

    private static boolean matchesSourceSignal(BlockState state, SourceSignal signal) {
        return switch (signal) {
            case AMETHYST -> state.is(Blocks.AMETHYST_CLUSTER) || isAmethystBud(state);
            case KELP -> state.is(Blocks.KELP);
            case CAVE_VINES -> state.is(Blocks.CAVE_VINES);
            case VINES -> state.is(Blocks.VINE);
            case BAMBOO -> state.is(Blocks.BAMBOO);
            case COCOA -> state.is(Blocks.COCOA);
            case BEE_NEST -> state.is(Blocks.BEE_NEST);
        };
    }

    private static boolean isAmethystBud(BlockState state) {
        return state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD)
            || state.is(Blocks.LARGE_AMETHYST_BUD);
    }

    private static boolean hasFacing(BlockState state) {
        for (Property<?> property : state.getProperties()) if (property.getName().equals("facing")) return true;
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Direction facing(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("facing")) {
                Object value = state.getValue((Property) property);
                return value instanceof Direction direction ? direction : Direction.UP;
            }
        }
        return Direction.UP;
    }

    private static boolean hasSideAttachment(BlockState state) {
        for (String name : List.of("north", "east", "south", "west")) {
            for (Property<?> property : state.getProperties()) {
                if (property.getName().equals(name)) {
                    try {
                        if (Boolean.parseBoolean(String.valueOf(propertyValue(state, property)))) return true;
                    } catch (RuntimeException ignored) { }
                }
            }
        }
        return false;
    }

    private int sourceChunkRadius() {
        return Math.max(1, this.mc.options.renderDistance().get() + 1);
    }

    private boolean isSourceSuspect(ClientLevel world, Map<SuspectGrowthHeat.Key, ChunkSummary> summariesByChunk,
                                    Map<SuspectGrowthHeat.Key, Integer> scores, ChunkSummary summary,
                                    SuspectGrowthHeat.Key key) {
        if (world.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false) == null) return false;
        if (this.scannedNeighbourCount(world, summariesByChunk, key) < 3) return false;
        int value = scores.getOrDefault(key, 0);
        boolean fallback = !summary.sourcePacketData && this.signalHeat.heat(key, SourceSignal.AMETHYST.ordinal()) > 0;
        if (value < this.packetSensitivity.get() && !fallback) return false;
        int radius = this.simulationRadius.get();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx == 0 && dz == 0) continue;
            SuspectGrowthHeat.Key neighbor = new SuspectGrowthHeat.Key(key.x() + dx, key.z() + dz);
            if (!summariesByChunk.containsKey(neighbor)) continue;
            if (world.getChunkSource().getChunk(neighbor.x(), neighbor.z(), ChunkStatus.FULL, false) == null) continue;
            if (this.scannedNeighbourCount(world, summariesByChunk, neighbor) < 3) continue;
            if (scores.getOrDefault(neighbor, 0) > value) return false;
        }
        return true;
    }

    private int scannedNeighbourCount(ClientLevel world, Map<SuspectGrowthHeat.Key, ChunkSummary> summariesByChunk,
                                     SuspectGrowthHeat.Key key) {
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            SuspectGrowthHeat.Key neighbor = new SuspectGrowthHeat.Key(key.x() + dx, key.z() + dz);
            if (summariesByChunk.containsKey(neighbor)
                && world.getChunkSource().getChunk(neighbor.x(), neighbor.z(), ChunkStatus.FULL, false) != null) count++;
        }
        return count;
    }

    private boolean isHeatPlate(Map<SuspectGrowthHeat.Key, Integer> scores, int chunkX, int chunkZ) {
        SuspectGrowthHeat.Key key = new SuspectGrowthHeat.Key(chunkX, chunkZ);
        int value = scores.getOrDefault(key, 0);
        if (value < this.packetSensitivity.get()) return false;
        int radius = this.simulationRadius.get();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (dx == 0 && dz == 0) continue;
            if (scores.getOrDefault(new SuspectGrowthHeat.Key(chunkX + dx, chunkZ + dz), 0) > value) return false;
        }
        return true;
    }

    private void queueLoadedChunks() {
        ClientLevel world = this.mc.level;
        if (!this.isActive() || world == null || this.mc.player == null) return;
        int centerX = this.mc.player.getBlockX() >> 4;
        int centerZ = this.mc.player.getBlockZ() >> 4;
        int radius = this.method.get() == SurveyMethod.NaturalGrowth ? this.scanRadius.get() : this.sourceChunkRadius();
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                ChunkAccess chunk = world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk instanceof LevelChunk loaded) this.queueChunk(loaded);
            }
        }
    }

    private void queueChunk(LevelChunk chunk) {
        long key = chunk.getPos().pack();
        this.minedSurvey.forgetChunk(chunk.getPos().x(), chunk.getPos().z());
        if (this.pendingChunks.size() >= MAX_PENDING_CHUNKS && !this.pendingChunks.containsKey(key)) return;
        this.summaries.remove(key);
        this.signalHeat.remove(new SuspectGrowthHeat.Key(chunk.getPos().x(), chunk.getPos().z()));
        this.minedChunks.remove(key);
        this.pendingChunks.put(key, chunk);
        if (!this.pending.contains(key)) this.pending.addLast(key);
    }

    private boolean startNext(ClientLevel world) {
        while (!this.pending.isEmpty()) {
            long key = this.pending.removeFirst();
            LevelChunk chunk = this.pendingChunks.remove(key);
            if (chunk == null) continue;
            int chunkX = chunk.getPos().x();
            int chunkZ = chunk.getPos().z();
            if (!world.getChunkSource().hasChunk(chunkX, chunkZ)) continue;
            this.cursor = new ScanCursor(key, chunk);
            return true;
        }
        return false;
    }

    private boolean evaluate(ClientLevel world, BlockPos.MutableBlockPos pos, BlockState state, Kind kind) {
        if (!this.enabled(kind)) return false;
        Sample sample = sample(world, pos, state, kind);
        return NaturalGrowthRules.isOpportunity(sample);
    }

    private boolean drainPacketSignals(ClientLevel world) {
        synchronized (this.packetBoundaryLock) {
            if (this.packetOverflowed) {
                this.packetOverflowed = false;
                this.pendingPackets.clear();
                this.packetGeneration.incrementAndGet();
                return true;
            }
        }
        int drained = 0;
        while (drained < MAX_PACKETS_PER_TICK) {
            PendingSignalPacket pending;
            synchronized (this.packetBoundaryLock) { pending = this.pendingPackets.pollFirst(); }
            if (pending == null) break;
            drained++;
            if (pending.generation != this.packetGeneration.get()) continue;
            Object packet = pending.packet;
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
                this.applyBlockLight(world, chunk.x(), chunk.z(), chunk.lightData(), true);
                this.queueChunkAt(world, chunk.x(), chunk.z());
                if (this.detectMinedClusters.get()) this.queueMinedNeighbors(world, chunk.x(), chunk.z());
            } else if (packet instanceof ClientboundLightUpdatePacket light) {
                this.applyBlockLight(world, light.x(), light.z(), light.lightData(), false);
            } else if (packet instanceof ClientboundBlockUpdatePacket block) {
                this.queueChangedBlock(world, block.getPos());
            } else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
                Set<Long> touchedBlocks = new HashSet<>();
                section.runUpdates((pos, ignored) -> {
                    touchedBlocks.add(pos.asLong());
                });
                for (long packed : touchedBlocks) this.queueChangedBlock(world, BlockPos.of(packed));
            }
        }
        return false;
    }

    private void applyBlockLight(ClientLevel world, int chunkX, int chunkZ,
                                 ClientboundLightUpdatePacketData data, boolean replaceChunk) {
        if (data == null) return;
        var engine = world.getLightEngine();
        this.serverLight.applyPacket(chunkX, chunkZ, engine.getMinLightSection(), engine.getLightSectionCount(),
            data.blockYMask(), data.emptyBlockYMask(), data.blockUpdates(), replaceChunk);
    }

    private void queueChangedBlock(ClientLevel world, BlockPos pos) {
        this.serverLight.blockChanged(pos);
        int chunkX = pos.getX() >> 4, chunkZ = pos.getZ() >> 4;
        this.queueChunkAt(world, chunkX, chunkZ);
        if (!this.detectMinedClusters.get()) return;
        if ((pos.getX() & 15) == 0) this.queueChunkAt(world, chunkX - 1, chunkZ);
        if ((pos.getX() & 15) == 15) this.queueChunkAt(world, chunkX + 1, chunkZ);
        if ((pos.getZ() & 15) == 0) this.queueChunkAt(world, chunkX, chunkZ - 1);
        if ((pos.getZ() & 15) == 15) this.queueChunkAt(world, chunkX, chunkZ + 1);
    }

    private void queueMinedNeighbors(ClientLevel world, int chunkX, int chunkZ) {
        if (world == null) return;
        this.queueChunkAt(world, chunkX - 1, chunkZ);
        this.queueChunkAt(world, chunkX + 1, chunkZ);
        this.queueChunkAt(world, chunkX, chunkZ - 1);
        this.queueChunkAt(world, chunkX, chunkZ + 1);
    }

    private void queueChunkAt(ClientLevel world, int chunkX, int chunkZ) {
        if (world == null) return;
        ChunkAccess chunk = world.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
        if (chunk instanceof LevelChunk loaded) this.queueChunk(loaded);
    }

    private void resetPacketBoundary(boolean advanceGeneration) {
        synchronized (this.packetBoundaryLock) {
            if (advanceGeneration) this.packetGeneration.incrementAndGet();
            this.pendingPackets.clear();
            this.packetOverflowed = false;
        }
    }

    private static Sample sample(ClientLevel world, BlockPos.MutableBlockPos pos, BlockState state, Kind kind) {
        int stage = integerProperty(state, "age", -1);
        int terminal = maximumIntegerProperty(state, "age", -1);
        int light = world.getMaxLocalRawBrightness(pos);
        int minLight = switch (kind) {
            case BAMBOO, BERRY_BUSH, CROP, STEM, PROPAGULE, SOIL -> 9;
            default -> 0;
        };
        Fact freeSpace = Fact.UNKNOWN;
        Fact support = Fact.UNKNOWN;
        Fact exposed = Fact.UNKNOWN;
        Fact cycle = Fact.UNKNOWN;
        int columnHeight = 0;
        int columnLimit = 0;
        switch (kind) {
            case KELP -> {
                freeSpace = air(world.getBlockState(pos.above()));
                support = waterOrPlant(world.getBlockState(pos.below()));
                if (terminal < 0) terminal = 25;
            }
            case CAVE_VINE -> {
                String berries = stringProperty(state, "berries");
                stage = "false".equals(berries) ? 0 : "true".equals(berries) ? 1 : -1;
                cycle = stage == 0 ? Fact.YES : stage == 1 ? Fact.NO : Fact.UNKNOWN;
            }
            case HANGING_VINE -> {
                freeSpace = air(world.getBlockState(pos.below()));
                columnHeight = columnLength(world, pos, state.getBlock());
                columnLimit = 25;
                if (terminal < 0) terminal = 25;
            }
            case BAMBOO -> {
                freeSpace = air(world.getBlockState(pos.above()));
                support = notAir(world.getBlockState(pos.below()));
                columnHeight = columnLength(world, pos, state.getBlock());
                columnLimit = 16;
            }
            case SUGAR_CANE -> {
                freeSpace = air(world.getBlockState(pos.above()));
                support = notAir(world.getBlockState(pos.below()));
                columnHeight = columnLength(world, pos, state.getBlock());
                columnLimit = 3;
            }
            case CACTUS -> {
                freeSpace = air(world.getBlockState(pos.above()));
                support = notAir(world.getBlockState(pos.below()));
                columnHeight = columnLength(world, pos, state.getBlock());
                columnLimit = 3;
            }
            case COCOA -> {
                support = horizontalLog(world, pos);
                if (terminal < 0) terminal = 2;
            }
            case BERRY_BUSH -> {
                support = soil(world.getBlockState(pos.below()));
                if (terminal < 0) terminal = 3;
            }
            case NETHER_WART -> {
                support = soulSoil(world.getBlockState(pos.below()));
                if (terminal < 0) terminal = 3;
            }
            case CROP -> {
                support = farmable(world.getBlockState(pos.below()));
            }
            case STEM -> {
                support = farmable(world.getBlockState(pos.below()));
                if (terminal < 0) terminal = 7;
            }
            case AMETHYST_BUD -> exposed = exposedFace(world, pos);
            case VINE -> {
                freeSpace = air(world.getBlockState(pos.above()));
                support = adjacentSolid(world, pos);
            }
            case LEAF -> { }
            case SOIL -> {
                freeSpace = air(world.getBlockState(pos.above()));
                support = adjacentGrass(world, pos);
            }
            case PROPAGULE -> {
                support = soil(world.getBlockState(pos.below()));
            }
            case EYEBLOSSOM -> cycle = eyeblossomCycle(world, state);
            case SNOW -> cycle = light > 10 ? Fact.YES : light < 0 ? Fact.UNKNOWN : Fact.NO;
            case ICE -> cycle = light > 11 ? Fact.YES : light < 0 ? Fact.UNKNOWN : Fact.NO;
        }
        return new Sample(kind, stage, terminal, light, minLight, freeSpace, support,
            columnHeight, columnLimit, exposed, cycle);
    }

    private static Kind kindOf(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) return null;
        String name = id.getPath();
        if (name.equals("kelp") || name.equals("kelp_plant")) return Kind.KELP;
        if (name.equals("cave_vines") || name.equals("cave_vines_plant")) return Kind.CAVE_VINE;
        if (name.equals("weeping_vines") || name.equals("weeping_vines_plant") || name.equals("twisting_vines") || name.equals("twisting_vines_plant")) return Kind.HANGING_VINE;
        if (name.equals("bamboo")) return Kind.BAMBOO;
        if (name.equals("sugar_cane")) return Kind.SUGAR_CANE;
        if (name.equals("cactus")) return Kind.CACTUS;
        if (name.equals("cocoa")) return Kind.COCOA;
        if (name.equals("sweet_berry_bush")) return Kind.BERRY_BUSH;
        if (name.equals("nether_wart")) return Kind.NETHER_WART;
        if (name.endsWith("_stem") && !name.startsWith("attached_")) return Kind.STEM;
        if (name.equals("wheat") || name.equals("carrots") || name.equals("potatoes") || name.equals("beetroots") || name.equals("torchflower_crop")) return Kind.CROP;
        if (name.contains("amethyst") && (name.contains("bud") || name.equals("amethyst_cluster"))) return Kind.AMETHYST_BUD;
        if (name.equals("vine")) return Kind.VINE;
        if (name.endsWith("_leaves")) return Kind.LEAF;
        if (name.equals("grass_block") || name.equals("dirt") || name.equals("coarse_dirt")) return Kind.SOIL;
        if (name.equals("mangrove_propagule")) return Kind.PROPAGULE;
        if (name.contains("eyeblossom")) return Kind.EYEBLOSSOM;
        if (name.equals("snow") || name.equals("snow_block")) return Kind.SNOW;
        if (name.equals("ice") || name.equals("packed_ice") || name.equals("blue_ice")) return Kind.ICE;
        return null;
    }

    private static Fact air(BlockState state) { return state.isAir() ? Fact.YES : Fact.NO; }
    private static Fact notAir(BlockState state) { return state.isAir() ? Fact.NO : Fact.YES; }

    private static Fact waterOrPlant(BlockState state) {
        return state.isAir() ? Fact.NO : Fact.YES;
    }

    private static Fact soil(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.endsWith("dirt") || path.equals("grass_block") || path.equals("mud") || path.equals("moss_block")
            ? Fact.YES : Fact.NO;
    }

    private static Fact farmable(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.equals("farmland") || soil(state) == Fact.YES ? Fact.YES : Fact.NO;
    }

    private static Fact soulSoil(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.equals("soul_sand") || path.equals("soul_soil") ? Fact.YES : Fact.NO;
    }

    private static Fact horizontalLog(ClientLevel world, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            String path = BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos.relative(direction)).getBlock()).getPath();
            if (path.endsWith("_log") || path.endsWith("_wood")) return Fact.YES;
        }
        return Fact.NO;
    }

    private static Fact adjacentSolid(ClientLevel world, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!world.getBlockState(pos.relative(direction)).isAir()) return Fact.YES;
        }
        return Fact.NO;
    }

    private static Fact adjacentGrass(ClientLevel world, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (world.getBlockState(pos.relative(direction)).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)) return Fact.YES;
        }
        return Fact.NO;
    }

    private static Fact exposedFace(ClientLevel world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (world.getBlockState(pos.relative(direction)).isAir()) return Fact.YES;
        }
        return Fact.NO;
    }

    private static Fact eyeblossomCycle(ClientLevel world, BlockState state) {
        if (world.dimension() != net.minecraft.world.level.Level.OVERWORLD || world.dimensionType().hasFixedTime()) return Fact.UNKNOWN;
        String current = stringProperty(state, "open");
        if (!"true".equals(current) && !"false".equals(current)) return Fact.UNKNOWN;
        int time = (int)Math.floorMod(world.getOverworldClockTime(), 24000L);
        boolean shouldOpen = time >= 12600 && time < 23401;
        int since = shouldOpen ? time - 12600 : Math.floorMod(time - 23401, 24000);
        if (since < 4000) return Fact.UNKNOWN;
        return Boolean.parseBoolean(current) == shouldOpen ? Fact.NO : Fact.YES;
    }

    private static int columnLength(ClientLevel world, BlockPos pos, net.minecraft.world.level.block.Block block) {
        int length = 1;
        for (int y = 1; y < 16; y++) {
            if (world.getBlockState(pos.below(y)).getBlock() != block) break;
            length++;
        }
        return length;
    }

    private static int integerProperty(BlockState state, String name, int fallback) {
        for (Property<?> property : state.getProperties()) {
            if (!property.getName().equals(name)) continue;
            try { return Integer.parseInt(String.valueOf(propertyValue(state, property))); }
            catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }

    private static int maximumIntegerProperty(BlockState state, String name, int fallback) {
        for (Property<?> property : state.getProperties()) {
            if (!property.getName().equals(name)) continue;
            int max = Integer.MIN_VALUE;
            for (Comparable<?> value : property.getPossibleValues()) {
                try { max = Math.max(max, Integer.parseInt(value.toString())); }
                catch (NumberFormatException ignored) { return fallback; }
            }
            return max == Integer.MIN_VALUE ? fallback : max;
        }
        return fallback;
    }

    private static String stringProperty(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) return String.valueOf(propertyValue(state, property));
        }
        return "";
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object propertyValue(BlockState state, Property<?> property) {
        return state.getValue((Property)property);
    }

    private void reset() {
        this.pending.clear();
        this.pendingChunks.clear();
        this.summaries.clear();
        this.signalHeat.clear();
        this.minedChunks.clear();
        this.minedSurvey.clear();
        this.surveyTick = 0L;
        this.minedAge = 0;
        this.cursor = null;
        this.snapshot = DonutWorldFlagSnapshot.empty(++this.snapshotGeneration);
    }

    @FunctionalInterface
    private interface KindSelector { boolean enabled(Kind kind); }

    @FunctionalInterface
    private interface EvidenceReader { boolean count(ClientLevel world, BlockPos.MutableBlockPos pos, BlockState state, Kind kind); }

    private static final class ScanCursor {
        private final long key;
        private final LevelChunk chunk;
        private final LevelChunkSection[] sections;
        private final int[] counts = new int[Kind.values().length];
        private final int[] observed = new int[Kind.values().length];
        private final int[] sourceGrown = new int[SourceSignal.values().length];
        private final int[] sourceObserved = new int[SourceSignal.values().length];
        private boolean sourcePacketData;
        private int sourceVetoMask;
        private int beeNestObserved;
        private int beeNestFull;
        private int section;
        private int block;

        private ScanCursor(long key, LevelChunk chunk) {
            this.key = key;
            this.chunk = chunk;
            this.sections = chunk.getSections();
        }

        private int advance(ClientLevel world, int budget, KindSelector enabled, EvidenceReader reader,
                            SourceSignalReader sourceReader, boolean sourceScan) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int visited = 0;
            while (visited < budget && this.section < this.sections.length) {
                LevelChunkSection current = this.sections[this.section];
                if (current == null || current.hasOnlyAir()) {
                    this.section++;
                    this.block = 0;
                    continue;
                }
                int index = this.block++;
                int localX = index & 15;
                int localZ = index >> 4 & 15;
                int localY = index >> 8 & 15;
                int y = this.chunk.getMinY() + this.section * 16 + localY;
                int x = this.chunk.getPos().getMinBlockX() + localX;
                int z = this.chunk.getPos().getMinBlockZ() + localZ;
                pos.set(x, y, z);
                BlockState state = current.getBlockState(localX, localY, localZ);
                if (sourceScan && y >= SOURCE_MIN_Y && y <= SOURCE_MAX_Y && !state.isAir()) this.sourcePacketData = true;
                Kind kind = kindOf(state);
                if (kind != null) this.observed[kind.ordinal()]++;
                if (kind != null && enabled.enabled(kind) && reader.count(world, pos, state, kind)) {
                    this.counts[kind.ordinal()]++;
                }
                if (sourceReader != null) {
                    for (SourceSignal signal : SourceSignal.values()) {
                        if (!matchesSourceSignal(state, signal)) continue;
                        int signalIndex = signal.ordinal();
                        this.sourceObserved[signalIndex]++;
                        int sign = sourceReader.sign(world, pos, state, signal);
                        if (sign > 0) this.sourceGrown[signalIndex]++;
                        else if (sign < 0) this.sourceVetoMask |= 1 << signalIndex;
                    }
                }
                var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                if (blockId != null && (blockId.getPath().equals("bee_nest") || blockId.getPath().equals("beehive"))) {
                    this.beeNestObserved++;
                    if (integerProperty(state, "honey_level", 0) >= 5) this.beeNestFull++;
                }
                visited++;
                if (this.block >= 4096) {
                    this.section++;
                    this.block = 0;
                }
            }
            return visited;
        }

        private boolean complete() { return this.section >= this.sections.length; }
    }

    @FunctionalInterface
    private interface SourceSignalReader {
        int sign(ClientLevel world, BlockPos.MutableBlockPos pos, BlockState state, SourceSignal signal);
    }

    private enum SurveyMethod {
        Classic("Classic"), DonutScan("Donut Scan"), NaturalGrowth("Natural Growth");
        private final String label;
        SurveyMethod(String label) { this.label = label; }
        @Override public String toString() { return this.label; }
    }

    private enum SourceSignal {
        AMETHYST("growth-amethyst", "amethyst"),
        KELP("growth-kelp", "kelp"),
        CAVE_VINES("growth-cave-vines", "cave vines"),
        VINES("growth-vines", "vines"),
        BAMBOO("growth-bamboo", "bamboo"),
        COCOA("growth-cocoa", "cocoa"),
        BEE_NEST("growth-bee-nests", "bee nests");
        private final String settingName;
        private final String label;
        SourceSignal(String settingName, String label) { this.settingName = settingName; this.label = label; }
    }

    record LightReading(int buds, int clusters, int darkVotes, int glowVotes) {}
    private record PendingSignalPacket(Object packet, long generation) {}
    private record ChunkSummary(long key, int[] counts, boolean sourcePacketData) {}
}
