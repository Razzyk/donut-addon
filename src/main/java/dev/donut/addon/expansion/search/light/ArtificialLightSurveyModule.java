package dev.donut.addon.expansion.search.light;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.expansion.search.light.LightCellClassifier.Cell;
import dev.donut.addon.mv.compat.events.EventHandler;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.systems.modules.Category;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Local underground light survey; separate from packet-light activity tracking. */
public final class ArtificialLightSurveyModule extends Module {
    private static final long SCAN_INTERVAL_NANOS = 1_000_000_000L;
    private static final long WORK_BUDGET_NANOS = 1_500_000L;
    private static final int MAX_RENDERED_CELLS = 12_000;
    private static final int MAX_HITS = 250_000;

    private final Setting<Integer> radius = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("render-radius")
            .description("Loaded-chunk radius surveyed around the player.")
            .defaultValue(8).range(1, 16).sliderRange(1, 16).build()
    );
    private final Setting<Integer> minimumLight = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("minimum-light")
            .description("Minimum block light included in the survey.")
            .defaultValue(5).range(1, 15).sliderRange(1, 15).build()
    );
    private final Setting<Boolean> denoise = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("denoise")
            .description("Drop isolated light cells that do not belong to a small cluster.")
            .defaultValue(true).build()
    );
    private final Setting<SettingColor> color = this.settings.getDefaultGroup().add(
        new ColorSetting.Builder().name("color")
            .description("Color of artificial light cells.")
            .defaultValue(new SettingColor(255, 145, 58, 110)).build()
    );

    private ClientLevel lastWorld;
    private ArrayDeque<ChunkPos> workQueue;
    private ChunkCursor cursor;
    private final Map<Cell, Integer> collecting = new LinkedHashMap<>();
    private Map<Cell, Integer> shown = Map.of();
    private long nextScanAt;

    public ArtificialLightSurveyModule() {
        super(new Category(AddonCategories.BASEFINDING), "Donut Light Survey",
            "Maps local underground block light that exceeds the natural sky-light level.");
        this.radius.onChanged(ignored -> this.resetScan());
        this.minimumLight.onChanged(ignored -> this.resetScan());
        this.denoise.onChanged(ignored -> this.resetScan());
    }

    @Override
    public void onActivate() {
        this.clear();
        this.lastWorld = this.mc.level;
        this.startScan();
    }

    @Override
    public void onDeactivate() {
        this.clear();
        this.lastWorld = null;
    }

    @EventHandler
    private void onJoined(GameJoinedEvent event) {
        this.clear();
        this.lastWorld = this.mc.level;
        this.startScan();
    }

    @EventHandler
    private void onLeft(GameLeftEvent event) {
        this.clear();
        this.lastWorld = null;
    }

    @EventHandler
    private void onTick(Post event) {
        ClientLevel world = this.mc.level;
        if (world != this.lastWorld) {
            this.clear();
            this.lastWorld = world;
            this.startScan();
        }
        if (world == null) return;
        if (this.workQueue == null && System.nanoTime() >= this.nextScanAt) this.startScan();
        if (this.workQueue == null) return;

        long deadline = System.nanoTime() + WORK_BUDGET_NANOS;
        int chunks = 0;
        while (System.nanoTime() < deadline && chunks < 16) {
            if (this.cursor == null) {
                ChunkPos next = this.workQueue.pollFirst();
                if (next == null) {
                    this.finishScan();
                    break;
                }
                ChunkAccess chunk = world.getChunkSource().getChunk(next.x(), next.z(), ChunkStatus.FULL, false);
                if (!(chunk instanceof LevelChunk loaded)) continue;
                this.cursor = new ChunkCursor(loaded);
                chunks++;
            }
            this.cursor.advance(world, this.minimumLight.get(), deadline, this.collecting);
            if (this.collecting.size() >= MAX_HITS) {
                this.workQueue.clear();
                this.cursor = null;
            } else if (this.cursor.complete()) {
                this.cursor = null;
            }
        }
        if (this.cursor == null && this.workQueue != null && this.workQueue.isEmpty()) this.finishScan();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (this.mc.level == null || this.mc.player == null || this.shown.isEmpty()) return;
        SettingColor selected = this.color.get();
        double maxDistanceSq = (this.radius.get() * 16.0 + 16.0) * (this.radius.get() * 16.0 + 16.0);
        int drawn = 0;
        for (Map.Entry<Cell, Integer> entry : this.shown.entrySet()) {
            if (drawn >= MAX_RENDERED_CELLS) break;
            Cell cell = entry.getKey();
            double dx = cell.x() + 0.5 - this.mc.player.getX();
            double dy = cell.y() + 0.5 - this.mc.player.getY();
            double dz = cell.z() + 0.5 - this.mc.player.getZ();
            if (dx * dx + dy * dy + dz * dz > maxDistanceSq) continue;
            float strength = entry.getValue() / 15.0f;
            Color fill = new Color(selected.r, Math.min(255, selected.g + Math.round(70 * strength)), selected.b, selected.a);
            Color line = new Color(selected.r, selected.g, selected.b, Math.max(100, selected.a));
            event.renderer.box(new BlockPos(cell.x(), cell.y(), cell.z()), fill, line, ShapeMode.Both, 0);
            drawn++;
        }
    }

    private void startScan() {
        ClientLevel world = this.mc.level;
        if (world == null || this.mc.player == null || !this.isActive()) return;
        int centerX = this.mc.player.getBlockX() >> 4;
        int centerZ = this.mc.player.getBlockZ() >> 4;
        int range = this.radius.get();
        List<ChunkPos> chunks = new ArrayList<>((range * 2 + 1) * (range * 2 + 1));
        for (int x = centerX - range; x <= centerX + range; x++) {
            for (int z = centerZ - range; z <= centerZ + range; z++) chunks.add(new ChunkPos(x, z));
        }
        chunks.sort(Comparator.comparingInt(pos -> (pos.x() - centerX) * (pos.x() - centerX) + (pos.z() - centerZ) * (pos.z() - centerZ)));
        this.collecting.clear();
        this.workQueue = new ArrayDeque<>(chunks);
        this.cursor = null;
        this.shown = Map.of();
    }

    private void finishScan() {
        if (this.workQueue == null) return;
        if (!this.denoise.get()) {
            this.shown = Map.copyOf(this.collecting);
        } else {
            LinkedHashMap<Cell, Integer> filtered = new LinkedHashMap<>();
            this.collecting.forEach((cell, level) -> {
                if (LightCellClassifier.hasEnoughNeighbours(cell, this.collecting, 2)) filtered.put(cell, level);
            });
            this.shown = Map.copyOf(filtered);
        }
        this.workQueue = null;
        this.cursor = null;
        this.nextScanAt = System.nanoTime() + SCAN_INTERVAL_NANOS;
    }

    private void resetScan() {
        this.collecting.clear();
        this.shown = Map.of();
        this.cursor = null;
        this.workQueue = null;
        this.nextScanAt = 0L;
        if (this.isActive()) this.startScan();
    }

    private void clear() {
        this.collecting.clear();
        this.shown = Map.of();
        this.cursor = null;
        this.workQueue = null;
        this.nextScanAt = 0L;
    }

    private static final class ChunkCursor {
        private final LevelChunk chunk;
        private final LevelChunkSection[] sections;
        private int sectionIndex;
        private int blockIndex;

        private ChunkCursor(LevelChunk chunk) {
            this.chunk = chunk;
            this.sections = chunk.getSections();
            while (this.sectionIndex < this.sections.length
                && this.sectionBaseY(this.sectionIndex) >= 0) this.sectionIndex++;
        }

        private void advance(ClientLevel world, int minimumLight, long deadline, Map<Cell, Integer> output) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            while (this.sectionIndex < this.sections.length && System.nanoTime() < deadline && output.size() < MAX_HITS) {
                LevelChunkSection section = this.sections[this.sectionIndex];
                if (section == null || section.hasOnlyAir()) {
                    this.sectionIndex++;
                    this.blockIndex = 0;
                    continue;
                }
                int index = this.blockIndex++;
                int x = (this.chunk.getPos().x() << 4) + (index & 15);
                int z = (this.chunk.getPos().z() << 4) + (index >> 4 & 15);
                int y = this.sectionBaseY(this.sectionIndex) + (index >> 8 & 15);
                pos.set(x, y, z);
                int blockLight = world.getBrightness(LightLayer.BLOCK, pos);
                if (LightCellClassifier.isArtificial(blockLight, world.getBrightness(LightLayer.SKY, pos), minimumLight)) {
                    output.put(new Cell(x, y, z), blockLight);
                }
                if (this.blockIndex >= 4096) {
                    this.sectionIndex++;
                    this.blockIndex = 0;
                    while (this.sectionIndex < this.sections.length && this.sectionBaseY(this.sectionIndex) >= 0) this.sectionIndex++;
                }
            }
        }

        private boolean complete() { return this.sectionIndex >= this.sections.length; }

        private int sectionBaseY(int index) { return this.chunk.getMinY() + index * 16; }
    }
}
