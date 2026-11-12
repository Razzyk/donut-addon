package dev.donut.addon.expansion.search.media;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.expansion.search.SearchFeatures;
import dev.donut.addon.hud.region.RegionMapData;
import dev.donut.addon.hud.region.RegionShard;
import dev.donut.addon.mv.compat.events.EventHandler;
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent;
import dev.donut.addon.mv.compat.events.game.GameLeftEvent;
import dev.donut.addon.mv.compat.events.packets.PacketEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.systems.modules.Category;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.player.ChatUtils;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

/** Donut roster heuristic for media accounts that are absent from the visible tab list. */
public final class DonutMediaRosterModule extends Module {
    private static final String MEDIA_TEAM_PREFIX = "09-";
    private static final String CAMERA_MARK = "📹";
    private static final long RTP_WINDOW_NANOS = 60_000_000_000L;

    private final Setting<Boolean> reportEmpty = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("report-empty").description("Report when the current shard has no matching media accounts.").defaultValue(true).build()
    );
    private final Setting<Boolean> statusPanel = this.settings.getDefaultGroup().add(
        new BoolSetting.Builder().name("status-panel").description("Show the current shard and matching names on screen.").defaultValue(true).build()
    );
    private final Setting<Integer> maximumNames = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("maximum-names").description("Maximum names listed in chat or the status panel.")
            .defaultValue(8).range(1, 8).sliderRange(1, 8).build()
    );
    private final Setting<Double> panelScale = this.settings.getDefaultGroup().add(
        new DoubleSetting.Builder().name("scale").description("Status panel scale.").defaultValue(1.0).range(0.5, 3.0).sliderRange(0.5, 3.0).build()
    );
    private final Setting<Integer> panelOpacity = this.settings.getDefaultGroup().add(
        new IntSetting.Builder().name("opacity").description("Status panel background opacity.").defaultValue(150).range(0, 255).sliderRange(0, 255).build()
    );

    private final TreeSet<String> proven = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final TreeSet<String> current = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final TreeSet<String> previous = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    private final ArrayBlockingQueue<UUID> modeUpdates = new ArrayBlockingQueue<>(2048);
    private double anchorX;
    private double anchorZ;
    private String shardLabel = "—";
    private long rtpAt;
    private int settleTicks;
    private int scanTicks;
    private boolean initialReportPending;
    private boolean afterRtp;

    public DonutMediaRosterModule() {
        super(new Category(AddonCategories.BASEFINDING), "Donut Media Roster",
            "Reports media-team account names found on the current Donut shard after arrival or /rtp.");
        this.statusPanel.onChanged(ignored -> SearchFeatures.syncMediaHud());
    }

    @Override public void onActivate() { this.resetArrival(); SearchFeatures.syncMediaHud(); }
    @Override public void onDeactivate() { this.clear(); SearchFeatures.syncMediaHud(); }

    @EventHandler private void onJoined(GameJoinedEvent event) { this.resetArrival(); }
    @EventHandler private void onLeft(GameLeftEvent event) { this.clear(); }

    @EventHandler
    private void onOutgoing(PacketEvent.Send event) {
        String command = null;
        if (event.packet instanceof ServerboundChatCommandPacket packet) command = packet.command();
        else if (event.packet instanceof ServerboundChatPacket packet) command = packet.message();
        if (command == null) return;
        String text = command.trim().toLowerCase(Locale.ROOT);
        if (text.equals("rtp") || text.startsWith("rtp ") || text.equals("/rtp") || text.startsWith("/rtp ")) {
            this.rtpAt = System.nanoTime();
        }
    }

    @EventHandler
    private void onIncoming(PacketEvent.Receive event) {
        if (!(event.packet instanceof ClientboundPlayerInfoUpdatePacket info)) return;
        Set<ClientboundPlayerInfoUpdatePacket.Action> actions = info.actions();
        boolean updatesMode = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE);
        boolean addsPlayer = actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER);
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : info.entries()) {
            UUID profileId = entry.profileId();
            if (!MediaRosterClassifier.shouldQueueGameModeUpdate(updatesMode, addsPlayer, profileId)) continue;
            this.modeUpdates.offer(profileId);
        }
    }

    private void drainModeUpdates(ClientPacketListener connection) {
        UUID profileId;
        while ((profileId = this.modeUpdates.poll()) != null) {
            var playerInfo = connection.getPlayerInfo(profileId);
            if (playerInfo == null || playerInfo.getProfile() == null) continue;
            PlayerTeam team = this.mc.level.getScoreboard().getPlayersTeam(playerInfo.getProfile().name());
            if (team != null && isMediaTeam(team)) this.proven.add(playerInfo.getProfile().name());
        }
    }

    @EventHandler
    private void onTick(Post event) {
        if (this.mc.player == null || this.mc.level == null || this.mc.getConnection() == null) return;
        this.drainModeUpdates(this.mc.getConnection());
        double dx = this.mc.player.getX() - this.anchorX;
        double dz = this.mc.player.getZ() - this.anchorZ;
        if (dx * dx + dz * dz >= 128.0 * 128.0) this.arriveAtCurrentPosition();
        this.shardLabel = shardAt(this.mc.player.getX(), this.mc.player.getZ());
        if (this.settleTicks > 0) { this.settleTicks--; return; }
        if (++this.scanTicks % 10 != 0 && !this.initialReportPending) return;
        Set<String> found = this.scanRoster();
        if (this.initialReportPending) {
            this.initialReportPending = false;
            this.previous.clear();
            this.previous.addAll(found);
            this.report(found);
            return;
        }
        TreeSet<String> joined = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        joined.addAll(found);
        joined.removeAll(this.previous);
        this.previous.clear();
        this.previous.addAll(found);
        if (!joined.isEmpty()) this.announce(joined, false);
    }

    public boolean statusPanelEnabled() { return this.statusPanel.get(); }
    public double panelScale() { return Math.max(0.5, Math.min(3.0, this.panelScale.get())); }
    public int panelOpacity() { return Math.max(0, Math.min(255, this.panelOpacity.get())); }

    public List<String> panelLines() {
        List<String> names = this.limit(this.current);
        String region = this.shardLabel.equals("—") ? "outside shard map" : this.shardLabel;
        if (this.settleTicks > 0) return List.of("Donut Media · " + region, "Checking…");
        if (names.isEmpty()) return List.of("Donut Media", region, "No media accounts found");
        List<String> lines = new ArrayList<>();
        lines.add("Donut Media · " + region);
        lines.addAll(names);
        if (this.current.size() > names.size()) lines.add("+" + (this.current.size() - names.size()) + " more");
        return List.copyOf(lines);
    }

    private Set<String> scanRoster() {
        TreeSet<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        String self = this.mc.player.getGameProfile().name();
        for (Team team : this.mc.level.getScoreboard().getPlayerTeams()) {
            if (!(team instanceof PlayerTeam playerTeam) || !isMediaTeam(playerTeam)) continue;
            for (String name : playerTeam.getPlayers()) {
                boolean listed = this.mc.getConnection().getPlayerInfo(name) != null;
                if (!MediaRosterClassifier.shouldInclude(name, self, listed, this.proven.contains(name))) continue;
                result.add(name);
            }
        }
        this.current.clear();
        this.current.addAll(result);
        return result;
    }

    private void report(Set<String> found) {
        this.afterRtp = this.rtpAt != 0L && System.nanoTime() - this.rtpAt < RTP_WINDOW_NANOS;
        this.rtpAt = 0L;
        if (found.isEmpty()) {
            if (this.reportEmpty.get()) ChatUtils.sendMsg(net.minecraft.network.chat.Component.literal("No media accounts on " + this.shardLabel + "."));
            return;
        }
        this.announce(found, true);
    }

    private void announce(Collection<String> names, boolean summary) {
        List<String> visible = this.limit(names);
        String joined = String.join(", ", visible);
        String message = summary
            ? visible.size() + (visible.size() == 1 ? " media account on " : " media accounts on ") + this.shardLabel + ": " + joined
            : joined + " joined " + this.shardLabel + ".";
        ChatUtils.sendMsg(net.minecraft.network.chat.Component.literal(message));
        if (summary && this.afterRtp) {
            this.mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING, 1.2F));
        }
    }

    private List<String> limit(Collection<String> names) {
        return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).limit(this.maximumNames.get()).toList();
    }

    private void arriveAtCurrentPosition() {
        this.anchorX = this.mc.player.getX();
        this.anchorZ = this.mc.player.getZ();
        this.shardLabel = shardAt(this.anchorX, this.anchorZ);
        this.current.clear();
        this.previous.clear();
        this.proven.clear();
        this.scanTicks = 0;
        this.settleTicks = 60;
        this.initialReportPending = true;
        this.modeUpdates.clear();
    }

    private void resetArrival() {
        this.clear();
        if (this.mc.player != null) this.arriveAtCurrentPosition();
        else { this.settleTicks = 60; this.initialReportPending = true; }
    }

    private void clear() {
        this.proven.clear();
        this.current.clear();
        this.previous.clear();
        this.modeUpdates.clear();
        this.shardLabel = "—";
        this.rtpAt = 0L;
        this.settleTicks = 0;
        this.scanTicks = 0;
        this.initialReportPending = false;
        this.afterRtp = false;
    }

    private static boolean isMediaTeam(PlayerTeam team) {
        return team.getName().startsWith(MEDIA_TEAM_PREFIX) || team.getPlayerPrefix().getString().contains(CAMERA_MARK);
    }

    private static String shardAt(double x, double z) {
        double[] point = RegionMapData.projectWorldPosition(x, z, RegionMapData.GRID_SIZE);
        int column = Math.max(0, Math.min(RegionMapData.GRID_SIZE - 1, (int) point[0]));
        int row = Math.max(0, Math.min(RegionMapData.GRID_SIZE - 1, (int) point[1]));
        RegionShard shard = RegionMapData.cells[row][column];
        if (shard == null) return "outside shard map";
        return shard.getRegion() + "-" + shard.getNumber();
    }
}
