package dev.mulveo.mvaddon.chunkscanner.light;

import java.util.Arrays;

public final class ChunkLightSnapshot {
   public final int chunkX;
   public final int chunkZ;
   public final int sectionCount;
   public final int bottomSectionY;
   public final SectionStatus[] block;
   public final SectionStatus[] sky;
   public final NibbleSummary[] blockValues;
   public final NibbleSummary[] skyValues;
   public final long[] blockUpdatedNanos;
   public final long[] skyUpdatedNanos;

   public ChunkLightSnapshot(int chunkX, int chunkZ, int sectionCount, int bottomSectionY) {
      this.chunkX = chunkX;
      this.chunkZ = chunkZ;
      this.sectionCount = sectionCount;
      this.bottomSectionY = bottomSectionY;
      int n = sectionCount + 2;
      this.block = new SectionStatus[n];
      this.sky = new SectionStatus[n];
      this.blockValues = new NibbleSummary[n];
      this.skyValues = new NibbleSummary[n];
      this.blockUpdatedNanos = new long[n];
      this.skyUpdatedNanos = new long[n];
      Arrays.fill(this.block, SectionStatus.UNKNOWN);
      Arrays.fill(this.sky, SectionStatus.UNKNOWN);
   }
}
