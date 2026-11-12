package dev.donut.addon.expansion.search.seed;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTestType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Immutable native ore-placement inputs for bounded symbolic decisions on Minecraft 26.3. */
final class SeedOreRecipes {
    static final int MAX_NATIVE_READS = 32_000;
    private static final ThreadLocal<Set<Long>> activeReads = new ThreadLocal<>();

    static final class ReadLimitException extends RuntimeException {
        ReadLimitException() { super("Native ore input-read cap reached"); }
    }
    static final class ReadCapture implements AutoCloseable {
        private final Set<Long> positions = new HashSet<>();
        private boolean closed;
        Set<Long> finish() {
            if (!closed) { activeReads.remove(); closed = true; }
            return Set.copyOf(positions);
        }
        @Override public void close() {
            if (!closed) { activeReads.remove(); closed = true; }
        }
    }

    static ReadCapture captureReads() {
        if (activeReads.get() != null) throw new IllegalStateException("Nested native read capture");
        ReadCapture capture = new ReadCapture();
        activeReads.set(capture.positions);
        return capture;
    }

    private static void read(long position) {
        Set<Long> reads = activeReads.get();
        if (reads != null && reads.add(position) && reads.size() > MAX_NATIVE_READS) throw new ReadLimitException();
    }

    private static final class ProbedRuleTest extends RuleTest {
        private final RuleTest delegate;
        ProbedRuleTest(RuleTest delegate) { this.delegate = delegate; }
        @Override public boolean test(BlockState state, BlockPos pos, RandomSource random) {
            read(SeedScanCore.packBlock(pos.getX(), pos.getY(), pos.getZ()));
            return delegate.test(state, pos, random);
        }
        @Override protected RuleTestType<?> getType() { return null; }
    }

    record Recipe(int stage, int featureIndex, int sequence, int attemptsLow, int attemptsHigh,
                  int rarity, int bottom, int top, int plateau, boolean triangular,
                  int size, float airDiscard, byte material, Set<Holder<Biome>> biomes, List<BlockReplacement> targets,
                  OreFeature feature, net.minecraft.world.level.levelgen.placement.PlacedFeature placed) {
        Recipe { biomes = Set.copyOf(biomes); targets = List.copyOf(targets); }
        int horizontalMargin() { return (int)Math.ceil(size/8.0F)+(int)Math.ceil((size/8.0F+1.0F)/2.0F); }
        int verticalReach() { return (int)Math.ceil(size / 16.0F + 0.5F); }
    }
    private final List<Recipe> recipes;
    private final Map<Integer, String> unsupported;

