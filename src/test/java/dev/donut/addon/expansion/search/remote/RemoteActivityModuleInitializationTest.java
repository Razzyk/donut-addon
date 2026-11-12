package dev.donut.addon.expansion.search.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RemoteActivityModuleInitializationTest {
    @BeforeAll
    static void bootstrapVanillaTypes() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void initializesTheRealModuleSettingsWithoutOpeningThePersistentStore() {
        RemoteActivityModule module = new RemoteActivityModule();

        assertTrue(module.runInMainMenu);
        assertEquals(Boolean.FALSE, module.settings.getDefaultGroup().get("auto-rtp").defaultValue);
        assertNotNull(module.settings.getDefaultGroup().get("rtp-command"));
        assertNotNull(module.settings.getDefaultGroup().get("rtp-menu-item"));
    }
}
