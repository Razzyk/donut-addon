package dev.donut.addon.expansion.render

import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Original Donut edge graphics rendered through the native HUD extraction event. */
object DonutHealthAlert : ClientModule("Donut Health Alert", AddonCategories.DONUT) {
    private val threshold by int("Health Threshold", 7, 2..20)
    private val absorption by boolean("Include Absorption", true)
    private val animation by enumChoice("Animation", Pattern.HEARTBEAT)
    private val intensity by float("Intensity", 0.7f, 0.2f..1f)
    private val reach by float("Edge Depth", 0.16f, 0.06f..0.35f)
    private val accent by boolean("Use Donut Accent", true)
    private val glow by color("Glow Color", Color4b(52, 119, 246))
    private val rim by color("Rim Color", Color4b(169, 200, 255))
    private val outline by boolean("Bright Rim", true)
    private val envelope = HealthAlertEnvelope()
    private var worldOwner: WeakReference<Any>? = null
    private var playerOwner: WeakReference<Any>? = null

    private enum class Pattern(override val tag: String, val curve: HealthAlertPattern) : Tagged {
        HEARTBEAT("Heartbeat", HealthAlertPattern.HEARTBEAT),
        BREATHING("Breathing", HealthAlertPattern.BREATHING),
        STEADY("Steady", HealthAlertPattern.STEADY),
    }

    init {
        literalDescription { "Animates the screen edges when your health falls below the chosen threshold." }
        enabledValue.onChanged { if (!it) release() }
    }

    override fun onEnabled() = release()
    override fun onDisabled() = release()

    private fun release() {
        envelope.clear()
        worldOwner = null
        playerOwner = null
    }

    @Suppress("unused")
    private val overlay = handler<OverlayRenderEvent> { event ->
        val player = mc.player ?: run { release(); return@handler }
        val world = mc.level ?: run { release(); return@handler }
        if (worldOwner?.get() !== world || playerOwner?.get() !== player) {
            release()
            worldOwner = WeakReference(world)
            playerOwner = WeakReference(player)
        }
        if (mc.gui.hud.isHidden) {
            envelope.clear()
            return@handler
        }
        val health = player.health + if (absorption) player.absorptionAmount else 0f
        val frame = envelope.sample(System.nanoTime(), health, threshold.toFloat(),
            !player.isSpectator && !player.abilities.invulnerable, player.isDeadOrDying, animation.curve)
        if (frame.visibility == 0f) return@handler
        val context = event.context
        val width = context.guiWidth()
        val height = context.guiHeight()
        val depth = reach * minOf(width, height) * (1f + 0.12f * frame.beat)
        val strength = intensity * frame.visibility * (0.45f + 0.55f * frame.severity)
        val glowRgb = if (accent) 0xD89A58 else glow.argb and 0xFFFFFF
        val rimRgb = if (accent) 0xEBCCAB else rim.argb and 0xFFFFFF
        val depths = floatArrayOf(1f, 0.55f, 0.22f)
        val weights = floatArrayOf(0.5f, 0.34f, 0.3f)
        for (index in depths.indices) {
            border(context, width, height, depth * depths[index], glowRgb,
                strength * weights[index] * (0.6f + 0.4f * frame.beat))
        }
        if (outline) border(context, width, height, maxOf(2f, depth * 0.07f), rimRgb,
            intensity * frame.visibility * (0.2f + 0.55f * frame.beat))
    }

    /** Rotated native gradients keep all four edges smooth without a per-pixel draw loop. */
    private fun border(context: GuiGraphicsExtractor, width: Int, height: Int,
                       depth: Float, rgb: Int, opacity: Float) {
        val pixels = ceil(depth).toInt().coerceIn(0, minOf(width, height) / 2)
        if (pixels < 1 || opacity <= 0f) return
        fun color(factor: Float): Int = ((opacity * factor * 255f).roundToInt().coerceIn(0, 255) shl 24) or rgb
        context.fillGradient(0, 0, width, pixels, color(1f), rgb)
        context.fillGradient(0, height - pixels, width, height, rgb, color(1f))
        val pose = context.pose()
        pose.pushMatrix()
        try {
            pose.translate(0f, height.toFloat())
            pose.rotate((-Math.PI / 2).toFloat())
            context.fillGradient(0, 0, height, pixels, color(1f), rgb)
        } finally {
            pose.popMatrix()
        }
        pose.pushMatrix()
        try {
            pose.translate(width.toFloat(), 0f)
            pose.rotate((Math.PI / 2).toFloat())
            context.fillGradient(0, 0, height, pixels, color(1f), rgb)
        } finally {
            pose.popMatrix()
        }
    }
}
