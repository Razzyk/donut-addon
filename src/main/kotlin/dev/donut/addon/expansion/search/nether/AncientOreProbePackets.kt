package dev.donut.addon.expansion.search.nether

import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket

/** Sends bounded probe actions with the game's normal block-state prediction sequence. */
object AncientOreProbePackets {
    @JvmStatic
    fun send(action: ServerboundPlayerActionPacket.Action, pos: BlockPos) {
        val level = mc.level ?: return
        val interaction = mc.gameMode ?: return
        interaction.startPrediction(level) { sequence ->
            ServerboundPlayerActionPacket(action, pos, Direction.UP, sequence)
        }
    }

    @JvmStatic
    fun selectServerSlot(slot: Int) {
        mc.connection?.send(ServerboundSetCarriedItemPacket(slot))
    }
}
