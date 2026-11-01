package dev.donut.addon.anubis.module.setting;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Source setting backed by a native LiquidBounce value once attached. */
public class Setting<T> {
    public final String name;
    public final T defaultValue;
    private T local;
    private Supplier<T> getter;
    private Consumer<T> setter;
    private BooleanSupplier visible = () -> true;
    private Runnable change = () -> {};
    private Runnable policyChanged = () -> {};
    private boolean privateValue;
    private boolean sessionOnly;

    public Setting(String name, T value) {
        this.name = Objects.requireNonNull(name);
        this.defaultValue = Objects.requireNonNull(value);
        this.local = value;
    }
    public T get() { return getter == null ? local : getter.get(); }
    public void set(T value) {
        Objects.requireNonNull(value);
        if (setter != null) setter.accept(value);
        else if (!Objects.equals(local, value)) { local = value; change.run(); }
    }
    public void attach(Supplier<T> getter, Consumer<T> setter) {
        this.getter = getter;
        this.setter = setter;
    }
    public void nativeChanged() { change.run(); }
    public Setting<T> onChange(Runnable callback) { change = Objects.requireNonNull(callback); return this; }
    public Setting<T> visibleWhen(BooleanSupplier condition) { visible = condition; return this; }
    public boolean isVisible() { return visible.getAsBoolean(); }
    public Setting<T> privateValue() { privateValue = true; policyChanged.run(); return this; }
    public Setting<T> sessionOnly() { sessionOnly = true; policyChanged.run(); return this; }
    public boolean isPrivateValue() { return privateValue; }
    public boolean isSessionOnly() { return sessionOnly; }
    public void onPolicyChanged(Runnable callback) { policyChanged = callback; callback.run(); }
}
