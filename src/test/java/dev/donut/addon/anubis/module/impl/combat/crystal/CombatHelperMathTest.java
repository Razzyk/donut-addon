package dev.donut.addon.anubis.module.impl.combat.crystal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
class CombatHelperMathTest {
    @Test void confirmationWindowUsesRoundTripAndBoundedMargin() {
        assertEquals(4, CombatHelperMath.confirmTicks(-1));
        assertEquals(4, CombatHelperMath.confirmTicks(0));
        assertEquals(5, CombatHelperMath.confirmTicks(101));
        assertEquals(12, CombatHelperMath.confirmTicks(500));
        assertEquals(40, CombatHelperMath.confirmTicks(Integer.MAX_VALUE));
    }
}
