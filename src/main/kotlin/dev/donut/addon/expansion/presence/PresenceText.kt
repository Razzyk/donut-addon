package dev.donut.addon.expansion.presence

import java.text.Normalizer
import java.util.Locale

/** Small, side-effect-free text helpers used by the presence packet analyzers. */
object PresenceText {
    private val legacyCode = Regex("§[0-9a-fk-orx]", RegexOption.IGNORE_CASE)
    private val basicName = Regex("[A-Za-z0-9_]{1,16}")

    fun stripFormatting(value: String?): String = value.orEmpty().replace(legacyCode, "")

    /** Folds formatting, accents and common look-alike characters before comparing chat. */
    fun normalized(value: String?): String {
        val decomposed = Normalizer.normalize(stripFormatting(value), Normalizer.Form.NFKD).lowercase(Locale.ROOT)
        val out = StringBuilder(decomposed.length)
        decomposed.codePoints().forEach { point ->
            if (Character.getType(point) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(point) == Character.COMBINING_SPACING_MARK.toInt() ||
                Character.getType(point) == Character.ENCLOSING_MARK.toInt() || point in 0x200B..0x200D || point == 0xFEFF
            ) return@forEach
            val folded = confusable(point)
            when {
                folded in 'a'.code..'z'.code || folded in '0'.code..'9'.code -> out.append(folded.toChar())
                else -> out.append(' ')
            }
        }
        return out.toString().trim().replace(Regex("\\s+"), " ")
    }

    fun namesMentioned(message: String?, name: String?): Boolean {
        val cleaned = stripFormatting(message).lowercase(Locale.ROOT)
        val candidate = stripFormatting(name).trim().lowercase(Locale.ROOT)
        if (candidate.isEmpty()) return false
        if (basicName.matches(candidate)) {
            val escaped = Regex.escape(candidate)
            return Regex("(?<![a-z0-9_])$escaped(?![a-z0-9_])").containsMatchIn(cleaned)
        }
        val text = " ${normalized(cleaned)} "
        val token = " ${normalized(candidate)} "
        return token.length > 2 && text.contains(token)
    }

    fun looksLikeDeparture(message: String?, name: String?): Boolean {
        if (!namesMentioned(message, name)) return false
        val clean = " ${normalized(message)} "
        val player = normalized(name)
        if (player.isBlank()) return false
        return listOf("left the game", "left", "has left", "quit", "has quit", "disconnected", "logged out")
            .any { clean.contains(" $player $it ") }
    }

    fun plausiblePlayerName(name: String?): Boolean = name?.let { it.length in 3..16 && !it.any(Char::isWhitespace) } == true

    fun isUsernameLike(name: String?): Boolean = name != null && name.length in 2..32 &&
        name.any { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' } &&
        name.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' || it == ' ' }

    private fun confusable(cp: Int): Int = when (cp) {
        593, 945, 1072, 7424 -> 'a'.code
        665, 946, 1074 -> 'b'.code
        1010, 1089, 7428 -> 'c'.code
        1281, 7429 -> 'd'.code
        949, 1077, 7431 -> 'e'.code
        42800 -> 'f'.code
        609, 610 -> 'g'.code
        668, 1085 -> 'h'.code
        618, 953, 1110 -> 'i'.code
        1112, 7434 -> 'j'.code
        954, 1082, 7435 -> 'k'.code
        671 -> 'l'.code
        1084, 7437 -> 'm'.code
        628, 1400 -> 'n'.code
        959, 1086, 7439 -> 'o'.code
        961, 1088, 7448 -> 'p'.code
        1307 -> 'q'.code
        640 -> 'r'.code
        1109, 42801 -> 's'.code
        964, 1090, 7451 -> 't'.code
        965, 7452 -> 'u'.code
        957, 7456 -> 'v'.code
        1121, 7457 -> 'w'.code
        1093 -> 'x'.code
        655, 1091 -> 'y'.code
        7458, 7459 -> 'z'.code
        else -> cp
    }
}
