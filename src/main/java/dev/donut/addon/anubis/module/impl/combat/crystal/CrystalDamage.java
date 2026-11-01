package dev.donut.addon.anubis.module.impl.combat.crystal;
import net.ccbluex.liquidbounce.utils.entity.EntityExtensionsKt;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.util.List;
public final class CrystalDamage {
    private CrystalDamage() {}
    public static float estimate(Vec3 pos, LivingEntity entity) { return estimate(pos, entity, 6f, null); }
    public static float estimate(Vec3 pos, LivingEntity entity, float power, BlockPos ignored) {
        float range = power * 2;
        return EntityExtensionsKt.getDamageFromExplosion(entity, pos, power, range, range * range,
            ignored == null ? null : List.of(ignored), null, null, null, null);
    }
    public static boolean endangers(Player p, float damage) {
        if (p.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || p.getMainHandItem().is(Items.TOTEM_OF_UNDYING)) return false;
        return damage >= p.getHealth() + p.getAbsorptionAmount();
    }
}
