package dev.donut.addon.modules

import dev.donut.addon.GlideResetState
import dev.donut.addon.Gliding
import dev.donut.addon.mixin.LivingEntityAccessor
import net.ccbluex.liquidbounce.event.events.PlayerPostTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.utils.item.armorToughness
import net.ccbluex.liquidbounce.utils.item.armorValue
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack

object ModuleUnbreakableElytra : ClientModule("UnbreakableElytra", AddonCategories.DONUT) {
    private val period by int("Period", 16, 1..19, "ticks")
    private val resetVanilla by boolean("ResetVanilla", false)
    private val transaction = GlideResetState()
    private var owner: LocalPlayer? = null
    private var counter = 0
    private var movedElytra = ItemStack.EMPTY

    init {
        literalDescription { "Resets server glide ticks using SlimefunHelper's elytra swap transaction." }
    }

    override fun onEnabled() {
        ensureOwner()
        counter = 0
    }

    override fun onDisabled() {
        counter = 0
        cleanupTick()
    }

    private fun ensureOwner() {
        if (owner !== mc.player) {
            transaction.finish()
            counter = 0
            movedElytra = ItemStack.EMPTY
            owner = mc.player
        }
    }

    /** Also called by the add-on's listener while this module is disabled. */
    fun cleanupTick() {
        ensureOwner()
        val current = mc.player ?: return
        if (!transaction.needsRestore(
                current.tickCount, enabled, Gliding.canContinue(current), current.isFallFlying
            )) return
        val slot = transaction.slot()
        if (slot != -1) {
            // Defer while a container or a carried stack prevents a safe swap.
            if (!inventoryReady(current)) return
            if (!Gliding.isUsable(current.getItemBySlot(EquipmentSlot.CHEST))) {
                val restoreSlot = slot.takeIf {
                    Gliding.isUsable(current.inventoryMenu.getSlot(it).item)
                } ?: ((9..44).toList() + 45).firstOrNull {
                    val stack = current.inventoryMenu.getSlot(it).item
                    Gliding.isUsable(stack) && ItemStack.isSameItemSameComponents(stack, movedElytra)
                }
                if (restoreSlot != null && !swapChest(current, restoreSlot)) return
            }
        }
        transaction.finish()
        movedElytra = ItemStack.EMPTY
        counter = 0
        if (enabled && Gliding.canContinue(current)) {
            Gliding.start(current)
        } else if (!Gliding.canContinue(current)) {
            current.stopFallFlying()
        }
    }

    @Suppress("unused")
    private val tick = handler<PlayerPostTickEvent> {
        ensureOwner()
        if (transaction.isPending) return@handler
        val chest = player.getItemBySlot(EquipmentSlot.CHEST)
        if (!player.isFallFlying || !Gliding.canContinue(player) ||
            !Gliding.isUsable(chest) || chest.has(DataComponents.UNBREAKABLE)) {
            counter = 0
            return@handler
        }
        if (++counter < period) return@handler
        counter = 0
        val slot = findSwitchSlot(player)
        movedElytra = chest.copy()
        if (slot != -1) {
            if (!swapChest(player, slot)) return@handler
        } else {
            // Original fallback when there is no usable armor/empty inventory slot.
            network.send(
                ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING)
            )
        }
        transaction.begin(player.tickCount, slot)
        if (resetVanilla) (player as LivingEntityAccessor).`donut$setFallFlyTicks`(0)
    }

    private fun inventoryReady(current: LocalPlayer): Boolean =
        current.containerMenu === current.inventoryMenu && current.inventoryMenu.carried.isEmpty

    private fun findSwitchSlot(current: LocalPlayer): Int {
        if (!inventoryReady(current)) return -1
        val chest = current.inventoryMenu.getSlot(6)
        return ((9..44).toList() + 45).filter { index ->
            val slot = current.inventoryMenu.getSlot(index)
            val stack = slot.item
            !Gliding.isUsable(stack) && (stack.isEmpty || chest.mayPlace(stack)) &&
                slot.mayPlace(chest.item) && slot.mayPickup(current) && chest.mayPickup(current)
        }.maxByOrNull { index ->
            val stack = current.inventoryMenu.getSlot(index).item
            if (stack.isEmpty) -3.0 else stack.armorValue * stack.armorToughness
        } ?: -1
    }

    private fun swapChest(current: LocalPlayer, index: Int): Boolean {
        if (!inventoryReady(current)) return false
        val menu = current.inventoryMenu
        val chest = menu.getSlot(6)
        val target = menu.getSlot(index)
        if ((!target.item.isEmpty && !chest.mayPlace(target.item)) ||
            !target.mayPlace(chest.item) || !chest.mayPickup(current) || !target.mayPickup(current)) return false
        interaction.handleContainerInput(menu.containerId, 6, 0, ContainerInput.PICKUP, current)
        interaction.handleContainerInput(menu.containerId, index, 0, ContainerInput.PICKUP, current)
        interaction.handleContainerInput(menu.containerId, 6, 0, ContainerInput.PICKUP, current)
        return menu.carried.isEmpty
    }

    /** Runs on the game thread immediately before vanilla applies the metadata list. */
    @JvmStatic
    fun preserveGlideFlag(entityId: Int, values: List<SynchedEntityData.DataValue<*>>): List<SynchedEntityData.DataValue<*>> {
        val current = mc.player ?: return values
        if (!running || owner !== current || entityId != current.id || !transaction.isPending ||
            !current.isFallFlying || !Gliding.canContinue(current)) return values
        return values.map { value ->
            // Entity's shared byte flags are metadata 0; bit 7 is fall flying in 26.3.
            if (value.id() == 0 && value.value() is Byte && (value.value() as Byte).toInt() and 128 == 0) {
                transaction.serverStopped()
                SynchedEntityData.DataValue(
                    value.id(), EntityDataSerializers.BYTE, ((value.value() as Byte).toInt() or 128).toByte()
                )
            } else value
        }
    }
}
