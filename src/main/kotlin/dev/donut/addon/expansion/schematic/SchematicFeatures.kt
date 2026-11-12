package dev.donut.addon.expansion.schematic

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.features.module.ClientModule

/** Optional Litematica modules are exposed only after both runtime mod ids have passed the gate. */
object SchematicFeatures {
    private val loadedModules: List<ClientModule> by lazy {
        if (LitematicaRuntimeAdapter.modsAvailable()) listOf(DonutSchematicBuilder) else emptyList()
    }

    val modules: List<ClientModule> get() = loadedModules
    val listeners: List<EventListener> = listOf(SchematicSessionLifecycle)

    fun shutdown() {
        SchematicSessionLifecycle.shutdown()
        if (loadedModules.isNotEmpty()) DonutSchematicBuilder.shutdown()
    }
}
