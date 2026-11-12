package dev.donut.addon.expansion.search.growth;

/** Conservative Minecraft growth-opportunity rules represented only by primitive facts. */
public final class NaturalGrowthRules {
    public enum Kind {
        KELP, CAVE_VINE, HANGING_VINE, BAMBOO, SUGAR_CANE, CACTUS,
        COCOA, BERRY_BUSH, NETHER_WART, CROP, STEM, AMETHYST_BUD,
        VINE, LEAF, SOIL, PROPAGULE, EYEBLOSSOM, SNOW, ICE
    }

    public enum Fact { YES, NO, UNKNOWN }

    /** Missing stage/light/space facts are represented explicitly and never become a positive signal. */
    public record Sample(Kind kind, int stage, int terminalStage, int light, int requiredLight,
                         Fact freeSpace, Fact suitableSupport, int columnHeight, int columnLimit,
                         Fact exposedGrowthFace, Fact worldCycleAllowsChange) {}

    private NaturalGrowthRules() {}

    public static boolean isOpportunity(Sample sample) {
        if (sample == null || sample.kind() == null) return false;
        return switch (sample.kind()) {
            case KELP -> belowLimit(sample) && yes(sample.freeSpace()) && yes(sample.suitableSupport());
            case CAVE_VINE -> sample.stage() == 0 && yes(sample.worldCycleAllowsChange());
            case HANGING_VINE -> belowLimit(sample) && yes(sample.freeSpace()) && withinColumn(sample);
            case BAMBOO -> yes(sample.freeSpace()) && enoughLight(sample) && withinColumn(sample);
            case SUGAR_CANE, CACTUS -> yes(sample.freeSpace()) && yes(sample.suitableSupport()) && withinColumn(sample);
            case COCOA, BERRY_BUSH, NETHER_WART, CROP, STEM, PROPAGULE ->
                belowLimit(sample) && yes(sample.suitableSupport()) && enoughLight(sample);
            case AMETHYST_BUD -> yes(sample.exposedGrowthFace());
            case VINE -> yes(sample.freeSpace()) && yes(sample.suitableSupport());
            case LEAF -> false;
            case SOIL -> yes(sample.freeSpace()) && yes(sample.suitableSupport()) && enoughLight(sample);
            case EYEBLOSSOM, SNOW, ICE -> yes(sample.worldCycleAllowsChange());
        };
    }

    private static boolean belowLimit(Sample sample) {
        return sample.stage() >= 0 && sample.terminalStage() > sample.stage();
    }

    private static boolean enoughLight(Sample sample) {
        return sample.light() >= sample.requiredLight() && sample.requiredLight() >= 0;
    }

    private static boolean withinColumn(Sample sample) {
        return sample.columnHeight() > 0 && sample.columnLimit() > sample.columnHeight();
    }

    private static boolean yes(Fact fact) { return fact == Fact.YES; }
}
