package dev.donut.addon.expansion.automation

import dev.donut.addon.compat.DonutEventListener

import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.integration.theme.component.components.NativeHudComponent

/** Shared protocols ship with the builder; unfinished macro replacements stay out of the GUI. */
object AutomationFeatures {
    val modules: List<ClientModule> = emptyList()

    val listeners: List<EventListener> = listOf(AutomationSessionLifecycle)
    val hudComponents: List<NativeHudComponent> = emptyList()
}

/** EventListener runs independently of module in-game hooks, so transient state resets on disconnect. */
private object AutomationSessionLifecycle : DonutEventListener {
    @Suppress("unused")
    private val worldChange = handler<WorldChangeEvent> {
        AutomationFeatures.modules.filterIsInstance<DonutAutomationModule>()
            .forEach(DonutAutomationModule::onAutomationWorldChanged)
    }
}
