package dev.donut.addon.expansion.search.remote;

import java.nio.file.Path;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class RemoteKnownBaseStoreTest {
    @Test void persistsProtectedChunksPerServerAndDimension(@TempDir Path directory) {
        long chunk = ChunkPos.pack(-19, 42);
        RemoteKnownBaseStore first = new RemoteKnownBaseStore(directory);
        first.select("example.org", "minecraft:overworld");
        assertTrue(first.protect(chunk));
        assertFalse(first.flagAllowed(chunk, false));
        assertTrue(first.flagAllowed(chunk, true));
        first.flushAndWait();
        first.close();

        RemoteKnownBaseStore reloaded = new RemoteKnownBaseStore(directory);
        reloaded.select("example.org", "minecraft:overworld");
        assertTrue(reloaded.isProtected(chunk));
        reloaded.select("example.org", "minecraft:the_nether");
        assertFalse(reloaded.isProtected(chunk));
        reloaded.close();
    }

    @Test void normalizesServerAddressesAndSeparatesSingleplayerSaves() {
        assertEquals("example.org", RemoteKnownBaseStore.serverKey("  EXAMPLE.ORG:25565 "));
        assertEquals("[::1]", RemoteKnownBaseStore.serverKey("[::1]:25565"));
        assertEquals("example.org:25566", RemoteKnownBaseStore.serverKey("example.org:25566"));
        assertEquals("unknown", RemoteKnownBaseStore.serverKey(null));
        assertEquals("singleplayer/Survival", RemoteKnownBaseStore.singleplayerKey("Survival"));
    }

    @Test void disconnectBeforeFirstWorldDoesNotInitializePersistence() throws Exception {
        var shared = RemoteKnownBaseStore.class.getDeclaredField("sharedInstance");
        shared.setAccessible(true);
        Object previous = shared.get(null);
        try {
            shared.set(null, null);
            RemoteKnownBaseStore.endSharedSession();
            assertNull(shared.get(null));
        } finally {
            shared.set(null, previous);
        }
    }

    @Test void shutdownSavesCaptureOwnedDataWithoutAnEnabledModule(@TempDir Path directory) throws Exception {
        var shared = RemoteKnownBaseStore.class.getDeclaredField("sharedInstance");
        shared.setAccessible(true);
        Object previous = shared.get(null);
        RemoteKnownBaseStore captureStore = new RemoteKnownBaseStore(directory);
        try {
            shared.set(null, captureStore);
            captureStore.select("example.org", "minecraft:overworld");
            long protectedChunk = ChunkPos.pack(4, -8);
            assertTrue(captureStore.protect(protectedChunk));
            RemoteKnownBaseStore.shutdownShared();
            assertFalse(captureStore.protect(ChunkPos.pack(5, -8)));
            try (RemoteKnownBaseStore reloaded = new RemoteKnownBaseStore(directory)) {
                reloaded.select("example.org", "minecraft:overworld");
                assertTrue(reloaded.isProtected(protectedChunk));
            }
        } finally {
            captureStore.close();
            shared.set(null, previous);
        }
    }
}
