package dev.donut.addon.mv.compat;

import dev.donut.addon.mv.compat.events.EventHandler;
import dev.donut.addon.mv.compat.events.world.TickEvent;
import dev.donut.addon.mv.compat.systems.modules.Module;
import net.minecraft.client.Minecraft;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.LoggerFactory;

/** Immutable dispatch snapshots avoid scanning reflection or mutating iteration on each tick. */
public final class EventBus {
    private record Hook(Object owner, Method method, Class<?> type, int priority) {}
    private final CopyOnWriteArrayList<Hook> hooks = new CopyOnWriteArrayList<>();
    public synchronized void subscribe(Object listener) {
        if (hooks.stream().anyMatch(hook -> hook.owner == listener)) return;
        List<Hook> additions = new ArrayList<>();
        for (Class<?> type = listener.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                EventHandler annotation = method.getAnnotation(EventHandler.class);
                if (annotation == null) continue;
                if (method.getParameterCount() != 1) throw new IllegalArgumentException("Event handler needs one argument: " + method);
                method.setAccessible(true);
                additions.add(new Hook(listener, method, method.getParameterTypes()[0], annotation.priority()));
            }
        }
        List<Hook> merged = new ArrayList<>(hooks);
        merged.addAll(additions);
        merged.sort(Comparator.comparingInt(Hook::priority).reversed());
        hooks.clear(); hooks.addAll(merged);
    }
    public void unsubscribe(Object listener) { hooks.removeIf(hook -> hook.owner == listener); }
    public <T> T post(T event) {
        for (Hook hook : hooks) {
            if (!hook.type.isInstance(event)) continue;
            if (hook.owner instanceof Module module) {
                if (!module.isActive()) continue;
                if ((event instanceof TickEvent.Pre || event instanceof TickEvent.Post) && !module.runInMainMenu) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.level == null || mc.player == null) continue;
                }
            }
            try { hook.method.invoke(hook.owner, event); }
            catch (ReflectiveOperationException failure) {
                Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
                LoggerFactory.getLogger("Donut/MV").error("MV handler failed: " + hook.method, cause);
            }
        }
        return event;
    }
}
