package dev.donut.addon.expansion.building

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TunnelResourceRulesTest {
    @Test
    fun `food threshold matches hunger and injured rules`() {
        assertTrue(TunnelResourceRules.hungry(14, 20f, 20f))
        assertTrue(TunnelResourceRules.hungry(17, 19f, 20f))
        assertFalse(TunnelResourceRules.hungry(17, 20f, 20f))
        assertFalse(TunnelResourceRules.hungry(18, 19f, 20f))
    }

    @Test
    fun `tool stops before breaking unless a mending tool is configured`() {
        assertTrue(TunnelResourceRules.shouldStopPickaxe(9, false, false))
        assertTrue(TunnelResourceRules.shouldStopPickaxe(9, true, false))
        assertFalse(TunnelResourceRules.shouldStopPickaxe(9, true, true))
        assertFalse(TunnelResourceRules.shouldStopPickaxe(10, false, false))
    }

    @Test
    fun `inventory actions require no physical movement grounded and settled`() {
        assertTrue(TunnelResourceRules.calm(false, true, 0.0))
        assertFalse(TunnelResourceRules.calm(true, true, 0.0))
        assertFalse(TunnelResourceRules.calm(false, false, 0.0))
        assertFalse(TunnelResourceRules.calm(false, true, 0.0025))
    }
}
