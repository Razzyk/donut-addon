package dev.donut.addon.expansion.search.seed;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedFogSemanticsTest {
    @BeforeAll static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void oreFogChangesCertaintyWithoutChangingFamilyTopology() {
        long position = SeedScanCore.packBlock(2, 10, 3);
        var fog = fog(1L, 0, 0, SeedScanCore.ORE, position);

        var outcome = VanillaSeedWorldgen.resolveFogOutcome(SeedScanCore.GRANITE, List.of(),
            List.of(fog), position, new SeedWriterOrder());

        assertEquals(1, outcome.familyMask());
        assertEquals(SeedScanCore.MISSING, outcome.certain());
        assertTrue(outcome.exact());
        assertFalse(VanillaSeedWorldgen.candidateHasUnknownFog(List.of(fog), new long[]{position}, new long[0]));
    }

    @Test void familyFogAddsPossibleTopologyWithoutCertifyingItsCell() {
        long position = SeedScanCore.packBlock(2, 10, 3);
        var fog = fog(1L, 0, 0, SeedScanCore.GRANITE, position);

        var outcome = VanillaSeedWorldgen.resolveFogOutcome(SeedScanCore.STONE, List.of(),
            List.of(fog), position, new SeedWriterOrder());

        assertEquals(1, outcome.familyMask());
        assertEquals(SeedScanCore.MISSING, outcome.certain());
        assertTrue(outcome.exact());
    }

    @Test void unknownFogCannotCertifyTopology() {
        long position = SeedScanCore.packBlock(2, 10, 3);
        var fog = new SeedSymbolicOreReplay.Fog(1L, 2, 10, 3, 2, 10, 3, "unknown test fog");

        var outcome = VanillaSeedWorldgen.resolveFogOutcome(SeedScanCore.GRANITE, List.of(),
            List.of(fog), position, new SeedWriterOrder());

        assertEquals(15, outcome.familyMask());
        assertEquals(SeedScanCore.MISSING, outcome.certain());
        assertFalse(outcome.exact());
        assertTrue(VanillaSeedWorldgen.candidateHasUnknownFog(List.of(fog), new long[]{position}, new long[0]));
    }

    @Test void sameOriginFogWritesFollowFeatureOrder() {
        long origin = 11L;
        long position = SeedScanCore.packBlock(2, 10, 3);
        var first = new VanillaSeedWorldgen.CellWrite(origin, 2, 3, 0,
            new SeedDecorationModel.Write(SeedScanCore.GRANITE, false));
        var laterFog = fog(origin, 2, 4, SeedScanCore.DIORITE, position);

        var outcome = VanillaSeedWorldgen.resolveFogOutcome(SeedScanCore.STONE, List.of(first),
            List.of(laterFog), position, new SeedWriterOrder());

        assertEquals(3, outcome.familyMask());
        assertEquals(SeedScanCore.MISSING, outcome.certain());
    }

    @Test void crossOriginFogHonorsKnownOrderAndKeepsUnknownOrderOpen() {
        long position = SeedScanCore.packBlock(2, 10, 3);
        long fogOrigin = 11L;
        long nativeOrigin = 22L;
        var fog = fog(fogOrigin, 2, 3, SeedScanCore.GRANITE, position);
        var nativeWrite = new VanillaSeedWorldgen.CellWrite(nativeOrigin, 2, 3, 0,
            new SeedDecorationModel.Write(SeedScanCore.DIORITE, false));

        var unknownOrder = VanillaSeedWorldgen.resolveFogOutcome(SeedScanCore.STONE, List.of(nativeWrite),
            List.of(fog), position, new SeedWriterOrder());
        assertEquals(3, unknownOrder.familyMask());
        assertEquals(SeedScanCore.MISSING, unknownOrder.certain());

        SeedWriterOrder knownOrder = new SeedWriterOrder();
        assertTrue(knownOrder.replace(0L, List.of(
            new SeedWriterOrder.Witness(position, fogOrigin, nativeOrigin),
            new SeedWriterOrder.Witness(position + 1, fogOrigin, nativeOrigin),
            new SeedWriterOrder.Witness(position + 2, fogOrigin, nativeOrigin))));
        var ordered = VanillaSeedWorldgen.resolveFogOutcome(SeedScanCore.STONE, List.of(nativeWrite),
            List.of(fog), position, knownOrder);
        assertEquals(2, ordered.familyMask());
        assertEquals(SeedScanCore.DIORITE, ordered.certain());
    }

    private static SeedSymbolicOreReplay.Fog fog(long origin, int stage, int featureIndex, byte kind, long position) {
        int x = SeedScanCore.unpackX(position), y = SeedScanCore.unpackY(position), z = SeedScanCore.unpackZ(position);
        return new SeedSymbolicOreReplay.Fog(origin, x, y, z, x, y, z, "typed test fog", stage, featureIndex, kind);
    }
}
