package dev.donut.addon.expansion.search.evidence;

/** Conservative predicates for light values that disagree with the loaded block's own emission. */
public final class LightLeakClassifier {
    private LightLeakClassifier() {}

    public static boolean solidCell(int lightDampening, int insideLight, int blockEmission) {
        return lightDampening >= 15 && insideLight >= 2 && insideLight > blockEmission;
    }

    public static boolean darkGap(int insideLight, int brightestNeighbor, boolean nextToGeode) {
        return !nextToGeode && insideLight <= 12 && brightestNeighbor - insideLight >= 3;
    }
}
