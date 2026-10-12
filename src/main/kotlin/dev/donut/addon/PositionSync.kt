package dev.donut.addon

import dev.donut.addon.mixin.LocalPlayerAccessor
import net.minecraft.client.player.LocalPlayer

/** Keep helper implementations outside accessor interfaces so Mixin classifies them correctly. */
fun LocalPlayer.resyncPosition() {
    val accessor = this as LocalPlayerAccessor
    accessor.`donut$setXLast`(0.0)
    accessor.`donut$setYLast`(0.0)
    accessor.`donut$setZLast`(0.0)
}
