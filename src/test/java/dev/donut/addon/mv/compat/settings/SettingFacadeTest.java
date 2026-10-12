package dev.donut.addon.mv.compat.settings;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingFacadeTest {
    private enum Mode { First, Second }

    @Test
    void keepsTrueBoundsSeparateFromTheVisibleSliderRange() {
        Setting<Integer> setting = new IntSetting.Builder()
            .name("distance")
            .defaultValue(15)
            .range(0, 100)
            .sliderRange(10, 20)
            .build();

        assertEquals(Double.valueOf(0.0), setting.min);
        assertEquals(Double.valueOf(100.0), setting.max);
        assertEquals(Double.valueOf(10.0), setting.sliderMin);
        assertEquals(Double.valueOf(20.0), setting.sliderMax);
    }

    @Test
    void rejectsOutOfRangeLocalWritesWithoutChangingValueOrFiringCallbacks() {
        AtomicInteger changes = new AtomicInteger();
        Setting<Integer> setting = new IntSetting.Builder()
            .defaultValue(5)
            .range(0, 10)
            .build()
            .onChanged(ignored -> changes.incrementAndGet());

        assertFalse(setting.set(11));
        assertEquals(5, setting.get());
        assertEquals(0, changes.get());
        assertTrue(setting.set(7));
        assertEquals(7, setting.get());
        assertEquals(1, changes.get());
    }

    @Test
    void attachedGetterAndSetterStayTypedAndNativeChangesReachCallback() {
        AtomicReference<String> nativeValue = new AtomicReference<>("native");
        List<String> changes = new ArrayList<>();
        Setting<String> setting = new StringSetting.Builder().defaultValue("local").build()
            .onChanged(changes::add);
        setting.attach(nativeValue::get, nativeValue::set);

        assertEquals("native", setting.get());
        assertTrue(setting.set("written"));
        assertEquals("written", nativeValue.get());
        nativeValue.set("external");
        setting.changedNative("external");
        assertEquals(List.of("external"), changes);
        assertEquals("external", setting.get());
        setting.detach();
        nativeValue.set("detached");
        assertEquals("external", setting.get());
    }

    @Test
    void preservesEnumAndListDefaults() {
        Setting<Mode> enumSetting = new EnumSetting.Builder<Mode>()
            .defaultValue(Mode.Second)
            .build();
        List<String> supplied = new ArrayList<>(List.of("a", "b"));
        Setting<List<String>> listSetting = new StringListSetting.Builder()
            .defaultValue(supplied)
            .build();
        supplied.clear();

        assertEquals(Mode.Second, enumSetting.get());
        assertEquals("enum", enumSetting.kind);
        assertEquals(List.of("a", "b"), listSetting.get());
        assertEquals("list", listSetting.kind);
    }

    @Test
    void recordsDecimalAndNoSliderMetadata() {
        Setting<Double> setting = new DoubleSetting.Builder()
            .defaultValue(1.25)
            .range(0.0, 2.0)
            .decimalPlaces(2)
            .noSlider()
            .build();

        assertEquals(2, setting.decimalPlaces);
        assertTrue(setting.noSlider);
        assertEquals(Double.valueOf(0.0), setting.min);
        assertEquals(Double.valueOf(2.0), setting.max);
    }

    @Test
    void recordsWideTextPresentationMetadata() {
        Setting<String> setting = new StringSetting.Builder().defaultValue("url").wide().build();
        assertTrue(setting.wide);
    }
}
