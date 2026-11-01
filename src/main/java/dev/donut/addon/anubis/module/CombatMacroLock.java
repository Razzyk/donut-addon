// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module;

import dev.donut.addon.anubis.module.ClientModule;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(value=EnvType.CLIENT)
public final class CombatMacroLock {
    private static Object holder;

    private CombatMacroLock() {
    }

    public static boolean tryAcquire(Object owner) {
        ClientModule module;
        Object object = holder;
        if (object instanceof ClientModule && !(module = (ClientModule)object).isEnabled()) {
            holder = null;
        }
        if (holder == null || holder == owner) {
            holder = owner;
            return true;
        }
        return false;
    }

    public static void release(Object owner) {
        if (holder == owner) {
            holder = null;
        }
    }

    public static boolean heldByOther(Object owner) {
        ClientModule module;
        Object object = holder;
        if (object instanceof ClientModule && !(module = (ClientModule)object).isEnabled()) {
            holder = null;
        }
        return holder != null && holder != owner;
    }
}
