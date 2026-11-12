package dev.donut.addon.expansion.search.seed;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedScanCoreTest {
    @Test void parsesSeedsWithMinecraftDecimalAndTextFallbackRules() {
        assertEquals(OptionalLong.of(42L), SeedScanCore.parseSeed(" 42 "));
        assertEquals(OptionalLong.of(-9L), SeedScanCore.parseSeed("-9"));
        assertEquals(OptionalLong.of("Donut SMP".hashCode()), SeedScanCore.parseSeed(" Donut SMP "));
        assertTrue(SeedScanCore.parseSeed(" \t ").isEmpty());
        assertEquals(OptionalLong.of("9223372036854775808".hashCode()), SeedScanCore.parseSeed("9223372036854775808"));
    }

    @Test void clusterVerdictsRequireCompleteBoundaryAndEnoughAgreement() {
        long[] cells = new long[8];
        long[] shell = {SeedScanCore.packBlock(16, 10, 0)};
        for (int i = 0; i < cells.length; i++) cells[i] = SeedScanCore.packBlock(i, 10, 0);
        SeedScanCore.PredictedCluster cluster = new SeedScanCore.PredictedCluster(
            SeedScanCore.GRANITE, cells[0], cells, shell, new byte[]{SeedScanCore.GRANITE}, true);

        byte[] observed = chunk(SeedScanCore.GRANITE);
        Map<Long, byte[]> loaded = new HashMap<>();
        loaded.put(SeedScanCore.packChunk(0, 0), observed);
        assertEquals(SeedScanCore.Verdict.INCOMPLETE, SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(loaded), 1));

        loaded.put(SeedScanCore.packChunk(1, 0), chunk(SeedScanCore.GRANITE));
        assertEquals(SeedScanCore.Verdict.CLEAN, SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(loaded), 1));
    }

    @Test void clusterRequiresEightMatchesAndReportsChangedCellsOnlyAfterSeedAgreement() {
        long[] cells = new long[8];
        for (int i = 0; i < cells.length; i++) cells[i] = SeedScanCore.packBlock(i, 10, 0);
        SeedScanCore.PredictedCluster cluster = new SeedScanCore.PredictedCluster(SeedScanCore.GRANITE, cells[0], cells, new long[0]);
        byte[] observed = chunk(SeedScanCore.GRANITE);
        observed[index(0, 10, 0)] = SeedScanCore.MODIFIED;
        assertEquals(SeedScanCore.Verdict.ALERT,
            SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), observed)), 1));
        for (int i = 1; i < 5; i++) observed[index(i, 10, 0)] = SeedScanCore.MODIFIED;
        assertEquals(SeedScanCore.Verdict.MISPREDICTED,
            SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), observed)), 1));
    }

    @Test void sourceStyleFakesAndNaturalMaterialChangeTheJudgedDenominator() {
        long[] cells = new long[8];
        for (int i = 0; i < cells.length; i++) cells[i] = SeedScanCore.packBlock(i, 10, 0);
        SeedScanCore.PredictedCluster cluster = new SeedScanCore.PredictedCluster(
            SeedScanCore.GRANITE, cells[0], cells, new long[0]);
        byte[] observed = chunk(SeedScanCore.GRANITE);
        observed[index(0, 10, 0)] = SeedScanCore.STONE;
        assertEquals(SeedScanCore.Verdict.ALERT,
            SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), observed)), 1));

        observed = chunk(SeedScanCore.GRANITE);
        observed[index(0, 10, 0)] = SeedScanCore.NATURAL;
        observed[index(1, 10, 0)] = SeedScanCore.NATURAL;
        assertEquals(SeedScanCore.Verdict.TOO_FEW,
            SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), observed)), 1));

        observed = chunk(SeedScanCore.GRANITE);
        for (int i = 0; i < 3; i++) observed[index(i, 10, 0)] = SeedScanCore.ORE;
        assertEquals(SeedScanCore.Verdict.HIDDEN,
            SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), observed)), 1));
    }

    @Test void moreThanThreeFilledPredictedCaveShellCellsRejectTheCluster() {
        long[] cells = new long[8];
        for (int i = 0; i < cells.length; i++) cells[i] = SeedScanCore.packBlock(i, 10, 0);
        long[] shell = new long[4];
        byte[] model = new byte[4];
        java.util.Arrays.fill(model, SeedScanCore.AIR);
        for (int i = 0; i < shell.length; i++) shell[i] = SeedScanCore.packBlock(8 + i, 10, 0);
        SeedScanCore.PredictedCluster cluster = new SeedScanCore.PredictedCluster(
            SeedScanCore.GRANITE, cells[0], cells, shell, model, true);
        byte[] observed = chunk(SeedScanCore.GRANITE);
        assertEquals(SeedScanCore.Verdict.TERRAIN,
            SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), observed)), 1));
    }

    @Test void boundaryAirAndStructuresDoNotBecomeSeedAlerts() {
        long[] cells = new long[8];
        for (int i = 0; i < cells.length; i++) cells[i] = SeedScanCore.packBlock(i, 10, 0);
        SeedScanCore.PredictedCluster cluster = new SeedScanCore.PredictedCluster(SeedScanCore.GRANITE, cells[0], cells,
            new long[]{SeedScanCore.packBlock(8, 10, 0)});
        byte[] observed = chunk(SeedScanCore.GRANITE);
        observed[index(8, 10, 0)] = SeedScanCore.AIR;
        assertEquals(SeedScanCore.Verdict.OPEN,
            SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), observed)), 1));
        observed[index(8, 10, 0)] = SeedScanCore.STRUCTURE;
        assertEquals(SeedScanCore.Verdict.STRUCTURE,
            SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), observed)), 1));
    }

    @Test void generatedRegionEdgesAreRejectedEvenWhenClientChunksAreLoaded() {
        long[] cells = new long[8];
        for (int i = 0; i < cells.length; i++) cells[i] = SeedScanCore.packBlock(i, 10, 0);
        SeedScanCore.PredictedCluster cutOff = new SeedScanCore.PredictedCluster(
            SeedScanCore.GRANITE, cells[0], cells, new long[0], false);
        assertEquals(SeedScanCore.Verdict.INCOMPLETE,
            SeedScanCore.judge(cutOff, new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), chunk(SeedScanCore.GRANITE))), 1));
    }

    @Test void familyReplacementNextToCaveIsCountedExceptLooseGravel() {
        long[] cells = new long[8];
        for (int i = 0; i < cells.length; i++) cells[i] = SeedScanCore.packBlock(i, 10, 0);
        long[] shell = {SeedScanCore.packBlock(8, 10, 0)};
        var cluster = new SeedScanCore.PredictedCluster(SeedScanCore.GRANITE, cells[0], cells, shell,
            new byte[]{SeedScanCore.GRANITE}, true);
        byte[] observed = chunk(SeedScanCore.GRANITE);
        observed[index(8, 10, 0)] = SeedScanCore.DIORITE;
        observed[index(9, 10, 0)] = SeedScanCore.AIR;
        assertEquals(SeedScanCore.Verdict.ALERT,
            SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), observed)), 1));
        observed[index(8, 10, 0)] = SeedScanCore.GRAVEL;
        assertEquals(SeedScanCore.Verdict.CLEAN,
            SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), observed)), 1));
    }

    @Test void unexpectedFamilyInOriginallyDirtShellIsStillAReplacement() {
        long[] cells = new long[8];
        for (int i = 0; i < cells.length; i++) cells[i] = SeedScanCore.packBlock(i, 10, 0);
        var cluster = new SeedScanCore.PredictedCluster(SeedScanCore.GRANITE, cells[0], cells,
            new long[]{SeedScanCore.packBlock(8, 10, 0)}, new byte[]{SeedScanCore.DIRT}, true);
        byte[] observed = chunk(SeedScanCore.GRANITE);
        observed[index(8, 10, 0)] = SeedScanCore.DIORITE;
        assertEquals(SeedScanCore.Verdict.ALERT,
            SeedScanCore.judge(cluster, new SeedScanCore.Snapshot(Map.of(SeedScanCore.packChunk(0, 0), observed)), 1));
    }

    private static byte[] chunk(byte kind) {
        byte[] values = new byte[(SeedScanCore.JUDGE_MAX_Y - SeedScanCore.JUDGE_MIN_Y + 1) * 256];
        java.util.Arrays.fill(values, kind);
        return values;
    }

    private static int index(int x, int y, int z) {
        return (y - SeedScanCore.JUDGE_MIN_Y) * 256 + z * 16 + x;
    }
}
