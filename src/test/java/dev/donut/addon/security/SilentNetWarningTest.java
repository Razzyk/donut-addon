package dev.donut.addon.security;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SilentNetWarningTest {
    @Test void noFindingDoesNotProduceAnAllClearOrAnAlarm() {
        assertTrue(SilentNetWarning.messages(List.of(), false).isEmpty());
        assertTrue(SilentNetWarning.messages(List.of(), true).isEmpty());
    }

    @Test void warningsExplainUncertaintyAndMakeRecoveryConditional() {
        var messages = SilentNetWarning.messages(List.of("example.jar: published sample hash"), true);
        String text = messages.stream().map(Component::getString).reduce("", (a, b) -> a + "\n" + b);
        assertEquals(5, messages.size());
        assertTrue(text.contains("This may be a false positive"));
        assertTrue(text.contains("check incomplete"));
        assertTrue(text.contains("clean device"));
        assertTrue(text.contains("revoke sessions"));
        assertTrue(text.contains("If confirmed"));
        assertTrue(text.contains("factory reset"));
        var clicks = new ArrayList<ClickEvent>();
        messages.forEach(message -> collectClicks(message, clicks));
        assertEquals(List.of(new ClickEvent.OpenUrl(URI.create("https://www.malwation.com/blog/inside-silentnet-analysis-of-a-java-based-infostealer-with-blockchain-based-c2"))), clicks);
    }

    @Test void filenamesCannotInjectLinesFormattingOrAlternateLinks() {
        var messages = SilentNetWarning.messages(List.of("x\n\r\u00a7c\u202e.jar" + "a".repeat(200), "second.jar", "third.jar"), false);
        String evidence = messages.getFirst().getString();
        assertFalse(evidence.contains("\n"));
        assertFalse(evidence.contains("\r"));
        assertFalse(evidence.contains("\u00a7"));
        assertFalse(evidence.contains("\u202e"));
        assertFalse(evidence.contains("a".repeat(100)));
        assertTrue(evidence.contains("(+1 more)"));
        assertNull(messages.getFirst().getStyle().getClickEvent());
    }

    private static void collectClicks(Component message, List<ClickEvent> clicks) {
        if (message.getStyle().getClickEvent() != null) clicks.add(message.getStyle().getClickEvent());
        message.getSiblings().forEach(child -> collectClicks(child, clicks));
    }
}
