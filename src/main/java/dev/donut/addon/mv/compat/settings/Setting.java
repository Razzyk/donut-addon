package dev.donut.addon.mv.compat.settings;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Small source-compatible setting facade attached to a native LiquidBounce value by MVModuleBase. */
public final class Setting<T> {
    public final String name;
    public final String description;
    public final T defaultValue;
    public final String kind;
    public final Double min;
    public final Double max;
    public final Double sliderMin;
    public final Double sliderMax;
    public final Integer decimalPlaces;
    public final boolean noSlider;
    public final boolean wide;
    public final IVisible visible;

    private volatile T localValue;
    private volatile Supplier<T> nativeGetter;
    private volatile Consumer<T> nativeSetter;
    private volatile Consumer<T> onChanged;
    private volatile Consumer<Setting<T>> onModuleActivated;
    private volatile Runnable action;

    Setting(String name, String description, T defaultValue, String kind,
            Double min, Double max, Double sliderMin, Double sliderMax, Integer decimalPlaces,
            boolean noSlider, boolean wide, IVisible visible) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.min = min;
        this.max = max;
        this.sliderMin = sliderMin;
        this.sliderMax = sliderMax;
        this.decimalPlaces = decimalPlaces;
        this.noSlider = noSlider;
        this.wide = wide;
        this.visible = visible == null ? () -> true : visible;
        this.localValue = defaultValue;
    }

    public T get() {
        Supplier<T> getter = nativeGetter;
        return getter == null ? localValue : Objects.requireNonNull(getter.get(), "native setting value");
    }

    public boolean set(T value) {
        Objects.requireNonNull(value, "value");
        if (!isValueValid(value)) return false;
        Consumer<T> setter = nativeSetter;
        if (setter != null) {
            setter.accept(value);
        } else {
            changedNative(value);
        }
        return true;
    }

    public Setting<T> onChanged(Consumer<T> callback) {
        this.onChanged = callback;
        return this;
    }

    public Setting<T> onModuleActivated(Consumer<Setting<T>> callback) {
        this.onModuleActivated = callback;
        return this;
    }

    public Setting<T> onModuleActivated(Runnable callback) {
        this.onModuleActivated = callback == null ? null : ignored -> callback.run();
        return this;
    }

    public Setting<T> action(Runnable callback) {
        this.action = callback;
        return this;
    }

    public Runnable actionCallback() { return action; }

    public void moduleActivated() {
        Consumer<Setting<T>> callback = onModuleActivated;
        if (callback != null) callback.accept(this);
    }

    public boolean isVisible() {
        return visible.get();
    }

    private boolean isValueValid(T value) {
        if (!(value instanceof Number number)) return true;
        double numeric = number.doubleValue();
        return (min == null || numeric >= min) && (max == null || numeric <= max);
    }

    /** Connects this setting to its native value. The current native value is read lazily by get(). */
    public synchronized void attach(Supplier<T> getter, Consumer<T> setter) {
        Objects.requireNonNull(getter, "getter");
        Objects.requireNonNull(setter, "setter");
        if (nativeGetter != null) throw new IllegalStateException("Setting already attached: " + name);
        nativeGetter = getter;
        nativeSetter = setter;
    }

    /** Detaches from the native value while retaining its last observed value locally. */
    public synchronized void detach() {
        Supplier<T> getter = nativeGetter;
        if (getter == null) return;
        localValue = Objects.requireNonNull(getter.get(), "native setting value");
        nativeGetter = null;
        nativeSetter = null;
    }

    /** Called by MVModuleBase when LiquidBounce reports a native value change. */
    public void changedNative(T value) {
        Objects.requireNonNull(value, "value");
        localValue = value;
        Consumer<T> callback = onChanged;
        if (callback != null) callback.accept(value);
    }

    public Consumer<T> onChangedCallback() {
        return onChanged;
    }
}
