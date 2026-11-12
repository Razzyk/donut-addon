package dev.donut.addon.expansion.search.seed;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.mv.compat.events.EventHandler;
import dev.donut.addon.mv.compat.events.render.Render3DEvent;
import dev.donut.addon.mv.compat.events.world.ChunkDataEvent;
import dev.donut.addon.mv.compat.events.world.StorageBlocksChangedEvent;
import dev.donut.addon.mv.compat.events.world.StorageChunkUnloadedEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent;
import dev.donut.addon.mv.compat.renderer.ShapeMode;
import dev.donut.addon.mv.compat.settings.*;
import dev.donut.addon.mv.compat.systems.modules.Category;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.player.ChatUtils;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import java.lang.reflect.Modifier;
import java.util.*;

/** Coordinates isolated generation, bounded main-thread capture and revision-checked verdicts. */
public final class DonutSeedInspector extends Module {
    private final Setting<String> seed = settings.getDefaultGroup().add(new StringSetting.Builder()
        .name("seed").description("World seed; text uses Minecraft's seed hash.").defaultValue("").build());
    private final Setting<Integer> radius = settings.getDefaultGroup().add(new IntSetting.Builder()
        .name("scan-radius").description("Loaded chunk radius, capped by render distance.")
        .defaultValue(5).range(1, 8).sliderRange(1, 8).build());
    private final Setting<Integer> minimumChanges = settings.getDefaultGroup().add(new IntSetting.Builder()
        .name("minimum-changes").description("Minimum comparable changed cells per cluster.")
        .defaultValue(1).range(1, 8).sliderRange(1, 4).build());
    private final Setting<Boolean> tracers = settings.getDefaultGroup().add(new BoolSetting.Builder()
        .name("tracers").defaultValue(true).build());
    private final Setting<Boolean> notifications = settings.getDefaultGroup().add(new BoolSetting.Builder()
        .name("notifications").defaultValue(false).build());
    private final Setting<Boolean> sound = settings.getDefaultGroup().add(new BoolSetting.Builder()
        .name("sound").defaultValue(false).build());
    private final Setting<SettingColor> color = settings.getDefaultGroup().add(new ColorSetting.Builder()
        .name("color").defaultValue(new SettingColor(255, 110, 45, 90)).build());

    private final SeedObservationInbox observations = new SeedObservationInbox(2048);
    private final SeedScanSchedule schedule = new SeedScanSchedule();
    private final SeedVerdictLedger ledger = new SeedVerdictLedger(4096);
    private final SeedEvidenceHealth health = new SeedEvidenceHealth();
    private final SeedGroundEvidence groundHealth = new SeedGroundEvidence();
    private final Set<Long> working = new HashSet<>();
    private final Set<Long> scanned = new HashSet<>();
    private final ArrayDeque<CaptureJob> captures = new ArrayDeque<>();
    private SeedPredictionWorker worker;
    private SeedScanCore.Token token;
    private ClientLevel world;
    private long session;
    private long tick;
    private String seedText;
    private int seedAge;
    private int previousThreshold;
    private boolean halted;
    private boolean seedNotice;
    private boolean heightNotice;
    private record CaptureJob(long chunk, SeedSnapshotCapture capture, int[] modelHeights, SeedGroundCapture ground,
                              Map<Long, int[]> motionTops, int replayPass, SeedFeatureTrace.Trace featureTrace) {
        private CaptureJob {
            Map<Long, int[]> copy = new HashMap<>();
            motionTops.forEach((key, value) -> copy.put(key, value.clone()));
            motionTops = Map.copyOf(copy);
        }
        @Override public Map<Long, int[]> motionTops() {
            Map<Long, int[]> copy = new HashMap<>();
            motionTops.forEach((key, value) -> copy.put(key, value.clone()));
            return Map.copyOf(copy);
        }
    }

    public DonutSeedInspector() {
        super(new Category(AddonCategories.BASEFINDING), "Donut Seed Inspector",
            "Best-effort seed-based stone-cluster checks. Set a seed first; background scans may take time. Unknown evidence is withheld.");
    }

    @Override public void onActivate() { endSeedSession(); }
    @Override public void onDeactivate() { endSeedSession(); }

    /** Also called by the always-on native lifecycle listener, including out-of-world disables. */
    public void seedLifecycleTick(boolean ready) {
        if ((!isActive() || !ready) && (world != null || worker != null)) endSeedSession();
    }

