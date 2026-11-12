package dev.donut.addon.expansion.search.formation;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.mv.compat.events.EventHandler;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.ChunkDataEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.systems.modules.Category;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.player.ChatUtils;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loaded-chunk survey for tall stone stacks touching blob material near cave air. */
public final class DonutFormationSurveyModule extends Module {
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 32;
    private static final int MAX_QUEUE = 1024;
    private static final int MAX_MARKERS = 512;
    private static final long SWEEP_NANOS = 2_000_000_000L;
    private static final BlockPos.MutableBlockPos MUTABLE = new BlockPos.MutableBlockPos();

    private final Setting<Integer> radius = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("scan-radius").description("Loaded chunk radius surveyed around you.")
            .defaultValue(8).range(1, 16).sliderRange(1, 16).build()
    );
    private final Setting<Integer> minimumStack = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("sensitivity").description("Minimum contiguous stone stack height.")
            .defaultValue(3).range(2, 5).sliderRange(2, 5).build()
    );
    private final Setting<Integer> spreadRadius = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("spread-radius").description("Expand qualifying stack markers by this many chunks.")
            .defaultValue(1).range(0, 16).sliderRange(0, 8).build()
    );
    private final Setting<Boolean> tracers = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("tracers").description("Draw lines to marked chunks.").defaultValue(true).build()
    );
    private final Setting<Boolean> notifications = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("notifications").description("Report newly found formation chunks.").defaultValue(false).build()
    );
    private final Setting<Boolean> sound = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("sound").description("Play a short sound for a newly marked chunk.").defaultValue(false).build()
    );
    private final Setting<SettingColor> color = this.settings.getDefaultGroup().add(
        new ColorSetting.Builder().name("color").description("Formation chunk marker color.")
            .defaultValue(new SettingColor(220, 220, 235, 90)).build()
    );

    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Map<Long, Candidate> candidates = new HashMap<>();
    private List<Long> shown = List.of();
    private ClientLevel world;
    private long nextSweepAt;

    public DonutFormationSurveyModule() {
        super(new Category(AddonCategories.BASEFINDING), "Donut Formation Survey",
            "Marks loaded chunks with tall stone stacks touching blob material beside cave air.");
        this.radius.onChanged(ignored -> this.queueNearby());
        this.minimumStack.onChanged(ignored -> this.queueNearby());
    }

    @Override public void onActivate() { this.clear(); this.world = this.mc.level; this.queueNearby(); }
    @Override public void onDeactivate() { this.clear(); this.world = null; }

    @EventHandler private void onJoined(GameJoinedEvent event) { this.clear(); this.world = this.mc.level; this.queueNearby(); }
    @EventHandler private void onLeft(GameLeftEvent event) { this.clear(); this.world = null; }

    @EventHandler private void onChunkData(ChunkDataEvent event) {
        if (this.isActive() && event.chunk() != null) this.enqueue(event.chunk().getPos());
    }

    @EventHandler private void onTick(Post event) {
        ClientLevel level = this.mc.level;
        if (level != this.world) { this.clear(); this.world = level; this.queueNearby(); }
        if (level == null || this.mc.player == null) return;
        if (System.nanoTime() >= this.nextSweepAt) this.queueNearby();
        int chunks = 0;
        while (!this.queue.isEmpty() && chunks++ < 1) {
            ChunkPos pos = this.queue.removeFirst();
            this.queued.remove(pos.pack());
            ChunkAccess loaded = level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
            if (!(loaded instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) {
                this.candidates.remove(pos.pack());
                continue;
            }
            Candidate found = this.scan(level, chunk);
            Candidate was = found == null ? this.candidates.remove(pos.pack()) : this.candidates.put(pos.pack(), found);
            if (found != null && was == null) this.announce(pos, found);
            if (this.candidates.size() > MAX_MARKERS) {
                Long oldest = this.candidates.keySet().iterator().next();
                this.candidates.remove(oldest);
            }
        }
        this.rebuildShown(level);
    }

    @EventHandler private void onRender(Render3DEvent event) {
        if (this.mc.player == null || this.shown.isEmpty()) return;
        SettingColor selected = this.color.get();
        Color fill = new Color(selected.r, selected.g, selected.b, selected.a);
        Color line = new Color(selected.r, selected.g, selected.b, Math.max(150, selected.a));
        for (long key : this.shown) {
            int x = ChunkPos.getX(key) << 4;
            int z = ChunkPos.getZ(key) << 4;
            event.renderer.box(x, MIN_Y, z, x + 16, MAX_Y + 1, z + 16, fill, line, ShapeMode.Both, 0);
            if (this.tracers.get()) event.renderer.line(this.mc.player.getX(), this.mc.player.getEyeY(), this.mc.player.getZ(), x + 8, 16, z + 8, line);
        }
    }

    private Candidate scan(ClientLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        int bestHeight = 0;
        int bestScore = 0;
        BlockPos best = null;
        int minY = Math.max(MIN_Y, level.getMinY());
        int maxY = Math.min(MAX_Y, level.getMaxY() - 1);
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int y = minY;
            while (y <= maxY) {
                MUTABLE.set(baseX + x, y, baseZ + z);
                if (!level.getBlockState(MUTABLE).is(Blocks.STONE)) { y++; continue; }
                int bottom = y;
                while (y <= maxY) {
                    MUTABLE.set(baseX + x, y, baseZ + z);
                    if (!level.getBlockState(MUTABLE).is(Blocks.STONE)) break;
                    y++;
                }
                int height = y - bottom;
                if (height < this.minimumStack.get()) continue;
                boolean blob = false, cavity = false;
                int middle = bottom + height / 2;
                for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    int ny = middle + dy;
                    if (ny < minY || ny > maxY) continue;
                    MUTABLE.set(baseX + x + dx, ny, baseZ + z + dz);
                    BlockState neighbor = level.getBlockState(MUTABLE);
                    if (isBlob(neighbor)) blob = true;
                    if (neighbor.isAir()) cavity = true;
                }
                boolean caps = isStoneBearing(level, baseX + x, bottom - 1, baseZ + z)
                    && isStoneBearing(level, baseX + x, y, baseZ + z);
                int score = FormationEvidence.confidence(height, this.minimumStack.get(), blob, cavity, caps);
                if (score > bestScore || score == bestScore && height > bestHeight) {
                    bestScore = score;
                    bestHeight = height;
                    best = new BlockPos(baseX + x, bottom, baseZ + z);
                }
            }
        }
        return best == null ? null : new Candidate(best, bestHeight, bestScore);
    }

    private static boolean isBlob(BlockState state) {
        return state.is(Blocks.ANDESITE) || state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE) || state.is(Blocks.GRAVEL);
    }

    private static boolean isStoneBearing(ClientLevel level, int x, int y, int z) {
        if (!level.isOutsideBuildHeight(y)) {
            MUTABLE.set(x, y, z);
            BlockState state = level.getBlockState(MUTABLE);
            return state.is(Blocks.STONE) || state.is(Blocks.CALCITE);
        }
        return false;
    }

    private void queueNearby() {
        if (this.mc.level == null || this.mc.player == null) return;
        ChunkPos center = this.mc.player.chunkPosition();
        int r = this.radius.get();
        for (int cx = center.x() - r; cx <= center.x() + r; cx++) for (int cz = center.z() - r; cz <= center.z() + r; cz++) {
            ChunkAccess chunk = this.mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk != null) this.enqueue(new ChunkPos(cx, cz));
        }
        this.nextSweepAt = System.nanoTime() + SWEEP_NANOS;
    }

    private void enqueue(ChunkPos pos) {
        if (this.queue.size() >= MAX_QUEUE || !this.queued.add(pos.pack())) return;
        this.queue.addLast(pos);
    }

    private void rebuildShown(ClientLevel level) {
        Map<Long, Integer> heat = new HashMap<>();
        int r = this.spreadRadius.get();
        for (Map.Entry<Long, Candidate> entry : this.candidates.entrySet()) {
            int cx = ChunkPos.getX(entry.getKey()), cz = ChunkPos.getZ(entry.getKey());
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                ChunkAccess loaded = level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
                if (loaded != null) heat.merge(ChunkPos.pack(cx + dx, cz + dz), entry.getValue().confidence, Integer::sum);
            }
        }
        List<Long> keys = new ArrayList<>(heat.keySet());
        keys.sort(Comparator.<Long>comparingInt(heat::get).reversed());
        if (keys.size() > MAX_MARKERS) keys = keys.subList(0, MAX_MARKERS);
        this.shown = List.copyOf(keys);
    }

    private void announce(ChunkPos pos, Candidate candidate) {
        if (this.notifications.get()) ChatUtils.sendMsg(Component.literal("Formation signal in chunk " + pos.x() + ", " + pos.z() + " (stack " + candidate.height + ")."));
        if (this.sound.get()) this.mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 0.8F, 1.0F));
    }

    private void clear() {
        this.queue.clear(); this.queued.clear(); this.candidates.clear(); this.shown = List.of(); this.nextSweepAt = 0L;
    }

    private record Candidate(BlockPos position, int height, int confidence) {}
}
