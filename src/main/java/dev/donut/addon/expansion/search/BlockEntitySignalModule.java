package dev.donut.addon.expansion.search;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.expansion.render.BlockClusterMesh;
import dev.donut.addon.expansion.search.evidence.BlockSignalLedger;
import dev.donut.addon.expansion.search.evidence.HiddenBlockClassifier;
import dev.donut.addon.expansion.search.evidence.LightLeakClassifier;
import dev.donut.addon.expansion.search.evidence.BlockSignalDisplayRules;
import dev.donut.addon.expansion.search.evidence.BlockSignalDisplayRules.BoxStyle;
import dev.donut.addon.expansion.search.evidence.BlockSignalDisplayRules.DetectionBand;
import dev.donut.addon.expansion.search.spawner.SpawnerVisualStyle;
import dev.donut.addon.mv.compat.events.EventHandler;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.packets.PacketEvent;
import dev.donut.addon.mv.compat.events.world.ChunkDataEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.settings.EnumSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.StringSetting;
import dev.donut.addon.mv.compat.systems.modules.Category;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Highlights block-entity packet reports that conflict with the currently loaded client block. */
public final class BlockEntitySignalModule extends Module {
    private static final int DEFAULT_CONFLICT_COLOR = 0xdcff5555;
    private static final int ORDINARY_COLOR = 0xff5eb8ff;

