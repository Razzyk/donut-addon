package dev.donut.addon.expansion.automation

import dev.donut.addon.expansion.automation.core.AutomationCombatLock
import dev.donut.addon.expansion.automation.core.CombatDamageRules
import dev.donut.addon.expansion.automation.core.CrystalAttemptLedger
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.boss.enderdragon.EndCrystal
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.ccbluex.liquidbounce.utils.entity.getDamageFromExplosion
import kotlin.random.Random

/** Right-click crystal cycle with a conservative self-damage bound and locally attributed breaks. */
class DonutCrystalChain : DonutAutomationModule("Donut Crystal Chain") {
    private val breakDelay by int("Break Delay (ticks)", 0, 0..10)
    private val placeDelay by int("Place Delay (ticks)", 0, 0..10)
    private val autoObsidian by boolean("Auto Obsidian", true)
    private val obsidianDelay by int("Obsidian Delay (ticks)", 0, 0..10)
    private val switchBack by boolean("Switch Back", true)
    private val maxSelfDamage by float("Max Self Damage", 8f, 0f..20f)
    private val humanize by boolean("Humanize", true)
    private val random = Random(System.nanoTime())
    private val breakAttempts = CrystalAttemptLedger()
    private var originalPlayer: LocalPlayer? = null
    private var originalSlot = -1
    private var automationSlot = -1
    private var expectedCrystalUntil = 0L
    private var expectedObsidianAt: BlockPos? = null
    private var pendingBreakId = -1
    private var pendingBreakUntil = 0L
    private var nextActionTick = 0L

    init { literalDescription { "Chains safe crystal breaks and placements while use is held, with optional obsidian placement." } }

    override fun onAutomationReset() {
        restoreOwnedSelection()
        expectedCrystalUntil = 0L
        expectedObsidianAt = null
        pendingBreakId = -1
        pendingBreakUntil = 0L
        nextActionTick = 0L
        originalPlayer = null
        originalSlot = -1
        automationSlot = -1
        breakAttempts.reset()
        AutomationCombatLock.release(this)
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val frame = captureFrame() ?: return@handler
        if (!mc.options.keyUse.isPressedOnAny || mc.gui.screen() != null || frame.player.isDeadOrDying) {
            restoreSelection(frame)
            clearCycle()
            AutomationCombatLock.release(this)
            return@handler
        }

        val nowTick = frame.snapshot.tick
        val liveCrystals = frame.world.entitiesForRendering().filterIsInstance<EndCrystal>()
            .filter { it.isAlive }
        breakAttempts.forgetMissing(liveCrystals.mapTo(mutableSetOf()) { it.id })

        if (pendingBreakId >= 0) {
            if (liveCrystals.none { it.id == pendingBreakId }) {
                pendingBreakId = -1
            } else if (nowTick < pendingBreakUntil) return@handler
            else pendingBreakId = -1
        }
        if (nowTick < nextActionTick) return@handler

        when (val hit = mc.hitResult) {
            is EntityHitResult -> {
                val crystal = hit.entity as? EndCrystal ?: return@handler
                if (!AutomationCombatLock.tryAcquire(this)) return@handler
                if (!breakAttempts.canAttempt(crystal.id, nowTick)) return@handler
                if (!safeToBreak(frame, crystal)) return@handler
                if (attack(frame, crystal, 4.5)) {
                    breakAttempts.recordAttempt(crystal.id, nowTick)
                    pendingBreakId = crystal.id
                    pendingBreakUntil = nowTick + 12
                    nextActionTick = nowTick + delay(breakDelay)
                }
            }
            is BlockHitResult -> {
                val base = frame.world.getBlockState(hit.blockPos).`is`(Blocks.OBSIDIAN) ||
                    frame.world.getBlockState(hit.blockPos).`is`(Blocks.BEDROCK)
                if (base || autoObsidian) {
                    if (!AutomationCombatLock.tryAcquire(this)) return@handler
                }
                cycleBlock(frame, hit, nowTick)
            }
            else -> Unit
        }
    }

