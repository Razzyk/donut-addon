package dev.donut.addon.expansion.configuration

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Adapts the shipped addon's native value tree in memory; never edits profile files directly. */
object LegacyMacroProfileMigration {
    private data class Setting(val target: String, val transient: Boolean, val keyBinding: Boolean,
                               val valueAliases: Map<String, String>)
    private data class Module(val aliases: List<String>, val settings: Map<String, List<Setting>>)
    private val schema: Map<String, Module> by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/donut-addon/macro-migration.json"))
        stream.bufferedReader().use { reader ->
            val root = JsonParser.parseReader(reader).asJsonObject
            require(root["schemaVersion"].asInt == 1)
            root.getAsJsonArray("modules").associate { item ->
                val module = item.asJsonObject
                module["target"].asString to Module(
                    module.getAsJsonArray("aliases").map { it.asString },
                    module.getAsJsonArray("settings").groupBy { it.asJsonObject["oldLabel"].asString }.mapValues { (_, entries) ->
                        entries.map { entry ->
                        val setting = entry.asJsonObject
                        Setting(setting["targetLabel"].asString,
                            setting["transient"].asBoolean, setting["keyBinding"].asBoolean,
                            setting.getAsJsonObject("valueAliases")?.entrySet()?.associate { it.key to it.value.asString }
                                .orEmpty())
                        }
                    }
                )
            }
        }
    }

    @JvmStatic fun aliasesFor(moduleName: String): List<String> = schema[moduleName]?.aliases.orEmpty()

    /** Explicit new-format settings win when both schemas occur; repeat calls are idempotent. */
    @JvmStatic fun flattenOptions(moduleName: String, stored: JsonObject) {
        val mapping = schema[moduleName] ?: return
        val values = stored["value"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        val existing = values.filter { it.isJsonObject }
            .mapNotNull { it.asJsonObject["name"]?.takeIf { name -> name.isJsonPrimitive }?.asString }.toMutableSet()
        val pending = JsonArray()
        // The old product editor reused labels for each product; FileGson stores them in order.
        val queues = mapping.settings.mapValues { ArrayDeque(it.value) }
        for (entry in values) {
            if (!entry.isJsonObject) continue
            val group = entry.asJsonObject
            if (group["name"]?.asString != "Options") continue
            val children = group["value"]?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (child in children) {
                if (!child.isJsonObject) continue
                val old = child.asJsonObject
                val name = old["name"]?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                val setting = queues[name]?.removeFirstOrNull() ?: continue
                if (setting.transient || !existing.add(setting.target)) continue
                val copy = old.deepCopy()
                copy.addProperty("name", setting.target)
                if (setting.keyBinding && copy["value"]?.isJsonObject == true) {
                    val key = copy.getAsJsonObject("value")["boundKey"] ?: continue
                    copy.add("value", key.deepCopy())
                }
                if (copy["value"]?.isJsonPrimitive == true) {
                    setting.valueAliases[copy["value"].asString]?.let { copy.addProperty("value", it) }
                }
                pending.add(copy)
            }
        }
        values.addAll(pending)
    }
}
