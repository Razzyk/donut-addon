package dev.donut.addon.anubis.module.impl.combat.crystal;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.ccbluex.liquidbounce.utils.input.InputTracker;
public final class Crystals {
    private Crystals() {}
    public static boolean isPhysicallyDown(Minecraft mc, KeyMapping mapping) {
        return InputTracker.INSTANCE.isPressedOnAny(mapping);
    }
    public static int hotbarSlotOf(Inventory inv, Item item) {
        for (int i=0; i<9; i++) if (inv.getItem(i).is(item)) return i;
        return -1;
    }
    public static boolean isCombatItem(ItemStack stack) { return stack.isEmpty() || stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(Items.MACE); }
    public static int weaponRoute(LocalPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty() && isCombatItem(held) && (dealsDamage(player, held) || enchantsAddDamage(held))) return -2;
        for (int i=0; i<9; i++) {
            ItemStack candidate = player.getInventory().getItem(i);
            if (!candidate.isEmpty() && isCombatItem(candidate) && (dealsDamage(player, candidate) || enchantsAddDamage(candidate))) return i;
        }
        // A fist is a valid fallback only when the player can actually damage the crystal.
        for (int i=0; i<9; i++) if (player.getInventory().getItem(i).isEmpty() && predictedDamage(player, ItemStack.EMPTY) > 0) return i;
        return -1;
    }
    public static int route(LocalPlayer p, Item item, BlockState state, int saved) {
        if (p.getOffhandItem().is(item)) return -2;
        if (p.getMainHandItem().is(item)) return p.getInventory().getSelectedSlot();
        return hotbarSlotOf(p.getInventory(), item);
    }
    public static boolean isBase(BlockState state) { return state.is(Blocks.OBSIDIAN) || state.is(Blocks.BEDROCK); }
    public static boolean hasCrystal(Level level, BlockPos pos, Entity ignored) {
        return !level.getEntities(ignored, new AABB(pos.above()).inflate(0.2), e -> e instanceof EndCrystal && e.isAlive()).isEmpty();
    }
    public static boolean canHost(Level level, BlockPos pos, Entity ignored) {
        return isBase(level.getBlockState(pos)) && level.getBlockState(pos.above()).isAir()
            && level.getEntities(ignored, new AABB(pos.above()).expandTowards(0,1,0), e -> e.isAlive()).isEmpty();
    }
    public static BlockPos placedObsidian(ClientLevel level, LocalPlayer p, BlockHitResult hit, BlockState state) {
        BlockPos target = state.canBeReplaced() ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
        return level.getBlockState(target).canBeReplaced() && level.getWorldBorder().isWithinBounds(target) ? target.immutable() : null;
    }
    public static BlockPos obsidianTarget(ClientLevel l, LocalPlayer p, BlockHitResult h, BlockState s) { return placedObsidian(l,p,h,s); }
    public static int confirmWindow(Minecraft mc, LocalPlayer p) {
        var connection = mc.getConnection();
        var info = connection == null ? null : connection.getPlayerInfo(p.getUUID());
        return CombatHelperMath.confirmTicks(info == null ? 0 : info.getLatency());
    }
    // Sharpness affects all targets. Conditional damage effects such as Smite do not
    // reliably apply to crystals and must not bypass the weapon-settle wait.
    public static boolean enchantsAddDamage(ItemStack stack) {
        return stack.getEnchantments().keySet().stream().anyMatch(enchantment -> enchantment.is(Enchantments.SHARPNESS));
    }
    public static boolean dealsDamage(LivingEntity p, ItemStack stack) {
        // EMPTY is used by the caller to ask about the currently applied attack attribute.
        return stack.isEmpty() ? p.getAttributeValue(Attributes.ATTACK_DAMAGE) > 0 : predictedDamage(p, stack) > 0;
    }
    private static double predictedDamage(LivingEntity p, ItemStack stack) {
        double base = p.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
        var strength = p.getEffect(MobEffects.STRENGTH);
        var weakness = p.getEffect(MobEffects.WEAKNESS);
        if (strength != null) base += 3.0 * (strength.getAmplifier() + 1);
        if (weakness != null) base -= 4.0 * (weakness.getAmplifier() + 1);
        return stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
            .compute(Attributes.ATTACK_DAMAGE, base, EquipmentSlot.MAINHAND);
    }
    public static boolean rightClick(Minecraft mc, LocalPlayer p, BlockHitResult hit) {
        if (mc.gameMode == null) return false;
        var hand = p.getOffhandItem().is(Items.END_CRYSTAL) && !p.getMainHandItem().is(Items.OBSIDIAN) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        var result = mc.gameMode.useItemOn(p, hand, hit);
        if (result.consumesAction()) net.ccbluex.liquidbounce.utils.block.SwingMode.DO_NOT_HIDE.swing(hand);
        return result.consumesAction();
    }
    public static boolean strike(Minecraft mc, LocalPlayer p, Entity entity) {
        if (mc.gameMode == null || entity == null || !entity.isAlive()) return false;
        mc.gameMode.attack(p, entity); net.ccbluex.liquidbounce.utils.block.SwingMode.DO_NOT_HIDE.swing(InteractionHand.MAIN_HAND); return true;
    }
}
