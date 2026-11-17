package dev.donut.addon.expansion.utility

import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes

/** Removes selected trap entities and rejects their spawn packets before they enter the client world. */
class DonutEscapeAssist : DonutUtilityModule("Donut Escape Assist", "Removes configured trap entities and can block them before client-side spawn.") {
    private val removeExisting by boolean("Remove Existing", true)
    private val preventSpawn by boolean("Prevent Spawn", true)
    private val armorStands by boolean("Armor Stands", true)
    private val chestMinecarts by boolean("Chest Minecarts", true)
    private var tickCounter = 0

    @Suppress("unused")
    private val packet = handler<PacketEvent> { event ->
        if (!preventSpawn || event.origin != TransferOrigin.INCOMING) return@handler
        val spawn = event.packet as? ClientboundAddEntityPacket ?: return@handler
        if (isTrapType(spawn.type)) event.cancelEvent()
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        if (!removeExisting || ++tickCounter % 20 != 0) return@handler
        val level = mc.level ?: return@handler
        val camera = mc.cameraEntity
        level.entitiesForRendering().filter { it !== camera && isTrap(it) }.forEach { it.discard() }
    }

    private fun isTrap(entity: Entity): Boolean = isTrapType(entity.type)
    private fun isTrapType(type: net.minecraft.world.entity.EntityType<*>): Boolean =
        armorStands && type == EntityTypes.ARMOR_STAND || chestMinecarts && BuiltInRegistries.ENTITY_TYPE.getKey(type).path.let { it.contains("chest_minecart") || it.contains("minecart_chest") }
}
