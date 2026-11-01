package dev.donut.addon.anubis.module.setting;
public final class KeySetting extends Setting<Integer> {
    public KeySetting(String name, int value) { super(name, value); }
    public int value() { return get(); }
}
