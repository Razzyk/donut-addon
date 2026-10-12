package dev.donut.addon.mv.compat.settings;

import dev.donut.addon.mv.compat.utils.misc.Keybind;

public final class KeybindSetting {
    private KeybindSetting() {}
    public static final class Builder extends SettingBuilder<Keybind, Builder> {
        private Runnable action;
        public Builder() { kind = "keybind"; defaultValue = Keybind.fromKey(-1); }
        @Override protected Builder self() { return this; }
        public Builder action(Runnable action) { this.action = action; return this; }
        public Setting<Keybind> build() {
            Setting<Keybind> setting = buildSetting("keybind");
            if (action != null) setting.action(action);
            return setting;
        }
    }
}
