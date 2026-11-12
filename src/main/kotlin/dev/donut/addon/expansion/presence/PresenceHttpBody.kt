package dev.donut.addon.expansion.presence

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

internal object PresenceHttpBody {
    /** The limit applies after decompression, and a truncated page is never parsed as complete. */
    fun readUtf8(input: InputStream, limit: Int): String {
        require(limit > 0)
        val buffer = ByteArray(minOf(16_384, limit))
        val output = ByteArrayOutputStream()
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) return output.toString(StandardCharsets.UTF_8)
            if (output.size() + count > limit) throw IllegalStateException("Live page exceeds the response limit")
            output.write(buffer, 0, count)
        }
    }
}
