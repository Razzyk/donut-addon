package dev.donut.addon.expansion.search.nether;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

final class AncientOreIndexTest {
    @Test
    void paletteOnlyClassificationRejectsPresentTargetAndAbsentPalette() {
        assertTrue(HiddenOreClassifier.isPaletteOnlyTarget(true, 0));
        assertFalse(HiddenOreClassifier.isPaletteOnlyTarget(true, 1));
        assertFalse(HiddenOreClassifier.isPaletteOnlyTarget(false, 0));
        assertThrows(IllegalArgumentException.class, () -> HiddenOreClassifier.isPaletteOnlyTarget(true, -1));
    }

    @Test
    void chunkReplacementRevealUnloadAndCapacityAreBounded() {
        var index = new AncientOreSectionIndex(2);
        index.replaceChunk(1, 2, Set.of(4, 5));
        assertEquals(2, index.size());
        assertTrue(index.reveal(18, 4 * 16 + 3, 34));
        assertEquals(1, index.size());
        index.replaceChunk(1, 2, Set.of(7));
        assertEquals(Set.of(7), index.snapshot().stream().map(AncientOreSectionIndex.Candidate::sectionY).collect(java.util.stream.Collectors.toSet()));
        index.replaceChunk(3, 4, Set.of(1, 2));
        assertEquals(2, index.size());
        index.removeChunk(1, 2);
        assertEquals(2, index.size());
        index.retainChunkKeys(Set.of(AncientOreSectionIndex.Candidate.chunkKey(1, 2)));
        assertTrue(index.snapshot().isEmpty());
    }
}
