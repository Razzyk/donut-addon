package dev.donut.addon.expansion.utility

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.BlankFormat
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.ScoreHolder
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.random.Random

/** Client-only custom sidebar and self-name presentation for screen shares and recordings. */
class DonutStatusMirror : DonutUtilityModule(
    "Donut Status Mirror",
    "Shows a private profile and configurable sample statistics on your local sidebar and own tab-list row."
) {
    private val playerName by text("Name", "")
    private val money by text("Money", "")
    private val shards by text("Shards", "")
    private val kills by text("Kills", "")
    private val deaths by text("Deaths", "")
    private val playtime by text("Playtime", "")
    private val team by text("Team", "")

    private var seed = 0L
    private var board: Scoreboard? = null
    private var fakeObjective: Objective? = null
    private var serverSidebar: Objective? = null
    private var updating = false

    override fun onEnabled() = startSession()

    override fun onUtilitySessionStarted(world: net.minecraft.client.multiplayer.ClientLevel) = startSession()

    private fun startSession() {
        seed = Random.nextLong()
        live = this
        refresh()
    }

    override fun onUtilitySessionEnded() {
        restoreBoard()
        if (live === this) live = null
    }

    override fun onDisabled() = onUtilitySessionEnded()

    @Suppress("unused")
    private val tick = handler<GameTickEvent> { refresh() }

    private fun refresh() {
        val nextBoard = mc.level?.scoreboard
        if (nextBoard == null) {
            restoreBoard()
            return
        }
        if (nextBoard !== board) {
            restoreBoard()
            board = nextBoard
            serverSidebar = nextBoard.getDisplayObjective(DisplaySlot.SIDEBAR)
            fakeObjective = null
        }
        val activeBoard = board ?: return
        val objective = fakeObjective ?: newObjective(activeBoard).also { fakeObjective = it }
        writeRows(activeBoard, objective)
        if (activeBoard.getDisplayObjective(DisplaySlot.SIDEBAR) !== objective) {
            updating = true
            try { activeBoard.setDisplayObjective(DisplaySlot.SIDEBAR, objective) } finally { updating = false }
        }
    }

    private fun newObjective(scoreboard: Scoreboard): Objective {
        var id: String
        do {
            id = "dn" + UUID.randomUUID().toString().replace("-", "").take(12)
        } while (scoreboard.getObjective(id) != null)
        return scoreboard.addObjective(
            id,
            ObjectiveCriteria.DUMMY,
            Component.literal("Profile"),
            ObjectiveCriteria.RenderType.INTEGER,
            false,
            null,
        )
    }

    private fun writeRows(scoreboard: Scoreboard, objective: Objective) {
        objective.displayName = Component.literal("Profile")
        val customName = playerName.trim().ifBlank { DonutPrivacyMath.pseudoName(seed) }
        val rows = listOf(
            "Name: $customName",
            statLine("Money", money, "$", 1),
            statLine("Shards", shards, "★", 2),
            statLine("Kills", kills, "⚔", 3),
            statLine("Deaths", deaths, "☠", 4),
            statLine("Playtime", playtime, "h", 5),
            "Team: " + team.trim().ifBlank { "Group ${DonutPrivacyMath.pseudoValue("team", seed)}" },
        )
        rows.forEachIndexed { index, text ->
            val holder = ScoreHolder.forNameOnly("\u200B".repeat(index + 1))
            val score = scoreboard.getOrCreatePlayerScore(holder, objective)
            score.set(rows.size - index)
            score.display(Component.literal(text.take(MAX_LINE_LENGTH)))
            score.numberFormatOverride(BlankFormat.INSTANCE)
        }
    }

    private fun statLine(label: String, configured: String, suffix: String, index: Int): String {
        val shown = configured.trim().ifBlank { DonutPrivacyMath.pseudoValue(label, seed xor index.toLong()) }
        val display = if (suffix == "$") "$suffix$shown" else "$shown $suffix"
        return "$label: $display"
    }

    private fun restoreBoard() {
        val priorBoard = board ?: return
        val priorObjective = fakeObjective
        updating = true
        try {
            if (priorBoard.getDisplayObjective(DisplaySlot.SIDEBAR) === priorObjective) {
                priorBoard.setDisplayObjective(DisplaySlot.SIDEBAR, serverSidebar)
            }
            if (priorObjective != null) priorBoard.removeObjective(priorObjective)
        } catch (failure: Throwable) {
            LOGGER.debug("Unable to restore the client sidebar after status mirroring", failure)
        } finally {
            updating = false
            board = null
            fakeObjective = null
            serverSidebar = null
        }
    }

    private fun keepSidebar(scoreboard: Scoreboard, requested: Objective?): Boolean {
        if (!enabled || updating || board !== scoreboard || fakeObjective == null) return false
        if (requested === fakeObjective) return false
        serverSidebar = requested
        return true
    }

    private fun replaceName(player: UUID?, original: Component?): Component? {
        if (!enabled || player == null || player != mc.player?.uuid || original == null) return original
        val shown = playerName.trim().ifBlank { DonutPrivacyMath.pseudoName(seed) }
        val selfName = mc.player?.gameProfile?.name ?: return original
        val replaced = DonutAliasRules.replaceText(original.string, mapOf(selfName to shown))
        if (replaced == original.string) return original
        return Component.literal(replaced).withStyle(original.style)
    }

    private fun replaceTabScore(player: UUID?, original: Component?): Component? {
        if (!enabled || player == null || player != mc.player?.uuid || original == null) return original
        val shown = money.trim().ifBlank { DonutPrivacyMath.pseudoValue("Money", seed xor 1L) }
        val formatted = if (shown.startsWith("$")) shown else "$" + shown
        return Component.literal(formatted).withStyle(original.style)
    }

    companion object {
        private const val MAX_LINE_LENGTH = 48
        private val LOGGER = LoggerFactory.getLogger("Donut Status Mirror")
        @Volatile private var live: DonutStatusMirror? = null

        @JvmStatic fun keepSidebar(scoreboard: Scoreboard, slot: DisplaySlot, requested: Objective?): Boolean =
            slot == DisplaySlot.SIDEBAR && (live?.keepSidebar(scoreboard, requested) ?: false)

        @JvmStatic fun tabName(player: UUID?, original: Component?): Component? = live?.replaceName(player, original) ?: original

        @JvmStatic fun tabScore(player: UUID?, original: Component?): Component? = live?.replaceTabScore(player, original) ?: original
    }
}
