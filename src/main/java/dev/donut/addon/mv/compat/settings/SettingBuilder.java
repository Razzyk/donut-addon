package dev.donut.addon.mv.compat.settings;

import java.util.Objects;
import java.util.function.Consumer;

public abstract class SettingBuilder<T, B extends SettingBuilder<T, B>> {
    protected String name = "setting";
    protected String description = "";
    protected T defaultValue;
    protected Double min;
    protected Double max;
    protected Double sliderMin;
    protected Double sliderMax;
    protected IVisible visible = () -> true;
    protected String kind;
    protected Consumer<Setting<T>> onModuleActivated;
    protected Consumer<T> onChanged;
    protected int decimalPlaces = -1;
    protected boolean noSlider;
    protected boolean wide;

    protected abstract B self();

    public B name(String name) { this.name = Objects.requireNonNull(name); return self(); }
    public B description(String description) { this.description = description; return self(); }
    public B defaultValue(T value) { this.defaultValue = Objects.requireNonNull(value); return self(); }
    public B visible(IVisible visible) { this.visible = visible == null ? () -> true : visible; return self(); }
    public B onModuleActivated(Consumer<Setting<T>> callback) { this.onModuleActivated = callback; return self(); }
    public B onModuleActivated(Runnable callback) {
        this.onModuleActivated = callback == null ? null : ignored -> callback.run();
        return self();
    }
    public B onChanged(Consumer<T> callback) { this.onChanged = callback; return self(); }
    public B decimalPlaces(int places) {
        if (places < 0) throw new IllegalArgumentException("decimal places must be non-negative");
        this.decimalPlaces = places;
        return self();
    }
    public B noSlider() { this.noSlider = true; return self(); }
    public B wide() { this.wide = true; return self(); }

    protected final void bounds(double lower, double upper) {
        if (lower > upper) throw new IllegalArgumentException("Minimum exceeds maximum");
        min = lower;
        max = upper;
    }

    protected final void sliderBounds(double lower, double upper) {
        if (lower > upper) throw new IllegalArgumentException("Slider minimum exceeds maximum");
        sliderMin = lower;
        sliderMax = upper;
    }

    protected final Setting<T> buildSetting(String valueKind) {
        if (name == null || name.isBlank()) throw new IllegalStateException("Setting name cannot be blank");
        if (defaultValue == null) throw new IllegalStateException("Default value is required for " + name);
        if (min != null && max != null && min > max) throw new IllegalStateException("Minimum exceeds maximum for " + name);
        Double effectiveSliderMin = sliderMin;
        Double effectiveSliderMax = sliderMax;
        if (effectiveSliderMin != null && min != null) effectiveSliderMin = Math.max(effectiveSliderMin, min);
        if (effectiveSliderMax != null && max != null) effectiveSliderMax = Math.min(effectiveSliderMax, max);
        Setting<T> setting = new Setting<>(name, description, defaultValue, valueKind, min, max, effectiveSliderMin, effectiveSliderMax,
            decimalPlaces < 0 ? null : decimalPlaces, noSlider, wide, visible);
        if (onChanged != null) setting.onChanged(onChanged);
        if (onModuleActivated != null) setting.onModuleActivated(onModuleActivated);
        return setting;
    }

}
