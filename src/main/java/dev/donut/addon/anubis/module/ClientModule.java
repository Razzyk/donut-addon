package dev.donut.addon.anubis.module;

import dev.donut.addon.anubis.AnubisModuleBase;
import dev.donut.addon.anubis.event.EventHandler;
import dev.donut.addon.anubis.event.EventPriority;
import dev.donut.addon.anubis.event.EventType;
import dev.donut.addon.anubis.event.Stage;
import dev.donut.addon.anubis.module.setting.Setting;
import net.minecraft.client.Minecraft;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public abstract class ClientModule extends AnubisModuleBase {
    protected static final Minecraft mc = Minecraft.getInstance();

    private static final ClassValue<List<Method>> HANDLERS = new ClassValue<>() {
        @Override protected List<Method> computeValue(Class<?> type) {
            return Arrays.stream(type.getMethods())
                .filter(method -> method.isAnnotationPresent(EventHandler.class) && method.getParameterCount() == 1)
                .toList();
        }
    };
    private final List<Listener<?>> dynamic = new CopyOnWriteArrayList<>();

    protected ClientModule(String id, String name, String description, ModuleCategory category) {
        super(id, name, description);
    }

    protected final <T extends Setting<?>> T addSetting(String id, T setting) {
        attachSetting(id, setting);
        return setting;
    }

    protected void onEnable() {}
    protected void onDisable() {}
    public boolean inConfigs() { return true; }

    /** LiquidBounce already supplies the activation notifications requested by these modules. */
    protected final void usesNotifications() {}

    @Override public final void legacyActivate() {
        dynamic.clear();
        onEnable();
    }
    @Override public final void legacyDeactivate() {
        try { onDisable(); } finally { dynamic.clear(); }
    }

    public final void requestDisable() { requestStop(); }
    public final boolean isEnabled() { return getEnabled(); }

    protected final <T> void on(EventType<T> type, Stage stage, Consumer<T> handler) {
        on(type, stage, EventPriority.NORMAL, handler);
    }
    protected final <T> void on(EventType<T> type, Stage stage, EventPriority priority, Consumer<T> handler) {
        dynamic.add(new Listener<>(type, stage, priority, handler));
    }

    public final void dispatch(Object event) {
        if (!getEnabled()) return;
        for (Method method : HANDLERS.get(getClass())) {
            if (!method.getParameterTypes()[0].isInstance(event)) continue;
            try {
                method.invoke(this, event);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException("Handler failed: " + method, e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        for (Listener<?> listener : dynamic) {
            if (listener.type.type.isInstance(event)) listener.accept(event);
        }
    }

    private record Listener<T>(EventType<T> type, Stage stage, EventPriority priority, Consumer<T> handler) {
        void accept(Object event) { handler.accept(type.type.cast(event)); }
    }
}
