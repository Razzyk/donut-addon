package dev.donut.addon.expansion.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DonutTradeOrderSettingRulesTest {
    @Test
    void priceAndIntervalSettingsSnapToTheSourceAnchoredIntegerGrid() {
        assertEquals(10, DonutTradeOrderSettingRules.INSTANCE.snapInt(13, 10, 600, 10));
        assertEquals(20, DonutTradeOrderSettingRules.INSTANCE.snapInt(15, 10, 600, 10));
        assertEquals(5, DonutTradeOrderSettingRules.INSTANCE.snapInt(6, 5, 300, 5));
        assertEquals(10, DonutTradeOrderSettingRules.INSTANCE.snapInt(-1, 10, 600, 10));
        assertEquals(600, DonutTradeOrderSettingRules.INSTANCE.snapInt(900, 10, 600, 10));
        assertEquals(300, DonutTradeOrderSettingRules.INSTANCE.snapInt(298, 5, 300, 5));
    }

    @Test
    void delayAndMultiplierSettingsSnapToTheirSourceFractionalGrids() {
        assertEquals(1.25f, DonutTradeOrderSettingRules.INSTANCE.snapFloat(1.125f, 0f, 5f, 0.25f, 0f));
        assertEquals(0f, DonutTradeOrderSettingRules.INSTANCE.snapFloat(-1f, 0f, 5f, 0.25f, 0f));
        assertEquals(5f, DonutTradeOrderSettingRules.INSTANCE.snapFloat(5.1f, 0f, 5f, 0.25f, 0f));
        assertEquals(3.5f, DonutTradeOrderSettingRules.INSTANCE.snapFloat(3.25f, 1f, 20f, 0.5f, 3f));
        assertEquals(8f, DonutTradeOrderSettingRules.INSTANCE.snapFloat(8.4f, 1f, 100f, 1f, 20f));
    }

    @Test
    void invalidFloatingPointValuesKeepTheConfiguredDefaultAndInfinityClampsToRange() {
        assertEquals(3f, DonutTradeOrderSettingRules.INSTANCE.snapFloat(Float.NaN, 1f, 20f, 0.5f, 3f));
        assertEquals(20f, DonutTradeOrderSettingRules.INSTANCE.snapFloat(Float.POSITIVE_INFINITY, 1f, 20f, 0.5f, 3f));
        assertEquals(1f, DonutTradeOrderSettingRules.INSTANCE.snapFloat(Float.NEGATIVE_INFINITY, 1f, 20f, 0.5f, 3f));
    }
}
