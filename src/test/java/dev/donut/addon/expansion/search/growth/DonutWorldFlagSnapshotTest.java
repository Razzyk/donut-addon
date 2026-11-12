package dev.donut.addon.expansion.search.growth;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DonutWorldFlagSnapshotTest {
    @Test void snapshotCopiesFlagsAndRequiresExactWorldAndDimension() {
        Object world = new Object();
        Set<Long> source = new HashSet<>(Set.of(7L, 9L));
        DonutWorldFlagSnapshot snapshot = new DonutWorldFlagSnapshot("minecraft:the_nether", world, 12L, source);
        source.add(11L);
        assertEquals(Set.of(7L, 9L), snapshot.chunkKeys());
        assertTrue(snapshot.belongsTo(world, "minecraft:the_nether"));
        assertFalse(snapshot.belongsTo(new Object(), "minecraft:the_nether"));
        assertFalse(snapshot.belongsTo(world, "minecraft:overworld"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.chunkKeys().add(11L));
    }
}
