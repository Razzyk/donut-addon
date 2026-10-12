package dev.mulveo.mvaddon.chunkscanner.light;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class NibbleSummaryTest {
   @Test
   void countsBothNibblesInEveryPackedByte() {
      byte[] payload = new byte[2048];
      Arrays.fill(payload, (byte)0x21);

      assertEquals("values={1=2048, 2=2048} (4096 nibbles)", NibbleSummary.fromPayload(payload).describe());
   }

   @Test
   void describesEmptyAndInvalidPayloadsWithoutTreatingThemAsValues() {
      assertEquals("EMPTY_MASK (no nibble payload)", NibbleSummary.emptyMask().describe());
      assertEquals("INVALID (missing nibble payload)", NibbleSummary.fromPayload(null).describe());
      assertEquals("INVALID (payload bytes=3, expected=2048)", NibbleSummary.fromPayload(new byte[3]).describe());
   }
}
