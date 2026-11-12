package dev.donut.addon.expansion;

import dev.donut.addon.AddonCategories;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExpansionInitializationTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void newFeaturesHaveUniqueDonutNamesAndInitializeDisabled() {
        var modules = ExpansionFeatures.INSTANCE.getModules();
        assertTrue(modules.size() >= 6);
        var names = new HashSet<String>();
        var categories = Set.of(AddonCategories.BASEFINDING, AddonCategories.UTILITY,
            AddonCategories.DONUT, AddonCategories.MACROS);
        for (var module : modules) {
            assertTrue(module.getName().startsWith("Donut"), module.getName());
            assertTrue(names.add(module.getName()), module.getName());
            assertFalse(module.getEnabled(), module.getName());
            assertTrue(categories.contains(module.getCategory()), module.getName());
            module.walkInit();
            module.walkKeyPath(null);
        }
    }
}
