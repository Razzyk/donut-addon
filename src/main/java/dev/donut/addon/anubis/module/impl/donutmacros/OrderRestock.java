// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.module.impl.donutmacros.AuctionHouse;
import dev.donut.addon.anubis.module.impl.donutmacros.Dialogs;
import dev.donut.addon.anubis.module.impl.donutmacros.Gui;
import dev.donut.addon.anubis.module.impl.donutmacros.MacroWorker;
import dev.donut.addon.anubis.module.impl.donutmacros.OrderAhBot;
import dev.donut.addon.anubis.module.impl.donutmacros.OrdersNav;
import dev.donut.addon.anubis.module.impl.donutmacros.Product;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

@Environment(value=EnvType.CLIENT)
final class OrderRestock {
    private static final int ORDER_SLOTS = 45;
    private static final int DEFAULT_COLLECT_BUTTON = 13;
    private static final long OPEN_TIMEOUT_MS = 10000L;
    private static final long DIALOG_TIMEOUT_MS = 8000L;
    private static final long PICK_TIMEOUT_MS = 5000L;
    private final OrderAhBot bot;
    private final MacroWorker worker;
    private final Gui gui;
    private final Product product;
    private long nextCheckAt;

    OrderRestock(OrderAhBot bot) {
        this.bot = bot;
        this.worker = bot.worker;
        this.gui = bot.gui;
        this.product = bot.product;
    }

    private void say(ModuleChat.Body body) {
        this.bot.say(body);
    }

    private static AuctionHouse.Problem fail(String message) {
        return new AuctionHouse.Problem(message);
    }

    boolean run() {
        long now = System.currentTimeMillis();
        if (now < this.nextCheckAt) {
            return false;
        }
        this.nextCheckAt = now + this.bot.config.orderCheckMs();
        if (!this.openYourOrders()) {
            return false;
        }
        int own = this.worker.call(() -> Gui.findNow(stack -> stack.is(this.product.item()), 45));
        if (own >= 0) {
            return this.collect(own) > 0;
        }
        this.gui.closeAll();
        long price = this.orderPrice();
        if (price <= 0L) {
            return false;
        }
        this.create(price);
        return false;
    }

    private boolean openYourOrders() {
        return OrdersNav.openYourOrders(this.worker, this.gui, this::say);
    }

    private boolean isYourOrdersNow() {
        return OrdersNav.isYourOrdersNow();
    }

    private static boolean isNewOrderPane(ItemStack stack) {
        return OrdersNav.isNewOrderPane(stack);
    }

