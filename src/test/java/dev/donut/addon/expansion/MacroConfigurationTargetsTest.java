package dev.donut.addon.expansion;

import com.google.gson.JsonParser;
import dev.donut.addon.expansion.automation.DonutAutomationModule;
import dev.donut.addon.expansion.automation.DonutChatKeys;
import dev.donut.addon.expansion.automation.DonutQuickTransfer;
import dev.donut.addon.expansion.automation.DonutTradeOrders;
import net.ccbluex.liquidbounce.config.gson.GsonInstanceKt;
import net.ccbluex.liquidbounce.config.types.Value;
import net.ccbluex.liquidbounce.config.types.group.ValueGroup;
import net.ccbluex.liquidbounce.utils.input.InputBind;
import com.mojang.blaze3d.platform.InputConstants;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MacroConfigurationTargetsTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void everyMigratedSettingHasAnActualNativeDestination() throws Exception {
        var modules = MacroDrafts.modules().stream()
            .collect(Collectors.toMap(module -> module.getName(), module -> module));
        var missing = new ArrayList<String>();
        try (var stream = getClass().getResourceAsStream("/donut-addon/macro-migration.json")) {
            assertNotNull(stream);
            var schema = JsonParser.parseReader(new InputStreamReader(stream)).getAsJsonObject();
            assertEquals(11, schema.getAsJsonArray("modules").size());
            for (var entry : schema.getAsJsonArray("modules")) {
                var mapping = entry.getAsJsonObject();
                var name = mapping.get("target").getAsString();
                var module = modules.get(name);
                assertNotNull(module, name);
                var destinations = module.get().stream().map(value -> value.getName()).collect(Collectors.toSet());
                for (var item : mapping.getAsJsonArray("settings")) {
                    var setting = item.getAsJsonObject();
                    var target = setting.get("targetLabel").getAsString();
                    if (!destinations.contains(target)) missing.add(name + ": " + target);
                }
            }
        }
        assertTrue(missing.isEmpty(), () -> "Saved settings would lose their destination:\n" + String.join("\n", missing));
    }

    @Test void migratedValuesDecodeWithTheClientsActualFileAdapters() {
        var chat = new DonutChatKeys();
        applyStoredValues(chat, """
            {"name":"Donut Chat Keys","value":[
              {"name":"Bind","value":{"boundKey":"key.keyboard.f6","action":"Hold","modifiers":["Control"]}},
              {"name":"Options","value":[
                {"name":"Macro 1 Key","value":{"boundKey":"key.mouse.right","action":"Toggle"}},
                {"name":"Macro 1 Text","value":"/home"}]}]}
            """);
        var bind = (InputBind) setting(chat, "Bind").get();
        assertEquals(InputConstants.getKey("key.keyboard.f6"), bind.boundKey());
        assertEquals(InputBind.BindAction.HOLD, bind.action());
        assertEquals(Set.of(InputBind.Modifier.CONTROL), bind.modifiers());
        assertEquals(InputConstants.getKey("key.mouse.right"), setting(chat, "Macro 1 Key").get());
        assertEquals("/home", setting(chat, "Macro 1 Text").get());

        var orders = new DonutTradeOrders();
        applyStoredValues(orders, """
            {"name":"Donut Trade Orders","value":[{"name":"Options","value":[
              {"name":"Order Price","value":"Beat"},
              {"name":"Order Price","value":"Fixed"}]}]}
            """);
        assertEquals("Beat Top Order", setting(orders, "Product 1 Order Price").getValue());
        assertEquals("Fixed", setting(orders, "Product 2 Order Price").getValue());
    }

    @Test void localSerializationOmitsPaymentRecipientAndEnabledStateButKeepsTheBind() {
        var transfer = new DonutQuickTransfer();
        var file = GsonInstanceKt.getFileGson().toJsonTree(transfer, ValueGroup.class).getAsJsonObject();
        var names = new ArrayList<String>();
        for (var entry : file.getAsJsonArray("value")) {
            names.add(entry.getAsJsonObject().get("name").getAsString());
        }
        assertFalse(names.contains("Pay To"));
        assertFalse(names.contains(transfer.getEnabledValue().getName()));
        assertTrue(names.contains("Bind"));
        assertTrue(names.contains("Sell Armor"));
    }

    private static Value<?> setting(DonutAutomationModule module, String name) {
        return module.get().stream().filter(value -> value.getName().equals(name)).findFirst().orElseThrow();
    }

    private static void applyStoredValues(DonutAutomationModule module, String json) {
        JsonObject stored = JsonParser.parseString(json).getAsJsonObject();
        module.prepareDeserialize(stored);
        // Exercise native adapters without initializing ConfigSystem's Minecraft game directory.
        for (var entry : stored.getAsJsonArray("value")) {
            var data = entry.getAsJsonObject();
            var target = module.get().stream()
                .filter(value -> value.getName().equals(data.get("name").getAsString()))
                .findFirst().orElse(null);
            if (target != null && target.isPersistent()) {
                target.deserializeFrom(GsonInstanceKt.getFileGson(), data.get("value"));
            }
        }
    }
}
