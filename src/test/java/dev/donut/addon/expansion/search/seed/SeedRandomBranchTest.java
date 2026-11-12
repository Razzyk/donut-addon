package dev.donut.addon.expansion.search.seed;

import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedRandomBranchTest {
    @Test void mixedFeatureDrawsMatchActualNativeWorldgenRandom() {
        long seed = 780123456789L;
        var expected = new WorldgenRandom(new XoroshiroRandomSource(seed));
        long decoration = expected.setDecorationSeed(seed,-112,304);
        expected.setFeatureSeed(decoration,17,6);
        var actual = SeedRandomBranch.feature(seed,-112,304,17,6);
        for(int i=0;i<200;i++) {
            assertEquals(expected.nextInt(i+1),actual.rollInt(i+1));
            assertEquals(expected.nextFloat(),actual.unitFloat());
            assertEquals(expected.nextDouble(),actual.unitDouble());
            assertEquals(expected.nextLong(),actual.wordLong());
        }
    }
    @Test void branchAdvancementDoesNotMutateItsParent() {
        var parent=SeedRandomBranch.feature(-123,0,16,0,6);
        parent.unitDouble();
        var saved=parent.freeze(); var copy=parent.branch();
        assertEquals(saved,copy.freeze());
        long first=copy.wordLong();
        assertEquals(saved,parent.freeze());
        assertEquals(first,parent.wordLong());
        assertEquals(parent.freeze(),copy.freeze());
        copy.advance(30); assertNotEquals(parent.freeze(),copy.freeze());
        assertEquals(saved,SeedRandomBranch.resume(saved).freeze());
    }
    @Test void drawAndSkipInvalidateCachedState() {
        var branch=SeedRandomBranch.feature(42,32,-64,5,6);
        var before=branch.freeze();
        branch.rollInt(100);
        assertNotEquals(before,branch.freeze());
        var expected = branch.branch();
        for (int i=0;i<10;i++) expected.rollInt(1 << 30);
        branch.advance(10);
        assertEquals(expected.freeze(),branch.freeze());
        assertThrows(IllegalArgumentException.class,() -> branch.advance(-1));
    }
}
