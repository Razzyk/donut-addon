package dev.donut.addon.expansion.schematic;

import dev.donut.addon.expansion.ExpansionFeatures;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class SchematicRegistrationTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    }

    @Test void builderInitializesItsRealSettingsWithoutLinkingOptionalMods() {
        var builder=DonutSchematicBuilder.INSTANCE;
        assertEquals("Donut Schematic Builder",builder.getName());
        assertFalse(builder.getEnabled());
        assertTrue(builder.getDisableOnQuit());
        builder.walkInit(); builder.walkKeyPath(null);
        var settings=builder.get().stream().map(value -> value.getName()).collect(Collectors.toSet());
        assertTrue(settings.containsAll(Set.of("Buy From AH","Max Total Spend","Climb With Leaves",
            "Support Leaves","Crafter Slots","Place Water & Lava","Sell Leftovers","Tick Delay")));
    }

    @Test void optionalGateAndAlwaysOnLifecycleArePartOfTheAddonRegistry() {
        var schematic=SchematicFeatures.INSTANCE;
        assertEquals(LitematicaRuntimeAdapter.INSTANCE.modsAvailable(),!schematic.getModules().isEmpty());
        assertTrue(ExpansionFeatures.INSTANCE.getModules().containsAll(schematic.getModules()));
        assertFalse(schematic.getListeners().isEmpty());
        assertTrue(ExpansionFeatures.INSTANCE.getListeners().containsAll(schematic.getListeners()));
        assertTrue(schematic.getListeners().stream().allMatch(listener -> listener.getRunning()));
    }
}
