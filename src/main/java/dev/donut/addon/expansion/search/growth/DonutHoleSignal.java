package dev.donut.addon.expansion.search.growth;

/** Donut Growth Inspector's 1x1 and enclosed 3x1 shaft recognition over loaded-world snapshots. */
public final class DonutHoleSignal {
    @FunctionalInterface public interface OpenCell { boolean isOpen(int x, int y, int z); }

    private DonutHoleSignal() {}

    public static boolean hasHole(int minX, int minZ, int bottomY, int topYExclusive, OpenCell open) {
        if (open == null || topYExclusive <= bottomY) return false;
        for (int x = minX; x < minX + 16; x++) for (int z = minZ; z < minZ + 16; z++) {
            int single = 0, alongX = 0, alongZ = 0;
            for (int y = bottomY; y < topYExclusive; y++) {
                if (singleShaft(x, y, z, open)) single++;
                else { if (shaftEnds(x, y, z, single, open)) return true; single = 0; }
                if (wideShaft(x, y, z, 1, 0, open)) alongX++;
                else { if (shaftEnds(x, y, z, alongX, open)) return true; alongX = 0; }
                if (wideShaft(x, y, z, 0, 1, open)) alongZ++;
                else { if (shaftEnds(x, y, z, alongZ, open)) return true; alongZ = 0; }
            }
        }
        return false;
    }

    public static boolean singleShaft(int x, int y, int z, OpenCell open) {
        return open.isOpen(x, y, z) && !open.isOpen(x - 1, y, z) && !open.isOpen(x + 1, y, z) &&
            !open.isOpen(x, y, z - 1) && !open.isOpen(x, y, z + 1);
    }

    public static boolean wideShaft(int x, int y, int z, int stepX, int stepZ, OpenCell open) {
        for (int i = 0; i < 3; i++) if (!open.isOpen(x + stepX * i, y, z + stepZ * i)) return false;
        if (open.isOpen(x - stepX, y, z - stepZ) || open.isOpen(x + stepX * 3, y, z + stepZ * 3)) return false;
        for (int i = 0; i < 3; i++) {
            int wx = x + stepX * i, wz = z + stepZ * i;
            if (open.isOpen(wx - stepZ, y, wz - stepX) || open.isOpen(wx + stepZ, y, wz + stepX)) return false;
        }
        return true;
    }

    public static boolean shaftEnds(int x, int y, int z, int run, OpenCell open) {
        return run >= 2 && open.isOpen(x, y, z) && !open.isOpen(x, y - run - 1, z);
    }
}
