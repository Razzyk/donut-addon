package dev.donut.addon.mv.compat.settings;

public final class EnumSetting {
    private EnumSetting() {}
    public static final class Builder<T extends Enum<T>> extends SettingBuilder<T, Builder<T>> {
        public Builder() { kind = "enum"; }
        @Override protected Builder<T> self() { return this; }
        public Setting<T> build() { return buildSetting("enum"); }
    }
}
