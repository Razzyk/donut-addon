package dev.mulveo.mvaddon.chunkscanner.light;

import java.util.Arrays;
import java.util.StringJoiner;

public final class NibbleSummary {
   private final NibbleSummary.Kind kind;
   private final int payloadLength;
   private final int[] counts;

   private NibbleSummary(NibbleSummary.Kind kind, int payloadLength, int[] counts) {
      this.kind = kind;
      this.payloadLength = payloadLength;
      this.counts = counts;
   }

   public static NibbleSummary fromPayload(byte[] payload) {
      if (payload != null && payload.length == 2048) {
         int[] counts = new int[16];

         for (byte packed : payload) {
            int unsigned = packed & 255;
            counts[unsigned & 15]++;
            counts[unsigned >>> 4 & 15]++;
         }

         return new NibbleSummary(NibbleSummary.Kind.PAYLOAD, payload.length, counts);
      } else {
         return new NibbleSummary(NibbleSummary.Kind.INVALID, payload == null ? -1 : payload.length, new int[16]);
      }
   }

   public static NibbleSummary emptyMask() {
      return new NibbleSummary(NibbleSummary.Kind.EMPTY_MASK, 0, new int[16]);
   }

   public String describe() {
      if (this.kind == NibbleSummary.Kind.EMPTY_MASK) {
         return "EMPTY_MASK (no nibble payload)";
      } else if (this.kind == NibbleSummary.Kind.INVALID) {
         return this.payloadLength < 0 ? "INVALID (missing nibble payload)" : "INVALID (payload bytes=" + this.payloadLength + ", expected=2048)";
      } else {
         StringJoiner values = new StringJoiner(", ", "{", "}");

         for (int value = 0; value < this.counts.length; value++) {
            if (this.counts[value] > 0) {
               values.add(value + "=" + this.counts[value]);
            }
         }

         return "values=" + values + " (4096 nibbles)";
      }
   }

   @Override
   public boolean equals(Object other) {
      if (this == other) {
         return true;
      } else {
         return other instanceof NibbleSummary that
            ? this.kind == that.kind && this.payloadLength == that.payloadLength && Arrays.equals(this.counts, that.counts)
            : false;
      }
   }

   @Override
   public int hashCode() {
      int result = this.kind.hashCode();
      result = 31 * result + this.payloadLength;
      return 31 * result + Arrays.hashCode(this.counts);
   }

   private static enum Kind {
      PAYLOAD,
      EMPTY_MASK,
      INVALID;
   }
}
