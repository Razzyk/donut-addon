package dev.donut.addon.expansion.search.seed;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedScanLimitsTest {
    @Test void customWorldNamesAreNotRejectedWhenVanillaHeightMatches() {
        assertTrue(SeedScanLimits.supportsHeight(-64, 384));
        assertFalse(SeedScanLimits.supportsHeight(0, 256));
        assertFalse(SeedScanLimits.supportsHeight(-64, 512));
    }

    @Test void scannerRespectsAsymmetricChunkBorderAndLoadedViewRadius() {
        assertTrue(SeedScanLimits.containsChunk(-15_625, 15_624));
        assertFalse(SeedScanLimits.containsChunk(15_625, 0));
        assertFalse(SeedScanLimits.containsChunk(0, -15_626));
        assertEquals(4, SeedScanLimits.effectiveRadius(8, 4));
        assertEquals(8, SeedScanLimits.effectiveRadius(16, 16));
        assertEquals(0, SeedScanLimits.effectiveRadius(8, 0));
    }
}
