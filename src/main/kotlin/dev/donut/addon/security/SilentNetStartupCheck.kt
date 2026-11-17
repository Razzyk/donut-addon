package dev.donut.addon.security

import dev.donut.addon.compat.DonutEventListener
import net.ccbluex.liquidbounce.event.events.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.events.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.client.mc
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.metadata.ModOrigin
import org.slf4j.LoggerFactory
import java.nio.file.Path

/** Advisory only: the scan starts once at launch and never blocks or transmits game startup data. */
object SilentNetStartupCheck : DonutEventListener {
    private val logger = LoggerFactory.getLogger("Donut SilentNET Check")
    @Volatile private var stopped = false
    private data class Outcome(val report: SilentNetScanner.ScanResult, val originIncomplete: Boolean)
    private val check = StartupThreatCheck({
        val loader = FabricLoader.getInstance()
        var originIncomplete = false
        val origins = buildList<Path> {
            for (mod in loader.allMods) {
                try {
                    if (mod.origin.kind == ModOrigin.Kind.PATH) addAll(mod.origin.paths)
                } catch (_: Exception) {
                    originIncomplete = true
                }
            }
        }
        val localData = System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let { Path.of(it) }
        val report = SilentNetScanner.scan(loader.gameDir.resolve("mods"), origins, localData)
        if (report.incomplete() || originIncomplete) {
            logger.warn("SilentNET startup check was incomplete; no all-clear verdict is available")
        }
        if (report.findings().isNotEmpty()) {
            logger.warn("SilentNET startup check found {} known indicator matches; a local in-world warning is queued",
                report.findings().size)
        }
        Outcome(report, originIncomplete)
    }, { exception ->
        logger.warn("SilentNET startup check could not complete; no security verdict is available", exception)
    })

    fun start() = check.start()

    override val running: Boolean get() = !stopped

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        val world = mc.level
        val player = mc.player
        val ready = world != null && player != null && player.level() === world && mc.connection != null
        val outcome = check.poll(ready) ?: return@handler
        val report = outcome.report
        val evidence = report.findings().map { "${it.location()}: ${it.description()}" }
        SilentNetWarning.messages(evidence, report.incomplete() || outcome.originIncomplete).forEach { chat(it) }
    }

    @Suppress("unused")
    private val shutdown = handler<ClientShutdownEvent> { stop() }

    fun stop() {
        stopped = true
        check.close()
    }

    override fun unregister() {
        stop()
        super<DonutEventListener>.unregister()
    }
}
