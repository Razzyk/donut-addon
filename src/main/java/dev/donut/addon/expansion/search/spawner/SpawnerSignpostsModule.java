package dev.donut.addon.expansion.search.spawner;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.expansion.render.BlockClusterMesh;
import dev.donut.addon.expansion.search.DonutSearchMarkerStyle;
import dev.donut.addon.expansion.search.SearchClusterMeshes;
import dev.donut.addon.mv.compat.events.EventHandler;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.ChunkDataEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.settings.EnumSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.systems.modules.Category;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import dev.donut.addon.mv.compat.utils.player.ChatUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scans loaded chunk palettes incrementally and supplies projected spawner labels. */
public final class SpawnerSignpostsModule extends Module {
    public enum EspStyle { OUTLINE, BOXES }
    private static final int BLOCKS_PER_TICK = 8192;
    private static final int MAX_CHUNKS_QUEUED = 2048;
    private static final int MAX_SPAWNERS = 4096;
    private static final int MAX_LABELS = 48;
    private static final int RING_SEGMENTS = 48;
    private static final long RESCAN_NANOS = 2_000_000_000L;

    private final Setting<Integer> scanRadius = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("scan-radius")
            .description("Loaded chunk radius searched for spawners.")
            .defaultValue(8).range(1, 16).sliderRange(1, 16).build()
    );
    private final Setting<Integer> labelRange = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("label-range")
            .description("Maximum distance for projected spawner labels.")
            .defaultValue(64).range(8, 256).sliderRange(8, 256).build()
    );
    private final Setting<Boolean> renderEsp = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("render-esp")
            .description("Draw a configurable block ESP around discovered spawners.")
            .defaultValue(true).build()
    );
    private final Setting<Integer> espRange = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("range")
            .description("Maximum distance for spawner block ESP.")
            .defaultValue(256).range(16, 2048).sliderRange(16, 512).visible(this.renderEsp::get).build()
    );
    private final Setting<EspStyle> espStyle = this.settings.getDefaultGroup().add(
        new EnumSetting.Builder<EspStyle>().name("shape")
            .description("Draw one outlined shell or scaled boxes for each spawner.")
            .defaultValue(EspStyle.OUTLINE).visible(this.renderEsp::get).build()
    );
    private final Setting<Double> boxSize = this.settings.getDefaultGroup().add(
        new DoubleSetting.Builder().name("box-size")
            .description("Size of each spawner box when Shape is Boxes.")
            .defaultValue(1.0).range(0.1, 2.0).sliderRange(0.1, 2.0).decimalPlaces(1)
            .visible(() -> this.renderEsp.get() && this.espStyle.get() == EspStyle.BOXES).build()
    );
    private final Setting<Double> fillAlpha = this.settings.getDefaultGroup().add(
        new DoubleSetting.Builder().name("fill-alpha")
            .description("Opacity of the filled spawner ESP surface.")
            .defaultValue(0.1).range(0.0, 1.0).sliderRange(0.0, 1.0).decimalPlaces(2)
            .visible(this.renderEsp::get).build()
    );
    private final Setting<Double> lineWidth = this.settings.getDefaultGroup().add(
        new DoubleSetting.Builder().name("line-width")
            .description("Outline width for the spawner ESP.")
            .defaultValue(1.5).range(0.5, 5.0).sliderRange(0.5, 5.0).decimalPlaces(1)
            .visible(this.renderEsp::get).build()
    );
    private final Setting<Boolean> glow = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("glow")
            .description("Draw a soft, wider halo around the spawner outline.")
            .defaultValue(true).visible(this.renderEsp::get).build()
    );
    private final Setting<Boolean> espDistanceFade = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("distance-fade")
            .description("Fade spawner ESP with distance and taper its outline width.")
            .defaultValue(true).visible(this.renderEsp::get).build()
    );
    private final Setting<Boolean> reveal = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("reveal")
            .description("Animate the spawner ESP as each scanned position appears.")
            .defaultValue(true).visible(this.renderEsp::get).build()
    );
    private final Setting<SettingColor> espColor = this.settings.getDefaultGroup().add(
        new ColorSetting.Builder().name("color")
            .description("Color of spawner block ESP.")
            .defaultValue(new SettingColor(255, 85, 85, 220)).visible(this.renderEsp::get).build()
    );
    private final Setting<Boolean> showActivationRange = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("activation-range")
            .description("Include the spawner's activation radius and whether you are inside it.")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> showActivationRing = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("activation-ring")
            .description("Draw a horizontal ring around each visible spawner's activation radius.")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> notifyNew = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("notify-new")
            .description("Announce each newly discovered spawner once per loaded world.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> sound = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("sound")
            .description("Play a short alert when a new spawner is found.")
            .defaultValue(false).build()
    );

    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Map<Long, Set<Long>> byChunk = new LinkedHashMap<>();
    private final Set<Long> seenThisSweep = new HashSet<>();
    private final Set<Long> announced = new HashSet<>();
    private final Map<Long, Long> firstSeenNanos = new HashMap<>();
    private final SearchClusterMeshes clusterMeshes = new SearchClusterMeshes();
    private ClientLevel world;
    private ChunkCursor cursor;
    private long nextSweepAt;
    private boolean sweepActive;

    public SpawnerSignpostsModule() {
        super(new Category(AddonCategories.BASEFINDING), "Donut Spawner Signposts",
            "Shows mob, distance, and activation details over spawners in loaded chunks.");
        this.scanRadius.onChanged(ignored -> this.startSweep());
    }

    @Override
    public void onActivate() {
        this.clear();
        this.world = this.mc.level;
        this.startSweep();
    }

    @Override
    public void onDeactivate() {
        this.clear();
        this.world = null;
    }

    @EventHandler
    private void onJoined(GameJoinedEvent event) {
        this.clear();
        this.world = this.mc.level;
        this.startSweep();
    }

    @EventHandler
    private void onLeft(GameLeftEvent event) {
        this.clear();
        this.world = null;
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (!this.isActive() || event.chunk() == null) return;
        this.enqueue(event.chunk().getPos());
    }

    @EventHandler
    private void onTick(Post event) {
        ClientLevel current = this.mc.level;
        if (current != this.world) {
            this.clear();
            this.world = current;
            if (current != null) this.startSweep();
        }
        if (current == null || this.mc.player == null) return;
        if (!this.sweepActive && System.nanoTime() >= this.nextSweepAt) this.startSweep();

        int remaining = BLOCKS_PER_TICK;
        while (remaining > 0) {
            if (this.cursor == null) {
                ChunkPos next = this.queue.pollFirst();
                if (next == null) {
                    this.finishSweep();
                    break;
                }
                this.queued.remove(next.pack());
                ChunkAccess chunk = current.getChunkSource().getChunk(next.x(), next.z(), ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk loaded)) continue;
                this.cursor = new ChunkCursor(loaded);
            }
            int spent = this.cursor.advance(remaining, this.seenThisSweep);
            remaining -= Math.max(1, spent);
            if (this.cursor.complete()) this.cursor = null;
        }
        if (this.cursor == null && this.queue.isEmpty() && this.sweepActive) this.finishSweep();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (this.renderEsp.get()) this.renderSpawnerEsp(event);
        if (!this.showActivationRange.get() || !this.showActivationRing.get()) return;
        for (Label label : this.visibleLabels()) {
            int radius = label.activationRange();
            if (radius <= 0) continue;
            Color color = label.insideActivationRange()
                ? new Color(255, 198, 87, 190)
                : new Color(255, 255, 255, 110);
            double centerX = label.pos().getX() + 0.5;
            double centerY = label.pos().getY() + 0.08;
            double centerZ = label.pos().getZ() + 0.5;
            double previousX = centerX + radius;
            double previousZ = centerZ;
            for (int i = 1; i <= RING_SEGMENTS; i++) {
                double angle = Math.PI * 2.0 * i / RING_SEGMENTS;
                double x = centerX + Math.cos(angle) * radius;
                double z = centerZ + Math.sin(angle) * radius;
                event.renderer.line(previousX, centerY, previousZ, x, centerY, z, color);
                previousX = x;
                previousZ = z;
            }
        }
    }

    public boolean isActivationRangeEnabled() { return this.showActivationRange.get(); }

    /** Clears scan, notification, and reveal state at a search-session boundary without toggling the module. */
    public void endSearchSession() {
        this.clear();
        this.world = null;
    }

    /** Snapshot of current loaded spawners, capped and sorted for the HUD renderer. */
    public List<Label> visibleLabels() {
        ClientLevel current = this.mc.level;
        if (current == null || this.mc.player == null || this.byChunk.isEmpty()) return List.of();
        double maxDistance = this.labelRange.get();
        double maxDistanceSq = maxDistance * maxDistance;
        List<Label> labels = new ArrayList<>();
        Iterator<Map.Entry<Long, Set<Long>>> chunks = this.byChunk.entrySet().iterator();
        while (chunks.hasNext()) {
            Map.Entry<Long, Set<Long>> entry = chunks.next();
            int cx = ChunkPos.getX(entry.getKey());
            int cz = ChunkPos.getZ(entry.getKey());
            if (current.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) == null) {
                for (long packed : entry.getValue()) this.firstSeenNanos.remove(packed);
                chunks.remove();
                continue;
            }
            for (long packed : entry.getValue()) {
                BlockPos pos = BlockPos.of(packed);
                double distanceSq = this.mc.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distanceSq > maxDistanceSq || !current.getBlockState(pos).is(Blocks.SPAWNER)) continue;
                BlockEntity blockEntity = current.getBlockEntity(pos);
                if (!(blockEntity instanceof SpawnerBlockEntity spawner)) continue;
                Entity display = spawner.getSpawner().getOrCreateDisplayEntity(current, pos);
                String mob = display == null ? "Unknown" : display.getType().getDescription().getString();
                int activationRange = spawner.getUpdateTag(current.registryAccess()).getIntOr("RequiredPlayerRange", 16);
                boolean inActivationRange = activationRange > 0 && distanceSq < (double) activationRange * activationRange;
                labels.add(new Label(pos, mob, Math.sqrt(distanceSq), activationRange, inActivationRange));
            }
        }
        labels.sort(Comparator.comparingDouble(Label::distance));
        if (labels.size() > MAX_LABELS) return List.copyOf(labels.subList(0, MAX_LABELS));
        return List.copyOf(labels);
    }

    private void startSweep() {
        ClientLevel current = this.mc.level;
        if (!this.isActive() || current == null || this.mc.player == null) return;
        this.world = current;
        this.queue.clear();
        this.queued.clear();
        this.cursor = null;
        this.seenThisSweep.clear();
        this.sweepActive = true;
        int centerX = this.mc.player.getBlockX() >> 4;
        int centerZ = this.mc.player.getBlockZ() >> 4;
        int radius = this.scanRadius.get();
        List<ChunkPos> positions = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (current.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) positions.add(pos);
            }
        }
        positions.sort(Comparator.comparingInt(pos -> (pos.x() - centerX) * (pos.x() - centerX) + (pos.z() - centerZ) * (pos.z() - centerZ)));
        for (ChunkPos pos : positions) this.enqueue(pos);
    }

    private void enqueue(ChunkPos pos) {
        if (this.queue.size() >= MAX_CHUNKS_QUEUED || !this.queued.add(pos.pack())) return;
        this.queue.addLast(pos);
    }

    private void finishSweep() {
        if (!this.sweepActive) return;
        Iterator<Map.Entry<Long, Set<Long>>> entries = this.byChunk.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Long, Set<Long>> entry = entries.next();
            ChunkPos pos = new ChunkPos(ChunkPos.getX(entry.getKey()), ChunkPos.getZ(entry.getKey()));
            if (Math.abs(pos.x() - (this.mc.player.getBlockX() >> 4)) > this.scanRadius.get()
                || Math.abs(pos.z() - (this.mc.player.getBlockZ() >> 4)) > this.scanRadius.get()) {
                for (long packed : entry.getValue()) this.firstSeenNanos.remove(packed);
                entries.remove();
            }
        }
        this.sweepActive = false;
        this.nextSweepAt = System.nanoTime() + RESCAN_NANOS;
    }

    private void replaceChunk(long chunkKey, Set<Long> positions) {
        Set<Long> old = this.byChunk.put(chunkKey, positions);
        if (old != null) for (long position : old) if (!positions.contains(position)) this.firstSeenNanos.remove(position);
        long now = System.nanoTime();
        for (long position : positions) this.firstSeenNanos.putIfAbsent(position, now);
        if (this.byChunk.size() > MAX_CHUNKS_QUEUED) {
            Iterator<Map.Entry<Long, Set<Long>>> iterator = this.byChunk.entrySet().iterator();
            if (iterator.hasNext()) {
                Map.Entry<Long, Set<Long>> removed = iterator.next();
                iterator.remove();
                for (long position : removed.getValue()) this.firstSeenNanos.remove(position);
            }
        }
        if (!this.notifyNew.get() && !this.sound.get()) return;
        for (long pos : positions) {
            if (old != null && old.contains(pos) || !this.announced.add(pos)) continue;
            BlockPos blockPos = BlockPos.of(pos);
            String message = "Spawner found at " + blockPos.getX() + " " + blockPos.getY() + " " + blockPos.getZ();
            if (this.mc.player != null) message += " (" + Math.round(Math.sqrt(this.mc.player.distanceToSqr(blockPos.getX() + 0.5, blockPos.getY() + 0.5, blockPos.getZ() + 0.5))) + "m)";
            if (this.notifyNew.get()) ChatUtils.sendMsg(Component.literal(message));
            if (this.sound.get()) this.mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_CLUSTER_STEP, 0.8F, 1.0F));
        }
    }

    private void clear() {
        this.queue.clear();
        this.queued.clear();
        this.byChunk.clear();
        this.seenThisSweep.clear();
        this.announced.clear();
        this.firstSeenNanos.clear();
        this.clusterMeshes.clear();
        this.cursor = null;
        this.sweepActive = false;
        this.nextSweepAt = 0L;
    }

    private void renderSpawnerEsp(Render3DEvent event) {
        ClientLevel current = this.mc.level;
        if (current == null || this.mc.player == null) return;
        double range = this.espRange.get();
        double rangeSq = range * range;
        long now = System.nanoTime();
        SettingColor selected = this.espColor.get();
        boolean boxes = this.espStyle.get() == EspStyle.BOXES;
        ArrayList<BlockPos> visiblePositions = new ArrayList<>();
        scan:
        for (Set<Long> positions : this.byChunk.values()) {
            for (long packed : positions) {
                if (visiblePositions.size() >= MAX_SPAWNERS) break scan;
                BlockPos pos = BlockPos.of(packed);
                if (!current.hasChunkAt(pos) || !current.getBlockState(pos).is(Blocks.SPAWNER)) continue;
                visiblePositions.add(pos.immutable());
            }
        }
        for (List<BlockPos> component : BlockClusterMesh.connectedComponents(visiblePositions)) {
            double centerX = 0.0, centerY = 0.0, centerZ = 0.0;
            long firstSeen = Long.MAX_VALUE;
            for (BlockPos pos : component) {
                centerX += pos.getX() + 0.5;
                centerY += pos.getY() + 0.5;
                centerZ += pos.getZ() + 0.5;
                firstSeen = Math.min(firstSeen, this.firstSeenNanos.getOrDefault(pos.asLong(), now));
            }
            double n = component.size();
            centerX /= n; centerY /= n; centerZ /= n;
            double dx = centerX - this.mc.player.getX();
            double dy = centerY - this.mc.player.getY();
            double dz = centerZ - this.mc.player.getZ();
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > rangeSq) continue;
            double distance = Math.sqrt(distanceSq);
            long age = Math.max(0L, now - firstSeen);
                SpawnerVisualStyle.Frame frame = SpawnerVisualStyle.frame(distance, age,
                    this.lineWidth.get().floatValue(), this.espDistanceFade.get(), this.reveal.get());
                if (!frame.visible()) continue;
                int lineAlpha = clampByte(Math.round(selected.a * frame.opacity()));
                int fillAlpha = clampByte(Math.round(255.0f * this.fillAlpha.get().floatValue() * frame.opacity()));
                Color line = new Color(selected.r, selected.g, selected.b, lineAlpha);
                Color fill = new Color(selected.r, selected.g, selected.b, fillAlpha);
            BlockClusterMesh.Mesh mesh = boxes ? null : this.clusterMeshes.forPositions(component);
            DonutSearchMarkerStyle.drawCluster(event.renderer, component, mesh, boxes, this.boxSize.get(),
                fill, line, frame.lineWidth(), this.glow.get(), frame.scale());
        }
    }

    private static int clampByte(int value) { return Math.max(0, Math.min(255, value)); }

    public record Label(BlockPos pos, String mob, double distance, int activationRange, boolean insideActivationRange) {}

    private final class ChunkCursor {
        private final LevelChunk chunk;
        private final LevelChunkSection[] sections;
        private final Set<Long> found = new HashSet<>();
        private int sectionIndex;
        private int blockIndex;

        private ChunkCursor(LevelChunk chunk) {
            this.chunk = chunk;
            this.sections = chunk.getSections();
            this.skipEmptySections();
        }

        private int advance(int budget, Set<Long> seen) {
            int visited = 0;
            while (this.sectionIndex < this.sections.length && visited < budget) {
                LevelChunkSection section = this.sections[this.sectionIndex];
                if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(Blocks.SPAWNER))) {
                    this.sectionIndex++;
                    this.blockIndex = 0;
                    this.skipEmptySections();
                    continue;
                }
                int index = this.blockIndex++;
                int x = (this.chunk.getPos().x() << 4) + (index & 15);
                int z = (this.chunk.getPos().z() << 4) + ((index >> 4) & 15);
                int y = this.chunk.getMinY() + (this.sectionIndex << 4) + ((index >> 8) & 15);
                if (section.getBlockState(index & 15, (index >> 8) & 15, (index >> 4) & 15).is(Blocks.SPAWNER)) {
                    long packed = BlockPos.asLong(x, y, z);
                    this.found.add(packed);
                    seen.add(packed);
                }
                visited++;
                if (this.blockIndex >= 4096) {
                    this.sectionIndex++;
                    this.blockIndex = 0;
                    this.skipEmptySections();
                }
            }
            if (this.complete()) SpawnerSignpostsModule.this.replaceChunk(this.chunk.getPos().pack(), Set.copyOf(this.found));
            return visited;
        }

        private void skipEmptySections() {
            while (this.sectionIndex < this.sections.length) {
                LevelChunkSection section = this.sections[this.sectionIndex];
                if (section != null && !section.hasOnlyAir() && section.maybeHas(state -> state.is(Blocks.SPAWNER))) return;
                this.sectionIndex++;
            }
        }

        private boolean complete() { return this.sectionIndex >= this.sections.length; }
    }
}
