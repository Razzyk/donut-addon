package dev.donut.addon.expansion.search.growth;

/** Conservative mined-geode evidence rule based on shell scale, exposed buds, and light. */
public final class MinedGeodeClassifier {
    private MinedGeodeClassifier() {}
    public static boolean candidate(int liningBlocks, int buddingBlocks, boolean shellIsUnlit) {
        boolean large = buddingBlocks > 0 ? buddingBlocks >= 10 : liningBlocks >= 160;
        return large && shellIsUnlit;
    }
}
