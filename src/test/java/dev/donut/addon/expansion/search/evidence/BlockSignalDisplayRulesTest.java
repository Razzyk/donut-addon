package dev.donut.addon.expansion.search.evidence;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockSignalDisplayRulesTest {
    @Test
    void depthBandsUseStrictBelowBoundaries() {
        assertTrue(BlockSignalDisplayRules.DetectionBand.ALL.includesY(320));
        assertTrue(BlockSignalDisplayRules.DetectionBand.BELOW_ZERO.includesY(-1));
        assertFalse(BlockSignalDisplayRules.DetectionBand.BELOW_ZERO.includesY(0));
        assertTrue(BlockSignalDisplayRules.DetectionBand.BELOW_MINUS_20.includesY(-21));
        assertFalse(BlockSignalDisplayRules.DetectionBand.BELOW_MINUS_20.includesY(-20));
        assertTrue(BlockSignalDisplayRules.DetectionBand.BELOW_MINUS_30.includesY(-31));
    }

    @Test
    void parsesNamespacedAndVanillaIdsWithoutAcceptingMalformedTokens() {
        assertEquals(Set.of("minecraft:chest", "othermod:crate", "minecraft:spawner"),
            BlockSignalDisplayRules.parseIds("chest, othermod:crate; spawner !bad"));
    }

    @Test
    void filteringKeepsChestFallbackAndChecksReportedOrLoadedId() {
        Set<String> selected = BlockSignalDisplayRules.parseIds("barrel");
        assertTrue(BlockSignalDisplayRules.allowsBlock(true, selected, "minecraft:chest", "minecraft:air"));
        assertTrue(BlockSignalDisplayRules.allowsBlock(true, selected, "minecraft:unknown", "minecraft:barrel"));
        assertFalse(BlockSignalDisplayRules.allowsBlock(true, selected, "minecraft:unknown", "minecraft:stone"));
        assertTrue(BlockSignalDisplayRules.allowsBlock(false, Set.of(), "unknown", "stone"));
    }

    @Test
    void parsesSixAndEightDigitColorsAndIgnoresBadEntries() {
        Map<String, Integer> colors = BlockSignalDisplayRules.parseColors(
            "chest=#12abef; othermod:crate=#7f123456\nspawner=nope");
        assertEquals(0xff12abef, colors.get("minecraft:chest"));
        assertEquals(0x7f123456, colors.get("othermod:crate"));
        assertFalse(colors.containsKey("minecraft:spawner"));
        assertEquals(0xff010203, BlockSignalDisplayRules.colorFor("minecraft:other", colors, 0xff010203));
    }

    @Test
    void scalesFillOpacityByEvidenceKindAndClampsUserValues() {
        assertEquals(25, BlockSignalDisplayRules.scaledFillOpacityPercent(25, 100));
        assertEquals(16, BlockSignalDisplayRules.scaledFillOpacityPercent(25, 64));
        assertEquals(20, BlockSignalDisplayRules.scaledFillOpacityPercent(25, 80));
        assertEquals(0, BlockSignalDisplayRules.scaledFillOpacityPercent(-20, 100));
        assertEquals(100, BlockSignalDisplayRules.scaledFillOpacityPercent(150, 120));
    }
}
