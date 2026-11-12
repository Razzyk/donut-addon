package dev.donut.addon.expansion.search.growth;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SuspectGrowthHeatTest {
    @Test void spreadsEachKindAndVetoesOnlyThatKind() {
        SuspectGrowthHeat heat = new SuspectGrowthHeat(16, 1);
        heat.replace(new SuspectGrowthHeat.Key(0, 0), new int[]{3, 5, 0, 0, 0, 0, 0}, 1 << 1);
        assertEquals(3, heat.scores().stream().filter(s -> s.key().equals(new SuspectGrowthHeat.Key(1, 1))).findFirst().orElseThrow().value());
        assertTrue(heat.vetoed(new SuspectGrowthHeat.Key(1, 1), 1));
        heat.replace(new SuspectGrowthHeat.Key(0, 0), new int[]{0, 0, 0, 0, 0, 0, 0}, 0);
        assertTrue(heat.scores().isEmpty());
    }

    @Test void replacingAndRemovingProfilesRetractsTheirSpread() {
        SuspectGrowthHeat heat = new SuspectGrowthHeat(2, 0);
        var key = new SuspectGrowthHeat.Key(2, -3);
        heat.replace(key, new int[]{4, 0, 0, 0, 0, 0, 0}, 0);
        heat.replace(key, new int[]{1, 0, 0, 0, 0, 0, 0}, 0);
        assertEquals(1, heat.scores().getFirst().value());
        heat.remove(key);
        assertTrue(heat.scores().isEmpty());
    }

    @Test void amethystAndBeeNestRemainIndependentSignalsAndVetoes() {
        SuspectGrowthHeat heat = new SuspectGrowthHeat(8, 2);
        var origin = new SuspectGrowthHeat.Key(-4, 7);
        var target = new SuspectGrowthHeat.Key(-2, 9);
        heat.replace(origin, new int[]{2, 0, 0, 0, 0, 0, 5}, 1 << 6);
        assertEquals(2, heat.heat(target, 0));
        assertEquals(0, heat.heat(target, 6));
        assertTrue(heat.vetoed(target, 6));
        assertEquals(2, heat.scores().stream().filter(score -> score.key().equals(target)).findFirst().orElseThrow().value());
    }

    @Test void radiusChangesRebuildAllSevenKinds() {
        SuspectGrowthHeat heat = new SuspectGrowthHeat(8, 0);
        heat.replace(new SuspectGrowthHeat.Key(1, 1), new int[]{0, 1, 2, 3, 4, 5, 6}, 0);
        assertEquals(0, heat.heat(new SuspectGrowthHeat.Key(2, 1), 6));
        heat.setSpreadRadius(1);
        assertEquals(6, heat.heat(new SuspectGrowthHeat.Key(2, 1), 6));
    }
}
