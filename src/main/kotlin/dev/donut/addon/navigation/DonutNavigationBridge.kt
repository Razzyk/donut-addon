package dev.donut.addon.navigation

import dev.donut.addon.compat.DonutEventListener
import net.ccbluex.liquidbounce.config.types.Value
import net.ccbluex.liquidbounce.config.types.group.ValueGroup
import net.ccbluex.liquidbounce.event.EventListener
import net.ccbluex.liquidbounce.event.events.MovementInputEvent
import net.ccbluex.liquidbounce.event.events.ClientShutdownEvent
import net.ccbluex.liquidbounce.event.events.WorldChangeEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.utils.aiming.RotationsValueGroup
import net.ccbluex.liquidbounce.utils.aiming.data.Rotation
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.kotlin.EventPriorityConvention.CRITICAL_MODIFICATION
import net.ccbluex.liquidbounce.utils.input.InputTracker.isPressedOnAny
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * Optional adapter for LiquidBounce's native walking Navigator. The new Navigator is resolved by name so this addon
 * continues to link against LiquidBounce 0.42.0, where that API is not present.
 *
 * The adapter owns one movement lease at a time. It only steers the player on foot in the same loaded world, and the
 * native Navigator is configured not to break, place, parkour, fly, or use Elytra. A native path may open doors.
 */
object DonutNavigationBridge : DonutEventListener {
    private const val NAVIGATOR_CLASS = "net.ccbluex.liquidbounce.utils.navigation.Navigator"
    private const val MAX_RADIUS = 8.0
    private const val LEASE_TIMEOUT_NANOS = 1_500_000_000L
    private val YIELD_STATUSES = setOf(
        NavigationStatus.WAITING_FOR_ROUTE,
        NavigationStatus.WALKING,
        NavigationStatus.ACTING,
        NavigationStatus.INPUT_PAUSED,
        NavigationStatus.WORLD_UNAVAILABLE,
        NavigationStatus.CONFLICT,
        NavigationStatus.PREEMPTED,
    )

    private val nativeApi by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { NativeNavigatorApi.resolve() }
    private val lastSnapshots = WeakHashMap<EventListener, NavigationSnapshot>()
    private val failedOwners = Collections.newSetFromMap(WeakHashMap<EventListener, Boolean>())
    private val sessions = IdentityHashMap<EventListener, Session>()
    private var active: Session? = null
    @Volatile private var stopped = false

    override val running: Boolean
        get() = !stopped

    /** True only when the current runtime exposes and links the real native walking API. */
    val available: Boolean
        get() = nativeApi != null

    @Suppress("unused")
    private val movementInputHandler = handler<MovementInputEvent>(priority = CRITICAL_MODIFICATION) { event ->
        drive(event)
    }

    @Suppress("unused")
    private val worldChangeHandler = handler<WorldChangeEvent> {
        active?.let { finish(it, NavigationStatus.WORLD_UNAVAILABLE) }
    }

    @Suppress("unused")
    private val clientShutdownHandler = handler<ClientShutdownEvent> { stop() }

    /** Starts or refreshes this owner's target. An update acquires a missing lease but never steals another owner's. */
    @Synchronized
    fun start(owner: EventListener, world: ClientLevel, goal: Vec3, radius: Double): NavigationSnapshot =
        update(owner, world, goal, radius)