    public void endSeedSession() {
        observations.begin(null);
        if (worker != null) worker.close();
        worker = null;
        token = null;
        world = null;
        session++;
        tick = 0;
        seedText = null;
        seedAge = 0;
        halted = seedNotice = heightNotice = false;
        clearEvidence();
    }

    private void clearEvidence() {
        schedule.clear(); ledger.clear(); health.reset(); groundHealth.clear();
        working.clear(); scanned.clear(); captures.clear();
    }

    @EventHandler private void chunkArrived(ChunkDataEvent event) {
        if (event.chunk() == null) return;
        var p = event.chunk().getPos();
        observations.offer(observations.token(), p.x(), p.z(), false, false);
    }

    @EventHandler private void chunkLeft(StorageChunkUnloadedEvent event) {
        observations.offer(observations.token(), event.pos().x(), event.pos().z(), false, true);
    }

    @EventHandler private void appliedBlocks(StorageBlocksChangedEvent event) {
        var current = observations.token();
        Set<Long> chunks = new HashSet<>();
        for (var p : event.changes().keySet()) {
            long key = SeedScanCore.packChunk(p.getX() >> 4, p.getZ() >> 4);
            if (chunks.add(key)) observations.offer(current, p.getX() >> 4, p.getZ() >> 4, true, false);
        }
    }

    @EventHandler private void advance(TickEvent.Post ignored) {
        var level = mc.level;
        if (level == null || mc.player == null || mc.player.level() != level || mc.getConnection() == null) {
            seedLifecycleTick(false);
            return;
        }
        if (world != level) { endSeedSession(); world = level; }
        tick++;
        configure(level);
        if (token == null || halted) return;
        processChanges();
        publishEvidence();
        collectPredictions();
        advanceCapture(level);
        requestNearby(level);
    }

    private void configure(ClientLevel level) {
        String requested = seed.get();
        if (!Objects.equals(seedText, requested) || previousThreshold != minimumChanges.get()) {
            if (worker != null) worker.invalidate();
            observations.begin(null);
            token = null;
            clearEvidence();
            seedText = requested;
            previousThreshold = minimumChanges.get();
            seedAge = 0;
            halted = seedNotice = false;
        }
        if (halted || token != null || ++seedAge < 20) return;
        var parsed = SeedScanCore.parseSeed(seedText);
        if (parsed.isEmpty()) {
            if (!seedNotice) notice("Set a world seed before enabling seed inspection.");
            seedNotice = true;
            return;
        }
        if (!SeedScanLimits.supportsHeight(level.getMinY(), level.getHeight())) {
            if (!heightNotice) notice("Seed inspection needs a world spanning Y -64 through 319.");
            heightNotice = true;
            return;
        }
        var hash = biomeSeedHash(level);
        if (hash.isPresent() && hash.getAsLong() != BiomeManager.obfuscateSeed(parsed.getAsLong())) {
            notice("The biome seed hash differs. Inspection will continue; seed-confidence checks may suppress alerts.");
        }
        if (worker == null) worker = new SeedPredictionWorker();
        token = worker.begin(session, parsed.getAsLong());
        observations.begin(token);
    }

