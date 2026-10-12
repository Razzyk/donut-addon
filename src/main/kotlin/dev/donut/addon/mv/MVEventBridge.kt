package dev.donut.addon.mv

import dev.donut.addon.mv.compat.MeteorClient
import dev.donut.addon.mv.compat.events.game.GameJoinedEvent
import dev.donut.addon.mv.compat.events.game.GameLeftEvent
import dev.donut.addon.mv.compat.events.game.ReceiveMessageEvent
import dev.donut.addon.mv.compat.events.world.TickEvent
import dev.donut.addon.mv.compat.events.world.ChunkDataEvent
import dev.donut.addon.mv.compat.events.render.Render3DEvent
import dev.donut.addon.mv.compat.systems.modules.Modules
import dev.donut.addon.mv.compat.utils.misc.Keybind
import dev.donut.addon.mv.compat.utils.render.RenderUtils
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.events.*
import net.ccbluex.liquidbounce.utils.client.ServerObserver
import net.minecraft.client.Minecraft
import net.minecraft.world.level.chunk.status.ChunkStatus

/** The source event facade is fed by real LiquidBounce/Fabric hooks, never a second client runtime. */
object MVEventBridge : EventListener {
    private val mc get() = Minecraft.getInstance()
    private val bus get() = MeteorClient.EVENT_BUS
    private val keysDown = mutableSetOf<Any>()

    /** Login tail runs after both the world and local player have been installed. */
    @JvmStatic
    fun joinedServer() {
        Modules.get().reconnect().connected(ServerObserver.serverInfo)
        bus.post(GameJoinedEvent())
    }

    @Suppress("unused")
    private val tick = handler<GameTickEvent> {
        Modules.get().reconnect().tick()
        for (module in Modules.get().all) {
            for (setting in module.settings.all) {
                if (setting.kind != "keybind") continue
                val pressed = module.isActive && mc.gui.screen() == null && (setting.get() as Keybind).isPressed
                if (pressed) {
                    if (keysDown.add(setting)) setting.actionCallback()?.run()
                } else keysDown.remove(setting)
            }
        }
        bus.post(TickEvent.Pre())
    }
    @Suppress("unused")
    private val world = handler<WorldChangeEvent> { event ->
        if (event.world == null) {
            bus.post(GameLeftEvent())
        }
    }
    @Suppress("unused")
    private val connect = handler<ServerConnectEvent> { event ->
        Modules.get().reconnect().lastServerConnection = event.serverInfo
    }
    @Suppress("unused")
    private val disconnect = handler<DisconnectEvent> { Modules.get().reconnect().disconnected() }
    @Suppress("unused")
    private val packet = handler<PacketEvent> { event ->
        val source = if (event.origin == TransferOrigin.INCOMING)
            dev.donut.addon.mv.compat.events.packets.PacketEvent.Receive(event.packet)
        else dev.donut.addon.mv.compat.events.packets.PacketEvent.Send(event.packet)
        bus.post(source)
        if (source.isCancelled) event.cancelEvent()
    }
    @Suppress("unused")
    private val chunk = handler<ChunkLoadEvent> { event ->
        mc.level?.chunkSource?.getChunk(event.x, event.z, ChunkStatus.FULL, false)?.let {
            bus.post(ChunkDataEvent(it))
        }
    }
    @Suppress("unused")
    private val render = handler<WorldRenderEvent> { event ->
        RenderUtils.center = event.camera.position().add(mc.player?.lookAngle?.scale(0.2) ?: net.minecraft.world.phys.Vec3.ZERO)
        bus.post(Render3DEvent(WorldRenderer(event), event.partialTicks.toDouble()))
    }
    @Suppress("unused")
    private val move = handler<PlayerMoveEvent> { event ->
        val source = dev.donut.addon.mv.compat.events.entity.player.PlayerMoveEvent(event.movement)
        bus.post(source)
        event.movement = source.movement
    }
    @Suppress("unused")
    private val chat = handler<ChatReceiveEvent> { event ->
        val source = bus.post(ReceiveMessageEvent(event.textData))
        if (source.isCancelled) event.cancelEvent()
    }
}
