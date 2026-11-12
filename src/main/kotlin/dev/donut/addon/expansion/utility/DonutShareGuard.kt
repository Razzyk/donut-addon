package dev.donut.addon.expansion.utility

import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.events.OverlayRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.mc
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** Masks private inventory contents from local capture and, when Windows confirms it, restores
 * readable item labels through a separate capture-excluded overlay for the owner. */
class DonutShareGuard : DonutUtilityModule(
    "Donut Share Guard",
    "Masks inventory contents in screenshots and offers a capture-excluded local inventory preview on supported Windows systems."
) {
    private enum class CoverStyle(override val tag: String) : Tagged {
        TOTEM("Totem"), RANDOM("Random"), EMPTY("Empty")
    }

    private val style by enumChoice("Style", CoverStyle.TOTEM)
    private val fillEmpty by boolean("Fill Empty Slots", true).visibleWhen { style != CoverStyle.EMPTY }
    private val streamOnly by boolean("Stream Only", false).visibleWhen { DonutCaptureOverlay.supportedPlatform() }
    private val borderlessFullscreen by boolean("Borderless Fullscreen", true)
        .visibleWhen { streamOnly && DonutCaptureOverlay.supportedPlatform() }
    private val hideTooltips by boolean("Hide Tooltips", true)
    private val hideItemName by boolean("Hide Item Name", true)
    private val hideSelectedSlot by boolean("Hide Selected Slot", true)

    private val randomItems = listOf(
        Items.STONE, Items.COBBLESTONE, Items.IRON_INGOT, Items.GOLD_INGOT,
        Items.BREAD, Items.ARROW, Items.TORCH, Items.OAK_PLANKS, Items.APPLE,
        Items.COOKED_BEEF, Items.STRING, Items.LEATHER, Items.COPPER_INGOT,
    )
    private val randomStacks = HashMap<Int, ItemStack>()
    private var seed = 0L
    private var streamAsked = false
    private var streamReady = false
    private var nextRetryNanos = 0L
    private var warnedUnavailable = false
    private var changedFullscreen = false
    private var wasExclusiveFullscreen = false
    private var activeWorld: ClientLevel? = null
    private var appliedBorderlessPreference = true

    override fun onEnabled() = startSession(mc.level)

    override fun onUtilitySessionStarted(world: ClientLevel) = startSession(world)

    private fun startSession(world: ClientLevel?) {
        seed = Random.nextLong()
        randomStacks.clear()
        activeWorld = world
        appliedBorderlessPreference = borderlessFullscreen
        streamAsked = streamOnly
        streamReady = false
        live = this.takeIf { world != null }
        if (world != null && streamOnly) updateStreamMode(force = true)
    }

    override fun onUtilitySessionEnded() {
        restoreFullscreen()
        streamReady = false
        activeWorld = null
        randomStacks.clear()
        DonutCaptureOverlay.stop()
        if (live === this) live = null
    }

    override fun onDisabled() = onUtilitySessionEnded()

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val level = mc.level
        if (level !== activeWorld) {
            activeWorld = level
            DonutCaptureOverlay.beginLayer(HOTBAR_LAYER)
            DonutCaptureOverlay.beginLayer(CONTAINER_LAYER)
            if (level == null) {
                streamReady = false
                DonutCaptureOverlay.stop()
                restoreFullscreen()
            } else if (streamOnly) {
                updateStreamMode(force = true)
            }
        }
        if (streamOnly != streamAsked) {
            streamAsked = streamOnly
            warnedUnavailable = false
            updateStreamMode(force = true)
        } else if (streamOnly && !streamReady && System.nanoTime() >= nextRetryNanos) {
            updateStreamMode(force = true)
        }
        if (streamOnly && streamReady) {
            if (borderlessFullscreen != appliedBorderlessPreference) {
                appliedBorderlessPreference = borderlessFullscreen
                if (borderlessFullscreen) applyBorderlessPreference() else restoreFullscreen()
            } else {
                applyBorderlessPreference()
            }
        }
        if (!streamOnly) return@handler
        if (mc.level == null || mc.player == null || !mc.window.isFocused || mc.window.isIconified) {
            DonutCaptureOverlay.hide()
        }
        if (mc.gui.screen() !is AbstractContainerScreen<*>) DonutCaptureOverlay.beginLayer(CONTAINER_LAYER)
    }

    @Suppress("unused")
    private val render = handler<OverlayRenderEvent> {
        val visible = streamOnly && streamReady && enabled && mc.level != null && mc.player != null && mc.window.isFocused && !mc.window.isIconified
        val stillAvailable = DonutCaptureOverlay.present(
            mc.window.getX(), mc.window.getY(), mc.window.getWidth(), mc.window.getHeight(), visible,
        )
        if (visible && !stillAvailable) {
            streamReady = false
            restoreFullscreen()
            LOGGER.warn("Donut Share Guard's capture-excluded overlay stopped working. The inventory remains masked locally.")
        }
    }

    private fun updateStreamMode(force: Boolean) {
        if (!force) return
        if (!streamOnly) {
            streamReady = false
            DonutCaptureOverlay.stop()
            restoreFullscreen()
            return
        }
        streamReady = DonutCaptureOverlay.start()
        if (streamReady) {
            nextRetryNanos = 0L
            warnedUnavailable = false
            applyBorderlessPreference()
        } else {
            nextRetryNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(RETRY_SECONDS)
            if (!warnedUnavailable) {
                warnedUnavailable = true
                LOGGER.warn("Donut Share Guard Stream Only could not verify a capture-excluded overlay. Inventory contents will stay masked locally as well.")
            }
        }
    }

    private fun applyBorderlessPreference() {
        if (!borderlessFullscreen) return
        val window = mc.window
        if (!window.isExclusiveFullscreen) return
        wasExclusiveFullscreen = true
        window.setExclusiveFullscreen(false)
        window.updateFullscreenIfChanged()
        changedFullscreen = true
    }

    private fun restoreFullscreen() {
        if (!changedFullscreen) return
        changedFullscreen = false
        val window = runCatching { mc.window }.getOrNull() ?: return
        if (wasExclusiveFullscreen && !window.isExclusiveFullscreen) {
            window.setExclusiveFullscreen(true)
            window.updateFullscreenIfChanged()
        }
        wasExclusiveFullscreen = false
    }

    internal fun coverHud(stack: ItemStack?, seedIndex: Int, x: Int, y: Int): ItemStack? {
        if (!enabled || stack == null) return stack
        val player = mc.player ?: return stack
        val inventoryIndex = when (seedIndex) {
            in 1..9 -> seedIndex - 1
            10 -> Inventory.SLOT_OFFHAND
            else -> Inventory.SLOT_BODY_ARMOR
        }
        if (streamReady) DonutCaptureOverlay.record(HOTBAR_LAYER, inventoryIndex, stack, x, y, mc.window.getGuiScale())
        return mask(stack, inventoryIndex)
    }

    internal fun coverSlot(stack: ItemStack?, slot: Slot?, x: Int, y: Int): ItemStack? {
        if (!enabled || stack == null || slot == null) return stack
        val inventory = mc.player?.inventory ?: return stack
        if (slot.container !== inventory) return stack
        val index = slot.containerSlot
        if (streamReady) DonutCaptureOverlay.record(CONTAINER_LAYER, index, stack, x, y, mc.window.getGuiScale())
        return mask(stack, index)
    }

    internal fun coverCarried(stack: ItemStack?, x: Int, y: Int): ItemStack? {
        if (!enabled || stack == null || stack.isEmpty) return stack
        val index = Inventory.SLOT_BODY_ARMOR
        if (streamReady) DonutCaptureOverlay.record(CONTAINER_LAYER, CURSOR_SLOT, stack, x, y, mc.window.getGuiScale())
        return mask(stack, index)
    }

    internal fun coversSlot(slot: Slot?): Boolean = enabled && slot != null && slot.container === mc.player?.inventory

    internal fun shouldHideTooltip(slot: Slot?): Boolean = hideTooltips && coversSlot(slot)
    internal fun shouldHideItemName(): Boolean = enabled && hideItemName
    internal fun shouldHideSelection(): Boolean = enabled && hideSelectedSlot
    internal fun shouldUseOverlay(): Boolean = enabled && streamOnly && streamReady

    private fun mask(stack: ItemStack, slot: Int): ItemStack {
        if (stack.isEmpty && !fillEmpty) return stack
        return when (style) {
            CoverStyle.EMPTY -> ItemStack.EMPTY
            CoverStyle.TOTEM -> totem
            CoverStyle.RANDOM -> randomStacks.getOrPut(slot) { makeRandom(slot) }
        }
    }

    private fun makeRandom(slot: Int): ItemStack {
        val entropy = seed xor (slot.toLong() * -7046029254386353131L)
        val item = randomItems[java.lang.Long.remainderUnsigned(mix(entropy), randomItems.size.toLong()).toInt()]
        val stack = ItemStack(item)
        val max = stack.maxStackSize.coerceAtLeast(1)
        if (max > 1) stack.count = 1 + java.lang.Long.remainderUnsigned(mix(entropy xor 0x51L), max.toLong()).toInt()
        return stack
    }

    private fun mix(value: Long): Long {
        var mixed = value
        mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
        mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
        return mixed xor (mixed ushr 31)
    }

    companion object {
        private const val HOTBAR_LAYER = 0
        private const val CONTAINER_LAYER = 1
        private const val CURSOR_SLOT = 41
        private const val RETRY_SECONDS = 8L
        private val LOGGER = LoggerFactory.getLogger("Donut Share Guard")
        private val totem by lazy { ItemStack(Items.TOTEM_OF_UNDYING) }
        @Volatile private var live: DonutShareGuard? = null

        @JvmStatic fun beginHudFrame() {
            val current = live?.takeIf { it.enabled } ?: return
            if (current.streamReady) {
                DonutCaptureOverlay.beginLayer(HOTBAR_LAYER)
                if (current.mc.gui.screen() !is AbstractContainerScreen<*>) DonutCaptureOverlay.beginLayer(CONTAINER_LAYER)
            }
        }

        @JvmStatic fun beginContainerFrame() {
            live?.takeIf { it.enabled && it.streamReady }?.let { DonutCaptureOverlay.beginLayer(CONTAINER_LAYER) }
        }

        @JvmStatic fun maskHotbar(stack: ItemStack?, seed: Int, x: Int, y: Int): ItemStack? =
            live?.takeIf { it.enabled }?.coverHud(stack, seed, x, y) ?: stack

        @JvmStatic fun maskSlot(stack: ItemStack?, slot: Slot?, x: Int, y: Int): ItemStack? =
            live?.takeIf { it.enabled }?.coverSlot(stack, slot, x, y) ?: stack

        @JvmStatic fun maskCarried(stack: ItemStack?, x: Int, y: Int): ItemStack? =
            live?.takeIf { it.enabled }?.coverCarried(stack, x, y) ?: stack

        @JvmStatic fun hideInventoryTooltip(slot: Slot?): Boolean = live?.shouldHideTooltip(slot) ?: false
        @JvmStatic fun hideHotbarName(): Boolean = live?.shouldHideItemName() ?: false
        @JvmStatic fun hideHotbarSelection(): Boolean = live?.shouldHideSelection() ?: false
        @JvmStatic fun streamOverlayActive(): Boolean = live?.shouldUseOverlay() ?: false
        @JvmStatic fun setActive(module: DonutShareGuard?) { live = module }
    }
}
