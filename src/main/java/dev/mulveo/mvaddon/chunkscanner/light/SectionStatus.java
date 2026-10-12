package dev.mulveo.mvaddon.chunkscanner.light;

public enum SectionStatus {
   NORMAL(0, 220, 0),
   EMPTY_MASK(255, 220, 0),
   ZEROED_PRESENT(255, 40, 40),
   UNIFORM_MAX(0, 220, 255),
   MISSING(140, 140, 140),
   UNKNOWN(80, 80, 80);

   private final int r;
   private final int g;
   private final int b;

   private SectionStatus(int r, int g, int b) {
      this.r = r;
      this.g = g;
      this.b = b;
   }

   public int r() {
      return this.r;
   }

   public int g() {
      return this.g;
   }

   public int b() {
      return this.b;
   }
}
