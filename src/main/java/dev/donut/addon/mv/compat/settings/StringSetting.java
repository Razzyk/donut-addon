package dev.donut.addon.mv.compat.settings;

public final class StringSetting {
    private StringSetting() {}
    public static final class Builder extends SettingBuilder<String, Builder> {
        public Builder() { kind = "string"; defaultValue = ""; }
        @Override protected Builder self() { return this; }
        public Setting<String> build() { return buildSetting("string"); }
    }
}
