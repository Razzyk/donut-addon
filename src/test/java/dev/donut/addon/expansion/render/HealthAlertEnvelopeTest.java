package dev.donut.addon.expansion.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HealthAlertEnvelopeTest {
    @Test void thresholdAndEligibilityAvoidFalseAlerts() {
        var envelope = new HealthAlertEnvelope();
        envelope.sample(1, 7, 7, true, false, HealthAlertPattern.STEADY);
        assertEquals(0f, envelope.sample(100_000_001, 7, 7, true, false, HealthAlertPattern.STEADY).getVisibility());
        assertEquals(0f, envelope.sample(200_000_001, 1, 7, false, false, HealthAlertPattern.STEADY).getVisibility());
        assertTrue(envelope.sample(300_000_001, 6, 7, true, false, HealthAlertPattern.STEADY).getVisibility() > 0f);
    }

    @Test void frameGapAndExplicitResetDiscardOldWorldAnimation() {
        var envelope = new HealthAlertEnvelope();
        envelope.sample(1, 1, 7, true, false, HealthAlertPattern.HEARTBEAT);
        for (int i = 1; i <= 10; i++) envelope.sample(i * 50_000_000L + 1, 1, 7, true, false, HealthAlertPattern.HEARTBEAT);
        assertTrue(envelope.sample(550_000_001, 1, 7, true, false, HealthAlertPattern.HEARTBEAT).getVisibility() > 0.8f);
        envelope.clear();
        assertEquals(0f, envelope.sample(550_000_002, 1, 7, true, false, HealthAlertPattern.HEARTBEAT).getVisibility());
        assertEquals(0f, envelope.sample(900_000_003, 20, 7, true, false, HealthAlertPattern.HEARTBEAT).getVisibility());
    }

    @Test void deathFadesTheAlertFasterThanOrdinaryRecovery() {
        var calm = new HealthAlertEnvelope();
        var dead = new HealthAlertEnvelope();
        for (int i = 0; i < 10; i++) {
            calm.sample(i * 50_000_000L, 1, 7, true, false, HealthAlertPattern.STEADY);
            dead.sample(i * 50_000_000L, 1, 7, true, false, HealthAlertPattern.STEADY);
        }
        var recovered = calm.sample(500_000_000L, 20, 7, true, false, HealthAlertPattern.STEADY);
        var dying = dead.sample(500_000_000L, 0, 7, true, true, HealthAlertPattern.STEADY);
        assertTrue(dying.getVisibility() < recovered.getVisibility());
    }

    @Test void animationRemainsBoundedUnderClockReversalAndInvalidHealth() {
        for (var pattern : HealthAlertPattern.values()) {
            var envelope = new HealthAlertEnvelope();
            for (long now : new long[]{0, 50_000_000, 100_000_000, 80_000_000, 120_000_000, 1_000_000_000}) {
                var frame = envelope.sample(now, 1, 7, true, false, pattern);
                assertTrue(frame.getVisibility() >= 0f && frame.getVisibility() <= 1f);
                assertTrue(frame.getBeat() >= 0f && frame.getBeat() <= 1f);
            }
            envelope.clear();
            assertEquals(0f, envelope.sample(0, Float.NaN, 7, true, false, pattern).getVisibility());
            assertEquals(0f, envelope.sample(100_000_000, 1, 0, true, false, pattern).getVisibility());
        }
    }
}
