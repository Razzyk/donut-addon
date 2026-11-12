package dev.donut.addon.expansion.search.growth;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class DonutGrowthRulesTest {
    @Test void sourceDefinesNineteenDistinctSelectorsAndClassifiesOnlySourceBlocks() {
        assertEquals(19, DonutGrowthRules.Kind.values().length);
        assertEquals(DonutGrowthRules.Kind.CAVE_VINES, DonutGrowthRules.classify("minecraft:cave_vines", false, false));
        assertEquals(DonutGrowthRules.Kind.WEEPING_VINES, DonutGrowthRules.classify("weeping_vines", false, false));
        assertEquals(DonutGrowthRules.Kind.CROPS, DonutGrowthRules.classify("wheat", true, false));
        assertEquals(DonutGrowthRules.Kind.PROPAGULES, DonutGrowthRules.classify("mangrove_propagule", false, true));
        assertNull(DonutGrowthRules.classify("mangrove_propagule", false, false));
        assertNull(DonutGrowthRules.classify("twisting_vines", false, false));
        assertNull(DonutGrowthRules.classify("coarse_dirt", false, false));
    }

    @Test void sourcePlantGrowthBoundariesMatchEachSignalFamily() {
        assertTrue(DonutGrowthRules.kelp(24, true));
        assertFalse(DonutGrowthRules.kelp(25, true));
        assertTrue(DonutGrowthRules.donutKelpColumn(true, 2));
        assertFalse(DonutGrowthRules.donutKelpColumn(false, 1));
        assertFalse(DonutGrowthRules.vines(true, true, 4, true));
        assertTrue(DonutGrowthRules.vines(false, true, 24, true));
        assertFalse(DonutGrowthRules.bamboo(0, true, 15, 9));
        assertTrue(DonutGrowthRules.bamboo(0, true, 14, 9));
        assertFalse(DonutGrowthRules.bamboo(0, true, 0, 8));
        assertTrue(DonutGrowthRules.sugarCane(true, 2));
        assertFalse(DonutGrowthRules.sugarCane(true, 3));
        assertTrue(DonutGrowthRules.cactus(15, true, 2));
        assertFalse(DonutGrowthRules.cactus(15, false, 2));
        assertTrue(DonutGrowthRules.belowMaxAge(1, 2));
        assertTrue(DonutGrowthRules.berry(2, true));
        assertFalse(DonutGrowthRules.berry(2, false));
        assertTrue(DonutGrowthRules.stem(6, true));
        assertTrue(DonutGrowthRules.crop(3, 7, true));
        assertFalse(DonutGrowthRules.crop(7, 7, true));
        assertTrue(DonutGrowthRules.cropOpportunity(true, 9));
        assertFalse(DonutGrowthRules.cropOpportunity(true, 8));
        assertFalse(DonutGrowthRules.cropOpportunity(false, 15));
        assertTrue(DonutGrowthRules.amethyst(true, false, true, false));
        assertTrue(DonutGrowthRules.amethyst(false, true, false, true));
        assertFalse(DonutGrowthRules.amethyst(false, true, false, false));
        assertTrue(DonutGrowthRules.vine(true, true));
        assertTrue(DonutGrowthRules.leaf(7, false));
        assertFalse(DonutGrowthRules.leaf(7, true));
        assertTrue(DonutGrowthRules.grassDirt(true, false, true, false, false));
        assertTrue(DonutGrowthRules.grassDirt(false, true, false, false, true));
        assertFalse(DonutGrowthRules.grassDirt(false, true, false, true, true));
        assertTrue(DonutGrowthRules.propagule(true, 3));
        assertFalse(DonutGrowthRules.propagule(false, 1));
    }

    @Test void sourceSurfaceAndEyeblossomRulesRemainConservative() {
        assertEquals(0, DonutGrowthRules.eyeblossomCount(1, 5));
        assertEquals(3, DonutGrowthRules.eyeblossomCount(0, 3));
        assertTrue(DonutGrowthRules.snow(true, true, true, true, true, false, true));
        assertFalse(DonutGrowthRules.snow(true, true, true, true, true, true, true));
        assertTrue(DonutGrowthRules.ice(true, false, true, true, true));
        assertFalse(DonutGrowthRules.ice(true, true, true, true, true));
        assertFalse(DonutGrowthRules.ice(true, false, false, true, true));
    }

    @Test void customSelectorsKeepTheSourceAllOnDefaultAndHonorEachDisabledSignal() {
        boolean[] selected = new boolean[DonutGrowthRules.Kind.values().length];
        selected[DonutGrowthRules.Kind.KELP.ordinal()] = true;
        selected[DonutGrowthRules.Kind.ICE.ordinal()] = true;
        assertEquals((1 << 19) - 1, DonutGrowthRules.enabledMask(false, selected));
        assertEquals((1 << DonutGrowthRules.Kind.KELP.ordinal()) | (1 << DonutGrowthRules.Kind.ICE.ordinal()),
            DonutGrowthRules.enabledMask(true, selected));
        assertThrows(IllegalArgumentException.class, () -> DonutGrowthRules.enabledMask(true, new boolean[18]));
    }
}
