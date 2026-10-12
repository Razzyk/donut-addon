package dev.donut.addon.mv.compat.utils.render.color;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColorCompatibilityTest {
    @Test
    void packsChannelsAsArgbAndUnpacksWithoutLoss() {
        int packed = Color.fromRGBA(64, 32, 16, 128);
        assertEquals(0x80402010, packed);
        assertEquals(new Color(64, 32, 16, 128), Color.fromPacked(packed));
        assertEquals(packed, new Color(64, 32, 16, 128).getPacked());
    }

    @Test
    void settingColorCopyRetainsRainbowAndDoesNotAliasChannels() {
        SettingColor original = new SettingColor(10, 20, 30, 40, true);
        SettingColor copy = original.copy();
        copy.r(99).a(200);

        assertTrue(copy.rainbow);
        assertEquals(new SettingColor(10, 20, 30, 40, true), original);
        assertEquals(99, copy.r);
        assertEquals(200, copy.a);
    }
}
