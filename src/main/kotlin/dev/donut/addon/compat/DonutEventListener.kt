package dev.donut.addon.compat

import net.ccbluex.liquidbounce.event.EventListener
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence

/** Keeps stable-client diagnostic defaults local when a nightly removes them from EventListener. */
interface DonutEventListener : EventListener {
    override val debugOwnerId: String
        get() = javaClass.name

    override val debugDisplayName: FormattedCharSequence
        get() = FormattedCharSequence.forward(javaClass.simpleName.ifEmpty { javaClass.name }, Style.EMPTY)
}
