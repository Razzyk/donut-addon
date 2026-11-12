package dev.donut.addon.expansion.search.seed;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SeedGroundCaptureTest {
    @Test void completeEmptyChunkIsBoundedAndEventuallyFinishes() {
        var scan = new SeedGroundCapture(1); var reads = new AtomicInteger();
        var air = new SeedGroundCapture.Cell(false,false,false);
        int ticks = 0;
        while (scan.state() == SeedGroundCapture.State.READING) {
            int before = reads.get();
            scan.step(1,4096,Long.MAX_VALUE,() -> 0L,(x,y,z) -> {reads.incrementAndGet(); return air;});
            assertTrue(reads.get()-before <= 4096); assertTrue(++ticks <= 24);
        }
        assertEquals(256*384, reads.get());
        assertEquals(-64, scan.terrain()[0]); assertEquals(-64, scan.motion()[255]);
    }
    @Test void iceMakesTerrainUnknownWithoutLosingMotionHeight() {
        var scan = new SeedGroundCapture(1);
        scan.step(1,100_000,Long.MAX_VALUE,() -> 0L,(x,y,z) ->
            new SeedGroundCapture.Cell(y==100 || y==64,y==64,true));
        assertEquals(SeedGroundCapture.State.COMPLETE,scan.state());
        assertEquals(101,scan.motion()[0]); assertEquals(SeedGroundEvidence.UNKNOWN,scan.terrain()[0]);
        var copy=scan.motion(); copy[0]=0; assertEquals(101,scan.motion()[0]);
    }
    @Test void changedOrUnloadedChunkCannotYieldACompleteSnapshot() {
        var scan = new SeedGroundCapture(3);
        assertEquals(SeedGroundCapture.State.STALE,scan.step(4,100,Long.MAX_VALUE,() -> 0L,(x,y,z) -> fail("Must not read changed chunk")));
        assertThrows(IllegalStateException.class,scan::terrain);
        var unloaded = new SeedGroundCapture(4);
        assertEquals(SeedGroundCapture.State.STALE,unloaded.step(4,100,Long.MAX_VALUE,() -> 0L,(x,y,z) -> null));
    }
    @Test void expiredDeadlinePreventsAnyWorldReads() {
        var scan = new SeedGroundCapture(1);
        assertEquals(SeedGroundCapture.State.READING,scan.step(1,4096,20,() -> 20L,(x,y,z) -> fail("Deadline exceeded")));
    }
}
