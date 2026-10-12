package dev.donut.addon.mv.compat.settings;

import dev.donut.addon.mv.MVModuleBase;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class Settings implements Iterable<SettingGroup> {
    private final MVModuleBase owner;
    private final SettingGroup defaultGroup;
    private final List<SettingGroup> groups = new ArrayList<>();

    public Settings(MVModuleBase owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.defaultGroup = createGroup("General");
    }

    public SettingGroup getDefaultGroup() { return defaultGroup; }

    public synchronized SettingGroup createGroup(String name) {
        Objects.requireNonNull(name, "name");
        SettingGroup group = groups.stream().filter(g -> g.name.equalsIgnoreCase(name)).findFirst().orElse(null);
        if (group != null) return group;
        group = new SettingGroup(name, owner);
        groups.add(group);
        return group;
    }

    public synchronized SettingGroup getGroup(String name) {
        return groups.stream().filter(g -> g.name.equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public synchronized List<SettingGroup> getGroups() { return List.copyOf(groups); }

    public synchronized List<Setting<?>> getAll() {
        return groups.stream().flatMap(g -> g.getSettings().stream()).toList();
    }

    @Override public synchronized Iterator<SettingGroup> iterator() { return getGroups().iterator(); }
}
