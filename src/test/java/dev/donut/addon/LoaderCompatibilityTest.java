package dev.donut.addon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoaderCompatibilityTest {
    @Test void supportsReleasesAndNightliesOnTheSupportedMinecraftLine() throws Exception {
        var depends = addonMetadata().getAsJsonObject("depends");
        var client = VersionPredicate.parse(depends.get("liquidbounce").getAsString());
        for (String version : List.of("0.42.0", "0.42.1", "0.42.1-SNAPSHOT", "0.42.1+git.07f9d63")) {
            assertTrue(client.test(Version.parse(version)), version);
        }
        assertFalse(client.test(Version.parse("0.41.0")), "Older rotation APIs are incompatible");

        var minecraft = VersionPredicate.parse(depends.get("minecraft").getAsString());
        for (String version : List.of("26.3", "26.3.1")) {
            assertTrue(minecraft.test(Version.parse(version)), version);
        }
        for (String version : List.of("26.2", "26.4", "27.1")) {
            assertFalse(minecraft.test(Version.parse(version)), version);
        }
    }

    @Test void acceptsTheActualLiquidBounceDependencyVersion() throws Exception {
        var predicate = VersionPredicate.parse(addonMetadata().getAsJsonObject("depends")
            .get("liquidbounce").getAsString());
        var resources = getClass().getClassLoader().getResources("fabric.mod.json");
        while (resources.hasMoreElements()) {
            var url = resources.nextElement();
            try (var reader = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) {
                var metadata = JsonParser.parseReader(reader).getAsJsonObject();
                if (!"liquidbounce".equals(metadata.get("id").getAsString())) continue;
                String version = metadata.get("version").getAsString();
                assertTrue(predicate.test(Version.parse(version)), "Rejected actual dependency " + version);
                return;
            }
        }
        fail("LiquidBounce's fabric.mod.json was not found on the test classpath");
    }

    private JsonObject addonMetadata() throws Exception {
        try (var resource = getClass().getResourceAsStream("/fabric.mod.json")) {
            assertNotNull(resource);
            return JsonParser.parseReader(new InputStreamReader(resource, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
