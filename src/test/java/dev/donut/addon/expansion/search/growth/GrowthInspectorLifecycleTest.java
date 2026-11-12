package dev.donut.addon.expansion.search.growth;

import dev.donut.addon.mv.compat.MeteorClient;
import java.lang.reflect.Field;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GrowthInspectorLifecycleTest {
    @BeforeAll static void bootstrapNativeTypes() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void outsideWorldCleanupReleasesTheWorldAndInvalidatesPublishedFlags() throws Exception {
        checkCleanup(false);
    }

    @Test void disabledModuleIsCleanedEvenWhenTheClientReadinessFlagIsTrue() throws Exception {
        checkCleanup(true);
    }

    private static void checkCleanup(boolean clientReady) throws Exception {
        Field singleton = DonutGrowthInspector.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        DonutGrowthInspector inspector = new DonutGrowthInspector();
        try {
            Object oldWorld = new Object();
            Field snapshot = DonutGrowthInspector.class.getDeclaredField("snapshot");
            snapshot.setAccessible(true);
            snapshot.set(inspector, new DonutWorldFlagSnapshot("test:world", oldWorld, 0L, Set.of(12L)));
            assertTrue(inspector.flaggedChunkSnapshot().belongsTo(oldWorld, "test:world"));
            inspector.onInspectorLifecycleTick(clientReady);
            assertNull(inspector.flaggedChunkSnapshot().worldIdentity());
            assertTrue(inspector.flaggedChunkSnapshot().chunkKeys().isEmpty());
            assertFalse(inspector.flaggedChunkSnapshot().belongsTo(oldWorld, "test:world"));
            inspector.endInspectorSession();
            assertNull(inspector.flaggedChunkSnapshot().worldIdentity());
        } finally {
            MeteorClient.EVENT_BUS.unsubscribe(inspector);
            singleton.set(null, previous);
        }
    }
}
