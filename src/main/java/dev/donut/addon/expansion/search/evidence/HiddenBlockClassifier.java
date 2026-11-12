package dev.donut.addon.expansion.search.evidence;

/** Classifies a server block-entity report against the client's current block-state facts. */
public final class HiddenBlockClassifier {
    private HiddenBlockClassifier() {}

    public static boolean conflicts(String reportedTypeId, boolean clientChunkLoaded,
                                    boolean stateIsAir, boolean stateAcceptsReportedType) {
        if (reportedTypeId == null || reportedTypeId.isBlank() || !clientChunkLoaded) return false;
        return stateIsAir || !stateAcceptsReportedType;
    }
}
