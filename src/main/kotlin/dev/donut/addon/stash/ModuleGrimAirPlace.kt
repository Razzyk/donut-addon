package dev.donut.addon.stash

import dev.donut.addon.mv.WorldRenderer
import dev.donut.addon.mv.compat.MeteorClient
import dev.donut.addon.mv.compat.events.EventHandler
import dev.donut.addon.mv.compat.events.world.TickEvent
import dev.donut.addon.mv.compat.renderer.ShapeMode
import dev.donut.addon.mv.compat.utils.render.color.Color
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.events.WorldRenderEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategories
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.block.SwingMode
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.SpawnEggItem
import net.minecraft.world.phys.BlockHitResult

/** Port of miles352's 1.21.1 GrimAirPlace, including the original container-revision offset. */
object ModuleGrimAirPlace : ClientModule("GrimAirPlace", ModuleCategories.WORLD) {
    private val general = tree(ValueGroup("General"))
    private val ranges = tree(ValueGroup("range"))
    private val placeDelay by general.int("place-delay", 0, 0..10).also {
        it.literalDescription { "The delay in ticks between block placements." }
    }
    private val render by general.boolean("render", true).also {
        it.literalDescription { "Renders a block overlay where the block will be placed." }
    }
    private val shapeMode by general.enumChoice("shape-mode", PreviewMode.Both).also {
        it.literalDescription { "How the shapes are rendered." }
    }
    private val sideColor by general.color("side-color", Color4b(204, 0, 0, 10)).also {
        it.literalDescription { "The color of the sides of the blocks being rendered." }
    }
    private val lineColor by general.color("line-color", Color4b(204, 0, 0, 255)).also {
        it.literalDescription { "The color of the lines of the blocks being rendered." }
    }
    private val customRange by ranges.boolean("custom-range", false).also {
        it.literalDescription { "Use custom range for air place." }
    }
    private val range = ranges.value(RangedValue("range", defaultValue = 5.0,
        range = 0.0..5.5, suffix = "", valueType = ValueType.FLOAT))

    private var hitResult: BlockHitResult? = null
    private var delay = 0

    init {
        literalDescription { "Places a block at the crosshair using the original offhand swap packet sequence." }
        range.literalDescription { "Custom range to place at." }
        range.visibleWhen { customRange }
        range.onChange { if (it.isFinite() && it >= 0.0) it else range.get() }
        // Source Tick.Post is Minecraft.tick TAIL, rather than LocalPlayer.tick TAIL.
        MeteorClient.EVENT_BUS.subscribe(this)
    }

    override fun onEnabled() {
        delay = 0
        hitResult = null
        super.onEnabled()
    }

    override fun onDisabled() {
        hitResult = null
        super.onDisabled()
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    @EventHandler
    private fun onPostTick(event: TickEvent.Post) {
        if (!running) return
        delay++
        val camera = mc.cameraEntity ?: return
        val reach = if (customRange) range.get() else player.blockInteractionRange()
        hitResult = camera.pick(reach, 0f, false) as? BlockHitResult
        val hit = hitResult ?: return
        if (!holdingPlaceableItem() || delay < placeDelay || !mc.options.keyUse.isDown) return

        // Preserve the upstream action order, offhand interaction and stateId + 2 value.
        val connection = player.connection
        connection.send(ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN))
        connection.send(ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, player.containerMenu.stateId + 2))
        connection.send(ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN))
        SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND)
        delay = 0
    }

    @Suppress("unused")
    private val renderHandler = handler<WorldRenderEvent> { event ->
        val hit = hitResult ?: return@handler
        if (!render || !holdingPlaceableItem() || !world.getBlockState(hit.blockPos).canBeReplaced()) return@handler
        WorldRenderer(event).box(hit.blockPos, Color(sideColor.r, sideColor.g, sideColor.b, sideColor.a),
            Color(lineColor.r, lineColor.g, lineColor.b, lineColor.a), shapeMode.source, 0)
    }

    private fun holdingPlaceableItem(): Boolean =
        player.mainHandItem.item is BlockItem || player.mainHandItem.item is SpawnEggItem

    private enum class PreviewMode(val source: ShapeMode) : Tagged {
        Lines(ShapeMode.Lines), Sides(ShapeMode.Sides), Both(ShapeMode.Both);
        override val tag: String get() = name
    }
}
