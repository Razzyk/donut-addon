package dev.donut.addon.expansion.automation;

import static org.junit.jupiter.api.Assertions.*;

import dev.donut.addon.expansion.automation.core.AutomationCombatLock;
import dev.donut.addon.expansion.automation.core.BlastShieldPlanner;
import dev.donut.addon.expansion.automation.core.CombatDamageRules;
import dev.donut.addon.expansion.automation.core.CrystalAttemptLedger;
import dev.donut.addon.expansion.automation.core.LootProtectionLedger;
import dev.donut.addon.expansion.automation.core.ObservedPlayerDeath;
import dev.donut.addon.expansion.automation.core.ShieldPlacementCandidate;
import dev.donut.addon.expansion.automation.core.WorldPoint;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CombatSafetyCoreTest {
    @Test
    void damageGatesUsePointUnitsAndTotemOnlyBypassesAnchorLethality() {
        assertTrue(CombatDamageRules.INSTANCE.crystalAllowed(6, 20, 8, false));
        assertFalse(CombatDamageRules.INSTANCE.crystalAllowed(9, 20, 8, true));
        assertFalse(CombatDamageRules.INSTANCE.crystalAllowed(6, 8, 8, false));
        assertTrue(CombatDamageRules.INSTANCE.anchorAllowed(20, 8, true, false));
        assertFalse(CombatDamageRules.INSTANCE.anchorAllowed(20, 8, false, false));
        assertFalse(CombatDamageRules.INSTANCE.anchorAllowed(Double.NaN, 20, true, false));
    }

    @Test
    void crystalAttemptsRestAfterThreeUnconfirmedBreaksAndForgetRemovedEntities() {
        var attempts = new CrystalAttemptLedger(3, 100);
        attempts.recordAttempt(7, 10);
        attempts.recordAttempt(7, 20);
        assertTrue(attempts.canAttempt(7, 20));
        attempts.recordAttempt(7, 30);
        assertFalse(attempts.canAttempt(7, 129));
        assertTrue(attempts.canAttempt(7, 130));
        attempts.recordAttempt(9, 30);
        attempts.forgetMissing(Set.of(7));
        assertTrue(attempts.canAttempt(9, 31));
    }

    @Test
    void lockIsExclusiveAndOnlyItsOwnerCanReleaseIt() {
        Object first = new Object();
        Object second = new Object();
        assertTrue(AutomationCombatLock.INSTANCE.tryAcquire(first));
        assertFalse(AutomationCombatLock.INSTANCE.tryAcquire(second));
        AutomationCombatLock.INSTANCE.release(second);
        assertTrue(AutomationCombatLock.INSTANCE.tryAcquire(first));
        AutomationCombatLock.INSTANCE.release(first);
        assertTrue(AutomationCombatLock.INSTANCE.tryAcquire(second));
        AutomationCombatLock.INSTANCE.release(second);
    }

    @Test
    void lootProtectionPersistsOnlyWhileTheDeathSiteMayContainDrops() {
        var ledger = new LootProtectionLedger();
        var local = new WorldPoint(0, 64, 0);
        var death = new WorldPoint(4, 64, 1);
        var item = new WorldPoint(5, 64, 1);
        var players = List.of(new ObservedPlayerDeath(44, death, true, false));
        ledger.update(10_000, "overworld", 1, local, players, List.of(item));
        assertTrue(ledger.protects("overworld", new WorldPoint(9, 64, 2)));
        ledger.update(14_000, "overworld", 1, local, players, List.of(item));
        assertTrue(ledger.protects("overworld", new WorldPoint(9, 64, 2)));
        ledger.update(14_100, "overworld", 1, local, players, List.of());
        assertFalse(ledger.protects("overworld", new WorldPoint(9, 64, 2)));

        ledger.update(15_000, "overworld", 1, local, List.of(), List.of());
        ledger.update(15_100, "overworld", 1, local, players, List.of(item));
        assertTrue(ledger.protects("overworld", new WorldPoint(9, 64, 2)));
        assertFalse(ledger.protects("the_nether", new WorldPoint(9, 64, 2)));
        ledger.update(15_200, "the_nether", 1, local, List.of(), List.of());
        assertFalse(ledger.protects("overworld", new WorldPoint(9, 64, 2)));
    }

    @Test
    void shieldPlannerRejectsUnsafePlacementsAndPicksTheBestGuard() {
        var blocked = new ShieldPlacementCandidate(1, 2, "NORTH", 20, 2, true, true, true);
        var weak = new ShieldPlacementCandidate(2, 3, "SOUTH", 20, 18, true, true, false);
        var strong = new ShieldPlacementCandidate(3, 4, "WEST", 20, 5, true, true, false);
        var unreachable = new ShieldPlacementCandidate(4, 5, "EAST", 20, 0, true, false, false);
        assertEquals(strong, BlastShieldPlanner.INSTANCE.choose(List.of(blocked, weak, strong, unreachable), 0.15));
        assertNull(BlastShieldPlanner.INSTANCE.choose(List.of(weak), 0.15));
    }
}
