// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.combat;

import dev.donut.addon.anubis.event.EventHandler;
import dev.donut.addon.anubis.event.impl.PacketAppliedEvent;
import dev.donut.addon.anubis.event.impl.TickEvent;
import dev.donut.addon.anubis.module.ClientModule;
import dev.donut.addon.anubis.module.CombatMacroLock;
import dev.donut.addon.anubis.module.ModuleCategory;
import dev.donut.addon.anubis.module.impl.combat.AutoInvTotemModule;
import dev.donut.addon.anubis.module.impl.combat.anchor.AnchorGuard;
import dev.donut.addon.anubis.module.impl.combat.crystal.CrystalDamage;
import dev.donut.addon.anubis.module.impl.combat.crystal.Crystals;
import dev.donut.addon.anubis.module.impl.combat.crystal.LootLatch;
import dev.donut.addon.anubis.module.impl.combat.melee.MeleeAttacks;
import dev.donut.addon.anubis.module.impl.misc.AutoEatModule;
import dev.donut.addon.anubis.module.setting.BooleanSetting;
import dev.donut.addon.anubis.module.setting.ModeSetting;
import dev.donut.addon.anubis.module.setting.NumberSetting;
import dev.donut.addon.anubis.module.setting.Setting;
import dev.donut.addon.anubis.util.pvp.Rotation;
import dev.donut.addon.anubis.util.pvp.RotationUtils;
import dev.donut.addon.anubis.util.pvp.ServerRotation;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

