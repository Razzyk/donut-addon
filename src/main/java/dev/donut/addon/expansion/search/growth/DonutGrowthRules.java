package dev.donut.addon.expansion.search.growth;

import java.util.Locale;

/** Donut Growth Inspector's Minecraft-independent signal and threshold predicates. */
public final class DonutGrowthRules {
    public enum Kind {
        KELP, CAVE_VINES, WEEPING_VINES, BAMBOO, SUGAR_CANE, CACTUS, COCOA, SWEET_BERRIES,
        NETHER_WART, CROPS, STEMS, AMETHYST, VINES, LEAVES, GRASS_DIRT, PROPAGULES,
        EYEBLOSSOMS, SNOW, ICE
    }

    private DonutGrowthRules() {}

    public static Kind classify(String blockId, boolean cropBlock, boolean hangingPropagule) {
        if (blockId == null) return null;
        String path = blockId.toLowerCase(Locale.ROOT);
        int colon = path.indexOf(':');
        if (colon >= 0) path = path.substring(colon + 1);
        return switch (path) {
            case "kelp" -> Kind.KELP;
            case "cave_vines" -> Kind.CAVE_VINES;
            case "weeping_vines" -> Kind.WEEPING_VINES;
            case "bamboo" -> Kind.BAMBOO;
            case "sugar_cane" -> Kind.SUGAR_CANE;
            case "cactus" -> Kind.CACTUS;
            case "cocoa" -> Kind.COCOA;
            case "sweet_berry_bush" -> Kind.SWEET_BERRIES;
            case "nether_wart" -> Kind.NETHER_WART;
            case "pumpkin_stem", "melon_stem" -> Kind.STEMS;
            case "budding_amethyst", "amethyst_cluster", "small_amethyst_bud", "medium_amethyst_bud", "large_amethyst_bud" -> Kind.AMETHYST;
            case "vine" -> Kind.VINES;
            case "mangrove_propagule" -> hangingPropagule ? Kind.PROPAGULES : null;
            case "open_eyeblossom", "closed_eyeblossom" -> Kind.EYEBLOSSOMS;
            case "grass_block", "mycelium", "dirt" -> Kind.GRASS_DIRT;
            default -> {
                if (cropBlock) yield Kind.CROPS;
                if (path.endsWith("_leaves")) yield Kind.LEAVES;
                yield null;
            }
        };
    }

    public static boolean kelp(int age, boolean waterAbove) {
        return age >= 0 && age < 25 && waterAbove;
    }

    public static boolean donutKelpColumn(boolean waterAbove, int kelpPlantColumnLength) {
        return waterAbove && kelpPlantColumnLength >= 1 && kelpPlantColumnLength < 3;
    }

    public static boolean vines(boolean cave, boolean donut, int age, boolean airBelow) {
        return !(cave && donut) && age >= 0 && age < 25 && airBelow;
    }

    public static boolean bamboo(int stage, boolean airAbove, int bambooBelow, int lightAbove) {
        return stage == 0 && airAbove && bambooBelow >= 0 && bambooBelow + 1 < 16 && lightAbove >= 9;
    }

    public static boolean sugarCane(boolean airAbove, int sameBlockColumnLength) {
        return airAbove && sameBlockColumnLength >= 1 && sameBlockColumnLength < 3;
    }

    public static boolean cactus(int age, boolean airAbove, int sameBlockColumnLength) {
        return airAbove && (age >= 0 && age < 15 || sameBlockColumnLength >= 1 && sameBlockColumnLength < 3);
    }

    public static boolean belowMaxAge(int age, int maximumAge) {
        return maximumAge > 0 && age >= 0 && age < maximumAge;
    }

    public static boolean berry(int age, boolean lightAtAbove) {
        return belowMaxAge(age, 3) && lightAtAbove;
    }

    public static boolean stem(int age, boolean lightAtCurrent) {
        return belowMaxAge(age, 7) && lightAtCurrent;
    }

    public static boolean crop(int age, int maximumAge, boolean lightAtCurrent) {
        return belowMaxAge(age, maximumAge) && lightAtCurrent;
    }

    public static boolean cropOpportunity(boolean belowMaximumAge, int lightAtCurrent) {
        return belowMaximumAge && lightAtCurrent >= 9;
    }

    public static boolean amethyst(boolean budding, boolean bud, boolean airOrFullWaterNeighbor, boolean attachedToBudding) {
        return budding ? airOrFullWaterNeighbor : bud && attachedToBudding;
    }

    public static boolean vine(boolean airBelow, boolean attachedToSide) {
        return airBelow && attachedToSide;
    }

    public static boolean leaf(int distance, boolean persistent) {
        return distance == 7 && !persistent;
    }

    public static boolean grassDirt(boolean grassOrMycelium, boolean dirt, boolean upperSmothers,
                                    boolean upperIsWater, boolean nearbyGrassCanSpread) {
        if (grassOrMycelium) return upperSmothers;
        return dirt && !upperSmothers && !upperIsWater && nearbyGrassCanSpread;
    }

    public static boolean propagule(boolean hanging, int age) {
        return hanging && age >= 0 && age < 4;
    }

    public static int eyeblossomCount(int settled, int late) {
        return settled == 0 && late >= 3 ? late : 0;
    }

    public static boolean snow(boolean inBuildHeight, boolean airAtSurface, boolean supportCanHoldSnow,
                               boolean biomeSnowPrecipitation, boolean calmLight, boolean donut,
                               boolean deepslateBelow) {
        return inBuildHeight && airAtSurface && supportCanHoldSnow && biomeSnowPrecipitation && calmLight &&
            (!donut || !deepslateBelow);
    }

    public static boolean ice(boolean waterSource, boolean allFourNeighborsWater, boolean neighborsLoaded,
                              boolean coldEnough, boolean calmLight) {
        return waterSource && neighborsLoaded && !allFourNeighborsWater && coldEnough && calmLight;
    }

    public static int enabledMask(boolean customFlags, boolean[] flags) {
        if (flags == null || flags.length != Kind.values().length) throw new IllegalArgumentException("expected 19 flags");
        int mask = 0;
        for (int i = 0; i < flags.length; i++) if (!customFlags || flags[i]) mask |= 1 << i;
        return mask;
    }
}
