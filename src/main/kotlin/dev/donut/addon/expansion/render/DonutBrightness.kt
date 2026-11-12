package dev.donut.addon.expansion.render

import net.ccbluex.liquidbounce.features.module.ClientModule
import dev.donut.addon.AddonCategories
import kotlin.math.roundToInt

/** A render-time override leaves the user's saved vanilla gamma option untouched. */
object DonutBrightness : ClientModule("Donut Brightness", AddonCategories.DONUT) {
    private val strength by float("Light Level", 8f, 1f..16f)

    init {
        literalDescription { "Sets brightness immediately, with half-step control up to 16." }
    }

    @JvmStatic
    fun lightmapGamma(original: Float): Float =
        if (running) (strength.coerceIn(1f, 16f) * 2f).roundToInt() / 2f else original
}
