package dev.donut.addon.mv.compat.systems.modules;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import dev.donut.addon.mv.compat.systems.modules.misc.AutoReconnect;
public final class Modules {
    private static final Modules INSTANCE = new Modules();
    private final List<Module> modules = new CopyOnWriteArrayList<>();
    private final AutoReconnect reconnect = new AutoReconnect();
    public static Modules get() { return INSTANCE; }
    public void add(Module module) {
        if (modules.stream().anyMatch(existing -> existing.getClass() == module.getClass())) throw new IllegalArgumentException("Duplicate MV module " + module.name);
        modules.add(module);
    }
    public <T> T get(Class<T> type) {
        if (type.isInstance(reconnect)) return type.cast(reconnect);
        for (Module module : modules) if (type.isInstance(module)) return type.cast(module);
        return null;
    }
    public boolean isActive(Class<?> type) { Object module = get(type); return module instanceof Module m && m.isActive(); }
    public List<Module> getAll() { return List.copyOf(modules); }
    public AutoReconnect reconnect() { return reconnect; }
}