    /** Updates the target and acquires a lease if this owner has none. A busy lease returns CONFLICT. */
    @Synchronized
    fun update(owner: EventListener, world: ClientLevel, goal: Vec3, radius: Double): NavigationSnapshot {
        if (stopped || failedOwners.contains(owner)) {
            return remember(owner, NavigationSnapshot(NavigationBackend.LEGACY_FALLBACK_REQUIRED, NavigationStatus.UNAVAILABLE))
        }
        val api = nativeApi
        if (api == null) {
            val snapshot = NavigationSnapshot(NavigationBackend.LEGACY_FALLBACK_REQUIRED, NavigationStatus.UNAVAILABLE)
            lastSnapshots[owner] = snapshot
            return snapshot
        }

        if (!validGoal(world, goal, radius)) {
            val current = active
            if (current?.owner === owner) finish(current, NavigationStatus.WORLD_UNAVAILABLE)
            return remember(owner, NavigationSnapshot(NavigationBackend.NATIVE, NavigationStatus.WORLD_UNAVAILABLE))
        }

        val current = active
        if (current != null && current.owner !== owner) {
            return remember(owner, NavigationSnapshot(NavigationBackend.NATIVE, NavigationStatus.CONFLICT))
        }

        val existing = sessions[owner]
        val session = current ?: existing ?: createSession(api, owner, world, goal, radius)?.also {
            sessions[owner] = it
        } ?: run {
                failedOwners += owner
                val snapshot = NavigationSnapshot(NavigationBackend.LEGACY_FALLBACK_REQUIRED, NavigationStatus.UNAVAILABLE)
                lastSnapshots[owner] = snapshot
                return snapshot
            }
        if (session.failed) {
            return remember(owner, NavigationSnapshot(NavigationBackend.LEGACY_FALLBACK_REQUIRED, NavigationStatus.UNAVAILABLE))
        }
        val sameGoal = session.goal == goal && session.radius == radius && session.world === world
        if (session.snapshot.status == NavigationStatus.ARRIVED && sameGoal && playerPositionWithin(goal, radius)) {
            return session.snapshot
        }
        session.world = world
        session.goal = goal
        session.radius = radius
        session.lastUpdatedAtNanos = System.nanoTime()
        val player = mc.player
        if (mc.gui.screen() != null || manualMovementHeld() || player?.isPassenger == true ||
            player?.abilities?.flying == true || player?.isFallFlying == true) {
            if (!nativeHalt(session)) return session.snapshot
            session.listener.enabled = false
            session.snapshot = NavigationSnapshot(NavigationBackend.NATIVE, NavigationStatus.INPUT_PAUSED)
            lastSnapshots[owner] = session.snapshot
            active = session
            return session.snapshot
        }
        if (playerPositionWithin(goal, radius)) {
            if (!nativeReset(session)) return session.snapshot
            session.listener.enabled = false
            session.snapshot = NavigationSnapshot(NavigationBackend.NATIVE, NavigationStatus.ARRIVED)
            lastSnapshots[owner] = session.snapshot
            if (active === session) active = null
            return session.snapshot
        }
        session.listener.enabled = true
        if (!sameGoal || session.snapshot.status !in setOf(
                NavigationStatus.WAITING_FOR_ROUTE,
                NavigationStatus.WALKING,
                NavigationStatus.ACTING,
                NavigationStatus.INPUT_PAUSED,
            )) {
            session.snapshot = NavigationSnapshot(NavigationBackend.NATIVE, NavigationStatus.WAITING_FOR_ROUTE)
        }
        lastSnapshots[owner] = session.snapshot
        active = session
        return session.snapshot
    }

    /** Last known status, including terminal states. Returns null before this owner first requested navigation. */
    @Synchronized
    fun status(owner: EventListener): NavigationSnapshot? = lastSnapshots[owner]

    /** Whether this owner currently holds the exclusive native movement lease. */
    @Synchronized
    fun ownsControl(owner: EventListener): Boolean = active?.owner === owner

    /** Whether callers should leave movement and rotation alone, including while a competing native owner has control. */
    @Synchronized
    fun shouldYieldLocalControl(owner: EventListener): Boolean =
        lastSnapshots[owner]?.let { it.backend == NavigationBackend.NATIVE && it.status in YIELD_STATUSES } == true

    /** Native route-facing rotation while this owner holds the lease, or null when it is not driving. */
    @Synchronized
    fun movementRotation(owner: EventListener): Rotation? {
        val session = active?.takeIf { it.owner === owner } ?: return null
        if (session.snapshot.status !in setOf(NavigationStatus.WALKING, NavigationStatus.ACTING)) return null
        return session.api.movementRotation(session.navigator)
    }

