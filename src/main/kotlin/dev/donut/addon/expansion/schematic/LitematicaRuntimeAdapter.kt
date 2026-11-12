package dev.donut.addon.expansion.schematic

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.Level

/** No Litematica or MaLiLib type appears in a verifier-visible signature. */
data class LitematicaPlacementFrame(
    val schematicWorld: Level,
    val enabledRegions: List<LitematicaWorldRegion>,
    val identity: String,
    val placementCount: Int,
)

data class LitematicaWorldRegion(
    val placementId: String,
    val min: BlockPos,
    val max: BlockPos,
) {
    fun contains(pos: BlockPos): Boolean =
        pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z
}

sealed interface LitematicaFrameResult {
    data class Ready(val frame: LitematicaPlacementFrame) : LitematicaFrameResult
    data class Unavailable(val reason: String) : LitematicaFrameResult
}

/**
 * Reflective bridge for the public Litematica 26.3 API. External classes are resolved only after the
 * Fabric mod gate succeeds, so Donut loads cleanly when either optional mod is absent.
 */
object LitematicaRuntimeAdapter {
    private const val LITEMATICA = "fi.dy.masa.litematica"
    private const val DATA_MANAGER = "$LITEMATICA.data.DataManager"
    private const val PLACEMENT_MANAGER = "$LITEMATICA.schematic.placement.SchematicPlacementManager"
    private const val PLACEMENT = "$LITEMATICA.schematic.placement.SchematicPlacement"
    private const val SUB_REGION_PLACEMENT = "$LITEMATICA.schematic.placement.SubRegionPlacement\$RequiredEnabled"
    private const val SCHEMATIC_WORLD_HANDLER = "$LITEMATICA.world.SchematicWorldHandler"

    fun modsAvailable(): Boolean = runCatching {
        val loader = FabricLoader.getInstance()
        loader.isModLoaded("litematica") && loader.isModLoaded("malilib")
    }.getOrDefault(false)

    fun snapshot(): LitematicaFrameResult {
        if (!modsAvailable()) return LitematicaFrameResult.Unavailable("Litematica and MaLiLib are required")
        return runCatching(::readSnapshot).getOrElse { error ->
            LitematicaFrameResult.Unavailable("Litematica placement data is unavailable: ${error.cause?.message ?: error.message ?: error.javaClass.simpleName}")
        }
    }

    /** Returns null outside enabled boxes or when Litematica has not materialized that virtual chunk. */
    fun expectedStateAt(frame: LitematicaPlacementFrame, worldPosition: BlockPos): BlockState? {
        if (frame.enabledRegions.none { it.contains(worldPosition) } || !frame.schematicWorld.hasChunkAt(worldPosition)) return null
        return expectedStateAtInEnabledRegion(frame, worldPosition)
    }

    /** Fast path for coordinates supplied by the active layer-window cursor, which already unions enabled boxes. */
    internal fun expectedStateAtInEnabledRegion(frame: LitematicaPlacementFrame, worldPosition: BlockPos): BlockState? {
        if (!frame.schematicWorld.hasChunkAt(worldPosition)) return null
        return runCatching { frame.schematicWorld.getBlockState(worldPosition) }.getOrNull()
    }

    /** Reads the projected native crafter block entity only when that schematic-world chunk exists. */
    fun expectedCrafterDisabledSlots(frame: LitematicaPlacementFrame, worldPosition: BlockPos): List<Boolean>? {
        val state = expectedStateAt(frame, worldPosition) ?: return null
        if (state.block != net.minecraft.world.level.block.Blocks.CRAFTER) return null
        val projected = runCatching { frame.schematicWorld.getBlockEntity(worldPosition) }.getOrNull()
            as? net.minecraft.world.level.block.entity.CrafterBlockEntity ?: return null
        return (0 until 9).map(projected::isSlotDisabled)
    }

