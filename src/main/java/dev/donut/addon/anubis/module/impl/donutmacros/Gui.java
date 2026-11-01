// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import dev.donut.addon.anubis.module.impl.donutmacros.MacroWorker;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;

@Environment(value=EnvType.CLIENT)
final class Gui {
    private static final int COMMAND_GAP_MS = 200;
    final MacroWorker worker;

    Gui(MacroWorker worker) {
        this.worker = worker;
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static LocalPlayer player() {
        LocalPlayer player = Gui.mc().player;
        if (player == null) {
            throw new IllegalStateException("Not in a world");
        }
        return player;
    }

    String title() {
        return this.worker.call(() -> Gui.mc().gui.screen() == null ? "" : ServerText.normalize(Gui.mc().gui.screen().getTitle()));
    }

    static boolean containerOpenNow() {
        LocalPlayer player = Gui.mc().player;
        return player != null && player.containerMenu != player.inventoryMenu && Gui.mc().gui.screen() instanceof AbstractContainerScreen;
    }

    static List<ItemStack> containerNow() {
        ArrayList<ItemStack> out = new ArrayList<ItemStack>();
        LocalPlayer player = Gui.mc().player;
        if (player == null || player.containerMenu == player.inventoryMenu) {
            return out;
        }
        for (Slot slot : player.containerMenu.slots) {
            if (slot.container instanceof Inventory) break;
            out.add(slot.getItem().copy());
        }
        return out;
    }

    List<ItemStack> container() {
        return this.worker.call(Gui::containerNow);
    }

    static String fingerprintNow() {
        StringBuilder text = new StringBuilder();
        LocalPlayer player = Gui.mc().player;
        if (player != null) {
            text.append(player.containerMenu.containerId).append('#');
        }
        for (ItemStack stack : Gui.containerNow()) {
            text.append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append(':').append(stack.getCount()).append(':').append(stack.getHoverName().getString()).append('|');
        }
        return text.toString();
    }

    String fingerprint() {
        return this.worker.call(Gui::fingerprintNow);
    }

    static ItemStack slotNow(int index) {
        List<ItemStack> stacks = Gui.containerNow();
        return index >= 0 && index < stacks.size() ? stacks.get(index) : ItemStack.EMPTY;
    }

    static int findNow(Predicate<ItemStack> test, int limit) {
        List<ItemStack> stacks = Gui.containerNow();
        for (int i = 0; i < stacks.size() && i < limit; ++i) {
            if (stacks.get(i).isEmpty() || !test.test(stacks.get(i))) continue;
            return i;
        }
        return -1;
    }

    static String nameOf(ItemStack stack) {
        return ServerText.normalize(stack.getHoverName());
    }

    void click(int slot, int button, ContainerInput type) {
        this.worker.run(() -> {
            LocalPlayer player = Gui.player();
            AbstractContainerMenu menu = player.containerMenu;
            Gui.mc().gameMode.handleContainerInput(menu.containerId, slot, button, type, (Player)player);
        });
    }

    void clickInventory(int slot, int button, ContainerInput type) {
        this.worker.run(() -> {
            LocalPlayer player = Gui.player();
            Gui.mc().gameMode.handleContainerInput(player.inventoryMenu.containerId, slot, button, type, (Player)player);
        });
    }

    void closeInventory() {
        this.worker.run(() -> {
            LocalPlayer player = Gui.player();
            if (player.containerMenu == player.inventoryMenu) {
                player.connection.send((Packet)new ServerboundContainerClosePacket(player.inventoryMenu.containerId));
            }
        });
    }

    void command(String command) {
        this.worker.run(() -> Gui.player().connection.sendCommand(command));
        this.worker.sleep(200L);
    }

    void closeAll() {
        for (int i = 0; i < 4; ++i) {
            boolean open = this.worker.call(() -> {
                LocalPlayer player = Gui.mc().player;
                if (player == null) {
                    return false;
                }
                if (player.containerMenu != player.inventoryMenu) {
                    player.closeContainer();
                    return true;
                }
                if (Gui.mc().gui.screen() != null && !(Gui.mc().gui.screen() instanceof DeathScreen)) {
                    Gui.mc().gui.screen().onClose();
                    return true;
                }
                return false;
            });
            if (!open) {
                return;
            }
            this.worker.ticks(2);
        }
    }

    boolean waitChange(String before, long timeoutMs) {
        return this.worker.waitFor(() -> !Gui.fingerprintNow().equals(before), timeoutMs);
    }
}
