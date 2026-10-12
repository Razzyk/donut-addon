package dev.donut.addon.mv.compat.settings;

import dev.donut.addon.mv.MVModuleBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SettingGroup {
    public final String name;
    private final List<Setting<?>> settings = new ArrayList<>();
    private MVModuleBase owner;

    SettingGroup(String name, MVModuleBase owner) {
        this.name = Objects.requireNonNull(name, "name");
        this.owner = owner;
    }

    public synchronized void owner(MVModuleBase owner) {
        if (this.owner == owner) return;
        if (this.owner != null && this.owner != owner) throw new IllegalStateException("Setting group already has an owner");
        this.owner = Objects.requireNonNull(owner, "owner");
        for (Setting<?> setting : settings) owner.attachSetting(name, setting);
    }

    public synchronized <T> Setting<T> add(Setting<T> setting) {
        Objects.requireNonNull(setting, "setting");
        if (settings.stream().anyMatch(existing -> existing.name.equalsIgnoreCase(setting.name))) {
            throw new IllegalArgumentException("Duplicate setting in group " + name + ": " + setting.name);
        }
        settings.add(setting);
        if (owner != null) owner.attachSetting(name, setting);
        return setting;
    }

    public synchronized Setting<?> get(String settingName) {
        return settings.stream().filter(s -> s.name.equalsIgnoreCase(settingName)).findFirst().orElse(null);
    }

    public synchronized List<Setting<?>> getSettings() {
        return Collections.unmodifiableList(new ArrayList<>(settings));
    }

    public synchronized int size() { return settings.size(); }
}
