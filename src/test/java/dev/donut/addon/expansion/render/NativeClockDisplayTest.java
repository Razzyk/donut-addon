package dev.donut.addon.expansion.render;

import net.minecraft.SharedConstants;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NativeClockDisplayTest {
    @BeforeAll static void prepareNativeData() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void serverUpdatesAndElapsedTicksSurviveTheDisplayOverride() {
        var clockRegistry = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.WORLD_CLOCK);
        var definition = clockRegistry.getOrThrow(WorldClocks.OVERWORLD);
        var manager = new ClientClockManager();
        manager.handleUpdates(100L, Map.of(definition, new ClockNetworkState(12_000L, 0f, 1f)));
        var clock = manager.getInstance(definition);
        assertEquals(12_000L, clock.totalTicks());
        assertEquals(-2_000L, ClockDisplayPolicy.resolve(clock.totalTicks(), -2_000L, true, definition.is(WorldClocks.OVERWORLD)));
        manager.tick(105L);
        long progressed = clock.totalTicks();
        assertTrue(progressed > 12_000L, "the real native server clock continues while an override is displayed");
        assertEquals(progressed, ClockDisplayPolicy.resolve(clock.totalTicks(), -2_000L, false, true));
        manager.handleUpdates(105L, Map.of(definition, new ClockNetworkState(18_000L, 0f, 1f)));
        assertEquals(18_000L, clock.totalTicks());
        assertEquals(6_000L, ClockDisplayPolicy.resolve(clock.totalTicks(), 6_000L, true, true));
        assertEquals(18_000L, ClockDisplayPolicy.resolve(clock.totalTicks(), 6_000L, false, true));
    }

    @Test void otherClockDefinitionsAndOtherManagersRemainAuthoritative() {
        var registry = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.WORLD_CLOCK);
        var overworld = registry.getOrThrow(WorldClocks.OVERWORLD);
        var end = registry.getOrThrow(WorldClocks.THE_END);
        var first = new ClientClockManager();
        var second = new ClientClockManager();
        first.handleUpdates(1, Map.of(overworld, new ClockNetworkState(100, 0f, 0f),
            end, new ClockNetworkState(400, 0f, 1f)));
        second.handleUpdates(1, Map.of(overworld, new ClockNetworkState(800, 0f, 1f)));
        assertEquals(400, ClockDisplayPolicy.resolve(first.getInstance(end).totalTicks(), 0, true, end.is(WorldClocks.OVERWORLD)));
        assertEquals(100, first.getInstance(overworld).totalTicks());
        assertEquals(800, second.getInstance(overworld).totalTicks());
        assertEquals(800, ClockDisplayPolicy.resolve(second.getInstance(overworld).totalTicks(), 0, false, true));
    }
}
