package dev.donut.addon.expansion;

import com.google.gson.JsonParser;
import dev.donut.addon.expansion.configuration.LegacyMacroProfileMigration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyMacroProfileMigrationTest {
    @Test void oldOptionsMigrateWithoutOverwritingNewValuesOrChangingModuleBinds() {
        var stored = JsonParser.parseString("""
            {"name":"Anubis Crystal Macro","value":[
              {"name":"Bind","value":{"boundKey":"key.keyboard.f6","action":"Toggle"}},
              {"name":"Break Delay (ticks)","value":7},
              {"name":"Options","value":[
                {"name":"Break Delay","value":2},
                {"name":"Place Delay","value":3}]}]}
            """).getAsJsonObject();
        var bind = stored.getAsJsonArray("value").get(0).deepCopy();
        LegacyMacroProfileMigration.flattenOptions("Donut Crystal Chain", stored);
        var values = stored.getAsJsonArray("value");
        assertEquals(bind, values.get(0));
        assertEquals(7, values.get(1).getAsJsonObject().get("value").getAsInt());
        assertEquals("Place Delay (ticks)", values.get(3).getAsJsonObject().get("name").getAsString());
        assertEquals(3, values.get(3).getAsJsonObject().get("value").getAsInt());
        LegacyMacroProfileMigration.flattenOptions("Donut Crystal Chain", stored);
        assertEquals(4, values.size());
        assertTrue(LegacyMacroProfileMigration.aliasesFor("Donut Crystal Chain").contains("Anubis Crystal Macro"));
    }

    @Test void localChatMouseBindingsKeepTheirPhysicalKeyAndTransientRecipientsAreIgnored() {
        var chat = JsonParser.parseString("""
            {"name":"Anubis Chat Macro","value":[{"name":"Options","value":[
              {"name":"Macro 1 Key","value":{"boundKey":"key.mouse.right","action":"Toggle"}},
              {"name":"Macro 1 Text","value":"/home"}]}]}
            """).getAsJsonObject();
        LegacyMacroProfileMigration.flattenOptions("Donut Chat Keys", chat);
        assertEquals("key.mouse.right", chat.getAsJsonArray("value").get(1).getAsJsonObject().get("value").getAsString());
        assertEquals("/home", chat.getAsJsonArray("value").get(2).getAsJsonObject().get("value").getAsString());

        var payment = JsonParser.parseString("""
            {"name":"Anubis Panic Pay","value":[{"name":"Options","value":[
              {"name":"Pay To","value":"OldRecipient"},{"name":"Sell Armor","value":false}]}]}
            """).getAsJsonObject();
        LegacyMacroProfileMigration.flattenOptions("Donut Quick Transfer", payment);
        assertEquals(2, payment.getAsJsonArray("value").size());
        assertEquals("Sell Armor", payment.getAsJsonArray("value").get(1).getAsJsonObject().get("name").getAsString());
    }

    @Test void renamedOrderPriceChoiceKeepsItsMeaning() {
        var stored = JsonParser.parseString("""
            {"name":"Anubis Order AH Trader","value":[{"name":"Options","value":[
              {"name":"Order Price","value":"Beat"},
              {"name":"Order Price","value":"Fixed"}]}]}
            """).getAsJsonObject();
        LegacyMacroProfileMigration.flattenOptions("Donut Trade Orders", stored);
        assertEquals("Beat Top Order", stored.getAsJsonArray("value").get(1).getAsJsonObject().get("value").getAsString());
        assertEquals("Product 1 Order Price", stored.getAsJsonArray("value").get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("Product 2 Order Price", stored.getAsJsonArray("value").get(2).getAsJsonObject().get("name").getAsString());
        assertEquals("Fixed", stored.getAsJsonArray("value").get(2).getAsJsonObject().get("value").getAsString());
    }
}
