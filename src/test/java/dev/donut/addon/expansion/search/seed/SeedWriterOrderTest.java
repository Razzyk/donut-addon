package dev.donut.addon.expansion.search.seed;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SeedWriterOrderTest {
    private static SeedWriterOrder.Witness vote(long position,long a,long b) { return new SeedWriterOrder.Witness(position,a,b); }
    @Test void repeatedPositionCannotEstablishOrder() {
        var order=new SeedWriterOrder();
        order.replace(1,List.of(vote(1,10,20),vote(1,10,20),vote(1,10,20)));
        assertFalse(order.precedes(10,20));
        order.replace(1,List.of(vote(1,10,20),vote(2,10,20),vote(3,10,20)));
        assertTrue(order.precedes(10,20)); assertFalse(order.precedes(20,10));
    }
    @Test void oneOpposingWitnessRestoresUncertaintyAndRemovalRestoresOrder() {
        var order=new SeedWriterOrder();
        order.replace(1,List.of(vote(1,-10,20),vote(2,-10,20),vote(3,-10,20)));
        assertTrue(order.precedes(-10,20));
        order.replace(2,List.of(vote(4,20,-10)));
        assertFalse(order.precedes(-10,20)); assertFalse(order.precedes(20,-10));
        order.remove(2); assertTrue(order.precedes(-10,20));
    }
    @Test void replacingAChunkRetractsItsOldEvidenceInsteadOfDoubleCounting() {
        var order=new SeedWriterOrder();
        var one=List.of(vote(1,10,20));
        for(int i=0;i<10;i++) order.replace(1,one);
        assertFalse(order.precedes(10,20));
        order.replace(2,List.of(vote(2,10,20),vote(3,10,20)));
        assertTrue(order.precedes(10,20));
        order.replace(1,List.of()); assertFalse(order.precedes(10,20));
        order.clear(); assertFalse(order.precedes(10,20));
    }
    @Test void conflictingDuplicatePositionsDoNotBecomeAConfidentVote() {
        var order=new SeedWriterOrder();
        order.replace(1,List.of(vote(1,10,20),vote(1,20,10),vote(2,10,20),vote(3,10,20)));
        assertFalse(order.precedes(10,20));
    }
    @Test void discardedSourcesCannotKeepOrderingWitnessesAlive() {
        var order=new SeedWriterOrder();
        order.replace(1,List.of(vote(1,10,20),vote(2,10,20),vote(3,10,20)));
        assertTrue(order.precedes(10,20));
        order.retainSources(java.util.Set.of(2L));
        assertFalse(order.precedes(10,20));
    }
}
