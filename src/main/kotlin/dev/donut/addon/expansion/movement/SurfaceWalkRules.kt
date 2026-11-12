package dev.donut.addon.expansion.movement

/** Liquid-specific exceptions are intentionally independent; unsafe lava takes priority over dipping. */
internal object SurfaceWalkRules {
    data class Guards(
        val spectator: Boolean = false,
        val flying: Boolean = false,
        val boat: Boolean = false,
        val strider: Boolean = false,
        val flightModule: Boolean = false,
        val burning: Boolean = false,
        val sneaking: Boolean = false,
        val fallDistance: Double = 0.0,
        val lavaProtection: Boolean = false,
    )
    data class Options(val solid: Boolean = true, val sneakDip: Boolean = true,
                       val fallDip: Boolean = true, val fallLimit: Int = 4)

    fun water(g: Guards, option: Options, burningDip: Boolean): Boolean = option.solid &&
        !g.spectator && !g.flying && !g.boat && !g.flightModule && !(burningDip && g.burning) &&
        !(option.sneakDip && g.sneaking) && !(option.fallDip && g.fallDistance > option.fallLimit)

    fun lava(g: Guards, option: Options, resistanceDip: Boolean): Boolean {
        if (!option.solid || g.spectator || g.flying || g.strider) return false
        if (!resistanceDip || !g.lavaProtection) return true
        return !(option.sneakDip && g.sneaking) && !(option.fallDip && g.fallDistance > option.fallLimit)
    }

    class Pulse {
        var count = 0
            private set
        fun step(supported: Boolean): Boolean {
            if (!supported) { count = 0; return false }
            if (++count < 15) return false
            count = 0
            return true
        }
        fun reset() { count = 0 }
    }
}