    private fun cycleBlock(frame: LiveAutomationFrame, hit: BlockHitResult, tick: Long) {
        val level = frame.world
        val clicked = hit.blockPos
        val planned = expectedObsidianAt
        val base = when {
            planned != null && clicked == planned -> planned
            else -> clicked
        }
        if (planned != null && tick > expectedCrystalUntil) expectedObsidianAt = null

        val state = level.getBlockState(base)
        val crystalBase = state.`is`(Blocks.OBSIDIAN) || state.`is`(Blocks.BEDROCK)
        val clearSpace = level.getBlockState(base.above()).isAir && level.getBlockState(base.above(2)).isAir
        if (crystalBase && clearSpace) {
            val crystalSlot = findHotbar(frame.player, Items.END_CRYSTAL)
            if (crystalSlot < 0) return
            if (frame.player.inventory.selectedSlot != crystalSlot) {
                if (selectSlot(frame, crystalSlot)) return
            }
            if (clicked != base) return
            val useHit = if (hit.blockPos == base) hit else return
            expectedCrystalUntil = tick + 20
            if (useOnBlock(frame, useHit)) nextActionTick = tick + delay(placeDelay)
            return
        }

        if (!autoObsidian || planned != null) return
        val obsidianSlot = findHotbar(frame.player, Items.OBSIDIAN)
        if (obsidianSlot < 0) return
        if (frame.player.inventory.selectedSlot != obsidianSlot) {
            if (selectSlot(frame, obsidianSlot)) return
        }
        val destination = clicked.relative(hit.direction)
        if (!level.getBlockState(destination).canBeReplaced() ||
            !level.getBlockState(destination.above()).isAir || !level.getBlockState(destination.above(2)).isAir) return
        expectedObsidianAt = destination
        expectedCrystalUntil = tick + 20
        if (useOnBlock(frame, hit)) nextActionTick = tick + delay(obsidianDelay)
    }

    private fun safeToBreak(frame: LiveAutomationFrame, crystal: EndCrystal): Boolean {
        val damagePoints = frame.player.getDamageFromExplosion(crystal.position(), power = 6f).toDouble()
        val currentHealth = (frame.player.health + frame.player.absorptionAmount).toDouble()
        return CombatDamageRules.crystalAllowed(
            damagePoints,
            currentHealth,
            maxSelfDamage.toDouble(),
            frame.player.abilities.invulnerable,
        )
    }

    private fun selectSlot(frame: LiveAutomationFrame, slot: Int): Boolean {
        if (originalSlot < 0) {
            originalPlayer = frame.player
            originalSlot = frame.player.inventory.selectedSlot
        } else if (originalPlayer !== frame.player) {
            originalPlayer = frame.player
            originalSlot = frame.player.inventory.selectedSlot
        }
        automationSlot = slot
        return selectHotbar(frame, slot)
    }

    private fun restoreSelection(frame: LiveAutomationFrame) {
        if (!switchBack || originalSlot !in 0..8 || originalPlayer !== frame.player) return
        if (automationSlot in 0..8 && frame.player.inventory.selectedSlot == automationSlot) {
            selectHotbar(frame, originalSlot)
        }
        originalSlot = -1
        automationSlot = -1
        originalPlayer = null
    }

    private fun restoreOwnedSelection() {
        val player = originalPlayer ?: return
        if (switchBack && automationSlot in 0..8 && originalSlot in 0..8 && player === mc.player &&
            player.inventory.selectedSlot == automationSlot) {
            player.inventory.selectedSlot = originalSlot
        }
    }

    private fun clearCycle() {
        expectedCrystalUntil = 0L
        expectedObsidianAt = null
        pendingBreakId = -1
        pendingBreakUntil = 0L
        nextActionTick = 0L
    }

    private fun findHotbar(player: LocalPlayer, item: net.minecraft.world.item.Item): Int =
        (0..8).firstOrNull { player.inventory.getItem(it).`is`(item) } ?: -1

    private fun delay(value: Int): Long {
        if (!humanize) return value.coerceIn(0, 10).toLong()
        return maxOf(value.coerceIn(0, 10), random.nextInt(2, 5)).toLong()
    }
}
