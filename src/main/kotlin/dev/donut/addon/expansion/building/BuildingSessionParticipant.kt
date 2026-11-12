package dev.donut.addon.expansion.building

/** Narrow contract for registered building modules that own client controls across session changes. */
interface BuildingSessionParticipant {
    fun endBuildingSession(disableModule: Boolean = true)
}
