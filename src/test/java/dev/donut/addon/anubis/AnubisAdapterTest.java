package dev.donut.addon.anubis;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.anubis.module.ClientModule;
import dev.donut.addon.anubis.module.ModuleCategory;
import dev.donut.addon.anubis.module.setting.StringSetting;
import dev.donut.addon.anubis.module.setting.KeySetting;
import dev.donut.addon.anubis.input.ModuleKeybinds;
import com.mojang.blaze3d.platform.InputConstants;
import net.ccbluex.liquidbounce.utils.input.InputBind;
import net.ccbluex.liquidbounce.config.types.Value;
import net.ccbluex.liquidbounce.config.types.group.ValueGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AnubisAdapterTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
    private static class Example extends ClientModule {
        final StringSetting setting = addSetting("test", new StringSetting("Test", "before"));
        final KeySetting key = addSetting("key", new KeySetting("Key", -1));
        Example() { super("test", "Adapter Test", "", ModuleCategory.MISC); }
    }

    @SuppressWarnings("unchecked")
    @Test void nativeChangesNotifyAndPrivacyPoliciesCanBeSetAfterBinding() {
        var module = new Example();
        var options = (ValueGroup) module.get().stream()
            .filter(value -> value.getName().equals("Options")).findFirst().orElseThrow();
        var nativeValue = (Value<String>) options.get().stream()
            .filter(value -> value.getName().equals("Test")).findFirst().orElseThrow();
        var changes = new AtomicInteger();
        module.setting.onChange(changes::incrementAndGet);

        nativeValue.set("native");
        assertEquals("native", module.setting.get());
        assertEquals(1, changes.get());
        module.setting.set("facade");
        assertEquals("facade", nativeValue.get());
        assertEquals(2, changes.get());

        assertTrue(nativeValue.isPersistent());
        assertTrue(nativeValue.checkIfInclude());
        module.setting.privateValue().sessionOnly();
        assertFalse(nativeValue.checkIfInclude());
        assertFalse(nativeValue.isPersistent());
        assertFalse(module.getEnabledValue().isPersistent());
    }

    @Test void allMacroModulesConstructAndInitializeSettingsWithoutEnabling() {
        var modules = AnubisFeatures.INSTANCE.getModules();
        assertEquals(11, modules.size());
        for (var module : modules) {
            assertSame(AddonCategories.MACROS, module.getCategory());
            assertFalse(module.getEnabled());
            module.walkInit();
            module.walkKeyPath(null);
        }
    }

    @SuppressWarnings("unchecked")
    @Test void functionKeysKeepTheirKeyboardTypeAndMouseBindingsRoundTrip() {
        var module = new Example();
        var options = (ValueGroup) module.get().stream()
            .filter(value -> value.getName().equals("Options")).findFirst().orElseThrow();
        var value = (Value<InputBind>) options.get().stream()
            .filter(entry -> entry.getName().equals("Key")).findFirst().orElseThrow();
        module.key.set(InputConstants.KEY_F1);
        assertTrue(ModuleKeybinds.isBindable(module.key.value()));
        assertEquals(InputConstants.Type.KEYBOARD, value.get().boundKey().getType());
        assertEquals(InputConstants.KEY_F1, value.get().boundKey().getValue());
        module.key.set(1001);
        assertEquals(InputConstants.Type.MOUSE, value.get().boundKey().getType());
        assertEquals(1, value.get().boundKey().getValue());
        assertEquals(1001, module.key.value());
    }
}
