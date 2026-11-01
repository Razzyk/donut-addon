package dev.donut.addon.anubis.module.impl.combat.anchor;
import dev.donut.addon.anubis.module.impl.combat.crystal.Crystals;
import dev.donut.addon.anubis.util.pvp.Rotation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
public final class AnchorGuard {
    private AnchorGuard() {}
    public record Spot(BlockPos pos, Vec3 aim, boolean standing) {}
    public static boolean isGuardBlock(ItemStack stack) { return stack.getItem() instanceof BlockItem && Block.byItem(stack.getItem()).getExplosionResistance() >= 6f; }
    public static int detonatorSlot(Inventory inv) {
        int current=inv.getSelectedSlot();
        if (!inv.getItem(current).is(Items.GLOWSTONE)) return current;
        for(int i=0;i<9;i++) if(!inv.getItem(i).is(Items.GLOWSTONE)) return i;
        return -1;
    }
    public static Spot find(ClientLevel level, LocalPlayer p, BlockPos anchor, Vec3 sightline) {
        Vec3 eye = p.getEyePosition();
        Vec3 center = Vec3.atCenterOf(anchor);
        // A block only guards this player if it lies on the player's side of the blast.
        Direction[] sides = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        Spot best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Direction side : sides) {
            double facing = (eye.x - center.x) * side.getStepX() + (eye.z - center.z) * side.getStepZ();
            if (facing <= 0) continue;
            BlockPos pos = anchor.relative(side);
            var state = level.getBlockState(pos);
            if (!state.canBeReplaced()) {
                if (state.getBlock().getExplosionResistance() >= 600f) {
                    var obstruct = level.clip(new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                    if (obstruct.getType() == HitResult.Type.BLOCK && obstruct.getBlockPos().equals(pos))
                        return new Spot(pos, Vec3.atCenterOf(pos), true);
                }
                continue;
            }
            if (!level.getWorldBorder().isWithinBounds(pos) || !level.noCollision(null, new net.minecraft.world.phys.AABB(pos))) continue;
            Vec3 aim = center.add(side.getStepX() * 0.499, 0, side.getStepZ() * 0.499);
            double distance = eye.distanceToSqr(aim);
            if (distance > p.blockInteractionRange() * p.blockInteractionRange() || distance >= bestDistance) continue;
            BlockHitResult hit = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(anchor) || hit.getDirection() != side) continue;
            best = new Spot(pos, aim, false);
            bestDistance = distance;
        }
        return best;
    }
    public static BlockHitResult hitAlong(ClientLevel level, LocalPlayer p, Rotation rotation, Spot spot) {
        Vec3 eye=p.getEyePosition();
        Vec3 end=eye.add(Vec3.directionFromRotation(rotation.pitch(), rotation.yaw()).scale(p.blockInteractionRange()));
        BlockHitResult hit=level.clip(new ClipContext(eye,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,p));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().relative(hit.getDirection()).equals(spot.pos()) ? hit : null;
    }
    public static BlockHitResult anchorHit(ClientLevel level, LocalPlayer p, BlockPos anchor) {
        Vec3 eye=p.getEyePosition(); Vec3 center=Vec3.atCenterOf(anchor);
        if (eye.distanceTo(center) > p.blockInteractionRange()) return null;
        BlockHitResult hit=level.clip(new ClipContext(eye,center,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,p));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(anchor) ? hit : null;
    }
    public static boolean click(Minecraft mc, LocalPlayer p, BlockHitResult hit) {
        if (mc.gameMode == null) return false;
        var result=mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        if(result.consumesAction()) net.ccbluex.liquidbounce.utils.block.SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND);
        return result.consumesAction();
    }
}