    SeedOreRecipes(NoiseBasedChunkGenerator generator, List<FeatureSorter.StepFeatureData> plan, List<Holder<Biome>> biomes) {
        List<Recipe> out = new ArrayList<>();
        Map<Integer, String> unknown = new LinkedHashMap<>();
        for (int stage : new int[]{GenerationStep.Decoration.UNDERGROUND_ORES.ordinal(),GenerationStep.Decoration.UNDERGROUND_DECORATION.ordinal()}) {
            if (stage >= plan.size()) continue;
            var features = plan.get(stage).features();
            for (int index=0;index<features.size();index++) {
                var placed = features.get(index);
                if (!(placed.feature().value() instanceof OreFeature ore)) continue;
                String context = "stage "+stage+", feature "+index;
                try {
                if (placed.placement().size() != 4) throw unsupported(context,"placement length");
                List<JsonObject> modifiers = placed.placement().stream().map(modifier ->
                    PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE,modifier).getOrThrow().getAsJsonObject()).toList();
                int attemptsLow,attemptsHigh,rarity=0;
                var first=modifiers.get(0);
                if (type(first).equals("minecraft:count")) {
                    JsonElement count=first.get("count");
                    if (count.isJsonPrimitive()) attemptsLow=attemptsHigh=count.getAsInt();
                    else {
                        JsonObject provider=count.getAsJsonObject();
                        if (!type(provider).equals("minecraft:uniform")) throw unsupported(context,"count provider");
                        attemptsLow=provider.get("min_inclusive").getAsInt(); attemptsHigh=provider.get("max_inclusive").getAsInt();
                    }
                } else if (type(first).equals("minecraft:rarity_filter")) {
                    attemptsLow=attemptsHigh=1; rarity=first.get("chance").getAsInt();
                } else throw unsupported(context,"attempt modifier");
                if (!type(modifiers.get(1)).equals("minecraft:in_square") || !type(modifiers.get(2)).equals("minecraft:height_range") ||
                    !type(modifiers.get(3)).equals("minecraft:biome")) throw unsupported(context,"placement order");
                JsonObject height=modifiers.get(2).getAsJsonObject("height");
                boolean triangular=type(height).equals("minecraft:trapezoid");
                if (!triangular && !type(height).equals("minecraft:uniform")) throw unsupported(context,"height provider");
                int bottom=anchor(height.getAsJsonObject("min_inclusive"));
                int top=anchor(height.getAsJsonObject("max_inclusive"));
                int plateau=height.has("plateau") ? height.get("plateau").getAsInt() : 0;
                byte material=SeedScanCore.MISSING;
                Set<String> tags=new HashSet<>();
                for (var target : ore.targetStates()) {
                    JsonObject rule=RuleTest.CODEC.encodeStart(JsonOps.INSTANCE,target.target()).getOrThrow().getAsJsonObject();
                    deterministicRule(rule,tags,0,context);
                    byte next=material(target.state());
                    if (next == SeedScanCore.MISSING || material != SeedScanCore.MISSING && material != next)
                        throw unsupported(context,"mixed or unsupported replacement material");
                    material=next;
                }
                if (material == SeedScanCore.MISSING || !(tags.equals(Set.of("minecraft:base_stone_overworld")) ||
                    tags.equals(Set.of("minecraft:stone_ore_replaceables","minecraft:deepslate_ore_replaceables")) ||
                    tags.equals(Set.of("minecraft:stone_ore_replaceables","minecraft:deepslate_ore_replaceables","minecraft:height_specific_ore_replaceables"))))
                    throw unsupported(context,"replacement tag set");
                Set<Holder<Biome>> carriers=new HashSet<>();
                for (var biome : biomes) {
                    var biomeSteps=generator.getBiomeGenerationSettings(biome).features();
                    if (stage < biomeSteps.size() && biomeSteps.get(stage).stream().anyMatch(holder -> holder.value() == placed)) carriers.add(biome);
                }
                if (attemptsLow < 0 || attemptsHigh < attemptsLow || attemptsHigh > 4096 || rarity < 0 || plateau < 0)
                    throw unsupported(context,"unbounded provider");
                List<BlockReplacement> targets = ore.targetStates();
                OreFeature probed = instrumented(ore);
                out.add(new Recipe(stage,index,out.size(),attemptsLow,attemptsHigh,rarity,bottom,top,plateau,triangular,
                    ore.size(),ore.discardChanceOnAirExposure(),material,carriers,targets,probed,placed));
                } catch (RuntimeException unsupportedRecipe) {
                    unknown.put(stage << 16 | index & 0xffff, unsupportedRecipe.getMessage() == null ?
                        unsupportedRecipe.getClass().getSimpleName() : unsupportedRecipe.getMessage());
                }
            }
        }
        recipes=List.copyOf(out);
        unsupported=Map.copyOf(unknown);
    }
    List<Recipe> values() { return recipes; }
    Map<Integer, String> unsupported() { return unsupported; }

    static OreFeature instrumented(OreFeature ore) {
        List<BlockReplacement> probedTargets = ore.targetStates().stream()
            .map(target -> new BlockReplacement(new ProbedRuleTest(target.target()), target.state())).toList();
        return new OreFeature(probedTargets, ore.size(), ore.discardChanceOnAirExposure());
    }

    private static void deterministicRule(JsonObject rule, Set<String> tags, int depth, String context) {
        if (depth > 16) throw unsupported(context,"nested replacement rules");
        String kind=rule.has("predicate_type") ? rule.get("predicate_type").getAsString() : type(rule);
        switch (kind) {
            case "minecraft:tag_match" -> tags.add(rule.get("tag").getAsString());
            case "minecraft:height_match" -> {
                if (!rule.has("min_inclusive") || !rule.has("max_inclusive")) throw unsupported(context,"height replacement bounds");
            }
            case "minecraft:not" -> deterministicRule(rule.getAsJsonObject("rule"),tags,depth+1,context);
            case "minecraft:all_of", "minecraft:any_of" -> {
                var rules=rule.getAsJsonArray("rules");
                if (rules == null || rules.size() > 16) throw unsupported(context,"replacement rule count");
                for (var child : rules) deterministicRule(child.getAsJsonObject(),tags,depth+1,context);
            }
            default -> throw unsupported(context,"nondeterministic or unknown replacement rule "+kind);
        }
    }

    private static int anchor(JsonObject value) {
        if (value.has("absolute")) return value.get("absolute").getAsInt();
        if (value.has("above_bottom")) return SeedScanCore.MIN_Y+value.get("above_bottom").getAsInt();
        if (value.has("below_top")) return SeedScanCore.MAX_Y-value.get("below_top").getAsInt();
        throw unsupported("height","vertical anchor");
    }
    private static String type(JsonObject value) { return value.has("type") ? value.get("type").getAsString() : ""; }
    private static IllegalStateException unsupported(String context,String detail) {
        return new IllegalStateException("Unsupported native seed recipe ("+context+", "+detail+")");
    }
    private static byte material(BlockState state) {
        var block=state.getBlock();
        if (block==Blocks.GRANITE) return SeedScanCore.GRANITE;
        if (block==Blocks.DIORITE) return SeedScanCore.DIORITE;
        if (block==Blocks.ANDESITE) return SeedScanCore.ANDESITE;
        if (block==Blocks.GRAVEL) return SeedScanCore.GRAVEL;
        if (block==Blocks.DIRT) return SeedScanCore.DIRT;
        if (block==Blocks.CLAY) return SeedScanCore.CLAY;
        if (block==Blocks.TUFF) return SeedScanCore.TUFF;
        String path=BuiltInRegistries.BLOCK.getKey(block).getPath();
        return path.endsWith("_ore") || block==Blocks.RAW_IRON_BLOCK || block==Blocks.RAW_COPPER_BLOCK ||
            block==Blocks.INFESTED_STONE || block==Blocks.INFESTED_DEEPSLATE ? SeedScanCore.ORE : SeedScanCore.MISSING;
    }
}
