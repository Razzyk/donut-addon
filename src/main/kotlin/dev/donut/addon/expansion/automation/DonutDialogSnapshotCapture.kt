package dev.donut.addon.expansion.automation

import dev.donut.addon.expansion.automation.core.DialogSnapshot
import dev.donut.addon.expansion.automation.core.DialogWidgetSnapshot
import dev.donut.addon.expansion.automation.core.InventorySlotValue
import dev.donut.addon.mixin.AccessorOrderDialogScreen
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.dialog.DialogScreen
import net.minecraft.server.dialog.body.ItemBody
import net.minecraft.server.dialog.body.PlainMessage

/** Read-only capture of a native server dialog, reusable by independently composed hosts. */
object DonutDialogSnapshotCapture {
    @JvmStatic
    fun capture(screen: Screen?): DialogSnapshot? {
        val dialogScreen = screen as? DialogScreen<*> ?: return null
        val dialog = (dialogScreen as AccessorOrderDialogScreen).`anubis$dialog`() ?: return null
        val widgets = dialogScreen.children().mapIndexedNotNull { index, _ ->
            widget(dialogScreen, index)
        }
        val bodyText = mutableListOf<String>()
        val itemStacks = mutableListOf<InventorySlotValue>()
        for (part in dialog.common().body()) {
            when (part) {
                is PlainMessage -> bodyText += part.contents().string
                is ItemBody -> {
                    val value = InventorySlotValue.fromStack(part.item().create())
                    itemStacks += value
                    if (value.name.isNotEmpty()) bodyText += value.name
                    part.description().ifPresent { bodyText += it.contents().string }
                }
            }
        }
        return if (widgets.isEmpty() && bodyText.isEmpty() && itemStacks.isEmpty()) null
        else DialogSnapshot(System.identityHashCode(dialogScreen).toLong(), dialogScreen.title.string, widgets, bodyText, itemStacks)
    }

    @JvmStatic
    fun widget(screen: Screen?, index: Int): DialogWidgetSnapshot? {
        val dialogScreen = screen as? DialogScreen<*> ?: return null
        return when (val child = dialogScreen.children().getOrNull(index)) {
            is Button -> DialogWidgetSnapshot(index, "button", child.message.string)
            is EditBox -> DialogWidgetSnapshot(index, "input", child.message.string, child.value)
            else -> null
        }
    }
}
