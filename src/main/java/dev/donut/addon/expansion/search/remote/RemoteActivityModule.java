package dev.donut.addon.expansion.search.remote;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.expansion.search.remote.RemoteActivityLedger.Entry;
import dev.donut.addon.expansion.search.remote.RemoteActivityInference.Hotspot;
import dev.donut.addon.expansion.search.remote.RemoteLightClassifier.Evidence;
import dev.donut.addon.mv.compat.events.EventHandler;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.packets.PacketEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.ColorSetting;
import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.StringSetting;
import dev.donut.addon.mv.compat.systems.modules.Category;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.player.ChatUtils;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Visualizes remote update clusters and labels them as packet activity rather than player certainty. */
public final class RemoteActivityModule extends Module {
    private static final int MAX_RENDERED = 256;
    private final Setting<Integer> range = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("range").description("Maximum distance from the player for remote activity markers.")
            .defaultValue(512).range(64, 1024).sliderRange(64, 1024).build()
    );
    private final Setting<Double> minimumScore = this.settings.getDefaultGroup().add(
        new DoubleSetting.Builder().name("minimum-score").description("Minimum recent evidence sum required to infer a remote activity hotspot.")
            .defaultValue(3.0).range(1.0, 20.0).sliderRange(1.0, 10.0).build()
    );
    private final Setting<Integer> minimumSources = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("minimum-source-chunks").description("Distinct remote chunks that must support an inferred hotspot.")
            .defaultValue(2).range(1, 6).sliderRange(1, 6).build()
    );
    private final Setting<Integer> sensitivity = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("sensitivity").description("Adjust the packet-activity peak threshold; higher sensitivity accepts weaker signals.")
            .defaultValue(5).range(1, 10).sliderRange(1, 10).build()
    );
    private final Setting<Integer> opacity = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("opacity").description("Opacity of activity, base-signal, and suspect chunk markers.")
            .defaultValue(140).range(10, 255).sliderRange(10, 255).build()
    );
    private final Setting<Integer> memorySeconds = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("memory-seconds").description("Maximum age of retained update evidence.")
            .defaultValue(45).range(5, 120).sliderRange(5, 120).build()
    );
    private final Setting<Integer> ringMargin = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("ring-margin").description("Extra chunks beyond the estimated simulation ring before remote signals count.")
            .defaultValue(0).range(0, 8).sliderRange(0, 8).build()
    );
    private final Setting<Boolean> suppressFrozen = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("suppress-frozen").description("Ignore remote evidence that has stopped updating for the configured time.").defaultValue(true).build()
    );
    private final Setting<Integer> frozenSeconds = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("frozen-seconds").description("Maximum inactivity age before an area is treated as frozen.")
            .defaultValue(40).range(10, 120).sliderRange(10, 90).build()
    );
    private final Setting<Boolean> tracers = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("tracers").description("Draw lines to remote activity chunks.").defaultValue(false).build()
    );
    private final Setting<Boolean> showCoordinates = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("show-coordinates").description("Include chunk center coordinates in new activity alerts.").defaultValue(true).build()
    );
    private final Setting<Boolean> sound = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("sound").description("Play an alert for each newly qualified activity chunk.").defaultValue(false).build()
    );
    private final Setting<Boolean> notifications = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("notifications").description("Send chat alerts when a chunk crosses the evidence threshold.").defaultValue(false).build()
    );
    private final Setting<Boolean> autoDisconnect = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("auto-disconnect").description("Disconnect after a chunk meets the configured evidence threshold.").defaultValue(false).build()
    );
    private final Setting<Boolean> autoRtp = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("auto-rtp").description("Loop RTP commands while scanning for remote activity; disabled by default.").defaultValue(false).build()
    );
    private final Setting<String> rtpCommand = this.settings.getDefaultGroup().add(
        new StringSetting.Builder().name("rtp-command").description("Command used by Auto RTP, without a leading slash.").defaultValue("rtp").build()
    );
    private final Setting<String> rtpMenuItem = this.settings.getDefaultGroup().add(
        new StringSetting.Builder().name("rtp-menu-item").description("Optional comma-separated item name or item ID to select when the RTP command opens a menu.")
            .defaultValue("").build()
    );
    private final Setting<Double> rtpScanSeconds = this.settings.getDefaultGroup().add(
        new DoubleSetting.Builder().name("rtp-scan-time").description("Seconds spent watching each landing.").defaultValue(5.0).range(2.0, 30.0).sliderRange(2.0, 15.0).decimalPlaces(1).build()
    );
    private final Setting<Double> rtpDelaySeconds = this.settings.getDefaultGroup().add(
        new DoubleSetting.Builder().name("rtp-delay").description("Minimum delay between RTP attempts.").defaultValue(6.0).range(3.0, 120.0).sliderRange(3.0, 30.0).decimalPlaces(1).build()
    );
    private final Setting<Double> rtpTimeoutSeconds = this.settings.getDefaultGroup().add(
        new DoubleSetting.Builder().name("rtp-timeout").description("Maximum wait for a teleport before another attempt.").defaultValue(10.0).range(3.0, 60.0).sliderRange(3.0, 30.0).decimalPlaces(0).build()
    );
    private final Setting<Integer> rtpMax = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("rtp-max").description("Maximum RTP commands; zero is unlimited.").defaultValue(100).range(0, 500).sliderRange(0, 200).build()
    );
    private final Setting<Boolean> stopOnSuspect = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("rtp-stop-on-suspect").description("Stop the RTP loop when activity reaches the threshold.").defaultValue(false).build()
    );
    private final Setting<SettingColor> markerColor = this.settings.getDefaultGroup().add(
        new ColorSetting.Builder().name("color").description("Color for remote activity markers.").defaultValue(new SettingColor(255, 90, 30, 90)).build()
    );

    private ClientLevel world;
    private ClientLevel knownBaseWorld;
    private List<Hotspot> shown = List.of();
    private final Set<RemoteActivityLedger.Key> alerted = new HashSet<>();
    private final Set<Long> alertedLight = new LinkedHashSet<>();
    private long lastAlertAt;
    private final RemoteActivityRtpLoop rtpLoop = new RemoteActivityRtpLoop();
    private RemoteKnownBaseStore knownBaseStore;
    private final RemoteChunkEntryGuard entryGuard = new RemoteChunkEntryGuard();
    private final Set<Long> rtpKnownSuspects = new HashSet<>();
    private volatile Connection rtpConnection;
    private ClientLevel rtpWorld;
    private volatile long rtpSessionGeneration;
    private double rtpAnchorX;
    private double rtpAnchorY;
    private double rtpAnchorZ;
    private int rtpStartChunkX;
    private int rtpStartChunkZ;
    private boolean rtpWasDead;
    private boolean sendingRtpCommand;
    private long rtpCommandSentAt = Long.MIN_VALUE;
    private long rtpUserActionAt = Long.MIN_VALUE;
    private long rtpOfflineSince;
    private long rtpLastEvidenceNanos;
    private long rtpNoticedCooldownUntil;
    private boolean rtpLastTryLanded;
    private String rtpLastProgress = "";
    private long rtpLastProgressAt;
    private List<RemoteLightTracker.ChunkState> shownLight = List.of();
    private int rtpMenuId = -1;
    private int rtpIgnoredMenuId = -1;
    private long rtpMenuSeenAt;
    private int rtpMenuClicks;
    private long rtpLastMenuClickAt;
    private boolean rtpMenuClicked;

    public RemoteActivityModule() {
        super(new Category(AddonCategories.BASEFINDING), "Donut Remote Activity Map",
            "Scores remote chunk updates, sounds, entities, and light packets; the result is activity evidence, not proof of a player.");
        this.runInMainMenu = true;
        this.autoRtp.onChanged(enabled -> {
            if (enabled && this.isActive()) this.startRtpLoop();
            else this.stopRtpLoop();
        });
    }

    @Override public void onActivate() {
        this.world = this.mc.level;
        this.knownBaseWorld = null;
        if (this.usableWorld(this.world)) {
            this.knownBaseStore().selectCurrent(this.mc, this.world);
            this.knownBaseWorld = this.world;
        }
        this.entryGuard.reset();
        this.alerted.clear();
        this.alertedLight.clear();
        this.shownLight = List.of();
        this.shown = List.of();
        if (this.autoRtp.get()) this.startRtpLoop();
    }
    @Override public void onDeactivate() {
        this.stopRtpLoop();
        this.rtpLoop.forgetCooldown();
        this.autoRtp.set(false);
        this.entryGuard.reset();
        this.shown = List.of();
        this.shownLight = List.of();
        this.alerted.clear();
        this.alertedLight.clear();
        this.world = null;
        this.knownBaseWorld = null;
    }

    /** Clears presentation and automation state at a search-session boundary without toggling the module. */
    public void endSearchSession() {
        this.stopRtpLoop();
        this.autoRtp.set(false);
        this.entryGuard.reset();
        this.shown = List.of();
        this.shownLight = List.of();
        this.alerted.clear();
        this.alertedLight.clear();
        this.world = null;
        this.knownBaseWorld = null;
        this.lastAlertAt = 0L;
    }

    @EventHandler private void onJoined(GameJoinedEvent event) {
        this.world = this.mc.level;
        this.knownBaseWorld = null;
        if (this.usableWorld(this.world)) {
            this.knownBaseStore().selectCurrent(this.mc, this.world);
            this.knownBaseWorld = this.world;
        }
        this.entryGuard.reset();
        this.alerted.clear();
        this.alertedLight.clear();
        this.shownLight = List.of();
        this.shown = List.of();
    }
    @EventHandler private void onLeft(GameLeftEvent event) {
        this.shown = List.of();
        this.shownLight = List.of();
        this.alerted.clear();
        this.alertedLight.clear();
        this.stopRtpLoop();
        this.world = null;
        this.knownBaseWorld = null;
    }

    @EventHandler
    private void onRtpPacketReceived(PacketEvent.Receive event) {
        if (!this.autoRtp.get() || !this.rtpLoop.running()) return;
        Packet<?> packet = event.packet;
        long sessionGeneration = this.rtpSessionGeneration;
        Connection packetConnection = this.rtpConnection;
        ClientLevel packetWorld = this.mc.level;
        if (packet instanceof ClientboundSystemChatPacket chat) {
            String raw = chat.content().getString();
            boolean chatLine = !chat.overlay();
            this.mc.execute(() -> {
                if (this.isCurrentRtpCallback(sessionGeneration, packetConnection, packetWorld)) this.onRtpServerText(raw, chatLine);
            });
        } else if (packet instanceof ClientboundSetTitleTextPacket title) {
            String raw = title.text().getString();
            this.mc.execute(() -> {
                if (this.isCurrentRtpCallback(sessionGeneration, packetConnection, packetWorld)) this.onRtpServerText(raw, false);
            });
        } else if (packet instanceof ClientboundSetSubtitleTextPacket subtitle) {
            String raw = subtitle.text().getString();
            this.mc.execute(() -> {
                if (this.isCurrentRtpCallback(sessionGeneration, packetConnection, packetWorld)) this.onRtpServerText(raw, false);
            });
        } else if (packet instanceof ClientboundDamageEventPacket damage) {
            this.mc.execute(() -> {
                if (this.isCurrentRtpCallback(sessionGeneration, packetConnection, packetWorld)) this.onRtpDamage(damage);
            });
        }
    }

    @EventHandler
    private void onRtpPacketSent(PacketEvent.Send event) {
        if (!this.autoRtp.get() || !this.rtpLoop.running() || this.sendingRtpCommand) return;
        Packet<?> packet = event.packet;
        if (packet instanceof ServerboundUseItemOnPacket
            || packet instanceof ServerboundChatCommandPacket
            || packet instanceof ServerboundChatCommandSignedPacket
            || packet instanceof ServerboundInteractPacket interact && isRtpInteraction(interact)) {
            this.rtpUserActionAt = System.currentTimeMillis();
        }
    }

    @EventHandler
    private void onTick(Post event) {
        ClientLevel level = this.mc.level;
        boolean worldChanged = level != this.world;
        if (worldChanged) {
            this.world = level;
            this.knownBaseWorld = null;
            this.alerted.clear();
            this.alertedLight.clear();
            this.entryGuard.reset();
        }
        if (level == null || this.mc.player == null || this.mc.player.level() != level || this.mc.getConnection() == null) {
            if (this.autoRtp.get()) this.tickAutoRtp(System.currentTimeMillis(), level != this.rtpWorld, this.rtpLastEvidenceNanos);
            return;
        }
        if (this.knownBaseWorld != level) {
            this.knownBaseStore().selectCurrent(this.mc, level);
            this.knownBaseWorld = level;
        }
        long now = System.nanoTime();
        long rtpNow = System.currentTimeMillis();
        long age = this.memorySeconds.get() * 1_000_000_000L;
        double maxDistanceSq = (double) this.range.get() * this.range.get();
        List<Entry> signals = RemoteActivityState.LEDGER.snapshot(now, age);
        int viewDistance = Math.min(this.mc.options.renderDistance().get(), Math.max(1, this.range.get() / 16));
        int simulationDistance = Math.min(this.mc.options.simulationDistance().get(), Math.max(1, viewDistance - 3));
        int ringStart = simulationDistance + 2 + this.ringMargin.get();
        Set<RemoteActivityLedger.Key> frozenChunks = Set.of();
        if (this.suppressFrozen.get()) {
            Set<RemoteActivityLedger.Key> visiblePlayerChunks = new HashSet<>();
            for (var other : level.players()) {
                visiblePlayerChunks.add(new RemoteActivityLedger.Key(other.chunkPosition().x(), other.chunkPosition().z()));
            }
            List<RemoteFrozenWitnessTracker.Witness> witnesses = new ArrayList<>();
            for (var entity : level.entitiesForRendering()) {
                if (!(entity instanceof PathfinderMob mob)) continue;
                boolean eligible = !(mob instanceof Shulker) && !mob.isNoAi() && !mob.isVehicle()
                    && !mob.isPassenger() && !mob.isSleeping();
                witnesses.add(new RemoteFrozenWitnessTracker.Witness(entity.getId(), entity.chunkPosition().x(),
                    entity.chunkPosition().z(), eligible));
            }
            frozenChunks = RemoteActivityState.FROZEN.update(witnesses, this.mc.player.chunkPosition().x(),
                this.mc.player.chunkPosition().z(), ringStart, viewDistance, now,
                this.frozenSeconds.get() * 1_000_000_000L, visiblePlayerChunks);
        } else {
            RemoteActivityState.FROZEN.update(List.of(), this.mc.player.chunkPosition().x(),
                this.mc.player.chunkPosition().z(), ringStart, viewDistance, now, Long.MAX_VALUE, Set.of());
        }
        long frozenAge = this.suppressFrozen.get() ? this.frozenSeconds.get() * 1_000_000_000L : age;
        List<Hotspot> candidates = RemoteActivityInference.infer(signals, this.mc.player.chunkPosition().x(),
            this.mc.player.chunkPosition().z(), simulationDistance, viewDistance, this.ringMargin.get(),
            Math.max(this.minimumScore.get(), 8.0 * Math.pow(2.0, (5.0 - this.sensitivity.get()) / 2.5)),
            this.minimumSources.get(), now, frozenAge, frozenChunks);
        candidates = candidates.stream().filter(candidate -> this.distanceToChunk(candidate.key().chunkX(), candidate.key().chunkZ()) <= maxDistanceSq).toList();
        if (candidates.size() > MAX_RENDERED) candidates = candidates.subList(0, MAX_RENDERED);
        this.shown = List.copyOf(candidates);
        for (Hotspot candidate : this.shown) this.handleQualified(candidate, now);
        this.shownLight = RemoteActivityState.LIGHT.snapshot(now, age).stream()
            .filter(entry -> this.distanceToChunk(entry.chunkX(), entry.chunkZ()) <= maxDistanceSq)
            .limit(MAX_RENDERED).toList();
        RemoteKnownBaseStore knownBases = this.knownBaseStore();
        knownBases.tickAutosave();
        int playerChunkX = this.mc.player.chunkPosition().x();
        int playerChunkZ = this.mc.player.chunkPosition().z();
        this.entryGuard.update(ChunkPos.pack(playerChunkX, playerChunkZ), key ->
            level.getChunkSource().hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key)), key ->
            RemoteActivityState.LIGHT.isFlaggedOrHeld(key, level.getSectionsCount(), level.getMinSectionY()), knownBases);
        long evidenceNanos = latestEvidenceNanos(signals, this.shownLight);
        for (RemoteLightTracker.ChunkState state : this.shownLight) {
            long key = ChunkPos.pack(state.chunkX(), state.chunkZ());
            if (this.alertedLight.add(key)) this.handleLightQualified(state);
        }
        while (this.alertedLight.size() > 4096) this.alertedLight.remove(this.alertedLight.iterator().next());
        if (this.autoRtp.get()) this.tickAutoRtp(rtpNow, level != this.rtpWorld, evidenceNanos);
        else if (this.rtpLoop.running()) this.stopRtpLoop();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (this.mc.player == null || this.shown.isEmpty() && this.shownLight.isEmpty()) return;
        SettingColor selected = this.markerColor.get();
        int alpha = Math.min(selected.a, this.opacity.get());
        Color fill = new Color(selected.r, selected.g, selected.b, alpha);
        Color line = new Color(selected.r, selected.g, selected.b, Math.max(alpha, 170));
        Set<Long> lightKeys = new HashSet<>();
        int worldBottom = this.mc.level.getMinY();
        int worldTop = this.mc.level.getMaxY();
        for (RemoteLightTracker.ChunkState state : this.shownLight) {
            int x = state.chunkX() << 4;
            int z = state.chunkZ() << 4;
            boolean suspect = state.evidence() == Evidence.SUSPECT_DARK || state.evidence() == Evidence.SUSPECT_AIRLESS;
            Color lightLine = suspect ? new Color(255, 80, 80, Math.max(alpha, 170)) : new Color(100, 165, 255, Math.max(alpha, 170));
            Color lightFill = suspect ? new Color(255, 80, 80, alpha) : new Color(100, 165, 255, alpha);
            event.renderer.box(x, worldBottom, z, x + 16, worldTop, z + 16, lightFill, lightLine, ShapeMode.Both, 0);
            if (this.tracers.get()) event.renderer.line(this.mc.player.getX(), this.mc.player.getEyeY(), this.mc.player.getZ(), x + 8, 64, z + 8, lightLine);
            lightKeys.add(ChunkPos.pack(state.chunkX(), state.chunkZ()));
        }
        for (Hotspot entry : this.shown) {
            if (lightKeys.contains(ChunkPos.pack(entry.key().chunkX(), entry.key().chunkZ()))) continue;
            int x = entry.key().chunkX() << 4;
            int z = entry.key().chunkZ() << 4;
            event.renderer.box(x, worldBottom, z, x + 16, worldTop, z + 16, fill, line, ShapeMode.Both, 0);
            if (this.tracers.get()) event.renderer.line(this.mc.player.getX(), this.mc.player.getEyeY(), this.mc.player.getZ(), x + 8, 64, z + 8, line);
        }
    }

    public List<Hotspot> shownEntries() { return this.shown; }

    private void handleQualified(Hotspot entry, long now) {
        if (!this.alerted.add(entry.key())) return;
        int x = (entry.key().chunkX() << 4) + 8;
        int z = (entry.key().chunkZ() << 4) + 8;
        if (this.notifications.get()) {
            String location = this.showCoordinates.get() ? " near " + x + ", " + z : "";
            ChatUtils.sendMsg(Component.literal("Remote activity estimate" + location + "; " + entry.sourceChunks() + " remote source chunks, score " + String.format(Locale.ROOT, "%.1f", entry.score())));
        }
        if (this.sound.get()) this.mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.AMETHYST_CLUSTER_STEP, 0.8F, 1.0F));
        if (this.autoDisconnect.get() && this.mc.getConnection() != null && this.mc.getConnection().getConnection().isConnected()) {
            this.mc.getConnection().getConnection().disconnect(Component.literal("[Donut Remote Activity Map] Remote activity signal" + (this.showCoordinates.get() ? " near " + x + ", " + z : "")));
            return;
        }
        this.lastAlertAt = now;
    }

    private void handleLightQualified(RemoteLightTracker.ChunkState state) {
        int x = (state.chunkX() << 4) + 8;
        int z = (state.chunkZ() << 4) + 8;
        String type = state.evidence() == Evidence.BASE_SIGNAL ? "Base light signal" : "Suspect dark-section signal";
        if (this.notifications.get()) {
            String location = this.showCoordinates.get() ? " near " + x + ", " + z : "";
            ChatUtils.sendMsg(Component.literal(type + location + (state.observedUnderground() ? " (observed underground)" : "")));
        }
        if (this.sound.get()) this.mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.AMETHYST_CLUSTER_STEP, 0.8F, 1.0F));
        if (this.autoDisconnect.get() && this.mc.getConnection() != null && this.mc.getConnection().getConnection().isConnected()) {
            this.mc.getConnection().getConnection().disconnect(Component.literal("[Donut Remote Activity Map] " + type + (this.showCoordinates.get() ? " near " + x + ", " + z : "")));
        }
    }

    private void startRtpLoop() {
        if (this.rtpLoop.running()) return;
        if (this.mc.player == null || this.mc.level == null || this.mc.getConnection() == null
            || !this.mc.getConnection().getConnection().isConnected()) {
            this.autoRtp.set(false);
            ChatUtils.sendMsg(Component.literal("Join a server before enabling Auto RTP."));
            return;
        }
        this.rtpSessionGeneration++;
        long now = System.currentTimeMillis();
        RemoteActivityRtpLoop.Config config = this.rtpConfig();
        this.rtpLoop.start(now, config);
        this.rtpConnection = this.mc.getConnection().getConnection();
        this.rtpWorld = this.mc.level;
        this.rtpAnchorX = this.mc.player.getX();
        this.rtpAnchorY = this.mc.player.getY();
        this.rtpAnchorZ = this.mc.player.getZ();
        this.rtpStartChunkX = this.mc.player.chunkPosition().x();
        this.rtpStartChunkZ = this.mc.player.chunkPosition().z();
        this.rtpWasDead = this.mc.player.isDeadOrDying();
        this.rtpOfflineSince = 0L;
        this.rtpCommandSentAt = Long.MIN_VALUE;
        this.rtpUserActionAt = Long.MIN_VALUE;
        this.rtpNoticedCooldownUntil = 0L;
        this.rtpLastTryLanded = false;
        this.rtpLastProgress = "";
        this.rtpLastProgressAt = 0L;
        this.rtpLastEvidenceNanos = currentEvidenceRevision();
        this.resetRtpMenu();
        this.rememberRtpSuspects(now);
        String command = this.rtpCommand();
        String message = "Auto RTP started: /" + command + ", " + secondsText(config.scanMs()) + " scan per spot, at least "
            + secondsText(config.delayMs()) + " between RTPs" + (config.maxRtps() > 0 ? ", max " + config.maxRtps() : "") + ".";
        ChatUtils.sendMsg(Component.literal(message));
    }

    private void tickAutoRtp(long now, boolean levelChanged, long evidenceNanos) {
        if (!this.rtpLoop.running()) return;
        var connection = this.mc.getConnection();
        if (this.rtpConnection == null || !this.rtpConnection.isConnected()
            || connection != null && connection.getConnection() != this.rtpConnection) {
            this.stopRtpWithMessage("Disconnected, Auto RTP stopped.");
            return;
        }
        if (this.mc.player == null || this.mc.level == null || connection == null || this.mc.gameMode == null) {
            if (this.rtpOfflineSince == 0L) this.rtpOfflineSince = now;
            if (now - this.rtpOfflineSince > 30_000L) this.stopRtpWithMessage("No world for 30s, Auto RTP stopped.");
            return;
        }

        this.rtpOfflineSince = 0L;
        RemoteActivityRtpLoop.Config config = this.rtpConfig();
        if (evidenceNanos > this.rtpLastEvidenceNanos) {
            this.rtpLastEvidenceNanos = evidenceNanos;
            this.rtpLoop.dataArrived(now);
        }

        if (this.mc.player.isDeadOrDying()) {
            if (!this.rtpWasDead) {
                this.rtpWasDead = true;
                this.rtpLoop.interrupt(now, config);
                this.resetRtpMenu();
            }
            this.rtpLoop.holdUntil(now + 3_000L);
            this.progressRtp(now, "you died", config);
            return;
        }

        if (this.rtpWasDead) {
            this.rtpWasDead = false;
            this.rtpWorld = this.mc.level;
            this.setRtpAnchor(this.mc.player);
            this.rtpLoop.holdUntil(now + 3_000L);
        }

        levelChanged = levelChanged || this.mc.level != this.rtpWorld;
        if (levelChanged) {
            this.rtpWorld = this.mc.level;
            this.rtpKnownSuspects.clear();
        }
        boolean moved = this.rtpAnchorMoved();
        if (levelChanged || moved) {
            switch (this.rtpLoop.phase()) {
                case TELEPORTING -> this.rtpLanded(now, config);
                case SCANNING -> {
                    this.setRtpAnchor(this.mc.player);
                    this.rtpLoop.relanded(now, config);
                }
                default -> {
                    if (this.rtpLoop.landedLate(now, config)) {
                        this.setRtpAnchor(this.mc.player);
                        this.closeRtpMenu();
                        this.resetRtpMenu();
                    } else if (levelChanged) {
                        this.stopRtpWithMessage("World changed, Auto RTP stopped. Turn Auto RTP off and on to start again.");
                        return;
                    }
                }
            }
        }

        if (this.stopOnSuspect.get() && !levelChanged) {
            RemoteLightTracker.ChunkState suspect = this.newRtpSuspectNear(now);
            if (suspect != null) {
                this.foundRtpSuspect(suspect);
                return;
            }
        }

        if (!this.handleRtpMenu(now, config)) {
            String pause = this.mc.gui.screen() != null && !this.ourRtpMenuOpen() ? "a menu is open" : null;
            if (pause != null) this.rtpLoop.holdUntil(now + 1_000L);
            switch (this.rtpLoop.tick(now, config)) {
                case SEND -> this.sendRtp(now);
                case TIMED_OUT -> {
                    this.rtpLastTryLanded = false;
                    this.closeRtpMenu();
                }
                case SCAN_CLEAR -> this.rtpLastTryLanded = true;
                case LIMIT -> {
                    this.stopRtpWithMessage("Max RTPs reached (" + config.maxRtps() + "), no base found. Auto RTP stopped. Turn Auto RTP off and on to start again.");
                    return;
                }
                case GAVE_UP -> {
                    this.closeRtpMenu();
                    this.stopRtpWithMessage("No teleport after 5 tries; check RTP Command and RTP Menu Item. Auto RTP stopped. Turn Auto RTP off and on to start again.");
                    return;
                }
                default -> { }
            }
            this.progressRtp(now, pause, config);
        }
    }

    private void sendRtp(long now) {
        String command = this.rtpCommand();
        if (command.isBlank() || command.length() > 48 || command.chars().anyMatch(Character::isISOControl)) command = "rtp";
        this.setRtpAnchor(this.mc.player);
        this.resetRtpMenu();
        this.rememberRtpSuspects(now);
        this.rtpCommandSentAt = now;
        this.sendingRtpCommand = true;
        try {
            this.mc.getConnection().sendCommand(command);
        } finally {
            this.sendingRtpCommand = false;
        }
    }

    private void rtpLanded(long now, RemoteActivityRtpLoop.Config config) {
        this.setRtpAnchor(this.mc.player);
        this.closeRtpMenu();
        this.resetRtpMenu();
        this.rtpLoop.landed(now, config);
    }

    private void stopRtpLoop() {
        this.rtpSessionGeneration++;
        this.rtpLoop.stop();
        this.rtpConnection = null;
        this.rtpWorld = null;
        this.rtpAnchorX = 0.0;
        this.rtpAnchorY = 0.0;
        this.rtpAnchorZ = 0.0;
        this.rtpCommandSentAt = Long.MIN_VALUE;
        this.rtpUserActionAt = Long.MIN_VALUE;
        this.rtpOfflineSince = 0L;
        this.rtpKnownSuspects.clear();
        this.resetRtpMenu();
    }

    /** Permanently stops session automation and closes base persistence at addon shutdown. */
    public void shutdown() {
        this.stopRtpLoop();
        this.rtpLoop.forgetCooldown();
        this.autoRtp.set(false);
        this.entryGuard.reset();
        if (this.knownBaseStore != null) this.knownBaseStore.close();
    }

    /** Handles only a menu opened by the latest RTP command; other containers are ignored. */
    private boolean handleRtpMenu(long now, RemoteActivityRtpLoop.Config config) {
        if (this.rtpLoop.phase() != RemoteActivityRtpLoop.Phase.TELEPORTING || !this.rtpContainerOpen()) return false;
        var menu = this.mc.player.containerMenu;
        if (menu.containerId != this.rtpMenuId) {
            if (menu.containerId == this.rtpIgnoredMenuId) return false;
            if (!this.rtpMenuMayBeOurs(now)) {
                this.rtpIgnoredMenuId = menu.containerId;
                return false;
            }
            if (this.rtpMenuClicks >= 3) {
                this.stopRtpWithMessage("The RTP menu keeps opening. Auto RTP stopped. Turn Auto RTP off and on to start again.");
                return true;
            }
            this.rtpMenuId = menu.containerId;
            this.rtpMenuSeenAt = now;
            this.rtpMenuClicked = false;
            this.rtpLoop.extend(now, config);
        }
        if (menu.containerId != this.rtpMenuId || this.rtpMenuClicked) return false;
        if (now - this.rtpMenuSeenAt < 300L) return true;

        String wanted = this.rtpMenuItem.get().trim();
        List<String> names = new ArrayList<>();
        int match = this.findRtpMenuSlot(menu, wanted, names);
        boolean loading = now - this.rtpMenuSeenAt < 2_500L;
        if (wanted.isEmpty()) {
            if (names.isEmpty() && loading) return true;
            this.stopRtpWithMessage("The RTP command opened a menu. Set rtp-menu-item to an item name or ID" + menuNames(names) + ". Auto RTP stopped. Turn Auto RTP off and on to start again.");
            return true;
        }
        if (match >= 0) {
            if (this.mc.gameMode == null) return true;
            this.mc.gameMode.handleContainerInput(menu.containerId, match, 0, ContainerInput.PICKUP, this.mc.player);
            this.rtpMenuClicked = true;
            this.rtpMenuClicks++;
            this.rtpLastMenuClickAt = now;
            this.rtpLoop.extend(now, config);
            return false;
        }
        if (loading) return true;
        this.stopRtpWithMessage("No rtp-menu-item match in the RTP menu" + menuNames(names) + ". Auto RTP stopped. Turn Auto RTP off and on to start again.");
        return true;
    }

    private int findRtpMenuSlot(net.minecraft.world.inventory.AbstractContainerMenu menu, String wanted, List<String> names) {
        String normalizedWanted = normalizeMenuText(wanted);
        for (Slot slot : menu.slots) {
            if (slot.container == this.mc.player.getInventory()) break;
            if (slot.getItem().isEmpty()) continue;
            String name = normalizeMenuText(slot.getItem().getHoverName().getString());
            String itemId = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).getPath();
            if (!name.isBlank() && !names.contains(name)) names.add(name);
            if (!normalizedWanted.isBlank() && matchesMenuItem(normalizedWanted, name, itemId)) return slot.index;
        }
        return -1;
    }

    private static String menuNames(List<String> names) {
        if (names.isEmpty()) return "";
        List<String> shown = names.size() > 10 ? names.subList(0, 10) : names;
        return " (items: " + String.join(", ", shown) + (names.size() > shown.size() ? ", ..." : "") + ")";
    }

    private static boolean matchesMenuItem(String wanted, String name, String itemId) {
        for (String part : wanted.split(",")) {
            String target = normalizeMenuText(part);
            if (target.isEmpty()) continue;
            boolean nameMatch = name.contains(target);
            boolean itemIdMatch = itemId.replace('_', ' ').equals(target) || itemId.equals(target.replace(' ', '_'));
            if (nameMatch || itemIdMatch) return true;
        }
        return false;
    }

    private static String normalizeMenuText(String input) {
        return input == null ? "" : input.replaceAll("(?i)§.", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private RemoteActivityRtpLoop.Config rtpConfig() {
        double scan = halfStep(this.rtpScanSeconds.get(), 2.0, 30.0);
        double delay = halfStep(this.rtpDelaySeconds.get(), 3.0, 120.0);
        double timeout = Math.rint(clamp(this.rtpTimeoutSeconds.get(), 3.0, 60.0));
        return new RemoteActivityRtpLoop.Config(Math.round(scan * 1_000.0), Math.round(delay * 1_000.0),
            Math.round(timeout * 1_000.0), this.rtpMax.get());
    }

    private String rtpCommand() {
        String command = this.rtpCommand.get().trim();
        while (command.startsWith("/")) command = command.substring(1).trim();
        return command.isBlank() ? "rtp" : command;
    }

    private void setRtpAnchor(net.minecraft.client.player.LocalPlayer player) {
        this.rtpAnchorX = player.getX();
        this.rtpAnchorY = player.getY();
        this.rtpAnchorZ = player.getZ();
    }

    private boolean rtpAnchorMoved() {
        if (this.mc.player == null) return false;
        double dx = this.mc.player.getX() - this.rtpAnchorX;
        double dy = this.mc.player.getY() - this.rtpAnchorY;
        double dz = this.mc.player.getZ() - this.rtpAnchorZ;
        return dx * dx + dy * dy + dz * dz > 16.0 * 16.0;
    }

    private void resetRtpMenu() {
        this.rtpMenuId = -1;
        this.rtpIgnoredMenuId = -1;
        this.rtpMenuClicked = false;
        this.rtpMenuClicks = 0;
        this.rtpLastMenuClickAt = 0L;
    }

    private boolean rtpContainerOpen() {
        return this.mc.player != null && this.mc.gui.screen() instanceof AbstractContainerScreen<?>
            && this.mc.player.containerMenu != this.mc.player.inventoryMenu;
    }

    private boolean ourRtpMenuOpen() {
        return this.rtpMenuId >= 0 && this.rtpContainerOpen() && this.mc.player.containerMenu.containerId == this.rtpMenuId;
    }

    private boolean rtpMenuMayBeOurs(long now) {
        return this.rtpCommandSentAt != Long.MIN_VALUE && this.rtpUserActionAt < this.rtpCommandSentAt
            && (now - this.rtpCommandSentAt <= 4_000L || this.rtpMenuClicks > 0 && now - this.rtpLastMenuClickAt <= 4_000L);
    }

    private void closeRtpMenu() {
        if (this.mc.player != null && this.rtpMenuId >= 0 && this.mc.player.containerMenu != this.mc.player.inventoryMenu
            && this.mc.player.containerMenu.containerId == this.rtpMenuId) this.mc.player.closeContainer();
    }

    private void rememberRtpSuspects(long now) {
        this.rtpKnownSuspects.clear();
        List<RemoteLightTracker.ChunkState> states = new ArrayList<>(RemoteActivityState.LIGHT.snapshot(nowNanos(now), Long.MAX_VALUE));
        states.addAll(RemoteActivityState.LIGHT.heldSnapshot(nowNanos(now), Long.MAX_VALUE));
        for (RemoteLightTracker.ChunkState state : states) if (isRtpSuspect(state)) this.rtpKnownSuspects.add(ChunkPos.pack(state.chunkX(), state.chunkZ()));
    }

    private RemoteLightTracker.ChunkState newRtpSuspectNear(long now) {
        int radius = this.mc.options.renderDistance().get() + 1;
        int centerX = this.mc.player.chunkPosition().x();
        int centerZ = this.mc.player.chunkPosition().z();
        List<RemoteLightTracker.ChunkState> states = new ArrayList<>(RemoteActivityState.LIGHT.snapshot(nowNanos(now), Long.MAX_VALUE));
        states.addAll(RemoteActivityState.LIGHT.heldSnapshot(nowNanos(now), Long.MAX_VALUE));
        for (RemoteLightTracker.ChunkState state : states) {
            long key = ChunkPos.pack(state.chunkX(), state.chunkZ());
            if (this.rtpKnownSuspects.contains(key) || !isRtpSuspect(state)) continue;
            if (Math.abs(state.chunkX() - centerX) > radius || Math.abs(state.chunkZ() - centerZ) > radius) continue;
            if (this.rtpLoop.landings() == 0 && Math.abs(state.chunkX() - this.rtpStartChunkX) <= radius
                && Math.abs(state.chunkZ() - this.rtpStartChunkZ) <= radius) continue;
            return state;
        }
        return null;
    }

    private static boolean isRtpSuspect(RemoteLightTracker.ChunkState state) {
        return state.evidence() == Evidence.SUSPECT_DARK || state.evidence() == Evidence.SUSPECT_AIRLESS;
    }

    private void foundRtpSuspect(RemoteLightTracker.ChunkState state) {
        int x = (state.chunkX() << 4) + 8;
        int z = (state.chunkZ() << 4) + 8;
        int count = this.rtpLoop.landings() + (this.rtpLoop.phase() == RemoteActivityRtpLoop.Phase.TELEPORTING && this.rtpAnchorMoved() ? 1 : 0);
        String location = this.showCoordinates.get() ? " at " + x + ", " + z : "";
        this.stopRtpWithMessage("Suspect signal found" + location + " after " + count + (count == 1 ? " RTP. " : " RTPs. ")
            + "Auto RTP stopped. Turn Auto RTP off and on to start again.");
        if (this.sound.get()) this.mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.0F));
    }

    private void onRtpServerText(String raw, boolean chatLine) {
        if (!this.autoRtp.get() || !this.rtpLoop.running()) return;
        long now = System.currentTimeMillis();
        boolean teleporting = this.rtpLoop.phase() == RemoteActivityRtpLoop.Phase.TELEPORTING;
        if (!teleporting && !this.rtpLoop.sentWithin(now, 5_000L)) return;
        if (chatLine && RemoteActivityRtpReply.looksLikePlayerChat(raw, otherRtpPlayerNames())) return;
        RemoteActivityRtpReply.Reply reply = RemoteActivityRtpReply.parse(raw);
        boolean fresh = this.rtpLoop.sentWithin(now, 2_500L);
        switch (reply.kind()) {
            case COOLDOWN -> {
                this.rtpLoop.cooldown(now, reply.millis());
                this.noticeRtpCooldown(now, reply.millis());
            }
            case WARMUP -> {
                this.rtpLoop.warmup(now, reply.millis(), this.rtpConfig());
                if (reply.nextUseMs() >= 0L) {
                    this.rtpLoop.nextUseIn(now, reply.nextUseMs());
                    this.noticeRtpCooldown(now, reply.nextUseMs());
                }
            }
            case CANCELLED -> this.rtpLoop.cancelled(now);
            case UNKNOWN_COMMAND -> {
                if (fresh) this.stopRtpWithMessage("The server does not know /" + this.rtpCommand() + ", check RTP Command. Auto RTP stopped. Turn Auto RTP off and on to start again.");
            }
            case NO_PERMISSION -> {
                if (fresh) this.stopRtpWithMessage("No permission for /" + this.rtpCommand() + ". Auto RTP stopped. Turn Auto RTP off and on to start again.");
            }
            case COMBAT -> {
                if (teleporting && fresh) this.stopRtpWithMessage("In combat, Auto RTP stopped. Turn Auto RTP off and on to start again.");
            }
            default -> { }
        }
    }

    private void noticeRtpCooldown(long now, long millis) {
        long until = now + millis;
        if (millis >= 60_000L && until > this.rtpNoticedCooldownUntil + 5_000L) {
            this.rtpNoticedCooldownUntil = until;
            ChatUtils.sendMsg(Component.literal("Server cooldown " + secondsText(millis) + ", Auto RTP waits."));
        }
    }

    private List<String> otherRtpPlayerNames() {
        var connection = this.mc.getConnection();
        if (connection == null) return List.of();
        UUID selfId = this.mc.player == null ? null : this.mc.player.getUUID();
        String selfName = this.mc.player == null ? null : this.mc.player.getGameProfile().name();
        List<String> names = new ArrayList<>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            var profile = info.getProfile();
            if (profile != null && profile.name() != null && !java.util.Objects.equals(profile.id(), selfId)
                && !profile.name().equalsIgnoreCase(selfName)) names.add(profile.name());
        }
        return names;
    }

    private void onRtpDamage(ClientboundDamageEventPacket packet) {
        if (!this.autoRtp.get() || !this.rtpLoop.running() || this.mc.player == null || this.mc.level == null
            || packet.entityId() != this.mc.player.getId()) return;
        Entity cause = packet.sourceCauseId() >= 0 ? this.mc.level.getEntity(packet.sourceCauseId()) : null;
        if (cause == this.mc.player) return;
        boolean byPlayer = cause instanceof Player || packet.sourceType().is(DamageTypes.PLAYER_ATTACK)
            || packet.sourceType().is(DamageTypes.PLAYER_EXPLOSION);
        if (!byPlayer) return;
        String attacker = cause instanceof Player player ? player.getGameProfile().name() : "a player";
        this.stopRtpWithMessage("Hit by " + attacker + ", Auto RTP stopped (combat). Turn Auto RTP off and on to start again.");
    }

    private boolean isCurrentRtpCallback(long sessionGeneration, Connection packetConnection, ClientLevel packetWorld) {
        var connection = this.mc.getConnection();
        return sessionGeneration == this.rtpSessionGeneration && this.autoRtp.get() && this.rtpLoop.running()
            && packetConnection != null && packetConnection == this.rtpConnection
            && connection != null && connection.getConnection() == packetConnection && this.mc.level == packetWorld;
    }

    private boolean usableWorld(ClientLevel level) {
        return level != null && this.mc.player != null && this.mc.player.level() == level && this.mc.getConnection() != null;
    }

    private RemoteKnownBaseStore knownBaseStore() {
        if (this.knownBaseStore == null) this.knownBaseStore = RemoteKnownBaseStore.get();
        return this.knownBaseStore;
    }

    private void progressRtp(long now, String pause, RemoteActivityRtpLoop.Config config) {
        String label = "Auto RTP #" + this.rtpLoop.sent() + (config.maxRtps() > 0 ? "/" + config.maxRtps() : "");
        String text;
        if (pause != null) text = label + " - paused, " + pause;
        else {
            long next = this.rtpLoop.nextSendIn(now);
            text = switch (this.rtpLoop.phase()) {
                case TELEPORTING -> label + " - waiting for the teleport";
                case SCANNING -> label + " - scanning, " + secondsText(this.rtpLoop.scanLeft(now)) + " left";
                case WAITING -> this.rtpLoop.cooldownLeft(now) > 0L
                    ? label + " - server cooldown, next in " + secondsText(next)
                    : this.rtpLoop.landings() == 0 && this.rtpLoop.sent() == 0 ? label + " - starting" + (next >= 1_000L ? " in " + secondsText(next) : "")
                    : !this.rtpLastTryLanded ? label + " - no teleport, retry in " + secondsText(next)
                    : this.rtpLoop.settling(now) ? label + " - no base yet, chunks still loading"
                    : label + " - no base, next in " + secondsText(next);
                default -> "";
            };
        }
        boolean refresh = this.rtpLoop.phase() != RemoteActivityRtpLoop.Phase.TELEPORTING && now - this.rtpLastProgressAt >= 2_000L;
        if (!text.isEmpty() && (!text.equals(this.rtpLastProgress) || refresh)) {
            this.rtpLastProgress = text;
            this.rtpLastProgressAt = now;
            this.mc.execute(() -> {
                if (this.mc.player != null) this.mc.gui.hud.setOverlayMessage(Component.literal(text), false);
            });
        }
    }

    private void stopRtpWithMessage(String message) {
        this.stopRtpLoop();
        ChatUtils.sendMsg(Component.literal(message));
    }

    private static boolean isRtpInteraction(ServerboundInteractPacket packet) {
        return packet.hand() != null || packet.location() != null;
    }

    private long currentEvidenceRevision() {
        long now = System.nanoTime();
        long latest = latestEvidenceNanos(RemoteActivityState.LEDGER.snapshot(now, Long.MAX_VALUE),
            RemoteActivityState.LIGHT.snapshot(now, Long.MAX_VALUE));
        for (RemoteLightTracker.ChunkState state : RemoteActivityState.LIGHT.heldSnapshot(now, Long.MAX_VALUE)) latest = Math.max(latest, state.updatedNanos());
        return latest;
    }

    private static long latestEvidenceNanos(List<Entry> signals, List<RemoteLightTracker.ChunkState> light) {
        long latest = 0L;
        for (Entry signal : signals) latest = Math.max(latest, signal.lastSeenNanos());
        for (RemoteLightTracker.ChunkState state : light) latest = Math.max(latest, state.updatedNanos());
        return latest;
    }

    private static long nowNanos(long nowMillis) {
        return System.nanoTime() + (nowMillis - System.currentTimeMillis()) * 1_000_000L;
    }

    private static double halfStep(double value, double min, double max) {
        return Math.max(min, Math.min(max, Math.round(value * 2.0) / 2.0));
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    private static String secondsText(long millis) {
        long seconds = Math.max(0L, (millis + 999L) / 1_000L);
        return seconds >= 120L ? String.format(Locale.ROOT, "%dm %ds", seconds / 60L, seconds % 60L) : seconds + "s";
    }

    private double distanceToChunk(int chunkX, int chunkZ) {
        double minX = chunkX * 16.0;
        double minZ = chunkZ * 16.0;
        double px = this.mc.player.getX();
        double pz = this.mc.player.getZ();
        double dx = Math.max(0.0, Math.max(minX - px, px - minX - 16.0));
        double dz = Math.max(0.0, Math.max(minZ - pz, pz - minZ - 16.0));
        return dx * dx + dz * dz;
    }

    private static long seconds(double value) { return (long) (Math.max(0.0, value) * 1_000_000_000L); }
}
