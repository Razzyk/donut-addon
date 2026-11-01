package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerTextTest {
    @Test void parsesPricesAndRejectsAmbiguousInput() {
        assertEquals(1_250_000L, ServerText.parseMoney("$1.25m"));
        assertEquals(2_500L, ServerText.parseMoney("2,500"));
        assertEquals(-1L, ServerText.parseMoney("price $20"));
        assertEquals(25_000.0, ServerText.askingPrice(List.of("Balance: $1m", "Price: $25k")));
    }

    @Test void normalizesFormattingAndEnchantmentLevels() {
        assertEquals("sharpness", ServerText.withoutRoman("§aSharpness IV"));
        assertEquals(4, ServerText.roman("IV"));
        assertEquals("diamondchestplate", ServerText.key("Diamond Chestplate"));
        var enchants = Product.parseEnchants("Sharpness IV, Unbreaking 3");
        assertEquals(2, enchants.size());
        assertEquals(4, enchants.get(0).level());
        assertEquals(3, enchants.get(1).level());
    }
}
