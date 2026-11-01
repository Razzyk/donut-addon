// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.combat;

import dev.donut.addon.anubis.event.EventHandler;
import dev.donut.addon.anubis.event.impl.PlayerAttackEntityEvent;
import dev.donut.addon.anubis.event.impl.TickEvent;
import dev.donut.addon.anubis.module.ClientModule;
import dev.donut.addon.anubis.module.CombatMacroLock;
import dev.donut.addon.anubis.module.ModuleCategory;
import dev.donut.addon.anubis.module.impl.combat.AutoInvTotemModule;
import dev.donut.addon.anubis.module.impl.combat.crystal.CrystalDamage;
import dev.donut.addon.anubis.module.impl.combat.crystal.Crystals;
import dev.donut.addon.anubis.module.impl.combat.crystal.LootLatch;
import dev.donut.addon.anubis.module.impl.combat.melee.MeleeAttacks;
import dev.donut.addon.anubis.module.impl.misc.AutoEatModule;
import dev.donut.addon.anubis.module.setting.BooleanSetting;
import dev.donut.addon.anubis.module.setting.NumberSetting;
import dev.donut.addon.anubis.module.setting.Setting;
import dev.donut.addon.anubis.util.pvp.ServerRotation;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

@Environment(value=EnvType.CLIENT)
public final class CrystalMacroModule
extends ClientModule {
    private static final int COUNTER_CAP = 1000;
    private static final int WEAPON_SETTLE_TICKS = 2;
    private final NumberSetting breakDelay = (NumberSetting)this.addSetting("break-delay", (Setting)new NumberSetting("Break Delay", 0.0f, 0.0f, 10.0f).step(1.0f));
    private final NumberSetting placeDelay = (NumberSetting)this.addSetting("place-delay", (Setting)new NumberSetting("Place Delay", 0.0f, 0.0f, 10.0f).step(1.0f));
    private final BooleanSetting autoObsidian = (BooleanSetting)this.addSetting("auto-obsidian", (Setting)new BooleanSetting("Auto Obsidian", true));
    private final NumberSetting obsidianDelay = (NumberSetting)this.addSetting("obsidian-delay", (Setting)new NumberSetting("Obsidian Delay", 2.0f, 0.0f, 10.0f).step(1.0f));
    private final BooleanSetting switchBack = (BooleanSetting)this.addSetting("switch-back", (Setting)new BooleanSetting("Switch Back", true));
    private boolean engaged;
    private int savedSlot = -1;
    private int ownSlot = -1;
    private int ticksSinceAction = 1000;
    private ItemStack lastMain;
    private int mainAge = 1000;
    private int pendingBreakId = -1;
    private int pendingBreakAge = 1000;
    private BlockPos pendingCrystalBase;
    private int pendingCrystalAge = 1000;
    private BlockPos pendingObsidian;
    private int pendingObsidianAge = 1000;

    public CrystalMacroModule() {
        super("crystal-macro", "Crystal Macro", "Breaks and places crystals while you hold right-click.", ModuleCategory.COMBAT);
        this.obsidianDelay.visibleWhen(() -> ((BooleanSetting)this.autoObsidian).get());
    }

    public int settingsVersion() {
        return 1;
    }

    public void migrateSettings(int savedVersion) {
        if (savedVersion < 1) {
            this.breakDelay.set(this.breakDelay.get() - 1.0f);
            this.placeDelay.set(this.placeDelay.get() - 1.0f);
            this.obsidianDelay.set(this.obsidianDelay.get() - 1.0f);
        }
    }

    protected void onEnable() {
        this.resetState();
    }

    protected void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        this.endHold(mc);
        LocalPlayer player = mc.player;
        if (player != null && !CrystalMacroModule.hotbarTakenByOther()) {
            this.restoreSlot(player);
        }
        this.resetState();
    }

    @EventHandler
    public void onTick(TickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        this.ageCounters();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null || player.isDeadOrDying()) {
            this.endHold(mc);
            this.forgetSlots();
            return;
        }
        ItemStack main = player.getMainHandItem();
        if (main != this.lastMain) {
            this.lastMain = main;
            this.mainAge = 0;
        }
        LootLatch.update((Minecraft)mc, (LocalPlayer)player);
        if (LootLatch.latched()) {
            this.endHold(mc);
            if (!player.isUsingItem() && !AutoEatModule.isEating() && (this.ownsSelection(player) || CrystalMacroModule.activationAllows(player))) {
                CrystalMacroModule.suppressVanillaUse(mc);
            }
            return;
        }
        if (!this.holdActive(mc, player)) {
            this.endHold(mc);
            if (mc.gui.screen() == null && !CrystalMacroModule.hotbarTakenByOther() && !MeleeAttacks.actedThisTick((Player)player)) {
                this.restoreSlot(player);
            }
            return;
        }
        if (AutoInvTotemModule.wantsHotbar() || CombatMacroLock.heldByOther((Object)this)) {
            CombatMacroLock.release((Object)this);
            if (this.engaged) {
                CrystalMacroModule.suppressVanillaUse(mc);
            }
            return;
        }
        if (this.engaged) {
            if (!this.keepEngaged(player)) {
                this.endHold(mc);
                return;
            }
        } else if (player.isUsingItem() || !this.ownsSelection(player) && !CrystalMacroModule.activationAllows(player)) {
            return;
        }
        Plan plan = this.plan(mc, player);
        if (!this.engaged && plan == null && !this.ownsSelection(player)) {
            return;
        }
        this.engaged = true;
        CombatMacroLock.tryAcquire((Object)this);
        CrystalMacroModule.suppressVanillaUse(mc);
        if (plan == null || !MeleeAttacks.handsFree((Minecraft)mc, (LocalPlayer)player) || MeleeAttacks.actedThisTick((Player)player) || ServerRotation.pending()) {
            return;
        }
        switch (plan.step().ordinal()) {
            case 0: {
                this.breakCrystal(mc, player, plan);
                break;
            }
            case 1: {
                if (!this.useOnCrosshair(mc, player, this.placeDelay, plan)) break;
                this.pendingCrystalBase = plan.target();
                this.pendingCrystalAge = 0;
                break;
            }
            case 2: {
                if (!this.useOnCrosshair(mc, player, this.obsidianDelay, plan)) break;
                this.pendingObsidian = plan.target();
                this.pendingObsidianAge = 0;
            }
        }
    }

    @EventHandler
    public void onAttack(PlayerAttackEntityEvent event) {
        EndCrystal crystal;
        Entity class_12972 = event.target;
        if (class_12972 instanceof EndCrystal && (crystal = (EndCrystal)class_12972).level().isClientSide()) {
            this.markBreak(crystal);
        }
    }

    private boolean holdActive(Minecraft mc, LocalPlayer player) {
        return MeleeAttacks.canAct((Minecraft)mc) && mc.isWindowActive() && !player.isSpectator() && Crystals.isPhysicallyDown((Minecraft)mc, (KeyMapping)mc.options.keyUse);
    }

    private static boolean hotbarTakenByOther() {
        return AutoEatModule.isEating() || AutoInvTotemModule.wantsHotbar();
    }

    private boolean keepEngaged(LocalPlayer player) {
        if (this.ownsSelection(player)) {
            return true;
        }
        this.forgetSlots();
        return CrystalMacroModule.activationAllows(player);
    }

    private boolean ownsSelection(LocalPlayer player) {
        return this.ownSlot != -1 && player.getInventory().getSelectedSlot() == this.ownSlot;
    }

    private static boolean activationAllows(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        ItemStack offhand = player.getOffhandItem();
        if (main.is(Items.END_CRYSTAL) || offhand.is(Items.END_CRYSTAL) && Crystals.isCombatItem((ItemStack)main)) {
            return true;
        }
        return !(!main.is(ItemTags.SWORDS) && !main.is(ItemTags.AXES) && !main.is(Items.MACE) || !offhand.isEmpty() && !offhand.is(Items.TOTEM_OF_UNDYING) || Crystals.hotbarSlotOf((Inventory)player.getInventory(), (Item)Items.END_CRYSTAL) == -1);
    }

    private void endHold(Minecraft mc) {
        CombatMacroLock.release((Object)this);
        if (!this.engaged) {
            return;
        }
        this.engaged = false;
        mc.options.keyUse.setDown(mc.gui.screen() == null && Crystals.isPhysicallyDown((Minecraft)mc, (KeyMapping)mc.options.keyUse));
    }

    private void restoreSlot(LocalPlayer player) {
        if (this.ownSlot == -1) {
            return;
        }
        Inventory inventory = player.getInventory();
        if (this.switchBack.get() && Inventory.isHotbarSlot((int)this.savedSlot) && this.savedSlot != this.ownSlot && inventory.getSelectedSlot() == this.ownSlot) {
            inventory.setSelectedSlot(this.savedSlot);
        }
        this.forgetSlots();
    }

    private void forgetSlots() {
        this.savedSlot = -1;
        this.ownSlot = -1;
    }

    private void resetState() {
        this.engaged = false;
        this.forgetSlots();
        this.ticksSinceAction = 1000;
        this.lastMain = null;
        this.mainAge = 1000;
        this.pendingBreakId = -1;
        this.pendingBreakAge = 1000;
        this.pendingCrystalBase = null;
        this.pendingCrystalAge = 1000;
        this.pendingObsidian = null;
        this.pendingObsidianAge = 1000;
        CombatMacroLock.release((Object)this);
    }

    private static void suppressVanillaUse(Minecraft mc) {
        KeyMapping use = mc.options.keyUse;
        use.setDown(false);
        while (use.consumeClick()) {
        }
    }

    private Plan plan(Minecraft mc, LocalPlayer player) {
        BlockHitResult blockHit;
        HitResult hit = player.raycastHitResult(1.0f, (Entity)player);
        int window = Crystals.confirmWindow((Minecraft)mc, (LocalPlayer)player);
        if (this.pendingCrystalBase != null && Crystals.hasCrystal((Level)mc.level, (BlockPos)this.pendingCrystalBase, null)) {
            this.pendingCrystalBase = null;
        }
        if (hit instanceof EntityHitResult) {
            EndCrystal crystal;
            EntityHitResult entityHit = (EntityHitResult)hit;
            Entity class_12972 = entityHit.getEntity();
            if (!(class_12972 instanceof EndCrystal) || !(crystal = (EndCrystal)class_12972).isAlive()) {
                return null;
            }
            if (crystal.getId() == this.pendingBreakId && this.pendingBreakAge < window) {
                return null;
            }
            if (CrystalDamage.endangers((Player)player, (float)CrystalDamage.estimate((Vec3)crystal.position(), (LivingEntity)player))) {
                return null;
            }
            int weapon = Crystals.weaponRoute((LocalPlayer)player);
            return weapon == -1 ? null : Plan.forBreak(crystal, weapon);
        }
        if (!(hit instanceof BlockHitResult) || (blockHit = (BlockHitResult)hit).getType() != HitResult.Type.BLOCK) {
            return null;
        }
        ClientLevel level = mc.level;
        BlockPos pos = blockHit.getBlockPos();
        if (!level.getWorldBorder().isWithinBounds(pos)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (Crystals.isBase((BlockState)state)) {
            if (pos.equals((Object)this.pendingCrystalBase) && this.pendingCrystalAge < window) {
                return null;
            }
            if (!Crystals.canHost((Level)level, (BlockPos)pos, null)) {
                return null;
            }
            int route = Crystals.route((LocalPlayer)player, (Item)Items.END_CRYSTAL, (BlockState)state, (int)this.savedSlot);
            return route == -1 ? null : Plan.forPlace(Step.CRYSTAL, blockHit, pos, route);
        }
        if (!this.autoObsidian.get()) {
            return null;
        }
        if (Crystals.route((LocalPlayer)player, (Item)Items.END_CRYSTAL, (BlockState)Blocks.OBSIDIAN.defaultBlockState(), (int)this.savedSlot) == -1) {
            return null;
        }
        int route = Crystals.route((LocalPlayer)player, (Item)Items.OBSIDIAN, (BlockState)state, (int)this.savedSlot);
        if (route == -1) {
            return null;
        }
        BlockPos target = Crystals.obsidianTarget((ClientLevel)level, (LocalPlayer)player, (BlockHitResult)blockHit, (BlockState)state);
        if (target == null || target.equals((Object)this.pendingObsidian) && this.pendingObsidianAge < window) {
            return null;
        }
        return Plan.forPlace(Step.OBSIDIAN, blockHit, target, route);
    }

    private void breakCrystal(Minecraft mc, LocalPlayer player, Plan plan) {
        if (this.ticksSinceAction <= this.breakDelay.getInt()) {
            return;
        }
        if (plan.route() != -2) {
            this.select(player, plan.route());
        }
        if (this.mainAge < 2 && !Crystals.enchantsAddDamage((ItemStack)player.getMainHandItem()) && !Crystals.dealsDamage((LivingEntity)player, (ItemStack)ItemStack.EMPTY)) {
            return;
        }
        if (!Crystals.strike((Minecraft)mc, (LocalPlayer)player, (Entity)plan.crystal())) {
            return;
        }
        MeleeAttacks.claimTick((Minecraft)mc);
        this.markBreak(plan.crystal());
        this.ticksSinceAction = 0;
    }

    private boolean useOnCrosshair(Minecraft mc, LocalPlayer player, NumberSetting delay, Plan plan) {
        if (this.ticksSinceAction <= delay.getInt()) {
            return false;
        }
        if (plan.route() != -2) {
            this.select(player, plan.route());
        }
        if (!Crystals.rightClick((Minecraft)mc, (LocalPlayer)player, (BlockHitResult)plan.blockHit())) {
            return false;
        }
        MeleeAttacks.claimTick((Minecraft)mc);
        this.ticksSinceAction = 0;
        return true;
    }

    private void select(LocalPlayer player, int slot) {
        Inventory inventory = player.getInventory();
        if (this.savedSlot == -1) {
            this.savedSlot = inventory.getSelectedSlot();
        }
        inventory.setSelectedSlot(slot);
        this.ownSlot = slot;
        this.lastMain = player.getMainHandItem();
        this.mainAge = 0;
    }

    private void markBreak(EndCrystal crystal) {
        this.pendingBreakId = crystal.getId();
        this.pendingBreakAge = 0;
    }

    private void ageCounters() {
        this.ticksSinceAction = Math.min(this.ticksSinceAction + 1, 1000);
        this.mainAge = Math.min(this.mainAge + 1, 1000);
        this.pendingBreakAge = Math.min(this.pendingBreakAge + 1, 1000);
        this.pendingCrystalAge = Math.min(this.pendingCrystalAge + 1, 1000);
        this.pendingObsidianAge = Math.min(this.pendingObsidianAge + 1, 1000);
    }

    @Environment(value=EnvType.CLIENT)
    private record Plan(Step step, EndCrystal crystal, BlockHitResult blockHit, BlockPos target, int route) {
        static Plan forBreak(EndCrystal crystal, int weapon) {
            return new Plan(Step.BREAK, crystal, null, null, weapon);
        }

        static Plan forPlace(Step step, BlockHitResult blockHit, BlockPos target, int route) {
            return new Plan(step, null, blockHit, target, route);
        }
    }

    @Environment(value=EnvType.CLIENT)
    private static enum Step {
        BREAK,
        CRYSTAL,
        OBSIDIAN;

    }
}
