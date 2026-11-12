package dev.donut.addon.expansion.search.amethyst;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.mv.compat.events.EventHandler;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.packets.PacketEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.ChunkDataEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.EnumSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.systems.modules.Category;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Palette-based geode candidates with client-thread cache invalidation from update packets. */
public final class DonutGeodePaletteSurveyModule extends Module {
    private static final int MAX_QUEUE = 1024;
    private static final int MAX_MARKERS = 1024;
    private static final int MAX_MARKS_PER_CHUNK = 200;
    private static final int FULL_REFRESH_TICKS = 600;
    private static final int IDLE_REFRESH_TICKS = 20;
    private static final long SCAN_BUDGET_NANOS = 2_000_000L;
    private final Setting<ScanMethod> method = this.settings.getDefaultGroup().add(
        new EnumSetting.Builder<ScanMethod>().name("method")
            .description("Select palette, amethyst-light, or dark-glow evidence.")
            .defaultValue(ScanMethod.Bypass).build()
    );
    private final Setting<Integer> radius = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("scan-radius").description("Loaded chunk radius surveyed around you.")
            .defaultValue(8).range(1, 16).sliderRange(1, 16).build()
    );
    private final Setting<Integer> minimumCells = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("minimum-palette-cells").description("Minimum visible amethyst growth blocks in a candidate chunk.")
            .defaultValue(12).range(1, 100).sliderRange(1, 50)
            .visible(() -> this.method.get() == ScanMethod.DonutScan).build()
    );
    private final Setting<Boolean> tracers = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("tracers").description("Draw lines to candidate geode positions.").defaultValue(false).build()
    );
    private final Setting<SettingColor> color = this.settings.getDefaultGroup().add(
        new ColorSetting.Builder().name("color").description("Palette candidate marker color.")
            .defaultValue(new SettingColor(120, 155, 255, 100)).build()
    );

    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Set<Long> scanned = new HashSet<>();
    private final Map<Long, List<BlockPos>> candidates = new HashMap<>();
    private final Set<Long> dirtyPending = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Long> dirtyQueue = new ConcurrentLinkedQueue<>();
    private ClientLevel world;
    private int ticks;
    private int idleTicks;

    public DonutGeodePaletteSurveyModule() {
        super(new Category(AddonCategories.BASEFINDING), "Donut Geode Palette Survey",
            "Finds geode clues from palettes, amethyst light, and dark glow edges.");
        this.method.onChanged(ignored -> this.resetScan());
        this.radius.onChanged(ignored -> this.queueLoaded());
        this.minimumCells.onChanged(ignored -> this.resetScan());
    }

    @Override public void onActivate() { this.clear(); this.world = this.mc.level; this.queueLoaded(); }
    @Override public void onDeactivate() { this.clear(); this.world = null; }

    @EventHandler private void onJoined(GameJoinedEvent event) { this.clear(); this.world = this.mc.level; this.queueLoaded(); }
    @EventHandler private void onLeft(GameLeftEvent event) { this.clear(); this.world = null; }

    @EventHandler private void onChunkData(ChunkDataEvent event) {
        if (this.isActive() && event.chunk() != null) this.markDirty(event.chunk().getPos().x(), event.chunk().getPos().z());
    }

    @EventHandler private void onPacket(PacketEvent.Receive event) {
        if (!this.isActive()) return;
        if (event.packet instanceof ClientboundLevelChunkWithLightPacket packet) {
            this.markDirty(packet.x(), packet.z());
        } else if (event.packet instanceof ClientboundLightUpdatePacket packet) {
            this.markDirty(packet.x(), packet.z());
        } else if (event.packet instanceof ClientboundBlockUpdatePacket packet) {
            BlockPos pos = packet.getPos();
            this.markDirty(pos.getX() >> 4, pos.getZ() >> 4);
        } else if (event.packet instanceof ClientboundSectionBlocksUpdatePacket packet) {
            packet.runUpdates((pos, state) -> this.markDirty(pos.getX() >> 4, pos.getZ() >> 4));
        }
    }

    @EventHandler private void onTick(Post event) {
        ClientLevel level = this.mc.level;
        if (level != this.world) { this.clear(); this.world = level; this.queueLoaded(); }
        if (level == null || this.mc.player == null) return;
        this.ticks++;
        if (this.ticks >= FULL_REFRESH_TICKS) {
            this.ticks = 0;
            this.scanned.clear();
            this.queueLoaded();
        } else if (this.queue.isEmpty() && ++this.idleTicks >= IDLE_REFRESH_TICKS) {
            this.idleTicks = 0;
            this.queueLoaded();
        }
        Long dirtyKey;
        while ((dirtyKey = this.dirtyQueue.poll()) != null) {
            this.dirtyPending.remove(dirtyKey);
            this.scanned.remove(dirtyKey);
            this.enqueue(ChunkPos.unpack(dirtyKey));
        }
        long deadline = System.nanoTime() + SCAN_BUDGET_NANOS;
        while (System.nanoTime() < deadline && !this.queue.isEmpty()) {
            ChunkPos pos = this.queue.removeFirst();
            long key = pos.pack();
            this.queued.remove(key);
            ChunkAccess loaded = level.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
            if (!(loaded instanceof LevelChunk chunk)) { this.candidates.remove(key); continue; }
            List<BlockPos> found = this.scan(level, chunk);
            if (found.isEmpty()) this.candidates.remove(key); else this.candidates.put(key, found);
            this.scanned.add(key);
            if (this.candidates.size() > MAX_MARKERS) this.candidates.remove(this.candidates.keySet().iterator().next());
        }
    }

    @EventHandler private void onRender(Render3DEvent event) {
        if (this.mc.player == null || this.mc.level == null) return;
        SettingColor selected = this.color.get();
        Color fill = new Color(selected.r, selected.g, selected.b, selected.a);
        Color line = new Color(selected.r, selected.g, selected.b, Math.max(170, selected.a));
        double rangeSq = (this.radius.get() * 16.0 + 16.0) * (this.radius.get() * 16.0 + 16.0);
        for (List<BlockPos> marks : this.candidates.values()) {
            for (BlockPos pos : marks) {
                if (pos.distToCenterSqr(this.mc.player.getX(), this.mc.player.getY(), this.mc.player.getZ()) > rangeSq) continue;
                event.renderer.box(pos, fill, line, ShapeMode.Both, 0);
                if (this.tracers.get()) event.renderer.line(this.mc.player.getX(), this.mc.player.getEyeY(), this.mc.player.getZ(), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, line);
            }
        }
    }

    private List<BlockPos> scan(ClientLevel level, LevelChunk chunk) {
        return switch (this.method.get()) {
            case Bypass -> this.scanPalette(chunk);
            case Light -> this.scanLight(level, chunk);
            case DonutScan -> this.scanGlow(level, chunk);
        };
    }

    private List<BlockPos> scanPalette(LevelChunk chunk) {
        int budCount = 0;
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        ChunkPos cp = chunk.getPos();
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinY() >> 4;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.getStates().maybeHas(DonutGeodePaletteSurveyModule::isBudOrCluster)) continue;
            int baseY = (minSectionY + i) << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = section.getBlockState(x, y, z);
                if (isBudOrCluster(state)) {
                    budCount++;
                    continue;
                }
                if (!state.isAir()) continue;
                int wx = cp.getMinBlockX() + x, wy = baseY + y, wz = cp.getMinBlockZ() + z;
                int score = Integer.MAX_VALUE;
                for (int[] side : SIDES) {
                    int nx = wx + side[0], ny = wy + side[1], nz = wz + side[2];
                    if ((nx >> 4) != cp.x() || (nz >> 4) != cp.z() || ny < chunk.getMinY() || ny >= chunk.getMaxY()) continue;
                    BlockState neighbor = chunk.getBlockState(new BlockPos(nx, ny, nz));
                    if (neighbor.is(Blocks.AMETHYST_BLOCK) || neighbor.is(Blocks.BUDDING_AMETHYST)) {
                        int candidateScore = Math.floorMod(wx * 73428767 ^ wy * 912931 ^ wz * 19349663, 4096);
                        if (candidateScore < score) score = candidateScore;
                    }
                }
                if (score < bestScore) { bestScore = score; best = new BlockPos(wx, wy, wz); }
            }
        }
        return GeodePaletteEvidence.candidate(budCount, best == null ? 0 : budCount, 1) && best != null
            ? List.of(best) : List.of();
    }

    private List<BlockPos> scanLight(ClientLevel level, LevelChunk chunk) {
        List<BlockPos> marks = new ArrayList<>();
        ChunkPos cp = chunk.getPos();
        LevelChunkSection[] sections = chunk.getSections();
        int minSectionY = chunk.getMinY() >> 4;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < sections.length && marks.size() < MAX_MARKS_PER_CHUNK; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()
                || !section.getStates().maybeHas(DonutGeodePaletteSurveyModule::isLining)) continue;
            int baseY = (minSectionY + i) << 4;
            for (int y = 0; y < 16 && marks.size() < MAX_MARKS_PER_CHUNK; y++) {
                for (int z = 0; z < 16 && marks.size() < MAX_MARKS_PER_CHUNK; z++) {
                    for (int x = 0; x < 16 && marks.size() < MAX_MARKS_PER_CHUNK; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.getLightEmission() > 0) continue;
                        cursor.set(cp.getMinBlockX() + x, baseY + y, cp.getMinBlockZ() + z);
                        int light = level.getBrightness(LightLayer.BLOCK, cursor);
                        if (!GeodePaletteEvidence.isGrowthLight(light) || !touchesLining(level, cursor)) continue;
                        int[] neighbours = new int[6];
                        int n = 0;
                        for (Direction direction : Direction.values()) {
                            neighbours[n++] = level.getBrightness(LightLayer.BLOCK, cursor.relative(direction));
                        }
                        if (GeodePaletteEvidence.isLocalMaximum(light, neighbours)) marks.add(cursor.immutable());
                    }
                }
            }
        }
        return marks;
    }

    private List<BlockPos> scanGlow(ClientLevel level, LevelChunk chunk) {
        List<BlockPos> marks = new ArrayList<>();
        ChunkPos cp = chunk.getPos();
        int minY = Math.max(-58, level.getMinY());
        int maxY = Math.min(30, level.getMaxY() - 1);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY && marks.size() < MAX_MARKS_PER_CHUNK; y++) {
            for (int z = 0; z < 16 && marks.size() < MAX_MARKS_PER_CHUNK; z++) {
                for (int x = 0; x < 16 && marks.size() < MAX_MARKS_PER_CHUNK; x++) {
                    cursor.set(cp.getMinBlockX() + x, y, cp.getMinBlockZ() + z);
                    BlockState state = level.getBlockState(cursor);
                    if (!isGlowOpen(state) || level.getBrightness(LightLayer.BLOCK, cursor) != 0) continue;
                    int brightest = 0;
                    boolean litOpening = false;
                    for (Direction direction : Direction.values()) {
                        BlockPos neighbor = cursor.relative(direction);
                        if (!level.hasChunkAt(neighbor)) { litOpening = false; brightest = 5; break; }
                        int light = level.getBrightness(LightLayer.BLOCK, neighbor);
                        if (light > 4) { brightest = light; break; }
                        brightest = Math.max(brightest, light);
                        if (light == 4 && isGlowOpen(level.getBlockState(neighbor))) litOpening = true;
                    }
                    if (GeodePaletteEvidence.isGlowEdge(0, true, brightest, litOpening)) marks.add(cursor.immutable());
                }
            }
        }
        return marks.size() >= this.minimumCells.get() ? marks : List.of();
    }

    private static boolean isBudOrCluster(BlockState state) {
        return state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD)
            || state.is(Blocks.LARGE_AMETHYST_BUD) || state.is(Blocks.AMETHYST_CLUSTER);
    }

    private static boolean isLining(BlockState state) {
        return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST);
    }

    private static boolean touchesLining(ClientLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) if (isLining(level.getBlockState(pos.relative(direction)))) return true;
        return false;
    }

    private static boolean isGlowOpen(BlockState state) {
        return state.isAir() || state.is(Blocks.AMETHYST_CLUSTER);
    }

    private static final int[][] SIDES = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

    private void markDirty(int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long key = ChunkPos.pack(chunkX + dx, chunkZ + dz);
            if (this.dirtyPending.size() < MAX_QUEUE && this.dirtyPending.add(key)) this.dirtyQueue.add(key);
        }
    }

    private void queueLoaded() {
        if (this.mc.level == null || this.mc.player == null) return;
        ChunkPos center = this.mc.player.chunkPosition();
        int r = this.radius.get();
        for (int x = center.x() - r; x <= center.x() + r; x++) for (int z = center.z() - r; z <= center.z() + r; z++) {
            if (!this.scanned.contains(ChunkPos.pack(x, z))
                && this.mc.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null) this.enqueue(new ChunkPos(x, z));
        }
    }

    private void enqueue(ChunkPos pos) {
        long key = pos.pack();
        if (this.queue.size() < MAX_QUEUE && this.queued.add(key)) this.queue.addLast(pos);
    }

    private void resetScan() { this.clear(); this.world = this.mc.level; this.queueLoaded(); }

    private void clear() {
        this.queue.clear(); this.queued.clear(); this.scanned.clear(); this.candidates.clear();
        this.dirtyQueue.clear(); this.dirtyPending.clear(); this.ticks = 0; this.idleTicks = 0;
    }

    private enum ScanMethod {
        Bypass("Palette"), Light("Light Scan"), DonutScan("Donut Scan");
        private final String label;
        ScanMethod(String label) { this.label = label; }
        @Override public String toString() { return this.label; }
    }
}
