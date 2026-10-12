package dev.donut.addon.mv.compat.settings;

import java.util.ArrayList;
import java.util.List;

public final class StringListSetting {
    private StringListSetting() {}
    public static final class Builder extends SettingBuilder<List<String>, Builder> {
        public Builder() { kind = "list"; defaultValue = new ArrayList<>(); }
        @Override protected Builder self() { return this; }
        @Override public Builder defaultValue(List<String> value) { defaultValue = new ArrayList<>(value); return this; }
        public Setting<List<String>> build() { return buildSetting("list"); }
    }
}
