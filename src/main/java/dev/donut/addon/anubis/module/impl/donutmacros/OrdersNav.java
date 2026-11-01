// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.function.Consumer;
import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.module.impl.donutmacros.Gui;
import dev.donut.addon.anubis.module.impl.donutmacros.MacroWorker;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;

@Environment(value=EnvType.CLIENT)
final class OrdersNav {
    static final int YOUR_ORDERS_BUTTON = 51;
    static final int ORDER_SLOTS = 45;
    private static final long OPEN_TIMEOUT_MS = 10000L;

    private OrdersNav() {
    }

    static boolean openYourOrders(MacroWorker worker, Gui gui, Consumer<ModuleChat.Body> say) {
        gui.closeAll();
        worker.sleep(150L);
        gui.command("orders");
        if (!worker.waitFor(OrdersNav::isOrdersRootNow, 10000L)) {
            say.accept(ModuleChat.body().warn("Could not open /orders."));
            gui.closeAll();
            return false;
        }
        String before = gui.fingerprint();
        gui.click(51, 0, ContainerInput.PICKUP);
        if (worker.waitFor(OrdersNav::isYourOrdersNow, 2500L)) {
            return true;
        }
        if (gui.waitChange(before, 1000L)) {
            worker.sleep(150L);
            if (worker.call(Gui::containerOpenNow).booleanValue() && !worker.call(OrdersNav::isOrdersRootNow).booleanValue()) {
                return true;
            }
        }
        say.accept(ModuleChat.body().warn("Could not open Your Orders."));
        gui.closeAll();
        return false;
    }

    static boolean isOrdersRootNow() {
        if (!Gui.containerOpenNow()) {
            return false;
        }
        String title = ServerText.normalize(Gui.mc().gui.screen().getTitle());
        if (title.startsWith("orders (page") || title.equals("orders")) {
            return true;
        }
        if (title.contains("your orders") || title.contains("auction")) {
            return false;
        }
        if (title.contains("orders")) {
            return true;
        }
        return Gui.nameOf(Gui.slotNow(51)).contains("your order");
    }

    static boolean isYourOrdersNow() {
        if (!Gui.containerOpenNow()) {
            return false;
        }
        String title = ServerText.normalize(Gui.mc().gui.screen().getTitle());
        return title.contains("your orders") || Gui.findNow(OrdersNav::isNewOrderPane, 45) >= 0;
    }

    static boolean isNewOrderPane(ItemStack stack) {
        return stack.is(Items.STAINED_GLASS_PANE.gray()) && Gui.nameOf(stack).contains("new order");
    }

    static boolean isPane(ItemStack stack) {
        return stack.isEmpty() || BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains("stained_glass_pane");
    }
}
