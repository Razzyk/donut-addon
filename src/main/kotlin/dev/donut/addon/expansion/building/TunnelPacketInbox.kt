package dev.donut.addon.expansion.building

/** Packet intake never grows while the client is paused; overflow requests a loaded-world rescan. */
internal class TunnelPacketInbox<T>(private val capacity: Int) {
    init { require(capacity > 0) }
    private val entries = ArrayDeque<T>()
    private var overflowed = false

    @Synchronized fun offer(value: T): Boolean {
        if (entries.size == capacity) {
            overflowed = true
            return false
        }
        entries.addLast(value)
        return true
    }

    @Synchronized fun poll(): T? = entries.removeFirstOrNull()
    @Synchronized fun consumeOverflow(): Boolean = overflowed.also { overflowed = false }
    @Synchronized fun clear() { entries.clear(); overflowed = false }
}
