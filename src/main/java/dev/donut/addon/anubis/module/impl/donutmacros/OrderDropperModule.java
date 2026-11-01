// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.List;
import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.module.ClientModule;
import dev.donut.addon.anubis.module.ModuleCategory;
import dev.donut.addon.anubis.module.impl.donutmacros.AuctionHouse;
import dev.donut.addon.anubis.module.impl.donutmacros.Gui;
import dev.donut.addon.anubis.module.impl.donutmacros.MacroWorker;
import dev.donut.addon.anubis.module.impl.donutmacros.OrdersNav;
import dev.donut.addon.anubis.module.setting.NumberSetting;
import dev.donut.addon.anubis.module.setting.Setting;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(value=EnvType.CLIENT)
public final class OrderDropperModule
extends ClientModule {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"Anubis");
    private static final int ITEM_SLOTS = 45;
    private static final int DEFAULT_COLLECT_BUTTON = 13;
    private static final int DEFAULT_DROP_BUTTON = 52;
    private static final int DEFAULT_NEXT_BUTTON = 53;
    private static final int MAX_EMPTY_FLIPS = 2;
    private static final long NEXT_PAGE_SETTLE_MS = 250L;
    private static final long OPEN_TIMEOUT_MS = 5000L;
    private static final long PAGE_TIMEOUT_MS = 4000L;
    private final NumberSetting orderSlot = (NumberSetting)this.addSetting("order-slot", (Setting)new NumberSetting("Order Slot (0 = first)", 0.0f, 0.0f, 44.0f).step(1.0f));
    private final NumberSetting pages = (NumberSetting)this.addSetting("pages", (Setting)new NumberSetting("Pages To Drop", 1.0f, 1.0f, 100.0f).step(1.0f));
    private final NumberSetting delay = (NumberSetting)this.addSetting("delay", (Setting)new NumberSetting("Drop Delay (s)", 0.5f, 0.25f, 5.0f).step(0.25f));
    private final MacroWorker worker = new MacroWorker("Anubis Order Dropper");
    private volatile boolean running;
    private long generation;

    public OrderDropperModule() {
        super("order-dropper", "Order Dropper", "Opens one of your orders, collects it and drops the chosen number of pages, then turns off.", ModuleCategory.DONUT_MACROS);
        this.usesNotifications();
    }

    public boolean persistEnabled() {
        return false;
    }

    protected void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            this.say(ModuleChat.body().warn("Join a server first."));
            this.requestDisable();
            return;
        }
        int slot = this.orderSlot.getInt();
        int wanted = this.pages.getInt();
        long delayMs = (long)(this.delay.get() * 1000.0f);
        long runGeneration = ++this.generation;
        this.running = true;
        this.worker.start(() -> {
            try {
                this.drop(new Gui(this.worker), slot, wanted, delayMs);
            }
            catch (AuctionHouse.Problem problem) {
                this.say(ModuleChat.body().warn(problem.getMessage()).text("."));
            }
            finally {
                this.finish(mc, runGeneration);
            }
        }, failure -> {
            LOGGER.error("[Anubis] Order Dropper stopped", failure);
            this.say(ModuleChat.body().danger("Stopped").text(": " + String.valueOf(failure)));
            this.finish(mc, runGeneration);
        });
    }

    private void finish(Minecraft mc, long runGeneration) {
        mc.execute(() -> {
            if (this.generation == runGeneration && this.running && this.isEnabled()) {
                this.setEnabled(false);
            }
        });
    }

    protected void onDisable() {
        ++this.generation;
        boolean wasRunning = this.running;
        this.running = false;
        this.worker.stop();
        LocalPlayer player = Minecraft.getInstance().player;
        if (wasRunning && player != null && player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
    }

    private void drop(Gui gui, int slot, int wanted, long delayMs) {
        ItemStack[] first = new ItemStack[]{ItemStack.EMPTY};
        String orderName = this.openCollect(gui, slot, first);
        int dropped = 0;
        int items = 0;
        for (int page = 0; page < wanted; ++page) {
            if (!this.worker.call(Gui::containerOpenNow).booleanValue()) {
                orderName = this.openCollect(gui, slot, first);
            }
            int[] state = this.worker.call(OrderDropperModule::pageState);
            for (int flips = 0; state[0] == 0 && state[2] >= 0 && flips < 2; ++flips) {
                String beforeNext = gui.fingerprint();
                gui.click(state[2], 0, ContainerInput.PICKUP);
                gui.waitChange(beforeNext, 4000L);
                this.worker.sleep(250L);
                state = this.worker.call(OrderDropperModule::pageState);
            }
            if (state[0] == 0) {
                this.say(ModuleChat.body().text("The order is empty."));
                break;
            }
            if (state[1] < 0) {
                throw new AuctionHouse.Problem("No Drop button in the collect menu");
            }
            String before = gui.fingerprint();
            gui.click(state[1], 0, ContainerInput.PICKUP);
            ++dropped;
            items += state[0];
            if (page >= wanted - 1) continue;
            gui.waitChange(before, 4000L);
            this.worker.sleep(delayMs);
        }
        this.worker.sleep(300L);
        gui.closeAll();
        this.say(ModuleChat.body().good("Dropped " + dropped + (dropped == 1 ? " page" : " pages")).text(" (").value((Object)items).text(" items) from ").name(orderName).text("."));
    }

    private String openCollect(Gui gui, int slot, ItemStack[] first) {
        if (!OrdersNav.openYourOrders(this.worker, gui, this::say)) {
            throw new AuctionHouse.Problem("Could not open Your Orders");
        }
        ItemStack order = this.worker.call(() -> Gui.slotNow(slot));
        if (OrdersNav.isPane(order)) {
            throw new AuctionHouse.Problem("There is no order in slot " + slot);
        }
        String name = order.getHoverName().getString();
        if (first[0].isEmpty()) {
            first[0] = order.copy();
        } else if (!order.is(first[0].getItem()) || !name.equals(first[0].getHoverName().getString())) {
            gui.closeAll();
            throw new AuctionHouse.Problem("Slot " + slot + " now holds a different order (" + name + "), stopped so nothing else is dropped");
        }
        String before = gui.fingerprint();
        gui.click(slot, 0, ContainerInput.PICKUP);
        if (!this.worker.waitFor(() -> Gui.containerOpenNow() && !OrdersNav.isYourOrdersNow() && !Gui.fingerprintNow().equals(before), 5000L)) {
            throw new AuctionHouse.Problem("The order in slot " + slot + " did not open");
        }
        this.worker.sleep(300L);
        int collect = this.worker.call(() -> {
            List<ItemStack> stacks = Gui.containerNow();
            for (int i = 0; i < stacks.size(); ++i) {
                if (stacks.get(i).isEmpty() || !Gui.nameOf(stacks.get(i)).contains("collect")) continue;
                return i;
            }
            return stacks.size() > 13 && !stacks.get(13).isEmpty() ? 13 : -1;
        });
        if (collect < 0) {
            throw new AuctionHouse.Problem("No Collect button in the order");
        }
        String beforeCollect = gui.fingerprint();
        gui.click(collect, 0, ContainerInput.PICKUP);
        if (!this.worker.waitFor(() -> Gui.containerOpenNow() && !Gui.fingerprintNow().equals(beforeCollect) && OrderDropperModule.dropButton(Gui.containerNow()) >= 0, 5000L)) {
            throw new AuctionHouse.Problem("The collect menu did not open");
        }
        this.worker.sleep(300L);
        return name;
    }

    private static int[] pageState() {
        List<ItemStack> stacks = Gui.containerNow();
        int count = 0;
        for (int i = 0; i < 45 && i < stacks.size(); ++i) {
            count += stacks.get(i).getCount();
        }
        return new int[]{count, OrderDropperModule.dropButton(stacks), OrderDropperModule.nextButton(stacks)};
    }

    private static int nextButton(List<ItemStack> stacks) {
        for (int i = 45; i < stacks.size(); ++i) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !Gui.nameOf(stack).contains("next")) continue;
            return i;
        }
        return stacks.size() > 53 && stacks.get(53).is(Items.ARROW) ? 53 : -1;
    }

    private static int dropButton(List<ItemStack> stacks) {
        for (int i = 45; i < stacks.size(); ++i) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !stack.is(Items.DROPPER) && !Gui.nameOf(stack).equals("drop")) continue;
            return i;
        }
        return stacks.size() > 52 && stacks.get(52).is(Items.DROPPER) ? 52 : -1;
    }

    private void say(ModuleChat.Body body) {
        ModuleChat.send((ClientModule)this, (ModuleChat.Body)body);
    }
}