    private void processChanges() {
        var batch = observations.drain(128);
        if (batch.overflowed()) {
            // Lost coordinates invalidate every outstanding snapshot before any result is published.
            worker.invalidate();
            clearEvidence();
            token = worker.begin(session, token.seed());
            observations.begin(token);
        }
        for (var change : batch.changes()) {
            int x = chunkX(change.chunk()), z = chunkZ(change.chunk());
            schedule.changed(x, z, tick);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                scanned.remove(SeedScanCore.packChunk(x + dx, z + dz));
            }
        }
    }

    private void publishEvidence() {
        for (var evidence : worker.drainEvidence(2)) {
            working.remove(evidence.chunk());
            if (!token.equals(evidence.token()) || !nearby(evidence.chunk()) || !schedule.unchanged(evidence.revisions()) ||
                !schedule.settled(evidence.chunk(), tick)) continue;
            boolean announced = ledger.get(evidence.chunk()) != null && ledger.get(evidence.chunk()).flagged();
            if (evidence.ground() != null && groundHealth.observe(evidence.chunk(), evidence.ground()))
                notice("Surface terrain differs from the supplied seed. Check the seed; inspection will continue.");
            for (var finding : evidence.findings()) {
                var update = health.observe(finding.owner(), finding.kind(), finding.verdict());
                if (update.seedMismatchNotice()) notice("Most comparable clusters disagree with the seed. Seed alerts are suppressed.");
                if (update.hiddenOreNotice()) notice("Many clusters are hidden by ore obfuscation; seed evidence is uncertain.");
            }
            var result = ledger.reconcile(evidence.chunk(), evidence.findings());
            // Incomplete evidence waits for a chunk/block event instead of regenerating in a hot loop.
            scanned.add(evidence.chunk());
            if (result == null) continue;
            if (result.flagged() && !announced && !health.alertsSuppressed()) announce(evidence.chunk());
        }
    }

    private void collectPredictions() {
        if (captures.size() >= 2) return;
        for (var result : worker.drain(2 - captures.size())) {
            long key = SeedScanCore.packChunk(result.chunkX(), result.chunkZ());
            if (!token.equals(result.token())) continue;
            if (!nearby(key)) { working.remove(key); continue; }
            if (result.value() == null || result.value().error() != null) {
                halted = true;
                observations.begin(null);
                worker.close();
                captures.clear(); working.clear();
                notice("Seed inspection stopped after a generation failure. Change the seed or re-enable to retry.");
                return;
            }
            var modelHeights = result.value().terrainHeights();
            var trace = result.value().featureTrace();
            var reads = trace == null || result.value().replayPass() >= 2 ? List.<Long>of() : trace.readPositions();
            captures.addLast(new CaptureJob(key, new SeedSnapshotCapture(token, result.value().clusters(), reads),
                modelHeights, modelHeights == null ? null : new SeedGroundCapture(schedule.revision(key)),
                result.value().motionTops(), result.value().replayPass(), trace));
        }
    }

    private void advanceCapture(ClientLevel level) {
        if (halted || captures.isEmpty()) return;
        var job = captures.peekFirst();
        if (!nearby(job.chunk())) { captures.removeFirst(); working.remove(job.chunk()); return; }
        if (!schedule.settled(job.chunk(), tick)) return;
        if (job.ground() != null) {
            if (schedule.revision(job.chunk()) != job.ground().revision()) {
                captures.removeFirst(); working.remove(job.chunk()); return;
            }
            if (job.ground().state() != SeedGroundCapture.State.COMPLETE) {
                int baseX = chunkX(job.chunk()) << 4, baseZ = chunkZ(job.chunk()) << 4;
                var groundState = job.ground().step(schedule.revision(job.chunk()), 4096,
                    System.nanoTime() + 2_000_000L, System::nanoTime, (x, y, z) -> {
                        if (level.getChunkSource().getChunk(chunkX(job.chunk()), chunkZ(job.chunk()), ChunkStatus.FULL, false) == null) return null;
                        return SeedBlockKinds.ground(level.getBlockState(new BlockPos(baseX + x, y, baseZ + z)));
                    });
                if (groundState == SeedGroundCapture.State.STALE) { captures.removeFirst(); working.remove(job.chunk()); }
                return;
            }
        }
        var state = job.capture().stepNative(token, 4096, System.nanoTime() + 2_000_000L, System::nanoTime,
            packed -> {
                int x = SeedScanCore.unpackX(packed), z = SeedScanCore.unpackZ(packed);
                if (level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false) == null)
                    return new SeedSnapshotCapture.CapturedBlock(SeedScanCore.MISSING, -1);
                var observed = level.getBlockState(new BlockPos(x, SeedScanCore.unpackY(packed), z));
                return new SeedSnapshotCapture.CapturedBlock(SeedBlockKinds.classify(observed),
                    BuiltInRegistries.BLOCK.getId(observed.getBlock()));
            }, schedule::revision);
        if (state == SeedSnapshotCapture.State.COMPLETE) {
            var revisions = new HashMap<>(job.capture().capturedRevisions());
            SeedGroundEvidence.Comparison ground = null;
            if (job.ground() != null) {
                revisions.put(job.chunk(), job.ground().revision());
                ground = SeedGroundEvidence.compare(job.modelHeights(), job.ground().terrain());
            }
            if (worker.submitInspection(new SeedPredictionWorker.Inspection(token, job.chunk(), job.capture().clusters(),
                job.capture().snapshot(), revisions, minimumChanges.get(), ground,
                job.capture().clusters().stream().flatMap(cluster -> cluster.writerProbes().stream()).distinct().toList(),
                job.motionTops(), job.replayPass(), job.featureTrace()))) captures.removeFirst();
        } else if (state == SeedSnapshotCapture.State.STALE || state == SeedSnapshotCapture.State.TOO_LARGE) {
            captures.removeFirst(); working.remove(job.chunk());
            // A capacity failure is deliberately not retried every client tick.
            if (state == SeedSnapshotCapture.State.TOO_LARGE) scanned.add(job.chunk());
        }
    }

    private void requestNearby(ClientLevel level) {
        if (halted) return;
        var center = mc.player.chunkPosition();
        int r = SeedScanLimits.effectiveRadius(radius.get(), mc.options.renderDistance().get());
        Set<Long> retained = new HashSet<>();
        for (int dx = -r - 3; dx <= r + 3; dx++) for (int dz = -r - 3; dz <= r + 3; dz++)
            retained.add(SeedScanCore.packChunk(center.x() + dx, center.z() + dz));
        schedule.retain(retained);
        scanned.retainAll(retained);
        ledger.retainArea(retained);
        if (working.size() >= 8) return;
        // Visit the nearest ring first so changing direction does not leave the player waiting on far jobs.
        for (int ring = 0; ring <= r; ring++) for (int dx = -ring; dx <= ring; dx++) for (int dz = -ring; dz <= ring; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
            int x = center.x() + dx, z = center.z() + dz;
            long key = SeedScanCore.packChunk(x, z);
            if (!SeedScanLimits.containsChunk(x, z) || scanned.contains(key) || working.contains(key) ||
                !schedule.settled(key, tick) || level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) continue;
            if (worker.submit(token, x, z, captureMotionTops(level, x, z))) working.add(key);
            return;
        }
    }

    private static Map<Long, int[]> captureMotionTops(ClientLevel level, int centerX, int centerZ) {
        Map<Long, int[]> tops = new HashMap<>();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            int x = centerX + dx, z = centerZ + dz;
            if (!SeedScanLimits.containsChunk(x, z)) continue;
            var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            int[] columns = new int[256];
            for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++)
                columns[localZ * 16 + localX] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, localX, localZ);
            tops.put(SeedScanCore.packChunk(x, z), columns);
        }
        return Map.copyOf(tops);
    }

    @EventHandler private void render(Render3DEvent event) {
        if (world != mc.level || mc.player == null || health.alertsSuppressed()) return;
        var selected = color.get();
        var fill = new Color(selected.r, selected.g, selected.b, selected.a);
        var line = new Color(selected.r, selected.g, selected.b, Math.max(165, selected.a));
        for (var result : ledger.flagged()) {
            int x = chunkX(result.chunk()) << 4, z = chunkZ(result.chunk()) << 4;
            if (Math.abs((x >> 4) - mc.player.chunkPosition().x()) > radius.get() + 1 ||
                Math.abs((z >> 4) - mc.player.chunkPosition().z()) > radius.get() + 1) continue;
            event.renderer.box(x, 0, z, x + 16, 32, z + 16, fill, line, ShapeMode.Both, 0);
            if (tracers.get()) event.renderer.line(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(), x + 8, 16, z + 8, line);
        }
    }

    private void announce(long chunk) {
        if (notifications.get()) notice("Seed cluster changes in chunk " + chunkX(chunk) + ", " + chunkZ(chunk) + ".");
        if (sound.get()) mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
            net.minecraft.sounds.SoundEvents.AMETHYST_CLUSTER_STEP, 0.8F, 1.0F));
    }
    private static void notice(String message) { ChatUtils.sendMsg(Component.literal(message)); }
    private boolean nearby(long key) {
        if (mc.player == null) return false;
        var center = mc.player.chunkPosition();
        int r = SeedScanLimits.effectiveRadius(radius.get(), mc.options.renderDistance().get());
        return Math.abs(chunkX(key) - center.x()) <= r && Math.abs(chunkZ(key) - center.z()) <= r;
    }
    private static int chunkX(long key) { return (int)(key >> 32); }
    private static int chunkZ(long key) { return (int)key; }
    private static OptionalLong biomeSeedHash(ClientLevel world) {
        try {
            java.lang.reflect.Field match = null;
            for (var f : BiomeManager.class.getDeclaredFields()) {
                if (f.getType() != long.class || Modifier.isStatic(f.getModifiers())) continue;
                if (match != null) return OptionalLong.empty();
                match = f;
            }
            return match != null && match.trySetAccessible() ?
                OptionalLong.of(match.getLong(world.getBiomeManager())) : OptionalLong.empty();
        } catch (ReflectiveOperationException | RuntimeException ignored) { return OptionalLong.empty(); }
    }
}
