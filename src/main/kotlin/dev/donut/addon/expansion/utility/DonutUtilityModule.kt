package dev.donut.addon.expansion.utility

import dev.donut.addon.AddonCategories
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.minecraft.client.multiplayer.ClientLevel

/** Common category boundary for the independently authored Donut utility modules. */
abstract class DonutUtilityModule(name: String, description: String) : ClientModule(name, AddonCategories.UTILITY) {
    init {
        literalDescription { description }
        // ClientModule deliberately skips onDisabled when it is toggled from a menu.
        enabledValue.onChanged { newState ->
            if (!newState) onUtilitySessionEnded()
        }
    }

    internal open fun onUtilitySessionEnded() {
        onDisabled()
    }

    internal open fun onUtilitySessionStarted(world: ClientLevel) = Unit

    internal open fun onUtilityWorldChanging(world: ClientLevel?) {
        onUtilitySessionEnded()
    }

    internal fun onUtilityWorldChanged(world: ClientLevel?) {
        onUtilityWorldChanging(world)
        if (world != null && enabled) onUtilitySessionStarted(world)
    }

    internal open fun onUtilityShutdown() = onUtilitySessionEnded()
}