    /** Cancels only this owner's route; another owner's live lease is never affected. */
    @Synchronized
    fun cancel(owner: EventListener): NavigationSnapshot {
        val session = active?.takeIf { it.owner === owner }
        if (session != null) {
            finish(session, NavigationStatus.CANCELLED)
            return session.snapshot
        }
        sessions[owner]?.let { idle ->
            idle.listener.enabled = false
            runCatching { idle.api.reset(idle.navigator) }
            idle.world = null
            idle.snapshot = NavigationSnapshot(idleBackend(idle), NavigationStatus.CANCELLED)
        }
        val backend = sessions[owner]?.let { idleBackend(it) }
            ?: lastSnapshots[owner]?.backend
            ?: if (nativeApi != null) NavigationBackend.NATIVE else NavigationBackend.LEGACY_FALLBACK_REQUIRED
        return remember(owner, NavigationSnapshot(backend, NavigationStatus.CANCELLED))
    }

    private fun drive(event: MovementInputEvent) {
        val session = synchronized(this) { active } ?: return
        if (!session.owner.running) {
            synchronized(this) { finishIfCurrent(session, NavigationStatus.CANCELLED) }
            return
        }
        if (System.nanoTime() - session.lastUpdatedAtNanos > LEASE_TIMEOUT_NANOS) {
            synchronized(this) { finishIfCurrent(session, NavigationStatus.CANCELLED) }
            return
        }
        val world = session.world
        if (world == null || mc.level !== world || mc.player == null || mc.gameMode == null) {
            synchronized(this) { finishIfCurrent(session, NavigationStatus.WORLD_UNAVAILABLE) }
            return
        }
        if (!world.hasChunkAt(BlockPos.containing(session.goal))) {
            session.listener.enabled = false
            if (!nativeHalt(session)) return
            session.snapshot = NavigationSnapshot(NavigationBackend.NATIVE, NavigationStatus.WORLD_UNAVAILABLE)
            synchronized(this) { lastSnapshots[session.owner] = session.snapshot }
            return
        }

        val player = mc.player ?: return
        if (mc.gui.screen() != null || manualMovementHeld() || player.isPassenger ||
            player.abilities.flying || player.isFallFlying) {
            session.listener.enabled = false
            if (!nativeHalt(session)) return
            session.snapshot = NavigationSnapshot(NavigationBackend.NATIVE, NavigationStatus.INPUT_PAUSED)
            synchronized(this) { lastSnapshots[session.owner] = session.snapshot }
            return
        }

        try {
            val plainWalk = session.api.navigate(session.navigator, event, session.goal, session.radius)
            val arrived = player.rootVehicle.position().closerThan(session.goal, session.radius)
            val acting = session.api.isActing(session.navigator)
            val status = when {
                arrived -> NavigationStatus.ARRIVED
                acting -> NavigationStatus.ACTING
                plainWalk -> NavigationStatus.WALKING
                else -> NavigationStatus.WAITING_FOR_ROUTE
            }
            session.snapshot = NavigationSnapshot(NavigationBackend.NATIVE, status, plainWalk)
            session.listener.enabled = true
            synchronized(this) {
                if (arrived) {
                    finishIfCurrent(session, NavigationStatus.ARRIVED)
                } else {
                    lastSnapshots[session.owner] = session.snapshot
                }
            }
        } catch (_: Throwable) {
            synchronized(this) { failToFallback(session) }
        }
    }

    private fun manualMovementHeld(): Boolean = with(mc.options) {
        listOf(keyUp, keyDown, keyLeft, keyRight, keyJump).any { it.isPressedOnAny }
    }

    private fun validGoal(world: ClientLevel, goal: Vec3, radius: Double): Boolean {
        if (mc.level !== world || mc.player == null || mc.gameMode == null ||
            !goal.x.isFinite() || !goal.y.isFinite() || !goal.z.isFinite() ||
            !radius.isFinite() || radius <= 0.0 || radius > MAX_RADIUS) {
            return false
        }
        val player = mc.player ?: return false
        if (player.isPassenger || player.abilities.flying || player.isFallFlying) return false
        return world.hasChunkAt(BlockPos.containing(goal))
    }

    private fun playerPositionWithin(goal: Vec3, radius: Double): Boolean =
        mc.player?.rootVehicle?.position()?.closerThan(goal, radius) == true

