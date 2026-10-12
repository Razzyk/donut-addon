package dev.donut.addon.mv.compat.settings;

import dev.donut.addon.mv.compat.utils.render.color.SettingColor;

public final class ColorSetting {
    private ColorSetting() {}
    public static final class Builder extends SettingBuilder<SettingColor, Builder> {
        public Builder() { kind = "color"; defaultValue = new SettingColor(255, 255, 255, 255); }
        @Override protected Builder self() { return this; }
        @Override public Builder defaultValue(SettingColor value) { defaultValue = new SettingColor(value); return this; }
        public Setting<SettingColor> build() { return buildSetting("color"); }
    }
}
