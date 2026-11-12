package dev.donut.addon.expansion.presence

import java.text.Normalizer
import java.util.Locale

/** Donut-specific staff recognition from profile, formatted tab text and scoreboard team. */
class DonutStaffMatcher(
    rankWords: String = DEFAULT_RANK_WORDS,
    markerGlyphs: String = DEFAULT_MARKERS,
    private val includePrivateIcons: Boolean = false,
    maxTeamRank: Int = 5,
    knownPlayers: Collection<String> = DONUT_NAMES,
    ignoredPlayers: Collection<String> = emptyList(),
) {
    data class Classification(val label: String, val order: Int)

    private val rankLabels = linkedMapOf<String, String>()
    private val glyphs = parseGlyphs(markerGlyphs)
    private val known = knownPlayers.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
    private val ignored = ignoredPlayers.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
    private val rankCap = maxTeamRank.coerceIn(0, 99)

    init {
        rankWords.split(',').forEach { input ->
            val normalized = fold(input).filter(Char::isLetter)
            if (normalized.isNotEmpty() && normalized !in rankLabels) {
                rankLabels[normalized] = DISPLAY[normalized] ?: input.trim().replaceFirstChar { it.uppercase() }
            }
        }
    }

    fun classify(name: String?, teamName: String?, vararg formattedParts: String?): Classification? {
        if (name.isNullOrBlank() || name.lowercase(Locale.ROOT) in ignored) return null
        val outsideName = buildString {
            formattedParts.filterNotNull().forEach { append(it).append(' ') }
            teamName?.let { append(it).append(' ') }
        }.replace(Regex("§."), " ").replace(name, " ", ignoreCase = true)

        val tokens = fold(outsideName).split(Regex("[^a-z]+") ).filter(String::isNotEmpty)
        var best: Pair<Int, String>? = null
        for (start in tokens.indices) {
            for (span in 1..3) {
                if (start + span > tokens.size) break
                val key = tokens.subList(start, start + span).joinToString("")
                val label = rankLabels[key] ?: continue
                val order = rankLabels.keys.indexOf(key)
                if (best == null || order < best.first) best = order to label
            }
        }
        best?.let { return Classification(it.second, it.first) }

        val teamRank = TEAM_PREFIX.find(teamName.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        if (teamRank != null && teamRank <= rankCap) return Classification(teamRank.toString().padStart(2, '0'), 500 + teamRank)

        val hasGlyph = outsideName.codePoints().anyMatch { glyphs.contains(it) || includePrivateIcons && isPrivateIcon(it) }
        if (hasGlyph || name.lowercase(Locale.ROOT) in known) return Classification("Staff", 700)
        return null
    }

    companion object {
        val DONUT_NAMES = listOf(
            "FluffyMaster07", "archivePedro", "Munkerlich", "Frenk_Btw", "Napooo_", "auzzitech",
            "CryptoDaveYT", "W1zox_", "ZEEF69", "showered", "CaptainMoose35", "BobIsFound",
            "NoahvdAa", "0GSummer", "LzouzMP5", "Pastagamer08", "u_vv", "Owen1212055",
            "Splaterd", "Fallerfly", "Bloodspulse", "_chaon", "Dough4", "evify"
        )

        const val DEFAULT_RANK_WORDS = "owner, co-owner, manager, admin, administrator, developer, dev, sr-admin, sr-mod, senior-mod, moderator, mod, trial-mod, sr-helper, senior-helper, helper, trial-helper, support, staff"
        const val DEFAULT_MARKERS = "U+2605 U+2606 U+2726 U+2727 U+272A U+272F U+2730 U+2B50"
        private val TEAM_PREFIX = Regex("^(\\d{2})-")
        private val CODE_POINT = Regex("(?i)(?:u\\+|\\\\u)([0-9a-f]{4,6})")
        private val DISPLAY = mapOf(
            "owner" to "Owner", "coowner" to "Co-Owner", "manager" to "Manager", "admin" to "Admin",
            "administrator" to "Admin", "sradmin" to "Sr. Admin", "developer" to "Dev", "dev" to "Dev",
            "srmod" to "Sr. Mod", "seniormod" to "Sr. Mod", "moderator" to "Mod", "mod" to "Mod",
            "trialmod" to "Trial Mod", "srhelper" to "Sr. Helper", "seniorhelper" to "Sr. Helper",
            "helper" to "Helper", "trialhelper" to "Trial Helper", "support" to "Support", "staff" to "Staff",
            "builder" to "Builder"
        )

        private fun fold(raw: String): String {
            val decomposed = Normalizer.normalize(raw.replace('\u01eb', 'q'), Normalizer.Form.NFKD).lowercase(Locale.ROOT)
            return buildString(decomposed.length) {
                decomposed.codePoints().forEach { cp ->
                    if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) return@forEach
                    append(smallLetter(cp) ?: if (cp in 'a'.code..'z'.code) cp.toChar() else ' ')
                }
            }
        }

        private fun smallLetter(cp: Int): Char? = when (cp) {
            7424 -> 'a'; 665 -> 'b'; 7428 -> 'c'; 7429 -> 'd'; 7431 -> 'e'; 42800 -> 'f'; 610 -> 'g';
            668 -> 'h'; 618 -> 'i'; 7434 -> 'j'; 7435 -> 'k'; 671 -> 'l'; 7437 -> 'm'; 628 -> 'n';
            7439 -> 'o'; 7448 -> 'p'; 640 -> 'r'; 42801 -> 's'; 7451 -> 't'; 7452 -> 'u'; 7456 -> 'v';
            7457 -> 'w'; 655 -> 'y'; 7458 -> 'z'; else -> null
        }

        private fun parseGlyphs(raw: String): Set<Int> = raw.split(Regex("[,\\s]+")).filter(String::isNotBlank).flatMap { token ->
            val match = CODE_POINT.matchEntire(token)
            if (match != null) listOfNotNull(match.groupValues[1].toIntOrNull(16)) else token.codePoints().toArray().toList()
        }.toSet()

        private fun isPrivateIcon(cp: Int): Boolean = cp in 0xE000..0xF8FF || cp in 0xF0000..0xFFFFD || cp in 0x100000..0x10FFFD
    }
}
