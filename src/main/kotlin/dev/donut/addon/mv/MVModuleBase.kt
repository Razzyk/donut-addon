package dev.donut.addon.mv

import dev.donut.addon.mv.compat.settings.Setting
import dev.donut.addon.mv.compat.utils.misc.Keybind
import dev.donut.addon.mv.compat.utils.render.color.SettingColor
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleCategory
import net.ccbluex.liquidbounce.render.engine.type.Color4b
import net.ccbluex.liquidbounce.utils.input.InputBind
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.Item
import net.minecraft.world.item.enchantment.Enchantment
import com.mojang.blaze3d.platform.InputConstants

/** Settings and lifecycle adapter; the actual modules are native LiquidBounce ClientModules. */
/** Keep existing saved module settings readable after display-name cleanup. */
private val previousBasefindingNames = mapOf(
    "Geode Finder" to "AmethystEsp",
    "Light Tracker" to "basefinder",
    "Chunk Finder" to "chunkfinder",
    "Light Scanner" to "chunkscanner",
    "Spawner Finder" to "spawner-finder",
    "Storage Finder" to "StorageEsp+",
    "Suspicious Chunks" to "suschunkfinder",
    "Activity Inspector" to "v3",
)

abstract class MVModuleBase(name: String, category: ModuleCategory) :
    ClientModule(name, category, aliases = listOfNotNull(previousBasefindingNames[name])) {
    private val groups = linkedMapOf<String, ValueGroup>()

    abstract fun legacyActivate()
    abstract fun legacyDeactivate()
    override fun onEnabled() { super.onEnabled(); legacyActivate() }
    override fun onDisabled() { legacyDeactivate(); super.onDisabled() }

    private data class EnumTag(val source: Enum<*>) : Tagged {
        override val tag: String get() = source.toString()
    }

    @Suppress("UNCHECKED_CAST")
    fun attachSetting(groupName: String, untyped: Setting<*>) {
        val group = groups.getOrPut(groupName) { ValueGroup(groupName).also { tree(it) } }
        val setting = untyped as Setting<Any>
        when (setting.kind) {
            "bool" -> bind(setting, group.boolean(setting.name, setting.defaultValue as Boolean))
            "int" -> {
                val default = setting.defaultValue as Int
                val min = (setting.min ?: Int.MIN_VALUE.toDouble()).toInt()
                val max = (setting.max ?: Int.MAX_VALUE.toDouble()).toInt()
                val range = (setting.sliderMin ?: setting.min ?: 0.0).toInt()..
                    (setting.sliderMax ?: setting.max ?: maxOf(default, 100).toDouble()).toInt()
                val value = if (setting.noSlider) group.value(setting.name, default, ValueType.INT)
                    else group.value(RangedValue(setting.name, defaultValue = default,
                        range = range, suffix = "", valueType = ValueType.INT))
                value.onChange { if (it in min..max) it else value.get() }
                bind(setting, value)
            }
            "double" -> {
                val default = setting.defaultValue as Double
                val min = setting.min ?: -Double.MAX_VALUE
                val max = setting.max ?: Double.MAX_VALUE
                val range = (setting.sliderMin ?: setting.min ?: 0.0)..
                    (setting.sliderMax ?: setting.max ?: maxOf(default, 100.0))
                // Keep doubles throughout. Converting these movement/timing values to Float changes behavior.
                val value = if (setting.noSlider) group.value(object : Value<Double>(
                        setting.name, defaultValue = default, valueType = ValueType.FLOAT) {
                        override fun setByString(string: String) { set(string.toDouble()) }
                    }) else group.value(RangedValue(setting.name, defaultValue = default,
                        range = range, suffix = "", valueType = ValueType.FLOAT))
                value.onChange { if (it.isFinite() && it in min..max) it else value.get() }
                bind(setting, value)
            }
            "string" -> bind(setting, group.text(setting.name, setting.defaultValue as String))
            "list" -> bind(setting, group.textList(setting.name,
                ArrayList(setting.defaultValue as Collection<String>)))
            "enum" -> {
                val default = setting.defaultValue as Enum<*>
                val choices = default.declaringJavaClass.enumConstants.mapTo(linkedSetOf()) { EnumTag(it) }
                bind(setting, group.enumChoice(setting.name, EnumTag(default), choices),
                    { it.source }, { EnumTag(it as Enum<*>) })
            }
            "color" -> {
                val default = setting.defaultValue as SettingColor
                bind(setting, group.color(setting.name, Color4b(default.getPacked())),
                    { SettingColor(it.r, it.g, it.b, it.a) },
                    { Color4b((it as SettingColor).getPacked()) })
            }
            "keybind" -> {
                val default = setting.defaultValue as Keybind
                bind(setting, group.bind(setting.name,
                    InputBind(InputConstants.Type.KEYBOARD, default.keyCode(), InputBind.BindAction.TOGGLE)),
                    { Keybind.fromNative(it.boundKey) },
                    { InputBind(InputConstants.Type.KEYBOARD, (it as Keybind).keyCode(), InputBind.BindAction.TOGGLE) })
            }
            "item" -> bind(setting, group.value(setting.name, setting.defaultValue as Item, ValueType.ITEM))
            "enchantment-list" -> {
                val default = setting.defaultValue as Set<ResourceKey<Enchantment>>
                val names = default.mapTo(linkedSetOf()) { it.identifier().toString() }
                val value = group.textList(setting.name, names)
                value.onChange { identifiers ->
                    if (identifiers.all { Identifier.tryParse(it) != null }) identifiers else value.get()
                }
                bind(setting, value,
                    { strings -> strings.mapTo(linkedSetOf()) {
                        ResourceKey.create(Registries.ENCHANTMENT, Identifier.parse(it))
                    } },
                    { keys -> (keys as Set<ResourceKey<Enchantment>>).mapTo(linkedSetOf()) {
                        it.identifier().toString()
                    } })
            }
            else -> error("Unsupported MV setting type: " + setting.kind + " for " + setting.name)
        }
    }

    private fun <T : Any> bind(setting: Setting<Any>, value: Value<T>) =
        bind(setting, value, { it }, { @Suppress("UNCHECKED_CAST") (it as T) })

    private fun <T : Any> bind(setting: Setting<Any>, value: Value<T>,
                             fromNative: (T) -> Any, toNative: (Any) -> T) {
        value.literalDescription { setting.description }
        value.visibleWhen { setting.isVisible }
        value.onChanged { setting.changedNative(fromNative(it)) }
        setting.attach({ fromNative(value.get()) }, { value.set(toNative(it)) })
    }
}
