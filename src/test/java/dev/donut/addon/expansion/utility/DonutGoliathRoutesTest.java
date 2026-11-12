package dev.donut.addon.expansion.utility;

import dev.donut.addon.hud.region.RegionMapData;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DonutGoliathRoutesTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void parsesOnlyRealShardLabelsWithoutLosingRegionWhitespace() {
        var routes = DonutGoliathRoutes.INSTANCE;
        var parsed = routes.parse(List.of("EU Central 1", " europe 1 ", "made up 9", "EU Central 1"));
        assertEquals(Set.of(new DonutGoliath("EU Central", 1), new DonutGoliath("Europe", 1)), parsed);
    }

    @Test
    void worldAndNetherPositionsResolveThroughTheSameDonutShardGrid() {
        var shard = RegionMapData.shards.stream()
            .filter(candidate -> candidate.getRegion().equals("Oceania"))
            .findFirst().orElseThrow();
        double x = (shard.getColumn() + 0.5) * 12_500.0 - 225_000.0;
        double z = (shard.getRow() + 0.5) * 12_500.0 - 225_000.0;
        var routes = DonutGoliathRoutes.INSTANCE;
        assertEquals(new DonutGoliath(shard.getRegion(), shard.getNumber()),
            routes.at(x, z, Level.OVERWORLD.identifier().toString()));
        assertEquals(new DonutGoliath(shard.getRegion(), shard.getNumber()),
            routes.at(x / 8.0, z / 8.0, Level.NETHER.identifier().toString()));
        assertNull(routes.at(x, z, Level.END.identifier().toString()));
    }

    @Test
    void routeSelectionPrefersTheDensestUnvisitedTargetRegionAndHasFallback() {
        var routes = DonutGoliathRoutes.INSTANCE;
        assertEquals("Oceania", routes.nextRegion("EU Central", Set.of(new DonutGoliath("Oceania", 1))));
        assertNull(routes.commandFor("Europe"));
        assertEquals("EU Central", routes.nextRegion("Europe", Set.of(new DonutGoliath("Europe", 1))));
        assertEquals("eu central", routes.commandFor("EU Central"));
    }
}
