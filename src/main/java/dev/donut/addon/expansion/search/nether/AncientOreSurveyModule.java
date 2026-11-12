package dev.donut.addon.expansion.search.nether;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.expansion.search.nether.AncientOreSectionIndex.Candidate;
import dev.donut.addon.mv.compat.events.EventHandler;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.ChunkDataEvent;
import dev.donut.addon.mv.compat.events.world.StorageBlocksChangedEvent;
import dev.donut.addon.mv.compat.events.world.StorageChunkUnloadedEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.EnumSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.systems.modules.Category;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.Utils;
import dev.donut.addon.mv.compat.utils.player.ChatUtils;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds debris-bearing palette entries that are unused in the client section, with an optional bounded probe mode. */
public final class AncientOreSurveyModule extends Module {
    private static final int MAX_SECTIONS = 512;
    private static final int MAX_SECTIONS_RENDERED = 128;
    private static final int MAX_BLOCKS_PER_TICK = 8192;
    private static final int MAX_PROBE_BLOCK_CHECKS = 12000;
    private static final int MAX_PROBES_PER_TICK = 18;
    private static final int MAX_REVEALED = 256;
    private static final int PROBE_REACH = 5;
    private static final long RESCAN_NANOS = 3_000_000_000L;

    private final Setting<Integer> range = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("range")
            .description("Maximum distance from the player for candidate sections and probes.")
            .defaultValue(512).range(64, 1024).sliderRange(64, 1024).build()
    );
    private final Setting<Integer> fadeDistance = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("fade-distance").description("Distance where candidate colors transition from near to far.")
            .defaultValue(192).range(32, 512).sliderRange(32, 512).build()
    );
    private final Setting<Boolean> tracers = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("tracers").description("Draw lines to candidate sections.").defaultValue(true).build()
    );
    private final Setting<Integer> tracerCount = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("tracer-count").description("Maximum nearest candidate sections with tracers.")
            .defaultValue(3).range(1, 8).sliderRange(1, 8).build()
    );
    private final Setting<Boolean> notifications = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("notifications").description("Report newly found candidate sections.").defaultValue(false).build()
    );
    private final Setting<Boolean> advancedProbe = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("advanced-probe")
            .description("Send bounded start/abort block-action probes within five blocks of a candidate section.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> showProbeProgress = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("show-probe-progress").description("Show projected section progress labels.")
            .defaultValue(true).visible(() -> this.advancedProbe.get()).build()
    );
    private final Setting<ProbeStyle> probeStyle = this.settings.getDefaultGroup().add(
        new EnumSetting.Builder<ProbeStyle>().name("probe-style").description("Render style for recently probed blocks.")
            .defaultValue(ProbeStyle.Dots).visible(() -> this.advancedProbe.get() && this.showProbeProgress.get()).build()
    );
    private final Setting<Integer> probesPerTick = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("probes-per-tick").description("Maximum action pairs sent by the optional probe mode.")
            .defaultValue(4).range(1, MAX_PROBES_PER_TICK).sliderRange(1, MAX_PROBES_PER_TICK)
            .visible(() -> this.advancedProbe.get()).build()
    );
    private final Setting<Integer> missingFrom = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("missing-from-percent").description("Stop adding probes after this fraction of predicted probeable blocks was checked.")
            .defaultValue(60).range(30, 100).sliderRange(30, 100).visible(() -> this.advancedProbe.get()).build()
    );
    private final Setting<SettingColor> nearColor = this.settings.getDefaultGroup().add(
        new ColorSetting.Builder().name("near-color").description("Color of nearby candidates.")
            .defaultValue(new SettingColor(255, 115, 55, 86)).build()
    );
    private final Setting<SettingColor> farColor = this.settings.getDefaultGroup().add(
        new ColorSetting.Builder().name("far-color").description("Color of distant candidates.")
            .defaultValue(new SettingColor(80, 160, 255, 54)).build()
    );

    private final AncientOreSectionIndex index = new AncientOreSectionIndex(MAX_SECTIONS);
    private final ArrayDeque<net.minecraft.world.level.ChunkPos> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Map<Candidate, ProbeScan> probeScans = new HashMap<>();
    private final LinkedHashSet<Long> revealed = new LinkedHashSet<>();
    private ClientLevel world;
    private ChunkCursor cursor;
    private boolean scanning;
    private long nextScanAt;
    private double probeBudget;
    private int tick;
    private long notifyAt;

    public AncientOreSurveyModule() {
        super(new Category(AddonCategories.BASEFINDING), "Donut Ancient Ore Survey",
            "Finds Nether sections whose block palette mentions ancient debris but whose loaded blocks contain none.");
        this.probesPerTick.onChanged(ignored -> this.probeBudget = 0.0);
    }

    @Override public void onActivate() { this.clear(); this.world = this.mc.level; this.startScan(); }
    @Override public void onDeactivate() { this.clear(); this.world = null; }

    @EventHandler private void onJoined(GameJoinedEvent event) { this.clear(); this.world = this.mc.level; this.startScan(); }
    @EventHandler private void onLeft(GameLeftEvent event) { this.clear(); this.world = null; }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (this.isActive() && this.isNether(this.mc.level) && event.chunk() != null) this.enqueue(event.chunk().getPos());
    }

    @EventHandler
    private void onChunkUnload(StorageChunkUnloadedEvent event) {
        if (event.pos() == null) return;
        this.index.removeChunk(event.pos().x(), event.pos().z());
        this.probeScans.keySet().removeIf(candidate -> candidate.chunkX() == event.pos().x() && candidate.chunkZ() == event.pos().z());
        this.queue.removeIf(pos -> pos.equals(event.pos()));
        this.queued.remove(event.pos().pack());
    }

    @EventHandler
    private void onBlockChanges(StorageBlocksChangedEvent event) {
        if (!this.isNether(this.mc.level)) return;
        for (Map.Entry<BlockPos, BlockState> change : event.changes().entrySet()) {
            BlockPos pos = change.getKey();
            if (change.getValue().is(Blocks.ANCIENT_DEBRIS) && this.index.reveal(pos.getX(), pos.getY(), pos.getZ())) {
                long packed = pos.asLong();
                this.revealed.add(packed);
                while (this.revealed.size() > MAX_REVEALED) this.revealed.remove(this.revealed.iterator().next());
                this.probeScans.remove(new Candidate(pos.getX() >> 4, pos.getZ() >> 4, pos.getY() >> 4));
                if (this.advancedProbe.get()) ChatUtils.info("Ancient debris revealed at %d %d %d", pos.getX(), pos.getY(), pos.getZ());
            }
            this.enqueue(net.minecraft.world.level.ChunkPos.containing(pos));
        }
    }

    @EventHandler
    private void onTick(Post event) {
        ClientLevel current = this.mc.level;
        if (current != this.world) {
            this.clear();
            this.world = current;
            if (this.isNether(current)) this.startScan();
        }
        if (!this.isNether(current) || this.mc.player == null) {
            if (current != null && !this.isNether(current)) this.clearCandidates();
            return;
        }
        this.tick++;
        if (!this.scanning && System.nanoTime() >= this.nextScanAt) this.startScan();
        int remaining = MAX_BLOCKS_PER_TICK;
        while (remaining > 0) {
            if (this.cursor == null) {
                net.minecraft.world.level.ChunkPos pos = this.queue.pollFirst();
                if (pos == null) { this.finishScan(); break; }
                this.queued.remove(pos.pack());
                ChunkAccess chunk = current.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk loaded)) continue;
                this.cursor = new ChunkCursor(loaded);
            }
            int consumed = this.cursor.advance(remaining);
            remaining -= Math.max(1, consumed);
            if (this.cursor.complete()) {
                Candidate[] additions = this.cursor.found.toArray(Candidate[]::new);
                Set<Integer> heights = new HashSet<>();
                for (Candidate candidate : additions) heights.add(candidate.sectionY());
                this.index.replaceChunk(this.cursor.chunk.getPos().x(), this.cursor.chunk.getPos().z(), heights);
                this.cursor = null;
            }
        }
        if (this.cursor == null && this.queue.isEmpty() && this.scanning) this.finishScan();
        if (this.advancedProbe.get()) this.probeNearby(current);
        this.pruneProbeScans();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!this.isNether(this.mc.level) || this.mc.player == null) return;
        double maxDistanceSq = (double) this.range.get() * this.range.get();
        SettingColor near = this.nearColor.get();
        SettingColor far = this.farColor.get();
        List<Candidate> candidates = new ArrayList<>(this.index.snapshot());
        candidates.sort(Comparator.comparingDouble(candidate -> this.distanceToSectionSq(candidate)));
        if (candidates.size() > MAX_SECTIONS_RENDERED) candidates = candidates.subList(0, MAX_SECTIONS_RENDERED);
        int tracersDrawn = 0;
        for (Candidate candidate : candidates) {
            double distanceSq = this.distanceToSectionSq(candidate);
            if (distanceSq > maxDistanceSq) continue;
            float blend = (float) Math.min(1.0, Math.sqrt(distanceSq) / this.fadeDistance.get());
            Color fill = new Color(lerp(near.r, far.r, blend), lerp(near.g, far.g, blend),
                lerp(near.b, far.b, blend), lerp(near.a, far.a, blend));
            Color line = new Color(fill.r, fill.g, fill.b, Math.max(fill.a, 160));
            int minX = candidate.chunkX() << 4;
            int minY = candidate.sectionY() << 4;
            int minZ = candidate.chunkZ() << 4;
            event.renderer.box(minX, minY, minZ, minX + 16, minY + 16, minZ + 16, fill, line, ShapeMode.Both, 0);
            if (this.tracers.get() && tracersDrawn++ < this.tracerCount.get()) {
                event.renderer.line(this.mc.player.getX(), this.mc.player.getEyeY(), this.mc.player.getZ(), minX + 8, minY + 8, minZ + 8, line);
            }
            if (this.showProbeProgress.get() && this.advancedProbe.get()) this.renderProbeMarks(event, candidate);
        }
        for (long packed : this.revealed) {
            BlockPos pos = BlockPos.of(packed);
            double distanceSq = this.mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            if (distanceSq <= maxDistanceSq) event.renderer.box(pos, new Color(80, 255, 120, 90), new Color(80, 255, 120, 230), ShapeMode.Both, 0);
        }
    }

    private void startScan() {
        if (!this.isActive() || !this.isNether(this.mc.level) || this.mc.player == null) return;
        this.queue.clear();
        this.queued.clear();
        this.cursor = null;
        this.scanning = true;
        int maxChunkDistance = (this.range.get() + 15) / 16;
        int centerX = this.mc.player.getBlockX() >> 4;
        int centerZ = this.mc.player.getBlockZ() >> 4;
        for (ChunkAccess chunk : Utils.chunks()) {
            int dx = chunk.getPos().x() - centerX;
            int dz = chunk.getPos().z() - centerZ;
            if (dx * dx + dz * dz <= maxChunkDistance * maxChunkDistance) this.enqueue(chunk.getPos());
        }
    }

    private void enqueue(net.minecraft.world.level.ChunkPos pos) {
        if (this.queue.size() >= 2048 || !this.queued.add(pos.pack())) return;
        this.queue.addLast(pos);
    }

    private void finishScan() {
        if (!this.scanning) return;
        this.scanning = false;
        this.nextScanAt = System.nanoTime() + RESCAN_NANOS;
        if (this.notifications.get() && !this.index.snapshot().isEmpty() && System.nanoTime() >= this.notifyAt) {
            List<Candidate> candidates = this.index.snapshot();
            Candidate nearest = candidates.stream().min(Comparator.comparingDouble(this::distanceToSectionSq)).orElse(null);
            if (nearest != null) {
                ChatUtils.info("%d ancient-debris sections found; nearest around %d %d %d", candidates.size(),
                    (nearest.chunkX() << 4) + 8, (nearest.sectionY() << 4) + 8, (nearest.chunkZ() << 4) + 8);
                this.notifyAt = System.nanoTime() + 2_000_000_000L;
            }
        }
    }

    private void probeNearby(ClientLevel current) {
        if (this.mc.player == null || this.mc.gameMode == null || this.mc.getConnection() == null
            || this.mc.gui.screen() != null || this.mc.player.isUsingItem() || this.mc.gameMode.isDestroying()) return;
        this.probeBudget = Math.min(120.0, this.probeBudget + this.probesPerTick.get());
        if (this.probeBudget < 1.0) return;

        BlockPos eyeBlock = this.mc.player.blockPosition();
        int cx = eyeBlock.getX();
        int cy = eyeBlock.getY();
        int cz = eyeBlock.getZ();
        List<Probe> probes = new ArrayList<>();
        int checks = 0;
        for (Candidate candidate : this.index.snapshot()) {
            if (this.distanceToSectionSq(candidate) > PROBE_REACH * PROBE_REACH) continue;
            ProbeScan scan = this.probeScans.computeIfAbsent(candidate, ignored -> new ProbeScan());
            if (scan.censusComplete && this.tick - scan.censusAt >= 10) scan.restartCensus();
            while (!scan.censusComplete && checks < MAX_PROBE_BLOCK_CHECKS) {
                int index = scan.censusCursor++;
                int x = (candidate.chunkX() << 4) + (index & 15);
                int y = (candidate.sectionY() << 4) + ((index >> 8) & 15);
                int z = (candidate.chunkZ() << 4) + ((index >> 4) & 15);
                BlockPos censusPos = new BlockPos(x, y, z);
                if (this.worthProbing(current, censusPos, current.getBlockState(censusPos))) {
                    scan.probeable.set(index);
                    scan.totalProbeable++;
                }
                checks++;
                if (scan.censusCursor >= 4096) {
                    scan.censusComplete = true;
                    scan.censusAt = this.tick;
                }
            }
            if (!scan.censusComplete || scan.totalProbeable == 0
                || scan.sent * 100 >= scan.totalProbeable * this.missingFrom.get()) continue;
            int baseX = candidate.chunkX() << 4;
            int baseY = candidate.sectionY() << 4;
            int baseZ = candidate.chunkZ() << 4;
            int minX = Math.max(baseX, cx - PROBE_REACH);
            int maxX = Math.min(baseX + 15, cx + PROBE_REACH);
            int minY = Math.max(baseY, cy - PROBE_REACH);
            int maxY = Math.min(baseY + 15, cy + PROBE_REACH);
            int minZ = Math.max(baseZ, cz - PROBE_REACH);
            int maxZ = Math.min(baseZ + 15, cz + PROBE_REACH);
            for (int x = minX; x <= maxX && checks < MAX_PROBE_BLOCK_CHECKS; x++) {
                for (int y = minY; y <= maxY && checks < MAX_PROBE_BLOCK_CHECKS; y++) {
                    for (int z = minZ; z <= maxZ && checks < MAX_PROBE_BLOCK_CHECKS; z++) {
                        int index = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
                        if (scan.probed.get(index) || !scan.probeable.get(index)) continue;
                        if (checks++ >= MAX_PROBE_BLOCK_CHECKS) break;
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockState state = current.getBlockState(pos);
                        double distanceSq = this.mc.player.distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
                        if (distanceSq > PROBE_REACH * PROBE_REACH) continue;
                        if (!this.worthProbing(current, pos, state)) { scan.probeable.clear(index); scan.totalProbeable--; continue; }
                        probes.add(new Probe(candidate, scan, index, pos, distanceSq));
                    }
                }
            }
        }
        if (probes.isEmpty()) return;
        probes.sort(Comparator.comparingDouble(Probe::distanceSq));

        Inventory inventory = this.mc.player.getInventory();
        int selected = inventory.getSelectedSlot();
        int probeSlot = this.findProbeSlot(inventory, selected);
        if (probeSlot < 0) return;
        if (probeSlot != selected) this.mc.getConnection().send(new ServerboundSetCarriedItemPacket(probeSlot));
        BlockPos last = null;
        int sent = 0;
        for (Probe probe : probes) {
            if (this.probeBudget < 1 || sent >= this.probesPerTick.get()) break;
            AncientOreProbePackets.send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, probe.pos());
            probe.scan().probed.set(probe.index());
            probe.scan().stamps[probe.index()] = this.tick;
            probe.scan().sent++;
            last = probe.pos();
            sent++;
            this.probeBudget -= 1.0;
        }
        if (last != null) AncientOreProbePackets.send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, last);
        if (probeSlot != selected) this.mc.getConnection().send(new ServerboundSetCarriedItemPacket(selected));
    }

    private boolean worthProbing(ClientLevel current, BlockPos pos, BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty()
            && !state.is(Blocks.ANCIENT_DEBRIS)
            && state.isCollisionShapeFullBlock(current, pos)
            && state.getDestroySpeed(current, pos) > 0.0F;
    }

    private int findProbeSlot(Inventory inventory, int selected) {
        if (!inventory.getItem(selected).has(DataComponents.TOOL)) return selected;
        for (int slot = 0; slot < 9; slot++) if (inventory.getItem(slot).isEmpty()) return slot;
        for (int slot = 0; slot < 9; slot++) if (!inventory.getItem(slot).has(DataComponents.TOOL)) return slot;
        return -1;
    }

    private void pruneProbeScans() {
        Set<Candidate> live = new HashSet<>(this.index.snapshot());
        this.probeScans.keySet().retainAll(live);
    }

    private void renderProbeMarks(Render3DEvent event, Candidate candidate) {
        ProbeScan scan = this.probeScans.get(candidate);
        if (scan == null || scan.probed.isEmpty()) return;
        int baseX = candidate.chunkX() << 4;
        int baseY = candidate.sectionY() << 4;
        int baseZ = candidate.chunkZ() << 4;
        SettingColor selected = this.nearColor.get();
        int rendered = 0;
        for (int index = scan.probed.nextSetBit(0); index >= 0 && rendered < 48; index = scan.probed.nextSetBit(index + 1)) {
            int age = this.tick - scan.stamps[index];
            if (age < 0 || age > 100) continue;
            int x = baseX + (index & 15), y = baseY + ((index >> 8) & 15), z = baseZ + ((index >> 4) & 15);
            int alpha = Math.max(30, 220 - age * 2);
            Color mark = new Color(selected.r, selected.g, selected.b, alpha);
            if (this.probeStyle.get() == ProbeStyle.Dots) {
                event.renderer.box(x + 0.38, y + 0.38, z + 0.38, x + 0.62, y + 0.62, z + 0.62, mark, mark, ShapeMode.Lines, 0);
            } else if (this.probeStyle.get() == ProbeStyle.Pulses) {
                double half = 0.15 + (Math.sin(age * 0.22) + 1.0) * 0.2;
                event.renderer.box(x + 0.5 - half, y + 0.5 - half, z + 0.5 - half,
                    x + 0.5 + half, y + 0.5 + half, z + 0.5 + half, mark, mark, ShapeMode.Lines, 0);
            } else {
                event.renderer.box(x, y, z, x + 1, y + 1, z + 1, mark, mark, ShapeMode.Lines, 0);
            }
            rendered++;
        }
    }

    private boolean isNether(ClientLevel level) {
        if (level == null) return false;
        ResourceKey<Level> dimension = level.dimension();
        return dimension == Level.NETHER || "worlds:smp_nether".equals(dimension.identifier().toString())
            || level.dimensionTypeRegistration().is(BuiltinDimensionTypes.NETHER);
    }

    private double distanceToSectionSq(Candidate candidate) {
        if (this.mc.player == null) return Double.MAX_VALUE;
        double minX = candidate.chunkX() * 16.0;
        double minY = candidate.sectionY() * 16.0;
        double minZ = candidate.chunkZ() * 16.0;
        double px = this.mc.player.getX();
        double py = this.mc.player.getY();
        double pz = this.mc.player.getZ();
        double dx = Math.max(0.0, Math.max(minX - px, px - minX - 16.0));
        double dy = Math.max(0.0, Math.max(minY - py, py - minY - 16.0));
        double dz = Math.max(0.0, Math.max(minZ - pz, pz - minZ - 16.0));
        return dx * dx + dy * dy + dz * dz;
    }

    private void clearCandidates() {
        this.index.clear();
        this.probeScans.clear();
        this.revealed.clear();
    }

    private void clear() {
        this.index.clear();
        this.queue.clear();
        this.queued.clear();
        this.probeScans.clear();
        this.revealed.clear();
        this.cursor = null;
        this.scanning = false;
        this.nextScanAt = 0L;
        this.probeBudget = 0.0;
        this.tick = 0;
    }

    public boolean shouldShowProbeProgress() {
        return this.isActive() && this.advancedProbe.get() && this.showProbeProgress.get();
    }

    public List<OverlayLabel> overlayLabels() {
        if (!this.shouldShowProbeProgress() || this.mc.player == null) return List.of();
        double maxDistanceSq = (double) this.range.get() * this.range.get();
        List<OverlayLabel> labels = new ArrayList<>();
        for (Candidate candidate : this.index.snapshot()) {
            if (this.distanceToSectionSq(candidate) > maxDistanceSq) continue;
            ProbeScan scan = this.probeScans.get(candidate);
            int sent = scan == null ? 0 : scan.sent;
            int total = scan == null ? 0 : scan.totalProbeable;
            boolean thresholdReached = total > 0 && sent * 100 >= total * this.missingFrom.get();
            labels.add(new OverlayLabel(candidate.chunkX(), candidate.chunkZ(), candidate.sectionY(), sent, total, thresholdReached));
        }
        labels.sort(Comparator.comparingInt(OverlayLabel::sent).reversed());
        return labels.size() > MAX_SECTIONS_RENDERED ? List.copyOf(labels.subList(0, MAX_SECTIONS_RENDERED)) : List.copyOf(labels);
    }

    private static int lerp(int start, int end, float amount) { return Math.max(0, Math.min(255, Math.round(start + (end - start) * amount))); }

    public record OverlayLabel(int chunkX, int chunkZ, int sectionY, int sent, int total, boolean thresholdReached) {}

    private record Probe(Candidate candidate, ProbeScan scan, int index, BlockPos pos, double distanceSq) {}

    private static final class ProbeScan {
        private final BitSet probed = new BitSet(4096);
        private final BitSet probeable = new BitSet(4096);
        private final int[] stamps = new int[4096];
        private int censusCursor;
        private int totalProbeable;
        private int censusAt;
        private boolean censusComplete;
        private int sent;

        private void restartCensus() {
            this.probeable.clear();
            this.censusCursor = 0;
            this.totalProbeable = 0;
            this.censusComplete = false;
        }
    }

    private enum ProbeStyle { Dots, Cubes, Pulses }

    private final class ChunkCursor {
        private final LevelChunk chunk;
        private final LevelChunkSection[] sections;
        private final List<Candidate> found = new ArrayList<>();
        private int sectionIndex;
        private int blockIndex;
        private int targetCount;
        private boolean scanningTargetSection;

        private ChunkCursor(LevelChunk chunk) { this.chunk = chunk; this.sections = chunk.getSections(); }

        private int advance(int budget) {
            int consumed = 0;
            while (this.sectionIndex < this.sections.length && consumed < budget) {
                LevelChunkSection section = this.sections[this.sectionIndex];
                if (section == null || section.hasOnlyAir() || !section.maybeHas(state -> state.is(Blocks.ANCIENT_DEBRIS))) {
                    this.sectionIndex++;
                    this.blockIndex = 0;
                    this.scanningTargetSection = false;
                    continue;
                }
                this.scanningTargetSection = true;
                int index = this.blockIndex++;
                if (section.getBlockState(index & 15, (index >> 8) & 15, (index >> 4) & 15).is(Blocks.ANCIENT_DEBRIS)) this.targetCount++;
                consumed++;
                if (this.blockIndex < 4096) continue;
                if (HiddenOreClassifier.isPaletteOnlyTarget(true, this.targetCount)) {
                    int sectionY = (this.chunk.getMinY() >> 4) + this.sectionIndex;
                    this.found.add(new Candidate(this.chunk.getPos().x(), this.chunk.getPos().z(), sectionY));
                }
                this.sectionIndex++;
                this.blockIndex = 0;
                this.targetCount = 0;
                this.scanningTargetSection = false;
            }
            return consumed;
        }

        private boolean complete() { return this.sectionIndex >= this.sections.length; }
    }
}
