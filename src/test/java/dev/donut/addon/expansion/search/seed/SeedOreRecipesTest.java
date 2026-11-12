package dev.donut.addon.expansion.search.seed;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SeedOreRecipesTest {
    @BeforeAll static void prepareNativeData() { VanillaSeedWorldgenTest.bootstrapMinecraftData(); }
    @SuppressWarnings("unchecked")
    @Test void allCurrentNativeOreRecipesHaveSupportedImmutableInputs() throws Exception {
        var backend=new VanillaSeedWorldgen(42);
        var generator=(NoiseBasedChunkGenerator)field(backend,"generator");
        var steps=(List<FeatureSorter.StepFeatureData>)field(backend,"featureSteps");
        var biomes=(List<Holder<Biome>>)field(backend,"biomeById");
        var settings=((Holder<NoiseGeneratorSettings>)field(backend,"settings")).value().noiseSettings();
        System.out.println("Native Overworld noise bounds: "+settings.minY()+" / "+settings.height());
        var nativeRecipes=new SeedOreRecipes(generator,steps,biomes);
        var recipes=nativeRecipes.values();
        assertFalse(recipes.isEmpty());
        assertEquals(30, recipes.size(), "all current 26.3 Overworld native ore placements stay source-comparable");
        assertTrue(nativeRecipes.unsupported().isEmpty(), "no current native ore placement falls through to fog");
        assertTrue(recipes.stream().anyMatch(recipe -> recipe.material()==SeedScanCore.GRANITE));
        assertTrue(recipes.stream().anyMatch(recipe -> recipe.material()==SeedScanCore.ORE));
        assertTrue(recipes.stream().allMatch(recipe -> !recipe.biomes().isEmpty()));
        assertThrows(UnsupportedOperationException.class,recipes::clear);
        System.out.println("Validated native ore recipes: "+recipes.size());
    }
    private static Object field(Object owner,String name) throws Exception {
        var field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
}
