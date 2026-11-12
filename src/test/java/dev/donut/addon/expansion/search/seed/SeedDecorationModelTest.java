package dev.donut.addon.expansion.search.seed;

import org.junit.jupiter.api.Test;
import java.util.List;
import static dev.donut.addon.expansion.search.seed.SeedScanCore.*;
import static org.junit.jupiter.api.Assertions.*;

class SeedDecorationModelTest {
    private static SeedDecorationModel.Writer writer(long id, byte kind, boolean optional) {
        return new SeedDecorationModel.Writer(id, List.of(new SeedDecorationModel.Write(kind, optional)));
    }
    @Test void unknownNeighborOrderPreservesBothStoneFamilies() {
        var outcome = SeedDecorationModel.resolve(STONE, List.of(writer(1, GRANITE, false), writer(2, DIORITE, false)), (a,b) -> false);
        assertTrue(outcome.canBe(GRANITE)); assertTrue(outcome.canBe(DIORITE));
        assertEquals(MISSING, outcome.certain()); assertEquals(3, outcome.familyMask());
    }
    @Test void witnessedOrderCanEstablishTheFinalFamily() {
        var outcome = SeedDecorationModel.resolve(STONE, List.of(writer(1, GRANITE, false), writer(2, DIORITE, false)), (a,b) -> a == 1 && b == 2);
        assertEquals(DIORITE, outcome.certain());
    }
    @Test void optionalFeatureIncludesItsAbsentOutcome() {
        var outcome = SeedDecorationModel.resolve(STONE, List.of(writer(1, GRANITE, true)), (a,b) -> false);
        assertTrue(outcome.canBe(STONE)); assertTrue(outcome.canBe(GRANITE)); assertEquals(MISSING, outcome.certain());
    }
    @Test void gravelPreventsLaterStoneReplacement() {
        var traces = List.of(writer(1, GRAVEL, false), writer(2, DIORITE, false));
        assertEquals(GRAVEL, SeedDecorationModel.resolve(STONE, traces, (a,b) -> false).certain());
    }
    @Test void localFeatureOrderIsPreservedWhileOptionalBranchesMerge() {
        var trace = new SeedDecorationModel.Writer(1, List.of(new SeedDecorationModel.Write(GRAVEL, true), new SeedDecorationModel.Write(DIORITE, false)));
        var outcome = SeedDecorationModel.resolve(STONE, List.of(trace), (a,b) -> false);
        assertTrue(outcome.canBe(GRAVEL)); assertTrue(outcome.canBe(DIORITE)); assertFalse(outcome.canBe(STONE));
    }
    @Test void contradictingWitnessesFallBackToUnknownOrder() {
        var outcome = SeedDecorationModel.resolve(STONE, List.of(writer(1, GRANITE, false), writer(2, DIORITE, false)), (a,b) -> true);
        assertEquals(MISSING, outcome.certain()); assertEquals(3, outcome.familyMask());
    }
    @Test void boundedFallbackNeverClaimsCertaintyForAComplexTrace() {
        var many = java.util.stream.LongStream.range(0, 5).mapToObj(id -> writer(id, GRANITE, false)).toList();
        var outcome = SeedDecorationModel.resolve(STONE, many, (a,b) -> false);
        assertFalse(outcome.exact()); assertTrue(outcome.canBe(STONE)); assertTrue(outcome.canBe(GRANITE));
    }
    @Test void nonReplaceableTerrainCannotBeChangedByAnOreTrace() {
        assertEquals(CLAY, SeedDecorationModel.resolve(CLAY, List.of(writer(1, GRANITE, false)), (a,b) -> false).certain());
        assertEquals(AIR, SeedDecorationModel.resolve(AIR, List.of(writer(1, DIORITE, true)), (a,b) -> false).certain());
    }
}