    private int collect(int ownSlot) {
        String before = this.gui.fingerprint();
        this.gui.click(ownSlot, 0, ContainerInput.PICKUP);
        boolean opened = this.worker.waitFor(() -> Gui.containerOpenNow() && !this.isYourOrdersNow() && !Gui.fingerprintNow().equals(before), 2000L);
        if (!opened) {
            this.say(ModuleChat.body().warn("Your order did not open."));
            this.gui.closeAll();
            return 0;
        }
        this.worker.sleep(1000L);
        int[] controls = this.worker.call(() -> {
            List<ItemStack> stacks = Gui.containerNow();
            int collectAt = -1;
            for (int i = 0; i < stacks.size(); ++i) {
                ItemStack stack = stacks.get(i);
                if (stack.isEmpty()) continue;
                String name = Gui.nameOf(stack);
                if (stack.is(Items.DYED_TERRACOTTA.red()) || name.contains("cancel")) {
                    return new int[]{1, -1};
                }
                if (collectAt >= 0 || !name.contains("collect")) continue;
                collectAt = i;
            }
            if (collectAt < 0 && stacks.size() > 13 && !stacks.get(13).isEmpty()) {
                collectAt = 13;
            }
            return new int[]{0, collectAt};
        });
        if (controls[0] == 1) {
            this.say(ModuleChat.body().text("Your ").name(this.product.displayName()).text(" order is still filling."));
            this.gui.closeAll();
            return 0;
        }
        if (controls[1] < 0) {
            this.say(ModuleChat.body().warn("No collect button in your order."));
            this.gui.closeAll();
            return 0;
        }
        String beforeCollect = this.gui.fingerprint();
        this.gui.click(controls[1], 0, ContainerInput.PICKUP);
        this.worker.sleep(250L);
        this.gui.waitChange(beforeCollect, 2000L);
        this.worker.sleep(150L);
        int collected = 0;
        for (int pass = 0; pass < 6 && this.worker.call(Gui::containerOpenNow).booleanValue() && !this.bot.inventoryCapReached(); ++pass) {
            boolean clicked = false;
            List<ItemStack> stacks = this.gui.container();
            for (int i = 0; i < stacks.size(); ++i) {
                ItemStack stack = stacks.get(i);
                if (stack.isEmpty() || !stack.is(this.product.item())) continue;
                if (this.bot.inventoryCapReached()) break;
                collected += stack.getCount();
                this.gui.click(i, 0, ContainerInput.QUICK_MOVE);
                clicked = true;
                this.worker.sleep(40L);
            }
            if (!clicked) break;
            this.worker.ticks(1);
        }
        this.gui.closeAll();
        if (collected > 0) {
            this.say(ModuleChat.body().good("Collected " + collected + "x").text(" ").name(this.product.displayName()).text(" from your order."));
        }
        return collected;
    }

    private long orderPrice() {
        long price;
        if (!this.product.beatTopOrder()) {
            price = this.product.fixedPrice();
        } else {
            double top = -1.0;
            for (int attempt = 1; attempt <= 3 && top <= 0.0; ++attempt) {
                this.gui.closeAll();
                this.worker.sleep(attempt == 1 ? 800L : 1400L);
                this.gui.command("order " + this.product.search());
                top = this.waitTopOrder(attempt == 3 ? 7000L : 4500L);
            }
            this.gui.closeAll();
            if (top <= 0.0) {
                this.say(ModuleChat.body().warn("No order price found").text(" for ").name(this.product.describe()).text(this.product.enchants().isEmpty() ? ", trying later." : ". Use a Fixed order price for enchanted items."));
                return -1L;
            }
            price = (long)Math.floor(top) + this.product.bidIncrement();
            long sell = this.bot.sellPrice();
            if (sell > 0L && price >= sell) {
                this.say(ModuleChat.body().warn("Top order is $" + ServerText.shortMoney(top)).text(", not below your AH price $").value((Object)ServerText.shortMoney(sell)).text(", so no order this time."));
                return -1L;
            }
            this.say(ModuleChat.body().text("Top order is $").value((Object)ServerText.shortMoney(top)).text(", ordering at $").value((Object)ServerText.shortMoney(price)).text(" each."));
        }
        if (this.product.maxPrice() > 0L && price > this.product.maxPrice()) {
            this.say(ModuleChat.body().text("Capped at your max price $").value((Object)ServerText.shortMoney(this.product.maxPrice())).text("."));
            price = this.product.maxPrice();
        }
        return price;
    }

