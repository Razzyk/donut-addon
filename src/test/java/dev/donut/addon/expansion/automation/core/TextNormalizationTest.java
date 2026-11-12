package dev.donut.addon.expansion.automation.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextNormalizationTest {
    @Test
    void sourceSmallCapsAndArrowsNormalizeBeforeAsciiTextFiltering() {
        assertEquals("sell items - collect", TextNormalization.normalizeServerText("§aꜱᴇʟʟ items ➤ Collect"));
        assertEquals("sell items - collect", TextNormalization.normalizeServerText("ꜱᴇʟʟ items → Collect"));
    }

    @Test
    void menuKindsAndSearchLabelsRecognizeSmallCapsSourceText() {
        var sell = menu("ꜱᴇʟʟ items", slot(53, "minecraft:lime_dye", "ᴄᴏɴꜰɪʀᴍ"));
        assertEquals(MenuKind.SELL, MenuClassifier.classify(sell));
        assertEquals("confirm", sell.slot(53).getSearchableText());

        var order = menu("ʙᴜʏ ᴏʀᴅᴇʀ", slot(13, "minecraft:lime_dye", "ᴄᴏʟʟᴇᴄᴛ"));
        assertEquals(MenuKind.ORDER, MenuClassifier.classify(order));
        assertEquals("collect", order.slot(13).getSearchableText());
    }

    @Test
    void dialogSearchTextUsesSourceNormalizationAndLeavesComponentIdentityRaw() {
        var dialog = new DialogSnapshot(9L, "ᴛᴇʟᴇᴘᴏʀᴛ ᴄᴏɴꜰɪʀᴍ", List.of(),
                List.of("ᴘʟᴇᴀꜱᴇ ᴄᴏɴꜰɪʀᴍ ➤ /tpa"), List.of());
        assertTrue(dialog.getSearchableText().contains("teleport confirm"));
        assertTrue(dialog.getSearchableText().contains("please confirm - /tpa"));

        var slot = new SlotSnapshot(1, "minecraft:paper", 1, "ᴄᴏʟʟᴇᴄᴛ", List.of(), "native-components-ᴀ");
        assertEquals("native-components-ᴀ", slot.getComponentFingerprint());
    }

    private static MenuSnapshot menu(String title, SlotSnapshot... slots) {
        return new MenuSnapshot(10L, 2, title, List.of(slots), true, 4);
    }

    private static SlotSnapshot slot(int index, String item, String name) {
        return new SlotSnapshot(index, item, 1, name, List.of(), "components-raw", java.util.Map.of(), 64, "procurement-raw");
    }
}
