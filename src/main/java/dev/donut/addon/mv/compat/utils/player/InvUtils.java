package dev.donut.addon.mv.compat.utils.player;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

/** Inventory actions used by AH Sniper, implemented through Minecraft's native container input. */
public final class InvUtils {
    private static final Action MOVE_ACTION = new Action();
    private static int previousSlot = -1;

    private InvUtils() {
    }

    /** Selects a hotbar slot and sends the change to the server. */
    public static boolean swap(int slot, boolean swapBack) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null || slot < 0 || slot >= Inventory.getSelectionSize()) return false;
        if (swapBack && previousSlot == -1) previousSlot = minecraft.player.getInventory().getSelectedSlot();
        else if (!swapBack) previousSlot = -1;

        minecraft.player.getInventory().setSelectedSlot(slot);
        if (minecraft.getConnection() != null) minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        return true;
    }

    public static boolean swapBack() {
        if (previousSlot == -1) return false;
        boolean swapped = swap(previousSlot, false);
        previousSlot = -1;
        return swapped;
    }

    public static Action move() {
        MOVE_ACTION.prepareMove();
        return MOVE_ACTION;
    }

    public static final class Action {
        private int from = -1;
        private boolean twoClicks;

        private Action() {
        }

        private void prepareMove() {
            from = -1;
            twoClicks = true;
        }

        public Action from(int inventoryIndex) {
            from = inventorySlotId(inventoryIndex);
            return this;
        }

        public void toHotbar(int hotbarIndex) {
            if (from < 0 || hotbarIndex < 0 || hotbarIndex >= Inventory.getSelectionSize()) {
                reset();
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.gameMode == null) {
                reset();
                return;
            }

            AbstractContainerMenu menu = minecraft.player.containerMenu;
            boolean hadEmptyCursor = menu.getCarried().isEmpty();
            int originalSource = from;
            click(originalSource, 0);
            if (twoClicks) click(36 + hotbarIndex, 0);
            if (hadEmptyCursor && twoClicks && !menu.getCarried().isEmpty()) click(originalSource, 0);
            reset();
        }

        private static int inventorySlotId(int inventoryIndex) {
            if (inventoryIndex < 0 || inventoryIndex >= 36) return -1;
            return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
        }

        private static void click(int slot, int button) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.gameMode == null) return;
            AbstractContainerMenu menu = minecraft.player.containerMenu;
            minecraft.gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP, minecraft.player);
        }

        private void reset() {
            from = -1;
            twoClicks = false;
        }
    }
}
