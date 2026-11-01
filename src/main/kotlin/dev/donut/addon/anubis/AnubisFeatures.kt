package dev.donut.addon.anubis

import dev.donut.addon.anubis.module.impl.combat.CrystalMacroModule
import dev.donut.addon.anubis.module.impl.combat.SingleAnchorMacroModule
import dev.donut.addon.anubis.module.impl.misc.ChatMacroModule
import dev.donut.addon.anubis.module.impl.donutmacros.*

object AnubisFeatures {
    val modules = listOf(
        AhSniperModule(), AhSellModule(), AutoSellModule(), OrderAhSellerModule(), OrderDropperModule(),
        TpMacroModule(), AutoTpaHereModule(), PanicPayModule(), CrystalMacroModule(),
        SingleAnchorMacroModule(), ChatMacroModule()
    )
}