    private fun nativeHalt(session: Session): Boolean = try {
        session.api.halt(session.navigator)
        true
    } catch (_: Throwable) {
        failToFallback(session)
        false
    }

    private fun nativeReset(session: Session): Boolean = try {
        session.api.reset(session.navigator)
        true
    } catch (_: Throwable) {
        failToFallback(session)
        false
    }

    private fun createSession(
        api: NativeNavigatorApi,
        owner: EventListener,
        world: ClientLevel,
        goal: Vec3,
        radius: Double,
    ): Session? {
        val listener = NavigationOwner(owner)
        val values = ValueGroup("Donut Navigation")
        var navigator: Any? = null
        return try {
            navigator = api.create(listener, values)
            configureSafeWalking(values)
            Session(owner, listener, api, navigator, values, world, goal, radius)
        } catch (_: ReflectiveOperationException) {
            cleanupFailedCreation(listener, values, api, navigator)
            null
        } catch (_: RuntimeException) {
            cleanupFailedCreation(listener, values, api, navigator)
            null
        } catch (_: LinkageError) {
            cleanupFailedCreation(listener, values, api, navigator)
            null
        }
    }

    private fun cleanupFailedCreation(
        listener: NavigationOwner,
        values: ValueGroup,
        api: NativeNavigatorApi,
        navigator: Any?,
    ) {
        listener.enabled = false
        values.containedValues.filterIsInstance<EventListener>().forEach { runCatching { it.unregister() } }
        if (navigator != null) runCatching { api.unregister(navigator) }
        runCatching { listener.unregister() }
    }

