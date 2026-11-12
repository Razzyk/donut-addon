package dev.donut.addon.expansion.search;

import dev.donut.addon.expansion.search.evidence.BlockSignalInbox;
import dev.donut.addon.expansion.search.evidence.BlockSignalLedger;
import dev.donut.addon.expansion.search.growth.GrowthSurveyModule;
import dev.donut.addon.expansion.search.growth.DonutGrowthInspector;
import dev.donut.addon.expansion.search.formation.DonutFormationSurveyModule;
import dev.donut.addon.expansion.search.amethyst.DonutGeodePaletteSurveyModule;
import dev.donut.addon.expansion.search.light.ArtificialLightSurveyModule;
import dev.donut.addon.expansion.search.media.DonutMediaRosterModule;
import dev.donut.addon.expansion.search.media.DonutMediaRosterHudComponent;
import dev.donut.addon.expansion.search.nether.AncientOreSurveyModule;
import dev.donut.addon.expansion.search.nether.AncientOreSurveyOverlay;
import dev.donut.addon.expansion.search.remote.RemoteActivityCapture;
import dev.donut.addon.expansion.search.remote.RemoteActivityModule;
import dev.donut.addon.expansion.search.spawner.SpawnerSignpostsModule;
import dev.donut.addon.expansion.search.spawner.SpawnerSignpostOverlay;
import dev.donut.addon.expansion.search.seed.DonutSeedInspector;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.systems.modules.Modules;
import net.ccbluex.liquidbounce.event.EventListener;
import net.ccbluex.liquidbounce.integration.theme.component.components.NativeHudComponent;
import java.util.List;

/** Search additions available to the root-owned module and listener registries. */
public final class SearchFeatures {
    public static final BlockSignalInbox BLOCK_SIGNAL_INBOX = new BlockSignalInbox(8192);
    public static final BlockSignalLedger BLOCK_SIGNAL_LEDGER = new BlockSignalLedger(8192);
    public static final SpawnerSignpostsModule SPAWNER_SIGNPOSTS = new SpawnerSignpostsModule();
    public static final AncientOreSurveyModule ANCIENT_ORE_SURVEY = new AncientOreSurveyModule();
    public static final DonutMediaRosterModule MEDIA_ROSTER = new DonutMediaRosterModule();
    public static final RemoteActivityModule REMOTE_ACTIVITY = new RemoteActivityModule();
    public static final DonutGrowthInspector GROWTH_INSPECTOR = new DonutGrowthInspector();
    public static final GrowthSurveyModule GROWTH_SURVEY = new GrowthSurveyModule();
    public static final DonutSeedInspector SEED_INSPECTOR = new DonutSeedInspector();
    public static final List<Module> modules = List.of(
        new BlockEntitySignalModule(),
        GROWTH_SURVEY,
        SEED_INSPECTOR,
        GROWTH_INSPECTOR,
        new DonutFormationSurveyModule(),
        new DonutGeodePaletteSurveyModule(),
        new ArtificialLightSurveyModule(),
        SPAWNER_SIGNPOSTS,
        ANCIENT_ORE_SURVEY,
        MEDIA_ROSTER,
        REMOTE_ACTIVITY
    );
    public static final List<EventListener> listeners = List.of(
        SearchPacketCapture.INSTANCE,
        SearchSessionLifecycle.INSTANCE,
        SpawnerSignpostOverlay.INSTANCE,
        AncientOreSurveyOverlay.INSTANCE,
        RemoteActivityCapture.INSTANCE
    );
    private static volatile NativeHudComponent mediaHudComponent;
    private static volatile List<NativeHudComponent> hudComponents;

    static {
        modules.forEach(Modules.get()::add);
        syncMediaHud();
    }

    private SearchFeatures() {}

    public static void shutdown() {
        SearchSessionLifecycle.INSTANCE.shutdown();
        SearchPacketCapture.INSTANCE.shutdown();
    }

    public static void syncMediaHud() {
        NativeHudComponent component = mediaHudComponent;
        if (component != null) component.setEnabled(MEDIA_ROSTER.isActive() && MEDIA_ROSTER.statusPanelEnabled());
    }

    /** Materialize native HUD components only when the UI asks for them. */
    public static List<NativeHudComponent> getHudComponents() {
        List<NativeHudComponent> components = hudComponents;
        if (components != null) return components;
        synchronized (SearchFeatures.class) {
            components = hudComponents;
            if (components == null) {
                mediaHudComponent = DonutMediaRosterHudComponent.INSTANCE;
                components = List.of(mediaHudComponent);
                hudComponents = components;
                syncMediaHud();
            }
        }
        return components;
    }

    /** Public intake for optional packet hooks that can expose extra server-side evidence. */
    public static boolean offerBlockEntityReport(int x, int y, int z, String typeId) {
        return offerBlockEntityReport(x, y, z, typeId, "");
    }

    public static boolean offerBlockEntityReport(int x, int y, int z, String typeId, String payloadPreview) {
        return BLOCK_SIGNAL_INBOX.offer(new BlockSignalInbox.Signal(x, y, z, typeId, payloadPreview,
            dev.donut.addon.expansion.search.evidence.BlockSignalLedger.Source.ENTITY_PACKET,
            System.nanoTime()));
    }

    public static boolean offerBlockClue(int x, int y, int z, String typeId,
                                         BlockSignalLedger.Source source) {
        if (source == BlockSignalLedger.Source.ENTITY_PACKET) return false;
        return BLOCK_SIGNAL_INBOX.offer(new BlockSignalInbox.Signal(x, y, z, typeId, "", source, System.nanoTime()));
    }

    /** Called only from the client thread when evidence should not cross a world boundary. */
    public static void clearPendingEvidence() {
        BLOCK_SIGNAL_INBOX.clear();
        BLOCK_SIGNAL_LEDGER.beginWorld();
    }
}
