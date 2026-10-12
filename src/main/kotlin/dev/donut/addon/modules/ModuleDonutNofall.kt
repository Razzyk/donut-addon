package dev.donut.addon.modules

import dev.donut.addon.NoFallTest2State
import dev.donut.addon.ServerFlightPermissions
import dev.donut.addon.resyncPosition
import net.ccbluex.liquidbounce.event.EventState
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.events.PlayerNetworkMovementTickEvent
import net.ccbluex.liquidbounce.event.events.PlayerTickEvent
import net.ccbluex.liquidbounce.event.events.TransferOrigin
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

object ModuleDonutNofall : ClientModule("DonutNofall", AddonCategories.DONUT) {
    private val safeDistanceModify by int("SafeDistanceModify", 0, -2..20, "blocks")
    private val disableWhenAllowFlying by boolean("DisableWhenAllowFlying", true)
    private val equipmentIdBypass by text("EquipmentIdBypass", "^(SLIME.*_BOOTS)$")
    private val state = NoFallTest2State()
    private var owner: LocalPlayer? = null
    private var tickPosition = Vec3.ZERO
    private var activeThisTick = false
    private var lastInResetBlock = false

    init {
        literalDescription { "SlimefunHelper NoFall Test 2 landing correction." }
    }

    override fun onEnabled() = reset()
    override fun onDisabled() = reset()

    private fun reset() {
        state.reset()
        owner = null
        activeThisTick = false
        lastInResetBlock = false
    }

    @Suppress("unused")
    private val tick = handler<PlayerTickEvent> {
        if (owner !== player) {
            reset()
            owner = player
        }
        activeThisTick = player.isAlive && !player.abilities.invulnerable &&
            !(disableWhenAllowFlying && ServerFlightPermissions.allowsFlying(player)) &&
            !hasInvulnerableEquipment() && !noGroundRunning()
        if (!activeThisTick) {
            state.reset()
            return@handler
        }
        tickPosition = player.position()
        val block = player.blockStateOn
        val resetBlock = player.isInWater ||
            world.getBlockState(player.blockPosition()).`is`(Blocks.BUBBLE_COLUMN) ||
            world.getBlockState(player.blockPosition()).`is`(Blocks.COBWEB) ||
            world.getBlockState(player.blockPosition().above()).`is`(Blocks.COBWEB) ||
            block.`is`(Blocks.COBWEB)
        state.beforeTick(
            player.y, player.onGround(), resetBlock || lastInResetBlock,
            player.getAttributeValue(Attributes.SAFE_FALL_DISTANCE) + safeDistanceModify
        )
        lastInResetBlock = resetBlock
    }

    private fun noGroundRunning(): Boolean = ModuleManager["NoGround"]?.running == true ||
        listOf("NoFall", "Criticals").any { name ->
            ModuleManager[name]?.let { it.running && it.tag.equals("NoGround", ignoreCase = true) } == true
        }

    private fun hasInvulnerableEquipment(): Boolean {
        val pattern = runCatching { equipmentIdBypass.toRegex() }.getOrNull() ?: return false
        return listOf(EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD).any { slot ->
            val stack = player.getItemBySlot(slot)
            if (stack.isEmpty) return@any false
            val itemId = BuiltInRegistries.ITEM.getKey(stack.item).path
            val data = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()
            val slimefunId = data?.getCompoundOrEmpty("PublicBukkitValues")?.getStringOr("slimefun:slimefun_item", "") ?: ""
            pattern.containsMatchIn(itemId) || pattern.containsMatchIn(slimefunId)
        }
    }

    @Suppress("unused")
    private val packet = handler<PacketEvent> {
        if (it.origin == TransferOrigin.INCOMING && it.packet is ClientboundPlayerPositionPacket) {
            state.onSetback()
        }
    }

    @Suppress("unused")
    private val networkTick = handler<PlayerNetworkMovementTickEvent>(priority = -50) { event ->
        if (event.state != EventState.PRE || !activeThisTick || event.isCancelled) return@handler
        val correction = state.beforeNetwork(player.y, player.onGround()) ?: return@handler
        val correctedY = correction.y()
        val correctedX = if (correction.restoreHorizontal()) tickPosition.x else player.x
        val correctedZ = if (correction.restoreHorizontal()) tickPosition.z else player.z
        // The original rolls a landing back to the pre-travel position, then forces a position resend.
        player.setPos(correctedX, correctedY, correctedZ)
        player.setOnGround(false)
        player.resyncPosition()
        event.x = correctedX
        event.y = correctedY
        event.z = correctedZ
        event.ground = false
    }
}
