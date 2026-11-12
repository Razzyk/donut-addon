package dev.donut.addon.expansion.search.spawner

import dev.donut.addon.compat.DonutEventListener

import dev.donut.addon.expansion.search.SearchFeatures
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.render.WorldToScreen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3

/** Projects compact spawner details through LiquidBounce's current world-to-screen matrices. */
object SpawnerSignpostOverlay : DonutEventListener {
    @Suppress("unused")
    private val render = handler<OverlayRenderEvent> { event ->
        val module = SearchFeatures.SPAWNER_SIGNPOSTS
        if (!module.isActive || mc.level == null || mc.player == null) return@handler

        module.visibleLabels().forEach { label ->
            val pos = label.pos()
            val projected = WorldToScreen.calculateScreenPos(
                Vec3(pos.x + 0.5, pos.y + 1.25, pos.z + 0.5)
            ) ?: return@forEach
            if (projected.x < 0f || projected.y < 0f ||
                projected.x > mc.window.guiScaledWidth || projected.y > mc.window.guiScaledHeight) return@forEach

            val display = buildString {
                append(label.mob()).append(" Spawner  ").append(label.distance().toInt()).append("m")
                if (module.isActivationRangeEnabled() && label.activationRange() > 0) {
                    append(" · ").append(label.activationRange()).append("m activation")
                    if (label.insideActivationRange()) append(" · active range")
                }
            }
            val x = (projected.x - mc.font.width(display) / 2f).toInt()
            event.context.text(
                mc.font,
                Component.literal(display),
                x,
                projected.y.toInt(),
                if (label.insideActivationRange()) 0xffffc857.toInt() else 0xffffffff.toInt(),
                true,
            )
        }
    }
}
