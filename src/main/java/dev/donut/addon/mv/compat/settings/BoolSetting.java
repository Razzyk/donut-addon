package dev.donut.addon.mv.compat.settings;

public final class BoolSetting {
    private BoolSetting() {}
    public static final class Builder extends SettingBuilder<Boolean, Builder> {
        public Builder() { kind = "bool"; defaultValue = false; }
        @Override protected Builder self() { return this; }
        public Setting<Boolean> build() { return buildSetting("bool"); }
    }
}