    private fun readSnapshot(): LitematicaFrameResult {
        val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        val dataManager = Class.forName(DATA_MANAGER, false, loader)
        val manager = dataManager.getMethod("getSchematicPlacementManager").invoke(null)
            ?: return LitematicaFrameResult.Unavailable("Litematica has no placement manager")
        val placements = manager.javaClass.getMethod("getAllSchematicsPlacements").invoke(manager) as? Iterable<*>
            ?: return LitematicaFrameResult.Unavailable("Litematica returned no placement collection")

        val enabled = placements.filterNotNull().filter { placement ->
            placement.javaClass.getMethod("isEnabled").invoke(placement) == true
        }.sortedBy { placement -> placement.javaClass.getMethod("getHashId").invoke(placement).toString() }
        val enumType = Class.forName(SUB_REGION_PLACEMENT, false, loader)
        val enabledValue = enumType.getField("PLACEMENT_ENABLED").get(null)
        val regions = ArrayList<SchematicRegion>()
        val worldRegions = ArrayList<LitematicaWorldRegion>()
        val identityParts = ArrayList<String>()
        for (placement in enabled) {
            check(placement.javaClass.name == PLACEMENT || placement.javaClass.superclass?.name == PLACEMENT) {
                "Unexpected placement implementation: ${placement.javaClass.name}"
            }
            val placementId = placement.javaClass.getMethod("getHashId").invoke(placement).toString()
            val origin = placement.javaClass.getMethod("getOrigin").invoke(placement) as? BlockPos
                ?: return LitematicaFrameResult.Unavailable("Litematica placement origin is unavailable")
            val rotation = placement.javaClass.getMethod("getRotation").invoke(placement).toString()
            val mirror = placement.javaClass.getMethod("getMirror").invoke(placement).toString()
            val schematic = placement.javaClass.getMethod("getSchematic").invoke(placement)
            identityParts += "placement=$placementId@${System.identityHashCode(placement)}:${origin.x},${origin.y},${origin.z}:$rotation:$mirror:schematic=${System.identityHashCode(schematic)}"

            // Bounds alone do not invalidate symmetric transforms. Include both placement-level
            // transforms and every subregion transform/enabled bit in the active frame identity.
            val subRegions = placement.javaClass.getMethod("getAllSubRegionsPlacements").invoke(placement) as? Iterable<*>
                ?: emptyList<Any>()
            for (subRegion in subRegions.filterNotNull().sortedBy { it.javaClass.getMethod("getName").invoke(it).toString() }) {
                val name = subRegion.javaClass.getMethod("getName").invoke(subRegion).toString()
                val subPosition = subRegion.javaClass.getMethod("getPos").invoke(subRegion) as? BlockPos
                    ?: return LitematicaFrameResult.Unavailable("Litematica subregion position is unavailable")
                val subRotation = subRegion.javaClass.getMethod("getRotation").invoke(subRegion).toString()
                val subMirror = subRegion.javaClass.getMethod("getMirror").invoke(subRegion).toString()
                val subEnabled = subRegion.javaClass.getMethod("isEnabled").invoke(subRegion)
                identityParts += "region=$name:${subPosition.x},${subPosition.y},${subPosition.z}:$subRotation:$subMirror:$subEnabled"
            }
            val boxes = placement.javaClass.getMethod("getSubRegionBoxes", enumType).invoke(placement)
            val values = when (boxes) {
                is Map<*, *> -> boxes.values
                is Iterable<*> -> boxes
                else -> emptyList<Any>()
            }
            for (box in values.filterNotNull()) {
                val first = box.javaClass.getMethod("getPos1").invoke(box) as? BlockPos ?: continue
                val second = box.javaClass.getMethod("getPos2").invoke(box) as? BlockPos ?: continue
                val region = SchematicRegion(
                    placementId = placementId,
                    min = BuildCellPos(first.x, first.y, first.z),
                    max = BuildCellPos(second.x, second.y, second.z),
                ).normalized()
                regions += region
                val low = BlockPos(region.min.x, region.min.y, region.min.z).immutable()
                val high = BlockPos(region.max.x, region.max.y, region.max.z).immutable()
                worldRegions += LitematicaWorldRegion(placementId, low, high)
                identityParts += "box=$placementId:${region.min.x},${region.min.y},${region.min.z}:${region.max.x},${region.max.y},${region.max.z}"
            }
        }

        val handler = Class.forName(SCHEMATIC_WORLD_HANDLER, false, loader)
        val world = handler.getMethod("getSchematicWorld").invoke(null) as? Level
            ?: return LitematicaFrameResult.Unavailable("Litematica has no projected schematic world")
        if (regions.isEmpty()) return LitematicaFrameResult.Unavailable("No enabled Litematica placement boxes are active")
        identityParts += "world=${System.identityHashCode(world)}"
        val key = identityParts.joinToString("|")
        return LitematicaFrameResult.Ready(LitematicaPlacementFrame(world, worldRegions.toList(), key, enabled.size))
    }
}
