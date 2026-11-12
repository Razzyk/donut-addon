package dev.donut.addon.expansion.search.growth;

import static dev.donut.addon.expansion.search.growth.NaturalGrowthRules.Fact.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NaturalGrowthRulesTest {
    @Test
    void cropRequiresAnUnfinishedStageLightAndAValidRoot() {
        var unfinished = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.CROP,
            6, 7, 9, 9, UNKNOWN, YES, 0, 0, UNKNOWN, UNKNOWN);
        var mature = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.CROP,
            7, 7, 15, 9, UNKNOWN, YES, 0, 0, UNKNOWN, UNKNOWN);
        var dim = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.CROP,
            6, 7, 8, 9, UNKNOWN, YES, 0, 0, UNKNOWN, UNKNOWN);
        var unsupported = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.CROP,
            6, 7, 12, 9, UNKNOWN, NO, 0, 0, UNKNOWN, UNKNOWN);

        assertTrue(NaturalGrowthRules.isOpportunity(unfinished));
        assertFalse(NaturalGrowthRules.isOpportunity(mature));
        assertFalse(NaturalGrowthRules.isOpportunity(dim));
        assertFalse(NaturalGrowthRules.isOpportunity(unsupported));
    }

    @Test
    void verticalPlantRequiresRoomSupportAndUnusedColumnCapacity() {
        var open = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.CACTUS,
            -1, -1, -1, -1, YES, YES, 2, 3, UNKNOWN, UNKNOWN);
        var blocked = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.CACTUS,
            -1, -1, -1, -1, NO, YES, 2, 3, UNKNOWN, UNKNOWN);
        var full = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.CACTUS,
            -1, -1, -1, -1, YES, YES, 3, 3, UNKNOWN, UNKNOWN);
        var unknownColumn = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.CACTUS,
            -1, -1, -1, -1, YES, YES, 0, 3, UNKNOWN, UNKNOWN);

        assertTrue(NaturalGrowthRules.isOpportunity(open));
        assertFalse(NaturalGrowthRules.isOpportunity(blocked));
        assertFalse(NaturalGrowthRules.isOpportunity(full));
        assertFalse(NaturalGrowthRules.isOpportunity(unknownColumn));
    }

    @Test
    void amethystNeedsAnExposedGrowthFaceAndLeavesAreNotGrowthEvidence() {
        var exposed = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.AMETHYST_BUD,
            -1, -1, -1, -1, UNKNOWN, UNKNOWN, 0, 0, YES, UNKNOWN);
        var enclosed = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.AMETHYST_BUD,
            -1, -1, -1, -1, UNKNOWN, UNKNOWN, 0, 0, NO, UNKNOWN);
        var unknown = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.AMETHYST_BUD,
            -1, -1, -1, -1, UNKNOWN, UNKNOWN, 0, 0, UNKNOWN, UNKNOWN);
        var leaves = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.LEAF,
            1, 7, 15, 9, YES, YES, 0, 0, UNKNOWN, UNKNOWN);

        assertTrue(NaturalGrowthRules.isOpportunity(exposed));
        assertFalse(NaturalGrowthRules.isOpportunity(enclosed));
        assertFalse(NaturalGrowthRules.isOpportunity(unknown));
        assertFalse(NaturalGrowthRules.isOpportunity(leaves));
    }

    @Test
    void cycleDependentBlocksRequireAnExplicitMatureCycle() {
        var mature = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.EYEBLOSSOM,
            -1, -1, -1, -1, UNKNOWN, UNKNOWN, 0, 0, UNKNOWN, YES);
        var transitionNotSettled = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.SNOW,
            -1, -1, -1, -1, UNKNOWN, UNKNOWN, 0, 0, UNKNOWN, NO);
        var unknown = new NaturalGrowthRules.Sample(NaturalGrowthRules.Kind.ICE,
            -1, -1, -1, -1, UNKNOWN, UNKNOWN, 0, 0, UNKNOWN, UNKNOWN);

        assertTrue(NaturalGrowthRules.isOpportunity(mature));
        assertFalse(NaturalGrowthRules.isOpportunity(transitionNotSettled));
        assertFalse(NaturalGrowthRules.isOpportunity(unknown));
    }

    @Test
    void chunkConfidenceUsesOnlySelectedEvidenceAndRequiresItsMinimumSample() {
        int[] counts = {4, 7, 3};
        boolean[] all = {true, true, true};
        boolean[] custom = {true, false, true};

        assertEquals(14, NaturalGrowthScore.total(counts, all));
        assertEquals(7, NaturalGrowthScore.total(counts, custom));
        assertFalse(NaturalGrowthScore.meetsThreshold(counts, custom, 8));
        assertTrue(NaturalGrowthScore.meetsThreshold(counts, custom, 7));
        assertFalse(NaturalGrowthScore.meetsThreshold(counts, custom, 0));
    }
}
