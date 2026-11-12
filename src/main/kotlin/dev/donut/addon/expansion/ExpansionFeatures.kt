package dev.donut.addon.expansion

import dev.donut.addon.expansion.presence.PresenceFeatures
import dev.donut.addon.expansion.search.SearchFeatures
import dev.donut.addon.expansion.movement.MovementFeatures
import dev.donut.addon.expansion.utility.UtilityFeatures
import dev.donut.addon.expansion.automation.AutomationFeatures
import dev.donut.addon.expansion.building.BuildingFeatures
import dev.donut.addon.expansion.render.RenderFeatures
import dev.donut.addon.expansion.schematic.SchematicFeatures
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.integration.theme.component.components.NativeHudComponent

/** Explicit registration keeps each independently implemented feature family reviewable. */
object ExpansionFeatures {
    val modules: List<ClientModule> = PresenceFeatures.modules + SearchFeatures.modules +
        MovementFeatures.modules + UtilityFeatures.modules + AutomationFeatures.modules +
        BuildingFeatures.modules + RenderFeatures.modules + SchematicFeatures.modules

    val listeners: List<EventListener> = PresenceFeatures.listeners + SearchFeatures.listeners +
        UtilityFeatures.listeners + MovementFeatures.listeners + AutomationFeatures.listeners + BuildingFeatures.listeners +
        SchematicFeatures.listeners

    val hudComponents: List<NativeHudComponent>
        get() = PresenceFeatures.hudComponents + SearchFeatures.getHudComponents() +
            UtilityFeatures.hudComponents + AutomationFeatures.hudComponents

    /** Release external handles without withdrawing values that are about to be saved. */
    fun shutdown() {
        PresenceFeatures.shutdown()
        MovementFeatures.shutdown()
        SearchFeatures.shutdown()
        BuildingFeatures.shutdown()
        SchematicFeatures.shutdown()
        UtilityFeatures.shutdown()
    }
}
