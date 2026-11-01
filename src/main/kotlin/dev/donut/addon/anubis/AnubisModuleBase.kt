package dev.donut.addon.anubis

import com.mojang.blaze3d.platform.InputConstants
import dev.donut.addon.AddonCategories
import dev.donut.addon.anubis.module.setting.*
import net.ccbluex.liquidbounce.config.types.RangedValue
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.config.types.list.Tagged
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.utils.input.InputBind

abstract class AnubisModuleBase(val sourceId: String, title: String, description: String) :
    ClientModule("Anubis $title", AddonCategories.MACROS, disableOnQuit = true) {
    private val options = ValueGroup("Options").also { tree(it) }
    private var activating = false
    private var activationRejected = false
    private var activated = false
    init {
        enabledValue.doNotIncludeAlways().notPersistent()
        doNotIncludeWhen { this is dev.donut.addon.anubis.module.ClientModule && !inConfigs() }
        enabledValue.onChange { requested ->
            if (requested && activationRejected) { activationRejected = false; false } else requested
        }
        literalDescription { description }
    }

    abstract fun legacyActivate()
    abstract fun legacyDeactivate()
    override fun onEnabled() {
        super.onEnabled()
        activating = true
        activated = true
        try { legacyActivate() } finally {
            activating = false
            if (activationRejected) deactivate()
        }
    }
    private fun deactivate() {
        if (activated) {
            activated = false
            legacyDeactivate()
        }
    }
    override fun onDisabled() { try { deactivate() } finally { super.onDisabled() } }
    fun requestStop() {
        if (activating) activationRejected = true else {
            // LiquidBounce suppresses toggle callbacks when the world has already gone away.
            if (!inGame) deactivate()
            enabled = false
        }
    }

    private data class Choice(override val tag: String) : Tagged

    @Suppress("UNCHECKED_CAST")
    fun attachSetting(id: String, setting: Setting<*>) {
        when (setting) {
            is BooleanSetting -> bind(setting, options.boolean(setting.name, setting.defaultValue))
            is NumberSetting -> {
                val value = options.value(RangedValue(setting.name, defaultValue = setting.defaultValue.toDouble(),
                    range = setting.min.toDouble()..setting.max.toDouble(), suffix = "", valueType = ValueType.FLOAT))
                value.onChange {
                    if (!it.isFinite()) setting.defaultValue.toDouble() else {
                        val lower = setting.min.toDouble()
                        val step = setting.increment.toDouble()
                        val rounded = if (step > 0.0) lower + kotlin.math.round((it - lower) / step) * step else it
                        rounded.coerceIn(lower, setting.max.toDouble())
                    }
                }
                bind(setting, value, { it.toFloat() }, { it.toDouble() })
            }
            is ModeSetting -> {
                val choices = setting.choices.mapTo(linkedSetOf()) { Choice(it) }
                bind(setting, options.enumChoice(setting.name, Choice(setting.defaultValue), choices),
                    { it.tag }, { Choice(it) })
            }
            is StringSetting -> {
                val value = options.text(setting.name, setting.defaultValue)
                if (setting.hint.isNotBlank()) value.literalDescription { setting.hint }
                bind(setting, value)
                if (setting.presets.isNotEmpty()) {
                    val entries = setting.presets.filter { it.selectable() }
                    if (entries.isNotEmpty()) {
                        val choices = entries.mapTo(linkedSetOf()) { Choice(it.label()) }
                        val initial = entries.firstOrNull { it.value() == setting.defaultValue } ?: entries.first()
                        val presetValue = options.enumChoice(setting.name + " Preset", Choice(initial.label()), choices)
                            .visibleWhen { setting.isVisible }
                            .onChanged { selected -> entries.firstOrNull { it.label() == selected.tag }?.let { setting.set(it.value()) } }
                        presetValue.doNotIncludeWhen { setting.isPrivateValue ||
                            (this is dev.donut.addon.anubis.module.ClientModule && !inConfigs()) }
                        // Presets are controls for the underlying text and should not persist separately.
                        presetValue.notPersistent()
                    }
                }
            }
            is StringListSetting -> bind(setting, options.textList(setting.name, ArrayList(setting.defaultValue)),
                { it.toList() }, { ArrayList(it) })
            is KeySetting -> {
                fun native(code: Int) = InputBind(
                    if (code in 1000..1032) InputConstants.Type.MOUSE else InputConstants.Type.KEYBOARD,
                    if (code in 1000..1032) code - 1000 else code, InputBind.BindAction.TOGGLE)
                bind(setting, options.bind(setting.name, native(setting.defaultValue)),
                    { if (it.boundKey.type == InputConstants.Type.MOUSE) 1000 + it.boundKey.value else it.boundKey.value },
                    { native(it) })
            }
            else -> error("Unsupported setting $id: ${setting.javaClass.name}")
        }
    }
    private fun <T : Any> bind(setting: Setting<T>, value: Value<T>) = bind(setting, value, { it }, { it })
    private fun <T : Any, N : Any> bind(setting: Setting<T>, value: Value<N>, fromNative: (N) -> T, toNative: (T) -> N) {
        value.visibleWhen { setting.isVisible }
        value.doNotIncludeWhen { setting.isPrivateValue || (this is dev.donut.addon.anubis.module.ClientModule && !inConfigs()) }
        setting.onPolicyChanged { if (setting.isSessionOnly) value.notPersistent() }
        value.onChanged { setting.nativeChanged() }
        setting.attach({ fromNative(value.get()) }, { value.set(toNative(it)) })
    }
}
