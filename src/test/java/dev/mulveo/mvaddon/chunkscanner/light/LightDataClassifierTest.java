package dev.mulveo.mvaddon.chunkscanner.light;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LightDataClassifierTest {
   @Test
   void distinguishesMissingInvalidEmptyFullAndMixedPayloads() {
      assertEquals(SectionStatus.UNKNOWN, LightDataClassifier.classifyPayload(null));
      assertEquals(SectionStatus.UNKNOWN, LightDataClassifier.classifyPayload(new byte[2047]));
      assertEquals(SectionStatus.ZEROED_PRESENT, LightDataClassifier.classifyPayload(new byte[2048]));

      byte[] full = new byte[2048];
      java.util.Arrays.fill(full, (byte)0xFF);
      assertEquals(SectionStatus.UNIFORM_MAX, LightDataClassifier.classifyPayload(full));

      byte[] mixed = new byte[2048];
      mixed[2047] = (byte)0xF0;
      assertEquals(SectionStatus.NORMAL, LightDataClassifier.classifyPayload(mixed));
   }
}
