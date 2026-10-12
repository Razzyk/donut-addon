package dev.donut.addon.mv

import dev.donut.addon.mv.compat.systems.modules.Module
import dev.donut.addon.mv.compat.systems.modules.Modules
import dev.mulveo.mvaddon.modules.*

/** Explicit registry: no reflective optional loads or hidden missing dependencies. */
object MVFeatures {
    val modules: List<Module> by lazy {
        listOf(
            AHSniper(), SusChunkFinder(), Basefinder(), OpBoatFlyPhase(), ChunkScanner(),
            Home(), HomeRelog(), AutoRelog(), SpawnerFinder(), PlayerDetect(), WeatherNoti(),
            AmethystEsp(), ChunkFinder(), StorageEspPlus(), TridentUtil(), mv1(),
            BypassAutoFlyDependency(), BypassBoatVoidFlyDependency()
        ).also { list -> list.forEach { Modules.get().add(it) } }
    }
}
