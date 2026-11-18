package dev.donut.addon.expansion.presence

import java.util.concurrent.ArrayBlockingQueue

/** Nonblocking packet intake with a fixed capacity and a per-tick drain budget. */
internal class BoundedPresenceInbox<T>(capacity: Int) {
    private val queue = ArrayBlockingQueue<T>(capacity.also { require(it > 0) })

    operator fun plusAssign(value: T) {
        queue.offer(value)
    }

    fun poll(): T? = queue.poll()

    fun clear() = queue.clear()

    fun size(): Int = queue.size
}
