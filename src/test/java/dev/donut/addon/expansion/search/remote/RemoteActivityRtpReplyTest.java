package dev.donut.addon.expansion.search.remote;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RemoteActivityRtpReplyTest {
    @Test
    void classifiesCooldownAndKeepsWarmupAndNextUseDurationsSeparate() {
        RemoteActivityRtpReply.Reply cooldown = RemoteActivityRtpReply.parse("You can use RTP again in 30 seconds.");
        assertEquals(RemoteActivityRtpReply.Kind.COOLDOWN, cooldown.kind());
        assertEquals(30_000L, cooldown.millis());

        RemoteActivityRtpReply.Reply warmup = RemoteActivityRtpReply.parse(
            "Teleporting you in 5 seconds. You can use RTP again in 2 minutes.");
        assertEquals(RemoteActivityRtpReply.Kind.WARMUP, warmup.kind());
        assertEquals(5_000L, warmup.millis());
        assertEquals(120_000L, warmup.nextUseMs());
    }

    @Test
    void classifiesProgressCancellationAndTerminalFailures() {
        assertEquals(RemoteActivityRtpReply.Kind.WARMUP, RemoteActivityRtpReply.parse("Searching for a safe location").kind());
        assertEquals(RemoteActivityRtpReply.Kind.CANCELLED, RemoteActivityRtpReply.parse("Teleport cancelled because you moved").kind());
        assertEquals(RemoteActivityRtpReply.Kind.UNKNOWN_COMMAND, RemoteActivityRtpReply.parse("Unknown or incomplete command").kind());
        assertEquals(RemoteActivityRtpReply.Kind.NO_PERMISSION, RemoteActivityRtpReply.parse("You do not have permission to use this command").kind());
        assertEquals(RemoteActivityRtpReply.Kind.COMBAT, RemoteActivityRtpReply.parse("You are in combat").kind());
        assertEquals(RemoteActivityRtpReply.Kind.NONE, RemoteActivityRtpReply.parse("You are no longer in combat").kind());
    }

    @Test
    void parsesCompoundDurationsAndClockDurations() {
        assertEquals(3_723_500L, RemoteActivityRtpReply.durationMs("wait 1 hour 2 minutes and 3.5 seconds"));
        assertEquals(155_000L, RemoteActivityRtpReply.durationMs("available in 2:35"));
        assertEquals(-1L, RemoteActivityRtpReply.durationMs("soon"));
    }

    @Test
    void normalizesFormattingSmallCapsAndTurkishText() {
        assertEquals("teleporting in 5 seconds", RemoteActivityRtpReply.normalize("§aᴛᴇʟᴇᴘᴏʀᴛɪɴɢ in 5 seconds"));
        assertEquals(RemoteActivityRtpReply.Kind.NO_PERMISSION, RemoteActivityRtpReply.parse("Yetkiniz yok").kind());
    }

    @Test
    void avoidsTreatingOrdinaryPlayerChatAsACommandReply() {
        assertTrue(RemoteActivityRtpReply.looksLikePlayerChat("Notch: use /rtp again", List.of("Notch", "Alex")));
        assertTrue(RemoteActivityRtpReply.looksLikePlayerChat("[Alex] > cooldown is 5 seconds", List.of("Notch", "Alex")));
        assertFalse(RemoteActivityRtpReply.looksLikePlayerChat("RTP cooldown: 5 seconds", List.of("Notch", "Alex")));
        assertFalse(RemoteActivityRtpReply.looksLikePlayerChat("Notch is not in combat", List.of("Notch")));
    }
}
