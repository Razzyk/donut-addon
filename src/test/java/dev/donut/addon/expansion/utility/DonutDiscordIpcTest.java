package dev.donut.addon.expansion.utility;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DonutDiscordIpcTest {
    @Test
    void framesUseDiscordLittleEndianOpcodeAndUtf8PayloadLength() {
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = DonutDiscordIpc.frameBytes(1, body);
        ByteBuffer header = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, header.getInt());
        assertEquals(body.length, header.getInt());
        byte[] payload = new byte[body.length];
        header.get(payload);
        assertArrayEquals(body, payload);
    }

    @Test
    void jsonEscapingKeepsUserLabelsInsideOneString() {
        assertEquals("\"Line \\\"A\\\"\\nnext\\\\path\"", DonutDiscordIpc.quote("Line \"A\"\nnext\\path"));
        assertEquals("\"\"", DonutDiscordIpc.quote(null));
    }
}
