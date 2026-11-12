package dev.donut.addon.expansion.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AutomationDisableCleanupTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void ownedCursorCleanupHookRunsExactlyOnceBeforeSessionInvalidation() {
        var probe = new DisableProbe();

        probe.onDisabled();

        assertEquals(List.of("owned-cleanup:0", "reset:1"), probe.callbacks);
    }

    private static final class DisableProbe extends DonutAutomationModule {
        private final List<String> callbacks = new ArrayList<>();

        private DisableProbe() { super("Donut Automation Disable Probe"); }

        @Override
        protected void onAutomationDisabled() {
            callbacks.add("owned-cleanup:" + generation());
        }

        @Override
        protected void onAutomationReset() {
            callbacks.add("reset:" + generation());
        }

        private long generation() {
            try {
                Field field = DonutAutomationModule.class.getDeclaredField("generation");
                field.setAccessible(true);
                return field.getLong(this);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
    }
}
