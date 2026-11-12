package dev.donut.addon.expansion.search.seed;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SeedGroundEvidenceTest {
    private static int[] columns(int height) { var out = new int[256]; Arrays.fill(out, height); return out; }
    @Test void smallTerrainDifferencesAndUnknownIceColumnsAreExcluded() {
        var model = columns(64); var shown = columns(66); shown[0] = SeedGroundEvidence.UNKNOWN;
        var result = SeedGroundEvidence.compare(model, shown);
        assertEquals(255, result.columns()); assertEquals(0, result.different()); assertFalse(result.doubtful());
    }
    @Test void mismatchThresholdIsStrictlyAboveTwentySixOfTwoHundredFiftySix() {
        var model = columns(64); var shown = columns(64);
        for (int i=0;i<26;i++) shown[i] = 67;
        assertFalse(SeedGroundEvidence.compare(model, shown).doubtful());
        shown[26] = 67; assertTrue(SeedGroundEvidence.compare(model, shown).doubtful());
    }
    @Test void fewerThanSixtyFourKnownColumnsCannotEstablishMismatch() {
        var shown = columns(SeedGroundEvidence.UNKNOWN);
        Arrays.fill(shown, 0, 63, 100);
        assertFalse(SeedGroundEvidence.compare(columns(64), shown).doubtful());
        shown[63] = 100; assertTrue(SeedGroundEvidence.compare(columns(64), shown).doubtful());
    }
    @Test void repeatedChunksNeverInflateOneTimeTwentyChunkCalibration() {
        var health = new SeedGroundEvidence(); var mismatch = new SeedGroundEvidence.Comparison(256, 50, true);
        for(int i=0;i<50;i++) assertFalse(health.observe(1, mismatch));
        for(int i=2;i<20;i++) assertFalse(health.observe(i, mismatch));
        assertTrue(health.observe(20, mismatch)); assertFalse(health.observe(21, mismatch));
        health.clear(); assertFalse(health.observe(1, mismatch));
    }
    @Test void exactlyHalfMismatchedDoesNotWarn() {
        var health = new SeedGroundEvidence();
        for(int i=0;i<20;i++) assertFalse(health.observe(i, new SeedGroundEvidence.Comparison(256, i<10 ? 50 : 0, i<10)));
    }
    @Test void heightGatesRetainUnseenAndTreeUncertainty() {
        assertEquals(new SeedGroundEvidence.HeightRange(56,104), SeedGroundEvidence.placementHeight(64,null,false,false));
        assertEquals(new SeedGroundEvidence.HeightRange(60,84), SeedGroundEvidence.placementHeight(64,60,false,false));
        assertEquals(new SeedGroundEvidence.HeightRange(52,106), SeedGroundEvidence.placementHeight(64,60,true,true));
        assertEquals(new SeedGroundEvidence.HeightRange(20,25), SeedGroundEvidence.placementHeight(20,25,false,true));
    }
}
