package dev.donut.addon.expansion;

import dev.donut.addon.expansion.automation.AutomationFeatures;
import dev.donut.addon.expansion.automation.DonutQuickTransfer;
import net.ccbluex.liquidbounce.config.types.Value;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MacroLifecycleTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void automationCannotRestoreAnEnabledStateFromAProfile() {
        var drafts = MacroDrafts.modules();
        assertEquals(11, drafts.size());
        assertTrue(AutomationFeatures.INSTANCE.getModules().isEmpty(), "unfinished replacements must not duplicate existing macros");
        for (var module : drafts) {
            assertFalse(module.getEnabledValue().isPersistent(), module.getName());
            assertFalse(module.getEnabledValue().checkIfInclude(), module.getName());
            assertTrue(module.getDisableOnQuit(), module.getName());
        }
    }

    @SuppressWarnings("unchecked")
    @Test void transferDefaultsToTheRequestedRecipientAndKeepsEditsWithinTheRunningApp() {
        var transfer = new DonutQuickTransfer();
        var recipient = (Value<String>) transfer.get().stream()
            .filter(value -> value.getName().equals("Pay To")).findFirst().orElseThrow();
        assertEquals("Razzy6", recipient.get());
        assertFalse(recipient.isPersistent());
        assertFalse(recipient.checkIfInclude());
        recipient.set("AnotherPlayer");
        assertEquals("AnotherPlayer", recipient.get());
        transfer.onDisabled();
        assertEquals("AnotherPlayer", recipient.get());
    }

    @SuppressWarnings("unchecked")
    @Test void disconnectCallbackClearsQueuedPaymentAndKeepsTheEditableSetting() throws Exception {
        var transfer = new DonutQuickTransfer();
        var recipient = (Value<String>) transfer.get().stream()
            .filter(value -> value.getName().equals("Pay To")).findFirst().orElseThrow();
        recipient.set("AnotherPlayer");
        var queuedTarget = transfer.getClass().getDeclaredField("target");
        queuedTarget.setAccessible(true);
        queuedTarget.set(transfer, "PreviouslyQueuedRecipient");
        assertFalse(transfer.getEnabled());
        var lifecycle = AutomationFeatures.INSTANCE.getListeners().getFirst();
        assertTrue(lifecycle.getRunning(), "world cleanup must not depend on an enabled module or loaded world");
        var reset = java.util.Arrays.stream(transfer.getClass().getSuperclass().getDeclaredMethods())
            .filter(method -> method.getName().startsWith("onAutomationWorldChanged$") && method.getParameterCount() == 0)
            .findFirst().orElseThrow();
        reset.invoke(transfer);
        assertEquals("AnotherPlayer", recipient.get());
        assertEquals("", queuedTarget.get(transfer));
        recipient.set("Razzy6");
    }
}
