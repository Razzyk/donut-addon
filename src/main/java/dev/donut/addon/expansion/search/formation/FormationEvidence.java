package dev.donut.addon.expansion.search.formation;

/** Small conservative rule used to classify a stone stack in a blob and cave context. */
public final class FormationEvidence {
    private FormationEvidence() {}

    public static boolean qualifies(int stackHeight, int minimumHeight, boolean blobContact,
                                    boolean cavityContact, boolean supportedAtBothEnds) {
        return stackHeight >= minimumHeight && blobContact && cavityContact && supportedAtBothEnds;
    }

    public static int confidence(int stackHeight, int minimumHeight, boolean blobContact,
                                 boolean cavityContact, boolean supportedAtBothEnds) {
        if (!qualifies(stackHeight, minimumHeight, blobContact, cavityContact, supportedAtBothEnds)) return 0;
        int score = 2;
        if (stackHeight >= minimumHeight + 2) score++;
        if (stackHeight >= minimumHeight + 4) score++;
        return Math.min(score, 4);
    }
}
