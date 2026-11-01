package dev.donut.addon.anubis.module.impl.combat.melee;
import dev.donut.addon.anubis.module.impl.combat.crystal.Crystals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
public final class MeleeAttacks {
    private static long actedTick = Long.MIN_VALUE;
    private static Object actedLevel;
    private MeleeAttacks() {}
    public static void claimTick(Minecraft mc) { if (mc.level != null) { actedLevel = mc.level; actedTick = mc.level.getGameTime(); } }
    public static boolean actedThisTick(Player p) { return p.level() == actedLevel && p.level().getGameTime() == actedTick; }
    public static boolean canAct(Minecraft mc) { return mc.player != null && mc.level != null && mc.gameMode != null && mc.gui.screen() == null; }
    public static boolean handsFree(Minecraft mc, LocalPlayer p) { return canAct(mc) && !p.isUsingItem(); }
    public static boolean rightClickComing(Minecraft mc) { return mc.options.keyUse.isDown() || Crystals.isPhysicallyDown(mc, mc.options.keyUse); }
    public static void takeAttackClicks(Minecraft mc) { while (mc.options.keyAttack.consumeClick()) {} }
    public static float charge(Player p) { return p.getAttackStrengthScale(0.5f); }
    public static int ticksToFull(Player p) { return (int)Math.ceil((1f - charge(p)) * p.getCurrentItemAttackStrengthDelay()); }
    public static boolean isEnemy(LocalPlayer p, Entity target) { return target instanceof Player other && other != p && other.isAlive() && !other.isSpectator() && !p.isAlliedTo(other) && !dev.donut.addon.anubis.config.FriendStore.getInstance().isFriend(other.getName().getString()); }
    public static boolean lineIsClear(LocalPlayer p, Vec3 from, Vec3 to) { return p.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS; }
    public static boolean attack(Minecraft mc, EntityHitResult hit) {
        if (!canAct(mc) || !hit.getEntity().isAlive()) return false;
        mc.gameMode.attack(mc.player, hit.getEntity()); net.ccbluex.liquidbounce.utils.block.SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND); return true;
    }
}
