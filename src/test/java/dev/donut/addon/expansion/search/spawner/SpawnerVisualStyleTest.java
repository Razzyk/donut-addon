package dev.donut.addon.expansion.search.spawner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpawnerVisualStyleTest {
    @Test void revealStartsTransparentAndUsesTheSourceScale() {
        var first = SpawnerVisualStyle.frame(0, 0, 1.5f, true, true);
        assertEquals(0.0f, first.opacity());
        assertEquals(0.55f, first.scale(), 0.0001f);
        assertFalse(first.visible());

        var finished = SpawnerVisualStyle.frame(0, 380_000_000L, 1.5f, true, true);
        assertEquals(1.0f, finished.opacity());
        assertEquals(1.0f, finished.scale(), 0.0001f);
        assertEquals(1.5f, finished.lineWidth(), 0.0001f);
        assertTrue(finished.visible());
    }

    @Test void distanceFadeAndOutlineWidthMatchTheSourceBands() {
        var near = SpawnerVisualStyle.frame(64, 380_000_000L, 2.0f, true, false);
        var mid = SpawnerVisualStyle.frame(160, 380_000_000L, 2.0f, true, false);
        var far = SpawnerVisualStyle.frame(512, 380_000_000L, 2.0f, true, false);
        assertEquals(1.0f, near.opacity(), 0.0001f);
        assertEquals(0.8625f, mid.opacity(), 0.0001f);
        assertEquals(0.45f, far.opacity(), 0.0001f);
        assertEquals(1.625f, mid.lineWidth(), 0.0001f);
        assertEquals(1.4f, far.lineWidth(), 0.0001f);
    }

    @Test void disabledAnimationKeepsFullOpacityAndNegativeAgesClampToZero() {
        var staticFrame = SpawnerVisualStyle.frame(200, -1, 2.0f, false, false);
        assertEquals(1.0f, staticFrame.opacity());
        assertEquals(1.0f, staticFrame.scale(), 0.0001f);
        assertEquals(2.0f, staticFrame.lineWidth(), 0.0001f);
    }
}