@Environment(value=EnvType.CLIENT)
public final class SingleAnchorMacroModule
extends ClientModule {
    private static final String SINGLE = "Single";
    private static final String DOUBLE = "Double";
    private static final String DETONATE = "Detonate";
    private static final String CANCEL = "Cancel";
    private static final int MAX_STEPS_PER_TICK = 4;
    private static final int GUARD_TRIES = 2;
    private static final int SETTLE_TICKS = 5;
    private static final float BLAST_PROOF = 600.0f;
    private final ModeSetting mode = (ModeSetting)this.addSetting("mode", (Setting)new ModeSetting("Mode", "Single", new String[]{"Single", "Double"}));
    private final NumberSetting delay = (NumberSetting)this.addSetting("delay", (Setting)new NumberSetting("Delay", 0.0f, 0.0f, 5.0f).step(1.0f));
    private final BooleanSetting blastGuard = (BooleanSetting)this.addSetting("safe-anchor", (Setting)new BooleanSetting("Safe Anchor", true));
    private final ModeSetting withoutGuard = (ModeSetting)this.addSetting("without-guard", (Setting)new ModeSetting("If Unguarded", "Detonate", new String[]{"Detonate", "Cancel"}));
    private final BooleanSetting switchBack = (BooleanSetting)this.addSetting("switch-back", (Setting)new BooleanSetting("Switch Back", false));
    private Step step = Step.IDLE;
    private int tick;
    private int readyAt;
    private int deadline;
    private int previousSlot = -1;
    private int ownSlot = -1;
    private boolean refilled;
    private BlockPos anchorPos;
    private Vec3 sightline;
    private ClientLevel activeLevel;
    private AnchorGuard.Spot guard;
    private Block guardBlock;
    private BlockState guardReported;
    private int guardTries;

    public SingleAnchorMacroModule() {
        super("anchor-macro", "Anchor Macro", "Places, charges and blows up respawn anchors on right-click.", ModuleCategory.COMBAT);
        this.withoutGuard.visibleWhen(() -> ((BooleanSetting)this.blastGuard).get());
    }

    protected void onEnable() {
        this.stop();
    }

    protected void onDisable() {
        Inventory inventory;
        Minecraft mc = Minecraft.getInstance();
        if (this.step != Step.IDLE && mc.player != null && !(mc.gui.screen() instanceof AbstractContainerScreen) && this.switchBackDue(inventory = mc.player.getInventory())) {
            inventory.setSelectedSlot(this.previousSlot);
        }
        this.stop();
    }

    @EventHandler
    public void onTick(TickEvent event) {
        ++this.tick;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null || player.isDeadOrDying()) {
            this.stop();
            return;
        }
        LootLatch.update((Minecraft)mc, (LocalPlayer)player);
        Inventory inventory = player.getInventory();
        if (mc.gui.screen() != null) {
            if (this.step != Step.IDLE && this.step != Step.SWITCH_BACK) {
                this.wrapUp(inventory);
            }
            return;
        }
        if (AutoEatModule.isEating() || AutoInvTotemModule.wantsHotbar()) {
            this.stop();
            return;
        }
        if (this.step != Step.IDLE && (this.hotbarMovedByPlayer(inventory) || mc.level != this.activeLevel)) {
            this.stop();
        }
        if ((LootLatch.latched() || LootLatch.killedNow()) && this.step != Step.IDLE && this.step != Step.SWITCH_BACK) {
            this.wrapUp(inventory);
        }
        if (LootLatch.latched() && this.step == Step.IDLE) {
            if (!player.isUsingItem() && SingleAnchorMacroModule.anchorInPlay(mc, player)) {
                SingleAnchorMacroModule.suppressUse(mc);
            }
            return;
        }
        if (this.step == Step.IDLE && !this.tryStart(mc, player)) {
            return;
        }
        SingleAnchorMacroModule.swallowClicks(mc);
        if (this.step == Step.SWITCH_BACK) {
            if (!MeleeAttacks.actedThisTick((Player)player)) {
                if (this.switchBackDue(inventory)) {
                    inventory.setSelectedSlot(this.previousSlot);
                }
                this.stop();
            }
            return;
        }
        for (int i = 0; i < 4 && this.tick >= this.readyAt && this.step != Step.IDLE && this.step != Step.SWITCH_BACK; ++i) {
            if (this.tick >= this.deadline) {
                this.expire(inventory);
                continue;
            }
            if (MeleeAttacks.actedThisTick((Player)player) || !MeleeAttacks.handsFree((Minecraft)mc, (LocalPlayer)player)) break;
            this.runStep(mc, player);
        }
    }

    @EventHandler
    public void onPacketApplied(PacketAppliedEvent event) {
        if (this.step != Step.AWAIT_GUARD || this.guard == null) {
            return;
        }
        BlockPos spot = this.guard.pos();
        Packet class_25962 = event.packet;
        if (class_25962 instanceof ClientboundBlockUpdatePacket) {
            ClientboundBlockUpdatePacket update = (ClientboundBlockUpdatePacket)class_25962;
            if (update.getPos().equals((Object)spot)) {
                this.guardReported = update.getBlockState();
            }
        } else {
            class_25962 = event.packet;
            if (class_25962 instanceof ClientboundSectionBlocksUpdatePacket) {
                ClientboundSectionBlocksUpdatePacket section = (ClientboundSectionBlocksUpdatePacket)class_25962;
                section.runUpdates((pos, state) -> {
                    if (pos.equals((Object)spot)) {
                        this.guardReported = state;
                    }
                });
            }
        }
    }

    private boolean tryStart(Minecraft mc, LocalPlayer player) {
        Step first;
        boolean charged;
        BlockPos target;
        BlockHitResult hit;
        if (!MeleeAttacks.rightClickComing((Minecraft)mc) || CombatMacroLock.heldByOther((Object)this)) {
            return false;
        }
        if (player.isSpectator() || !SingleAnchorMacroModule.canClick(player) || MeleeAttacks.actedThisTick((Player)player) || !MeleeAttacks.handsFree((Minecraft)mc, (LocalPlayer)player) || player.getOffhandItem().is(Items.GLOWSTONE)) {
            return false;
        }
        HitResult ItemParticleOption = player.pick(player.blockInteractionRange(), 1.0f, false);
        if (!(ItemParticleOption instanceof BlockHitResult) || (hit = (BlockHitResult)ItemParticleOption).getType() != HitResult.Type.BLOCK) {
            return false;
        }
        ClientLevel level = mc.level;
        BlockState clicked = level.getBlockState(hit.getBlockPos());
        boolean onAnchor = clicked.is(Blocks.RESPAWN_ANCHOR);
        if (onAnchor) {
            target = hit.getBlockPos().immutable();
        } else if (player.getMainHandItem().is(Items.RESPAWN_ANCHOR) && !ServerRotation.pending()) {
            target = Crystals.placedObsidian((ClientLevel)level, (LocalPlayer)player, (BlockHitResult)hit, (BlockState)clicked);
        } else {
            return false;
        }
        if (target == null || level.dimension() == Level.NETHER) {
            return false;
        }
        Inventory inventory = player.getInventory();
        boolean bl = charged = SingleAnchorMacroModule.chargeOf(clicked) > 0;
        if (!charged && SingleAnchorMacroModule.findHotbar(inventory, Items.GLOWSTONE) == -1) {
            return false;
        }
        if (AnchorGuard.detonatorSlot((Inventory)inventory) == -1) {
            return false;
        }
        if (this.blastGuard.get() && this.withoutGuard.is(CANCEL) && SingleAnchorMacroModule.guardSlot(inventory, this.glowstoneNeeded(charged)) == -1) {
            return false;
        }
        if (!CombatMacroLock.tryAcquire((Object)this)) {
            return false;
        }
        this.previousSlot = inventory.getSelectedSlot();
        this.activeLevel = level;
        this.anchorPos = target;
        this.sightline = hit.getLocation();
        Step step = first = this.blastGuard.get() ? Step.GUARD : Step.CHARGE;
        if (onAnchor) {
            this.scheduleNow(first);
        } else {
            AnchorGuard.click((Minecraft)mc, (LocalPlayer)player, (BlockHitResult)hit);
            this.schedule(first);
        }
        return true;
    }

    private static void swallowClicks(Minecraft mc) {
        MeleeAttacks.takeAttackClicks((Minecraft)mc);
        SingleAnchorMacroModule.suppressUse(mc);
    }

    private static boolean anchorInPlay(Minecraft mc, LocalPlayer player) {
        BlockHitResult hit;
        ItemStack main = player.getMainHandItem();
        if (main.is(Items.RESPAWN_ANCHOR) || main.is(Items.GLOWSTONE)) {
            return true;
        }
        HitResult ItemParticleOption = mc.hitResult;
        return ItemParticleOption instanceof BlockHitResult && (hit = (BlockHitResult)ItemParticleOption).getType() == HitResult.Type.BLOCK && mc.level.getBlockState(hit.getBlockPos()).is(Blocks.RESPAWN_ANCHOR);
    }

    private static void suppressUse(Minecraft mc) {
        KeyMapping use = mc.options.keyUse;
        use.setDown(false);
        while (use.consumeClick()) {
        }
    }

    private void runStep(Minecraft mc, LocalPlayer player) {
        switch (this.step.ordinal()) {
            case 1: {
                this.placeGuard(mc, player);
                break;
            }
            case 2: {
                this.awaitGuard(mc, player);
                break;
            }
            case 3: {
                this.charge(mc, player);
                break;
            }
            case 4: {
                this.detonate(mc, player);
            }
        }
    }

    private void expire(Inventory inventory) {
        if (this.step == Step.GUARD || this.step == Step.AWAIT_GUARD) {
            this.skipGuard(inventory);
        } else {
            this.wrapUp(inventory);
        }
    }

    private void placeGuard(Minecraft mc, LocalPlayer player) {
        BlockHitResult hit;
        Inventory inventory = player.getInventory();
        BlockState anchor = mc.level.getBlockState(this.anchorPos);
        if (!anchor.is(Blocks.RESPAWN_ANCHOR)) {
            this.wrapUp(inventory);
            return;
        }
        AnchorGuard.Spot spot = AnchorGuard.find((ClientLevel)mc.level, (LocalPlayer)player, (BlockPos)this.anchorPos, (Vec3)this.sightline);
        if (spot != null && spot.standing()) {
            this.guarded();
            return;
        }
        int slot = SingleAnchorMacroModule.guardSlot(inventory, this.glowstoneNeeded(SingleAnchorMacroModule.chargeOf(anchor) > 0));
        Rotation sent = ServerRotation.sent();
        BlockHitResult class_39652 = hit = spot == null || sent == null ? null : AnchorGuard.hitAlong((ClientLevel)mc.level, (LocalPlayer)player, (Rotation)sent, (AnchorGuard.Spot)spot);
        if (spot == null || slot == -1 || hit == null && !ServerRotation.canRotate((LocalPlayer)player)) {
            this.skipGuard(inventory);
            return;
        }
        if (hit == null) {
            ServerRotation.request((Rotation)RotationUtils.fromTo((Vec3)player.getEyePosition(), (Vec3)spot.aim()), this);
            MeleeAttacks.claimTick((Minecraft)mc);
            this.readyAt = this.tick + 1;
            return;
        }
        this.guard = spot;
        this.guardBlock = Block.byItem((Item)inventory.getItem(slot).getItem());
        this.guardReported = null;
        this.select(inventory, slot);
        AnchorGuard.click((Minecraft)mc, (LocalPlayer)player, (BlockHitResult)hit);
        ServerRotation.request((Rotation)RotationUtils.fromTo((Vec3)player.getEyePosition(), (Vec3)Vec3.atCenterOf((Vec3i)this.anchorPos)), this);
        ++this.guardTries;
        if (this.withoutGuard.is(CANCEL)) {
            this.schedule(Step.AWAIT_GUARD);
            this.deadline = this.readyAt + Crystals.confirmWindow((Minecraft)mc, (LocalPlayer)player);
        } else {
            this.schedule(Step.CHARGE);
        }
    }

    private void awaitGuard(Minecraft mc, LocalPlayer player) {
        boolean shown = mc.level.getBlockState(this.guard.pos()).is(this.guardBlock);
        if (shown && this.guardReported != null && this.guardReported.is(this.guardBlock)) {
            this.guarded();
        } else if (shown) {
            this.readyAt = this.tick + 1;
        } else if (this.guardTries < 2) {
            this.scheduleNow(Step.GUARD);
        } else {
            this.skipGuard(player.getInventory());
        }
    }

    private void guarded() {
        this.guard = null;
        this.scheduleNow(Step.CHARGE);
    }

    private void skipGuard(Inventory inventory) {
        this.guard = null;
        if (this.withoutGuard.is(CANCEL)) {
            this.wrapUp(inventory);
        } else {
            this.scheduleNow(Step.CHARGE);
        }
    }

    private void charge(Minecraft mc, LocalPlayer player) {
        Inventory inventory = player.getInventory();
        BlockState state = mc.level.getBlockState(this.anchorPos);
        if (!state.is(Blocks.RESPAWN_ANCHOR)) {
            this.wrapUp(inventory);
            return;
        }
        if (SingleAnchorMacroModule.chargeOf(state) > 0 && !this.refilled) {
            this.scheduleNow(Step.DETONATE);
            return;
        }
        int glowstone = SingleAnchorMacroModule.findHotbar(inventory, Items.GLOWSTONE);
        if (glowstone == -1 || !SingleAnchorMacroModule.canClick(player)) {
            this.wrapUp(inventory);
            return;
        }
        BlockHitResult hit = AnchorGuard.anchorHit((ClientLevel)mc.level, (LocalPlayer)player, (BlockPos)this.anchorPos);
        if (hit == null) {
            this.readyAt = this.tick + 1;
            return;
        }
        this.select(inventory, glowstone);
        AnchorGuard.click((Minecraft)mc, (LocalPlayer)player, (BlockHitResult)hit);
        this.schedule(Step.DETONATE);
    }

    private void detonate(Minecraft mc, LocalPlayer player) {
        int slot;
        Inventory inventory = player.getInventory();
        if (SingleAnchorMacroModule.chargeOf(mc.level.getBlockState(this.anchorPos)) == 0 || !SingleAnchorMacroModule.canClick(player) || player.getOffhandItem().is(Items.GLOWSTONE)) {
            this.wrapUp(inventory);
            return;
        }
        BlockHitResult hit = AnchorGuard.anchorHit((ClientLevel)mc.level, (LocalPlayer)player, (BlockPos)this.anchorPos);
        if (hit == null) {
            this.readyAt = this.tick + 1;
            return;
        }
        int refillSlot = this.mode.is(DOUBLE) && !this.refilled && hit.getDirection() == Direction.UP && this.anchorPos.getY() >= player.getBlockY() && SingleAnchorMacroModule.findHotbar(inventory, Items.GLOWSTONE) != -1 ? SingleAnchorMacroModule.findHotbar(inventory, Items.RESPAWN_ANCHOR) : -1;
        int n = slot = refillSlot != -1 ? refillSlot : AnchorGuard.detonatorSlot((Inventory)inventory);
        if (slot == -1) {
            this.wrapUp(inventory);
            return;
        }
        if (this.lethal(player, inventory.getItem(slot))) {
            this.wrapUp(inventory);
            return;
        }
        this.select(inventory, slot);
        AnchorGuard.click((Minecraft)mc, (LocalPlayer)player, (BlockHitResult)hit);
        if (slot == refillSlot) {
            AnchorGuard.click((Minecraft)mc, (LocalPlayer)player, (BlockHitResult)hit);
            this.refilled = true;
            this.schedule(Step.CHARGE);
        } else {
            this.wrapUp(inventory);
        }
    }

    private boolean lethal(LocalPlayer player, ItemStack held) {
        if (held.is(Items.TOTEM_OF_UNDYING) || player.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) {
            return false;
        }
        float damage = CrystalDamage.estimate((Vec3)Vec3.atCenterOf((Vec3i)this.anchorPos), (LivingEntity)player, (float)5.0f, (BlockPos)this.anchorPos);
        return CrystalDamage.endangers((Player)player, (float)damage);
    }

    private void wrapUp(Inventory inventory) {
        if (this.switchBackDue(inventory)) {
            this.step = Step.SWITCH_BACK;
        } else {
            this.stop();
        }
    }

    private boolean switchBackDue(Inventory inventory) {
        return this.switchBack.get() && Inventory.isHotbarSlot((int)this.previousSlot) && this.ownSlot != -1 && this.ownSlot != this.previousSlot && inventory.getSelectedSlot() == this.ownSlot;
    }

    private boolean hotbarMovedByPlayer(Inventory inventory) {
        return inventory.getSelectedSlot() != (this.ownSlot != -1 ? this.ownSlot : this.previousSlot);
    }

    private void stop() {
        this.step = Step.IDLE;
        this.anchorPos = null;
        this.sightline = null;
        this.activeLevel = null;
        this.previousSlot = -1;
        this.ownSlot = -1;
        this.refilled = false;
        this.guard = null;
        this.guardBlock = null;
        this.guardReported = null;
        this.guardTries = 0;
        CombatMacroLock.release((Object)this);
    }

    private void schedule(Step next) {
        this.step = next;
        this.readyAt = this.tick + 1 + this.delay.getInt();
        this.deadline = this.readyAt + 5;
    }

    private void scheduleNow(Step next) {
        this.step = next;
        this.readyAt = this.tick;
        this.deadline = this.tick + 5;
    }

    private void select(Inventory inventory, int slot) {
        if (inventory.getSelectedSlot() == slot) {
            return;
        }
        inventory.setSelectedSlot(slot);
        this.ownSlot = slot;
    }

    private static boolean canClick(LocalPlayer player) {
        return !player.isSecondaryUseActive();
    }

    private static int chargeOf(BlockState state) {
        return state.is(Blocks.RESPAWN_ANCHOR) ? (Integer)state.getValue((Property)RespawnAnchorBlock.CHARGE) : 0;
    }

    private int glowstoneNeeded(boolean charged) {
        int needed = charged ? 0 : 1;
        return this.mode.is(DOUBLE) ? needed + 1 : needed;
    }

    private static int guardSlot(Inventory inventory, int glowstoneNeeded) {
        int weaker = -1;
        for (int i = 0; i < 9; ++i) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(Items.GLOWSTONE) || !AnchorGuard.isGuardBlock((ItemStack)stack)) continue;
            if (Block.byItem((Item)stack.getItem()).getExplosionResistance() >= 600.0f) {
                return i;
            }
            if (weaker != -1) continue;
            weaker = i;
        }
        if (weaker != -1) {
            return weaker;
        }
        int glowstone = SingleAnchorMacroModule.findHotbar(inventory, Items.GLOWSTONE);
        return glowstone != -1 && SingleAnchorMacroModule.countHotbar(inventory, Items.GLOWSTONE) > glowstoneNeeded ? glowstone : -1;
    }

    private static int findHotbar(Inventory inventory, Item item) {
        for (int i = 0; i < 9; ++i) {
            if (!inventory.getItem(i).is(item)) continue;
            return i;
        }
        return -1;
    }

    private static int countHotbar(Inventory inventory, Item item) {
        int count = 0;
        for (int i = 0; i < 9; ++i) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.is(item)) continue;
            count += stack.getCount();
        }
        return count;
    }

    @Environment(value=EnvType.CLIENT)
    private static enum Step {
        IDLE,
        GUARD,
        AWAIT_GUARD,
        CHARGE,
        DETONATE,
        SWITCH_BACK;

    }
}