    private double waitTopOrder(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            double price = this.worker.call(() -> {
                if (!Gui.containerOpenNow()) {
                    return -1.0;
                }
                for (ItemStack stack : Gui.containerNow()) {
                    if (stack.isEmpty() || !stack.is(this.product.item()) || !this.product.enchants().isEmpty() && !this.product.matches(stack)) continue;
                    return ServerText.orderPrice(stack);
                }
                return -1.0;
            });
            if (price > 0.0) {
                return price;
            }
            this.worker.sleep(50L);
        }
        return -1.0;
    }

    private void create(long price) {
        Dialogs.Button item;
        if (!this.openYourOrders()) {
            return;
        }
        int pane = this.worker.call(() -> Gui.findNow(OrderRestock::isNewOrderPane, 45));
        if (pane < 0) {
            this.say(ModuleChat.body().warn("No free order slot").text(" (no New Order button)."));
            this.gui.closeAll();
            return;
        }
        this.gui.click(pane, 0, ContainerInput.PICKUP);
        Dialogs.View view = this.waitDialog(v -> v.titled("item"), 10000L);
        if (view == null) {
            throw OrderRestock.fail("Choose Item did not open");
        }
        if (!view.textInputs().isEmpty()) {
            Dialogs.Button search = view.labelled("search");
            view = this.press(view, search, Map.of(view.textInputs().get(0), this.product.search()));
        }
        if ((item = view.find(button -> this.product.nameMatches(button.label(), false))) == null) {
            item = this.closestLoose(view);
        }
        if (item == null) {
            throw OrderRestock.fail(this.product.displayName() + " is not in the search results, " + view.describe());
        }
        view = this.press(view, item, Map.of());
        this.checkItem(view);
        if (view.titled("enchant")) {
            view = this.pickEnchants(view);
        } else if (!this.product.enchants().isEmpty()) {
            throw OrderRestock.fail("No enchantment menu for " + this.product.displayName() + ", " + view.describe());
        }
        if (!view.titled("how many") && !view.titled("amount")) {
            throw OrderRestock.fail("Expected the amount menu, got " + view.describe());
        }
        if (!(view = this.press(view, view.labelled("next", "continue"), OrderRestock.textFor(view, Integer.toString(this.product.orderAmount())))).titled("price")) {
            throw OrderRestock.fail("Expected the price menu, got " + view.describe());
        }
        if (!(view = this.press(view, view.labelled("review order", "review", "next"), OrderRestock.textFor(view, ServerText.exactMoney(price)))).titled("review")) {
            throw OrderRestock.fail("Expected the review menu, got " + view.describe());
        }
        this.checkItem(view);
        String problem = this.checkReview(view, price);
        if (problem != null) {
            Dialogs.View review = view;
            Dialogs.Button cancel = review.labelled("cancel");
            if (cancel != null) {
                this.worker.call(() -> Dialogs.press(Gui.mc(), review, cancel, Map.of()));
            }
            throw OrderRestock.fail("Order review did not match (" + problem + "), cancelled");
        }
        long mark = System.currentTimeMillis();
        Dialogs.View review = view;
        Dialogs.Button createButton = review.labelled("create order", "create", "confirm");
        if (createButton == null) {
            throw OrderRestock.fail("No Create Order button, " + review.describe());
        }
        if (!this.worker.call(() -> Dialogs.press(Gui.mc(), review, createButton, Map.of())).booleanValue()) {
            throw OrderRestock.fail("The review menu closed");
        }
        this.worker.waitFor(() -> this.bot.orderedAt >= mark || Dialogs.current(Gui.mc()) == null || Dialogs.current(Gui.mc()).dialog() != review.dialog(), 8000L);
        this.worker.sleep(300L);
        this.gui.closeAll();
        this.say(ModuleChat.body().good("Ordered " + this.product.orderAmount() + "x").text(" ").name(this.product.describe()).text(" at $").value((Object)ServerText.shortMoney(price)).text(" each."));
    }

    private Dialogs.Button closestLoose(Dialogs.View view) {
        for (Dialogs.Button button : view.buttons()) {
            if (!this.product.nameMatches(button.key().replaceFirst("^[0-9]+x?", ""), false)) continue;
            return button;
        }
        return null;
    }

    private void checkItem(Dialogs.View view) {
        if (view.items().isEmpty()) {
            return;
        }
        for (ItemStack shown : view.items()) {
            if (!shown.is(this.product.item())) continue;
            return;
        }
        String picked = view.items().get(0).getHoverName().getString();
        this.worker.run(() -> Dialogs.close(Gui.mc()));
        throw OrderRestock.fail("Picked " + picked + " instead of " + this.product.displayName() + ", cancelled");
    }

    private Dialogs.View pickEnchants(Dialogs.View view) {
        if (this.product.enchants().isEmpty()) {
            return this.press(view, view.labelled("skip enchantments", "skip"), Map.of());
        }
        for (Product.Enchant wanted : this.product.enchants()) {
            Dialogs.Button level = OrderRestock.levelButton(view, wanted);
            if (level == null) {
                throw OrderRestock.fail(wanted.label() + " is not offered, " + view.describe());
            }
            if (level.green()) continue;
            Dialogs.View before = view;
            if (!this.worker.call(() -> Dialogs.press(Gui.mc(), before, level, Map.of())).booleanValue()) {
                throw OrderRestock.fail("The menu changed before picking " + wanted.label());
            }
            Dialogs.View next = this.waitDialog(v -> v.dialog() != before.dialog(), 5000L);
            if (next == null && (next = this.worker.call(() -> Dialogs.current(Gui.mc()))) == null) {
                throw OrderRestock.fail("The enchantment menu closed after picking " + wanted.label());
            }
            view = next;
            Dialogs.Button again = OrderRestock.levelButton(view, wanted);
            if (again == null || again.green()) continue;
            this.say(ModuleChat.body().warn("Could not confirm ").text(wanted.label()).text(" was picked, continuing."));
        }
        return this.press(view, view.labelled("continue", "confirm", "done", "next"), Map.of());
    }

    static Dialogs.Button levelButton(Dialogs.View view, Product.Enchant wanted) {
        String header = "";
        for (Dialogs.Button button : view.buttons()) {
            int level = ServerText.roman(button.label());
            if (level > 0) {
                if (!header.equals(wanted.key()) || level != wanted.level()) continue;
                return button;
            }
            if (button.label().isBlank()) continue;
            header = ServerText.key(ServerText.withoutRoman(button.label()));
        }
        return null;
    }

    private String checkReview(Dialogs.View view, long price) {
        List<Double> values;
        for (String line : view.body()) {
            if (!line.startsWith("amount") || (values = ServerText.moneyAll(line)).isEmpty() || !(Math.abs(values.get(0) - (double)this.product.orderAmount()) >= ServerText.displayStep(line))) continue;
            return line;
        }
        for (String line : view.body()) {
            double shown;
            if (!line.startsWith("price") || (values = ServerText.moneyAll(line)).isEmpty() || !(Math.abs((shown = values.get(0).doubleValue()) - (double)price) > ServerText.displayStep(line))) continue;
            return line;
        }
        return null;
    }

    private static Map<String, String> textFor(Dialogs.View view, String value) {
        return view.textInputs().isEmpty() ? Map.of() : Map.of(view.textInputs().get(0), value);
    }

    private Dialogs.View press(Dialogs.View view, Dialogs.Button button, Map<String, String> text) {
        return this.press(view, button, text, 8000L);
    }

    private Dialogs.View press(Dialogs.View view, Dialogs.Button button, Map<String, String> text, long timeoutMs) {
        if (button == null) {
            throw OrderRestock.fail("Button missing, " + view.describe());
        }
        if (!this.worker.call(() -> Dialogs.press(Gui.mc(), view, button, text)).booleanValue()) {
            throw OrderRestock.fail("The menu changed before clicking " + button.label());
        }
        Dialogs.View next = this.waitDialog(v -> v.dialog() != view.dialog(), timeoutMs);
        if (next == null) {
            throw OrderRestock.fail("No answer after clicking '" + button.label() + "'");
        }
        return next;
    }

    private Dialogs.View waitDialog(Predicate<Dialogs.View> test, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            Dialogs.View view = this.worker.call(() -> Dialogs.current(Gui.mc()));
            if (view != null && test.test(view)) {
                return view;
            }
            this.worker.sleep(50L);
        }
        return null;
    }
}