    private final Setting<Integer> renderDistance = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("range")
            .description("Maximum distance for block-data and hidden-light markers.")
            .defaultValue(256).range(16, 2048).sliderRange(16, 512).build()
    );
    private final Setting<DetectionBand> detection = this.settings.getDefaultGroup().add(
        new EnumSetting.Builder<DetectionBand>().name("detection")
            .description("Restrict report markers to blocks below the selected Y band.")
            .defaultValue(DetectionBand.ALL).build()
    );
    private final Setting<Boolean> blockFilter = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("block-filter").description("Only show selected block or block-entity IDs, plus chest reports.")
            .defaultValue(false).build()
    );
    private final Setting<String> blockTypes = this.settings.getDefaultGroup().add(
        new StringSetting.Builder().name("block-types").description("Comma-separated block or block-entity IDs included by the type filter.")
            .defaultValue("").visible(this.blockFilter::get).build()
    );
    private final Setting<String> blockColors = this.settings.getDefaultGroup().add(
        new StringSetting.Builder().name("block-colors").description("Per-type colors as id=#RRGGBB or id=#AARRGGBB, separated by commas or semicolons.")
            .defaultValue("").build()
    );
    private final Setting<Boolean> chatAlerts = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("chat-alerts")
            .description("Announce each new packet and block-state conflict once.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> showOrdinaryReports = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("show-ordinary-reports")
            .description("Draw reports whose block entity matches the client block state.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> showPayload = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("show-payload")
            .description("Include a bounded NBT preview in conflict alerts.")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> hiddenLightHints = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("hidden-light-hints")
            .description("Scan loaded chunk light layers for solid-block leaks and sharp dark gaps.")
            .defaultValue(true).build()
    );
    private final Setting<Integer> lightRange = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("light-range").description("Loaded chunk radius used by the light-hint scan.")
            .defaultValue(8).range(1, 16).sliderRange(1, 16).build()
    );
    private final Setting<BoxStyle> boxStyle = this.settings.getDefaultGroup().add(
        new EnumSetting.Builder<BoxStyle>().name("shape").description("Draw an outline or scaled boxes for report markers.")
            .defaultValue(BoxStyle.BOXES).build()
    );
    private final Setting<Double> boxSize = this.settings.getDefaultGroup().add(
        new DoubleSetting.Builder().name("box-size").description("Size of each marker box when Shape is Boxes.")
            .defaultValue(1.0).range(0.1, 2.0).sliderRange(0.1, 2.0).decimalPlaces(1)
            .visible(() -> this.boxStyle.get() == BoxStyle.BOXES).build()
    );
    private final Setting<Double> fillAlpha = this.settings.getDefaultGroup().add(
        new DoubleSetting.Builder().name("fill-alpha").description("Opacity of filled block-signal markers.")
            .defaultValue(0.8).range(0.0, 1.0).sliderRange(0.0, 1.0).decimalPlaces(2).build()
    );
    private final Setting<Double> lineWidth = this.settings.getDefaultGroup().add(
        new DoubleSetting.Builder().name("line-width").description("Outline width for block-signal markers.")
            .defaultValue(1.5).range(0.5, 5.0).sliderRange(0.5, 5.0).decimalPlaces(1).build()
    );
    private final Setting<Boolean> glow = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("glow").description("Draw a soft, wider halo around outline markers.")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> distanceFade = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("distance-fade").description("Fade markers and taper their outline with distance.")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> reveal = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("reveal").description("Animate each report and light hint as it appears.")
            .defaultValue(true).build()
    );
    private final Setting<SettingColor> lightHintColor = this.settings.getDefaultGroup().add(
        new ColorSetting.Builder().name("light-hint-color").description("Color for hidden-light hint markers.")
            .defaultValue(new SettingColor(255, 190, 50, 220)).visible(this.hiddenLightHints::get).build()
    );
    private final Setting<Boolean> tracers = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("tracers").description("Draw lines from the player to visible block-data reports.")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> customTracerColor = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("custom-tracer-color").description("Use one tracer color instead of the per-type marker color.")
            .defaultValue(false).visible(this.tracers::get).build()
    );
    private final Setting<SettingColor> tracerColor = this.settings.getDefaultGroup().add(
        new ColorSetting.Builder().name("tracer-color").description("Shared tracer color when custom tracer color is enabled.")
            .defaultValue(new SettingColor(255, 255, 255, 255))
            .visible(() -> this.tracers.get() && this.customTracerColor.get()).build()
    );

    private final Set<BlockSignalLedger.Key> announced = new HashSet<>();
    private final ArrayDeque<ChunkPos> lightQueue = new ArrayDeque<>();
    private final Set<Long> lightQueued = new HashSet<>();
    private final LinkedHashMap<Long, Long> lightHints = new LinkedHashMap<>(256, 0.75f, true);
    private final SearchClusterMeshes clusterMeshes = new SearchClusterMeshes();
    private ClientLevel lightWorld;
    private int lightScanTicks;
    private volatile Set<String> includedTypes = Set.of();
    private volatile Map<String, Integer> colorTable = Map.of();

    public BlockEntitySignalModule() {
        super(new Category(AddonCategories.BASEFINDING), "Donut Block Signals",
            "Tracks block-entity data reports and marks positions that disagree with loaded client blocks.");
        this.blockTypes.onChanged(value -> this.includedTypes = BlockSignalDisplayRules.parseIds(value));
        this.blockColors.onChanged(value -> this.colorTable = BlockSignalDisplayRules.parseColors(value));
    }

    @Override public void onActivate() {
        this.clearLightHints();
        this.lightWorld = this.mc.level;
        this.queueNearbyLightChunks();
    }

    @Override public void onDeactivate() { this.clearLightHints(); this.clusterMeshes.clear(); this.lightWorld = null; }

    @EventHandler
    private void onJoined(GameJoinedEvent event) {
        this.announced.clear();
        this.clusterMeshes.clear();
    }

    @EventHandler
    private void onLeft(GameLeftEvent event) {
        this.announced.clear();
        this.clearLightHints();
        this.lightWorld = null;
    }

    @EventHandler private void onChunkData(ChunkDataEvent event) {
        if (this.isActive() && event.chunk() != null && this.hiddenLightHints.get()) this.enqueueLightChunk(event.chunk().getPos());
    }

    @EventHandler private void onLightPacket(PacketEvent.Receive event) {
        if (!this.isActive() || !this.hiddenLightHints.get()) return;
        if (event.packet instanceof ClientboundLevelChunkWithLightPacket packet) {
            this.mc.execute(() -> this.enqueueLightChunk(new ChunkPos(packet.x(), packet.z())));
        } else if (event.packet instanceof ClientboundLightUpdatePacket packet) {
            this.mc.execute(() -> this.enqueueLightChunk(new ChunkPos(packet.x(), packet.z())));
        } else if (event.packet instanceof ClientboundBlockUpdatePacket packet) {
            BlockPos pos = packet.getPos().immutable();
            this.mc.execute(() -> this.enqueueLightChunk(ChunkPos.containing(pos)));
        } else if (event.packet instanceof ClientboundSectionBlocksUpdatePacket packet) {
            packet.runUpdates((pos, state) -> {
                ChunkPos chunk = ChunkPos.containing(pos);
                this.mc.execute(() -> this.enqueueLightChunk(chunk));
            });
        }
    }

    @EventHandler
    private void onTick(Post event) {
        ClientLevel world = this.mc.level;
        if (world == null) return;
        if (world != this.lightWorld) {
            this.clearLightHints();
            this.lightWorld = world;
            this.queueNearbyLightChunks();
        }
        if (this.hiddenLightHints.get() && ++this.lightScanTicks % 40 == 0) this.queueNearbyLightChunks();
        if (this.hiddenLightHints.get()) this.scanNextLightChunk(world);
        else this.clearLightHints();

        for (BlockSignalLedger.Evidence evidence : SearchFeatures.BLOCK_SIGNAL_LEDGER.snapshot()) {
            if (this.chatAlerts.get() && !this.announced.contains(evidence.key())) {
                this.announceConflict(world, evidence);
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        ClientLevel world = this.mc.level;
        if (world == null || this.mc.player == null) return;
        double limitSq = this.renderDistance.get() * (double)this.renderDistance.get();
        Vec3 eye = this.mc.player.getEyePosition((float)event.tickDelta);
        long now = System.nanoTime();
        boolean boxes = this.boxStyle.get() == BoxStyle.BOXES;
        Map<SignalStyleKey, LinkedHashMap<Long, BlockSignalLedger.Evidence>> grouped = new LinkedHashMap<>();
        for (BlockSignalLedger.Evidence evidence : SearchFeatures.BLOCK_SIGNAL_LEDGER.snapshot()) {
            BlockPos pos = new BlockPos(evidence.key().x(), evidence.key().y(), evidence.key().z());
            if (!world.hasChunkAt(pos)) continue;
            if (!this.passesDisplayFilter(world, evidence, pos)) continue;
            boolean conflict = this.conflicts(world, evidence, pos);
            if (!conflict && !this.showOrdinaryReports.get()) continue;
            int argb = conflict
                ? BlockSignalDisplayRules.colorFor(evidence.typeId(), this.colorTable, DEFAULT_CONFLICT_COLOR)
                : ORDINARY_COLOR;
            SignalStyleKey key = new SignalStyleKey(evidence.typeId(), conflict, argb);
            grouped.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(pos.asLong(), evidence);
        }
        for (Map.Entry<SignalStyleKey, LinkedHashMap<Long, BlockSignalLedger.Evidence>> entry : grouped.entrySet()) {
            List<BlockPos> positions = entry.getValue().keySet().stream().map(BlockPos::of).toList();
            for (List<BlockPos> component : BlockClusterMesh.connectedComponents(positions)) {
                Vec3 center = clusterCenter(component);
                double distance = eye.distanceTo(center);
                if (distance * distance > limitSq) continue;
                long bornAt = Long.MAX_VALUE;
                for (BlockPos pos : component) {
                    bornAt = Math.min(bornAt, entry.getValue().get(pos.asLong()).firstSeenNanos());
                }
                SpawnerVisualStyle.Frame frame = SpawnerVisualStyle.frame(distance,
                    Math.max(0L, now - bornAt), this.lineWidth.get().floatValue(),
                    this.distanceFade.get(), this.reveal.get());
            if (!frame.visible()) continue;
                int argb = entry.getKey().argb();
                int lineAlpha = Math.round((argb >>> 24) * frame.opacity());
                Color line = new Color((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, clampByte(lineAlpha));
                int fillPercent = BlockSignalDisplayRules.scaledFillOpacityPercent(
                    (int)Math.round(this.fillAlpha.get() * 100.0), 100);
                Color fill = new Color((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255,
                    clampByte(Math.round(255.0f * fillPercent / 100.0f * frame.opacity())));
                this.drawMarkerCluster(event, component, fill, line, frame, boxes);
                if (this.tracers.get()) {
                    SettingColor tracer = this.tracerColor.get();
                    Color trace = this.customTracerColor.get()
                        ? new Color(tracer.r, tracer.g, tracer.b, clampByte(Math.round(tracer.a * frame.opacity())))
                        : line;
                    event.renderer.line(this.mc.player.getX(), this.mc.player.getEyeY(), this.mc.player.getZ(),
                        center.x, center.y, center.z, trace);
                }
            }
        }

        SettingColor selectedHint = this.lightHintColor.get();
        ArrayList<BlockPos> hintPositions = new ArrayList<>();
        Map<Long, Long> hintBirths = new LinkedHashMap<>();
        Iterator<Map.Entry<Long, Long>> hints = this.lightHints.entrySet().iterator();
        while (hints.hasNext()) {
            Map.Entry<Long, Long> entry = hints.next();
            if (now - entry.getValue() > 30_000_000_000L) { hints.remove(); continue; }
            BlockPos pos = BlockPos.of(entry.getKey());
            if (!this.detection.get().includesY(pos.getY())) continue;
            hintPositions.add(pos);
            hintBirths.put(pos.asLong(), entry.getValue());
        }
        for (List<BlockPos> component : BlockClusterMesh.connectedComponents(hintPositions)) {
            Vec3 center = clusterCenter(component);
            double distance = eye.distanceTo(center);
            if (distance * distance > limitSq) continue;
            long bornAt = Long.MAX_VALUE;
            for (BlockPos pos : component) bornAt = Math.min(bornAt, hintBirths.get(pos.asLong()));
            SpawnerVisualStyle.Frame frame = SpawnerVisualStyle.frame(distance,
                Math.max(0L, now - bornAt), this.lineWidth.get().floatValue(),
                this.distanceFade.get(), this.reveal.get());
            if (!frame.visible()) continue;
            int hintFillPercent = BlockSignalDisplayRules.scaledFillOpacityPercent(
                (int)Math.round(this.fillAlpha.get() * 100.0), 25);
            Color hintLine = new Color(selectedHint.r, selectedHint.g, selectedHint.b,
                clampByte(Math.round(selectedHint.a * frame.opacity())));
            Color hintFill = new Color(selectedHint.r, selectedHint.g, selectedHint.b,
                clampByte(Math.round(255.0f * hintFillPercent / 100.0f * frame.opacity())));
            this.drawMarkerCluster(event, component, hintFill, hintLine, frame, boxes);
            if (this.tracers.get()) {
                SettingColor tracer = this.tracerColor.get();
                Color trace = this.customTracerColor.get()
                    ? new Color(tracer.r, tracer.g, tracer.b, clampByte(Math.round(tracer.a * frame.opacity())))
                    : hintLine;
                event.renderer.line(this.mc.player.getX(), this.mc.player.getEyeY(), this.mc.player.getZ(),
                    center.x, center.y, center.z, trace);
            }
        }
    }

    private void drawMarkerCluster(Render3DEvent event, List<BlockPos> component, Color fill, Color line,
                                   SpawnerVisualStyle.Frame frame, boolean boxes) {
        BlockClusterMesh.Mesh mesh = boxes ? null : this.clusterMeshes.forPositions(component);
        DonutSearchMarkerStyle.drawCluster(event.renderer, component, mesh, boxes, this.boxSize.get(),
            fill, line, frame.lineWidth(), this.glow.get(), frame.scale());
    }

    private static Vec3 clusterCenter(List<BlockPos> component) {
        double x = 0.0, y = 0.0, z = 0.0;
        for (BlockPos pos : component) { x += pos.getX() + 0.5; y += pos.getY() + 0.5; z += pos.getZ() + 0.5; }
        double count = component.size();
        return new Vec3(x / count, y / count, z / count);
    }

    private void announceConflict(ClientLevel world, BlockSignalLedger.Evidence evidence) {
        BlockPos pos = new BlockPos(evidence.key().x(), evidence.key().y(), evidence.key().z());
        if (!world.hasChunkAt(pos) || !this.passesDisplayFilter(world, evidence, pos) || !this.conflicts(world, evidence, pos)) return;
        if (!this.announced.add(evidence.key())) return;
        String payload = this.showPayload.get() && !evidence.payloadPreview().isBlank()
            ? " data=" + evidence.payloadPreview() : "";
        this.info("Block-data conflict at %d, %d, %d (%s)%s", pos.getX(), pos.getY(), pos.getZ(), evidence.typeId(), payload);
    }

    private boolean conflicts(ClientLevel world, BlockSignalLedger.Evidence evidence, BlockPos pos) {
        boolean packetEvidence = evidence.has(BlockSignalLedger.Source.ENTITY_PACKET);
        boolean corroboratedHint = evidence.has(BlockSignalLedger.Source.BLOCK_EVENT) && evidence.has(BlockSignalLedger.Source.SOUND)
            || evidence.has(BlockSignalLedger.Source.BLOCK_EVENT) && evidence.has(BlockSignalLedger.Source.LIGHT)
            || evidence.has(BlockSignalLedger.Source.SOUND) && evidence.has(BlockSignalLedger.Source.LIGHT);
        if (!packetEvidence && !corroboratedHint) return false;
        var state = world.getBlockState(pos);
        Identifier id = Identifier.tryParse(evidence.typeId());
        BlockEntityType<?> type = id != null && BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id)
            ? BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(id) : null;
        boolean validType = type != null && type.isValid(state);
        return HiddenBlockClassifier.conflicts(evidence.typeId(), world.hasChunkAt(pos), state.isAir(), validType);
    }

    private boolean passesDisplayFilter(ClientLevel world, BlockSignalLedger.Evidence evidence, BlockPos pos) {
        if (!this.detection.get().includesY(pos.getY())) return false;
        String loadedBlockId = BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()).toString();
        return BlockSignalDisplayRules.allowsBlock(this.blockFilter.get(), this.includedTypes,
            evidence.typeId(), loadedBlockId);
    }

    private void queueNearbyLightChunks() {
        ClientLevel world = this.mc.level;
        if (world == null || this.mc.player == null) return;
        ChunkPos center = this.mc.player.chunkPosition();
        int radius = this.lightRange.get();
        for (int x = center.x() - radius; x <= center.x() + radius; x++) for (int z = center.z() - radius; z <= center.z() + radius; z++) {
            ChunkAccess loaded = world.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (loaded instanceof LevelChunk) this.enqueueLightChunk(new ChunkPos(x, z));
        }
    }

    private void enqueueLightChunk(ChunkPos pos) {
        long key = pos.pack();
        if (this.lightQueue.size() >= 1024 || !this.lightQueued.add(key)) return;
        this.lightQueue.addLast(pos);
    }

    private void scanNextLightChunk(ClientLevel world) {
        ChunkPos pos = this.lightQueue.pollFirst();
        if (pos == null) return;
        this.lightQueued.remove(pos.pack());
        ChunkAccess access = world.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
        if (!(access instanceof LevelChunk chunk)) return;
        var listener = world.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
        int minSection = chunk.getMinY() >> 4;
        var sections = chunk.getSections();
        int baseX = pos.getMinBlockX(), baseZ = pos.getMinBlockZ();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int collected = 0;
        long deadline = System.nanoTime() + 1_500_000L;
        for (int index = 0; index < sections.length && System.nanoTime() < deadline; index++) {
            var section = sections[index];
            if (section == null || section.hasOnlyAir()) continue;
            int sectionY = minSection + index;
            DataLayer data = listener.getDataLayerData(SectionPos.of(pos.x(), sectionY, pos.z()));
            if (data == null || data.isDefinitelyFilledWith(0)) continue;
            for (int y = 0; y < 16 && System.nanoTime() < deadline; y++) for (int z = 0; z < 16 && System.nanoTime() < deadline; z++) for (int x = 0; x < 16; x++) {
                cursor.set(baseX + x, (sectionY << 4) + y, baseZ + z);
                var state = section.getBlockState(x, y, z);
                int inside = data.get(x, y, z);
                boolean leak = LightLeakClassifier.solidCell(state.getLightDampening(), inside, state.getLightEmission());
                if (!leak && state.isAir() && inside <= 12) {
                    int brightest = inside;
                    for (var direction : net.minecraft.core.Direction.values()) {
                        BlockPos neighbor = cursor.relative(direction);
                        brightest = Math.max(brightest, world.getBrightness(LightLayer.BLOCK, neighbor));
                        var neighboringState = world.getBlockState(neighbor);
                        if (neighboringState.is(Blocks.AMETHYST_BLOCK) || neighboringState.is(Blocks.BUDDING_AMETHYST)) leak = false;
                    }
                    boolean nextToGeode = false;
                    for (var direction : net.minecraft.core.Direction.values()) {
                        var neighbor = cursor.relative(direction);
                        var neighborState = world.getBlockState(neighbor);
                        if (neighborState.is(Blocks.AMETHYST_BLOCK) || neighborState.is(Blocks.BUDDING_AMETHYST)) { nextToGeode = true; break; }
                    }
                    leak = LightLeakClassifier.darkGap(inside, brightest, nextToGeode);
                }
                if (leak) {
                    this.lightHints.putIfAbsent(cursor.asLong(), System.nanoTime());
                    if (++collected >= 512 || this.lightHints.size() > 4096) break;
                }
            }
        }
    }

    private void clearLightHints() {
        this.lightQueue.clear(); this.lightQueued.clear(); this.lightHints.clear(); this.lightScanTicks = 0;
    }

    private AABB markerBounds(BlockPos pos, double size) {
        double half = Math.max(0.05, Math.min(1.0, size * 0.5));
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        return new AABB(x - half, y - half, z - half, x + half, y + half, z + half);
    }

    private static int clampByte(int value) { return Math.max(0, Math.min(255, value)); }

    private record SignalStyleKey(String typeId, boolean conflict, int argb) { }
}
