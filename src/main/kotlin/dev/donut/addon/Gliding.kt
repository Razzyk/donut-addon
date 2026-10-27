package dev.donut.addon

import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.component.DataComponents
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack

object Gliding {
    fun isUsable(stack: ItemStack): Boolean =
        stack.has(DataComponents.GLIDER) &&
            (!stack.isDamageableItem || stack.damageValue < stack.maxDamage - 1)

    fun canContinue(player: LocalPlayer): Boolean =
        !player.isInWater && !player.abilities.flying && !player.onGround() &&
            !player.isPassenger && !player.hasEffect(MobEffects.LEVITATION)

    fun start(player: LocalPlayer) {
        if (!canContinue(player) || !isUsable(player.getItemBySlot(EquipmentSlot.CHEST))) return
        player.startFallFlying()
        player.connection.send(
            ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING)
        )
    }
}
