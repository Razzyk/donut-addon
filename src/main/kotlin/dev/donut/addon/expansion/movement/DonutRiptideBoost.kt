package dev.donut.addon.expansion.movement

import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.minecraft.world.entity.player.Player

/** Separate from charge-time controls: changes the impulse applied by a Riptide release. */
object DonutRiptideBoost : ClientModule("DonutRiptideBoost", AddonCategories.UTILITY) {
    private val strength by float("Boost", 2f, 0.1f..10f)
    private val dryUse by boolean("OutOfWater", true)

    init { literalDescription { "Scale the Riptide impulse and optionally use it outside water or rain." } }

    private fun owns(player: Player): Boolean = running && mc.isSameThread && player === mc.player &&
        player.level() === mc.level

    @JvmStatic fun impulseFactor(player: Player): Double = if (owns(player)) strength.toDouble() else 1.0
    @JvmStatic fun permitsDryUse(player: Player): Boolean = owns(player) && dryUse
}
