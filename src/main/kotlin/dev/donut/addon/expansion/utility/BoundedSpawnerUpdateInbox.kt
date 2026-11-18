package dev.donut.addon.expansion.utility

import java.util.concurrent.ArrayBlockingQueue

/** Hard-bounded packet handoff. Tickets let each scheduled client task remove only its own update. */
internal class BoundedSpawnerUpdateInbox<T>(capacity: Int) {
    internal class Ticket<T> internal constructor(internal val value: T)

    private val queue = ArrayBlockingQueue<Ticket<T>>(capacity.also { require(it > 0) })

    fun offer(value: T): Ticket<T>? {
        val ticket = Ticket(value)
        return ticket.takeIf(queue::offer)
    }

    fun take(ticket: Ticket<T>): T? = if (queue.remove(ticket)) ticket.value else null

    fun clear() = queue.clear()

    fun size(): Int = queue.size
}
