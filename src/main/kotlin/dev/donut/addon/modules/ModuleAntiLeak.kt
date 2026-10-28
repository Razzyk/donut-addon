package dev.donut.addon.modules

import dev.donut.addon.AntiLeakRender
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories

/** Removes position-dependent deepslate model choices from client rendering. */
object ModuleAntiLeak : ClientModule("AntiLeak", ModuleCategories.RENDER) {
    init {
        literalDescription { "Makes deepslate textures face the same way to hide their position-based pattern." }
        // ClientModule skips toggle callbacks in menus, so synchronize the worker
        // switch here too (including settings loaded before joining a world).
        enabledValue.onChanged { state ->
            AntiLeakRender.setEnabled(state)
        }
    }

    override fun onEnabled() {
        AntiLeakRender.setEnabled(true)
        mc.levelExtractor.allChanged()
    }

    override fun onDisabled() {
        AntiLeakRender.setEnabled(false)
        mc.levelExtractor.allChanged()
    }

    override fun unregister() {
        AntiLeakRender.setEnabled(false)
        if (mc.level != null) mc.levelExtractor.allChanged()
        super.unregister()
    }
}