    internal fun configureSafeWalking(values: ValueGroup) {
        val flags = values.containedValues.associateBy { it.name }
        val requiredFalse = setOf("AllowBreak", "AllowPlace", "AllowParkour", "AllowFly", "AllowElytra", "ElytraRockets")
        for (name in requiredFalse) {
            val value = flags[name] ?: error("native Navigator omitted required setting $name")
            setBooleanValue(value, false)
            check(value.get() == false) { "failed to disable native Navigator setting $name" }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setBooleanValue(value: Value<*>, newValue: Boolean) {
        check(value.get() is Boolean) { "native Navigator setting ${value.name} was not Boolean" }
        (value as Value<Boolean>).set(newValue)
    }

    private fun finishIfCurrent(session: Session, status: NavigationStatus) {
        if (active === session) finish(session, status)
    }

    private fun finish(session: Session, status: NavigationStatus) {
        if (active === session) active = null
        session.listener.enabled = false
        runCatching { session.api.reset(session.navigator) }
        session.world = null
        session.snapshot = NavigationSnapshot(NavigationBackend.NATIVE, status)
        lastSnapshots[session.owner] = session.snapshot
    }

    private fun failToFallback(session: Session) {
        if (active === session) active = null
        session.failed = true
        session.listener.enabled = false
        runCatching { session.api.reset(session.navigator) }
        session.world = null
        failedOwners += session.owner
        session.snapshot = NavigationSnapshot(NavigationBackend.LEGACY_FALLBACK_REQUIRED, NavigationStatus.UNAVAILABLE)
        lastSnapshots[session.owner] = session.snapshot
    }

    /** Called by addon shutdown before its listener is unregistered. */
    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        active?.let { finish(it, NavigationStatus.CANCELLED) }
        sessions.values.forEach { session ->
            session.listener.enabled = false
            runCatching { session.api.reset(session.navigator) }
            session.api.unregisterTree(session.navigator, session.values)
            runCatching { session.listener.unregister() }
        }
        sessions.clear()
        failedOwners.clear()
    }

    override fun unregister() {
        stop()
        super<DonutEventListener>.unregister()
    }

    private fun remember(owner: EventListener, snapshot: NavigationSnapshot): NavigationSnapshot {
        lastSnapshots[owner] = snapshot
        return snapshot
    }

    private fun idleBackend(session: Session) =
        if (session.failed) NavigationBackend.LEGACY_FALLBACK_REQUIRED else NavigationBackend.NATIVE

    private class NavigationOwner(private val owner: EventListener) : DonutEventListener {
        @Volatile var enabled: Boolean = false
        override val running: Boolean
            get() = enabled && owner.running
        override fun parent(): EventListener = owner
    }

    private class Session(
        val owner: EventListener,
        val listener: NavigationOwner,
        val api: NativeNavigatorApi,
        val navigator: Any,
        val values: ValueGroup,
        var world: ClientLevel?,
        var goal: Vec3,
        var radius: Double,
        var lastUpdatedAtNanos: Long = System.nanoTime(),
        var snapshot: NavigationSnapshot = NavigationSnapshot(NavigationBackend.NATIVE, NavigationStatus.WAITING_FOR_ROUTE),
    ) {
        var failed: Boolean = false
    }

    internal class NativeNavigatorApi private constructor(
        private val constructor: Constructor<*>,
        private val navigateMethod: Method,
        private val haltMethod: Method,
        private val resetMethod: Method,
        private val unregisterMethod: Method,
        private val actingMethod: Method,
        private val movementRotationMethod: Method,
        private val childListenerFields: List<Field>,
    ) {
        fun create(owner: EventListener, values: ValueGroup): Any =
            constructor.newInstance(owner, values, null, false, false, false, false)

        fun navigate(navigator: Any, event: MovementInputEvent, goal: Vec3, radius: Double): Boolean =
            navigateMethod.invoke(navigator, event, goal, radius) as Boolean

        fun halt(navigator: Any) {
            haltMethod.invoke(navigator)
        }

        fun reset(navigator: Any) {
            resetMethod.invoke(navigator)
        }

        fun unregister(navigator: Any) {
            for (field in childListenerFields) {
                runCatching { (field.get(navigator) as? EventListener)?.unregister() }
            }
            unregisterMethod.invoke(navigator)
        }

        fun unregisterTree(navigator: Any, values: ValueGroup) {
            values.containedValues.filterIsInstance<EventListener>().forEach { runCatching { it.unregister() } }
            runCatching { unregister(navigator) }
        }

        fun isActing(navigator: Any): Boolean = actingMethod.invoke(navigator) as Boolean

        fun movementRotation(navigator: Any): Rotation? = movementRotationMethod.invoke(navigator) as? Rotation

        companion object {
            fun resolve(): NativeNavigatorApi? = runCatching {
                val loader = Thread.currentThread().contextClassLoader ?: DonutNavigationBridge::class.java.classLoader
                val type = Class.forName(NAVIGATOR_CLASS, false, loader)
                val childFields = type.declaredFields.filter { EventListener::class.java.isAssignableFrom(it.type) }
                check(childFields.all { it.trySetAccessible() }) {
                    "native Navigator child listeners are not accessible for shutdown cleanup"
                }
                NativeNavigatorApi(
                    type.getConstructor(
                        EventListener::class.java,
                        ValueGroup::class.java,
                        RotationsValueGroup::class.java,
                        Boolean::class.javaPrimitiveType!!,
                        Boolean::class.javaPrimitiveType!!,
                        Boolean::class.javaPrimitiveType!!,
                        Boolean::class.javaPrimitiveType!!,
                    ),
                    type.getMethod("navigate", MovementInputEvent::class.java, Vec3::class.java, Double::class.javaPrimitiveType!!),
                    type.getMethod("halt"),
                    type.getMethod("reset"),
                    type.getMethod("unregister"),
                    type.getMethod("isActing"),
                    type.getMethod("movementRotation"),
                    childFields,
                )
            }.getOrNull()
        }
    }

}

enum class NavigationBackend {
    NATIVE,
    LEGACY_FALLBACK_REQUIRED,
}

enum class NavigationStatus {
    WAITING_FOR_ROUTE,
    WALKING,
    ACTING,
    ARRIVED,
    INPUT_PAUSED,
    WORLD_UNAVAILABLE,
    CONFLICT,
    PREEMPTED,
    CANCELLED,
    UNAVAILABLE,
}

data class NavigationSnapshot(
    val backend: NavigationBackend,
    val status: NavigationStatus,
    val plainWalk: Boolean = false,
)
