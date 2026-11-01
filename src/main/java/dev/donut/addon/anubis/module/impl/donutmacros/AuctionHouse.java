// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.module.ClientModule;
import dev.donut.addon.anubis.module.impl.donutmacros.Dialogs;
import dev.donut.addon.anubis.module.impl.donutmacros.Gui;
import dev.donut.addon.anubis.module.impl.donutmacros.MacroWorker;
import dev.donut.addon.anubis.module.impl.donutmacros.Product;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;

@Environment(value=EnvType.CLIENT)
final class AuctionHouse {
    private static final int INVENTORY_SLOTS = 36;
    private static final int AH_SCAN_SLOTS = 45;
    private static final int AH_ROOT_BUTTON = 51;
    private static final int CHEST_CONFIRM_SLOT = 15;
    private static final int INVENTORY_SETTLE_TICKS = 4;
    private static final int COLLECT_LIMIT = 50;
    private static final int VERIFY_LIMIT = 15;
    private static final long OPEN_TIMEOUT_MS = 10000L;
    private static final long NAV_TIMEOUT_MS = 4000L;
    private static final long SELL_TIMEOUT_MS = 15000L;
    final ClientModule module;
    final MacroWorker worker;
    final Gui gui;
    final Config config;
    private final String ign;
    private final String ignKey;
    private volatile long tooManyAt;
    private volatile long alreadyBoughtAt;
    private volatile long listedAt;
    private volatile long errorAt;

    AuctionHouse(ClientModule module, MacroWorker worker, Config config, String ign) {
        this.module = module;
        this.worker = worker;
        this.gui = new Gui(worker);
        this.config = config;
        this.ign = ign;
        this.ignKey = ServerText.key(ign);
    }

    void say(ModuleChat.Body body) {
        ModuleChat.send((ClientModule)this.module, (ModuleChat.Body)body);
    }

    boolean inWorld() {
        return this.worker.call(() -> Gui.mc().player != null && Gui.mc().getConnection() != null);
    }

    void ensurePrice(Stock stock) {
        if (stock.fixedPrice) {
            stock.price = stock.product.minPrice();
            return;
        }
        if (stock.price <= 0L || System.currentTimeMillis() - stock.lastPriceCheck >= this.config.priceCheckMs()) {
            this.updatePrice(stock);
        }
    }

    private void updatePrice(Stock stock) {
        Product product = stock.product;
        double[] scan = this.scan(stock);
        stock.lastPriceCheck = System.currentTimeMillis();
        boolean confirmed = scan[0] > 0.0;
        boolean matched = scan[1] > 0.0;
        double cheapestUnit = scan[2];
        long undercut = this.config.undercut();
        String name = product.displayName();
        if (cheapestUnit > 0.0) {
            long cheapest = Math.round(cheapestUnit);
            long next = Math.max(cheapest - undercut, product.minPrice());
            if (stock.price > 0L && cheapest < stock.price) {
                double deep;
                long drop = stock.price - cheapest;
                double d = deep = undercut > 0L ? (double)undercut * this.config.ignoreDrops() : (double)stock.price / 2.0;
                if ((double)drop > deep) {
                    this.say(ModuleChat.body().name(name).text(": ").warn("ignoring a deep undercut").text(" to $").value((Object)ServerText.shortMoney(cheapest)).text(" (drop $").value((Object)ServerText.shortMoney(drop)).text(")."));
                } else if (next < stock.price) {
                    this.say(ModuleChat.body().name(name).text(": ").warn("undercut").text(" to $").value((Object)ServerText.shortMoney(cheapest)).text(", relisting at $").value((Object)ServerText.shortMoney(next)).text("."));
                    this.relist(stock, next);
                }
            } else if (stock.price > 0L && (double)next > (double)stock.price + (double)undercut * this.config.relistGap() && stock.inAh > 0) {
                this.say(ModuleChat.body().name(name).text(": price went up to $").value((Object)ServerText.shortMoney(cheapest)).text(", relisting at $").value((Object)ServerText.shortMoney(next)).text("."));
                this.relist(stock, next);
            } else {
                if (stock.price <= 0L) {
                    this.say(ModuleChat.body().name(name).text(": cheapest is $").value((Object)ServerText.shortMoney(cheapest)).text(", selling at $").value((Object)ServerText.shortMoney(next)).text(" each."));
                }
                stock.price = next;
            }
        } else if (stock.price <= 0L && confirmed && !matched) {
            stock.price = product.minPrice() * 2L;
            this.say(ModuleChat.body().name(name).text(": no other listings, selling at $").value((Object)ServerText.shortMoney(stock.price)).text(" each."));
        } else if (stock.price <= 0L) {
            this.say(ModuleChat.body().name(name).text(": ").warn("could not read AH prices").text(", waiting."));
        }
    }

    private double[] scan(Stock stock) {
        Product product = stock.product;
        this.gui.closeAll();
        this.worker.sleep(200L);
        this.gui.command("ah " + product.search());
        boolean open = this.worker.waitFor(() -> this.isAhResultsNow() && this.hasContent(), 10000L);
        if (!open) {
            this.gui.closeAll();
            return new double[]{0.0, 0.0, -1.0};
        }
        this.worker.sleep(600L);
        if (!this.worker.call(this::isAhResultsNow).booleanValue()) {
            this.gui.closeAll();
            return new double[]{0.0, 0.0, -1.0};
        }
        double[] result = this.worker.call(() -> {
            List<ItemStack> stacks = Gui.containerNow();
            boolean matched = false;
            double cheapest = -1.0;
            for (int i = 0; i < stacks.size() && i < 45; ++i) {
                double unit;
                ItemStack stack = stacks.get(i);
                if (!product.matches(stack)) continue;
                matched = true;
                double total = ServerText.listingPrice(stack);
                if (total <= 0.0 || this.ownListing(stock, stack, unit = total / (double)Math.max(1, stack.getCount())) || !(cheapest < 0.0) && !(unit < cheapest)) continue;
                cheapest = unit;
            }
            return new double[]{1.0, matched ? 1.0 : 0.0, cheapest};
        });
        this.gui.closeAll();
        this.worker.sleep(200L);
        return result;
    }

    private boolean ownListing(Stock stock, ItemStack stack, double unit) {
        for (String line : ServerText.lore(stack)) {
            if (this.ignKey.isEmpty() || !ServerText.key(line).contains(this.ignKey)) continue;
            return true;
        }
        return stock.inAh > 0 && stock.price > 0L && Math.abs(unit - (double)stock.price) < 1.0;
    }

    private boolean hasContent() {
        List<ItemStack> stacks = Gui.containerNow();
        if (stacks.size() > 49 && !stacks.get(49).isEmpty()) {
            return true;
        }
        for (int i = 0; i <= 10 && i < stacks.size(); ++i) {
            if (stacks.get(i).isEmpty()) continue;
            return true;
        }
        return false;
    }

    private boolean isAhResultsNow() {
        if (!Gui.containerOpenNow()) {
            return false;
        }
        String title = ServerText.normalize(Gui.mc().gui.screen().getTitle());
        if (title.contains("order")) {
            return false;
        }
        if (title.contains("auction") || title.contains("market") || title.equals("ah") || title.contains(" ah ")) {
            return true;
        }
        return AuctionHouse.isChest(Gui.slotNow(51));
    }

    private boolean isAhRootNow() {
        if (!Gui.containerOpenNow()) {
            return false;
        }
        if (ServerText.normalize(Gui.mc().gui.screen().getTitle()).contains("order")) {
            return false;
        }
        return AuctionHouse.isChest(Gui.slotNow(51));
    }

    private static boolean isChest(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return path.equals("chest") || path.equals("trapped_chest");
    }

    private boolean openYourItems() {
        this.gui.closeAll();
        this.worker.sleep(200L);
        this.gui.command("ah " + this.ign);
        if (!this.worker.waitFor(this::isAhRootNow, 10000L)) {
            return false;
        }
        for (int attempt = 0; attempt < 2; ++attempt) {
            String before = this.gui.fingerprint();
            this.gui.click(51, 0, ContainerInput.PICKUP);
            if (!this.gui.waitChange(before, 4000L)) continue;
            this.worker.sleep(200L);
            return this.worker.call(Gui::containerOpenNow);
        }
        return false;
    }

    boolean refreshListings(List<Stock> stocks) {
        if (!this.openYourItems()) {
            this.gui.closeAll();
            return false;
        }
        int[] counts = this.worker.call(() -> {
            int[] found = new int[stocks.size()];
            block0: for (ItemStack stack : Gui.containerNow()) {
                for (int i = 0; i < stocks.size(); ++i) {
                    if (!((Stock)stocks.get((int)i)).product.matches(stack)) continue;
                    int n = i;
                    found[n] = found[n] + 1;
                    continue block0;
                }
            }
            return found;
        });
        this.gui.closeAll();
        this.worker.sleep(200L);
        for (int i = 0; i < stocks.size(); ++i) {
            Stock stock = stocks.get(i);
            int gone = stock.inAh - counts[i];
            if (gone > 0 && stock.chatSales.getAndSet(0) == 0) {
                stock.sold.addAndGet(gone);
                stock.earned.addAndGet((long)gone * Math.max(0L, stock.price) * (long)this.config.perListing());
                this.say(ModuleChat.body().good("Sold " + gone + "x").text(" ").name(stock.product.displayName()).text(" (total ").value((Object)stock.sold.get()).text(")."));
            }
            stock.chatSales.set(0);
            stock.inAh = counts[i];
        }
        return true;
    }

    private int collect(Stock stock) {
        if (!this.openYourItems()) {
            this.say(ModuleChat.body().warn("Could not open Your Items."));
            this.gui.closeAll();
            return 0;
        }
        int collected = this.collectPass(stock.product, 50);
        if (collected > 0) {
            this.gui.closeAll();
            this.worker.sleep(500L);
            if (this.openYourItems()) {
                collected += this.collectPass(stock.product, 15);
            }
        }
        this.gui.closeAll();
        this.worker.sleep(200L);
        if (collected > 0) {
            this.say(ModuleChat.body().text("Took back ").value((Object)collected).text(collected == 1 ? " listing of " : " listings of ").name(stock.product.displayName()).text("."));
        }
        return collected;
    }

    private int collectPass(Product product, int limit) {
        int slot;
        int collected = 0;
        HashSet<Integer> skipped = new HashSet<Integer>();
        while (collected < limit && this.worker.call(Gui::containerOpenNow).booleanValue() && (slot = this.worker.call(() -> {
            List<ItemStack> stacks = Gui.containerNow();
            for (int i = 0; i < stacks.size(); ++i) {
                if (skipped.contains(i) || !product.matches(stacks.get(i))) continue;
                return i;
            }
            return -1;
        }).intValue()) >= 0) {
            long mark = System.currentTimeMillis();
            this.gui.click(slot, 0, ContainerInput.PICKUP);
            this.worker.ticks(3);
            if (this.alreadyBoughtAt >= mark) {
                skipped.add(slot);
                continue;
            }
            ++collected;
        }
        return collected;
    }

    private void relist(Stock stock, long next) {
        this.refreshListings(List.of(stock));
        stock.price = next;
        this.collect(stock);
        stock.inAh = 0;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    int listSome(Stock stock, int wanted, int[] totalInAh) {
        if (wanted <= 0) {
            return 0;
        }
        this.gui.closeAll();
        this.gui.closeInventory();
        int previous = this.worker.call(() -> Gui.player().getInventory().getSelectedSlot());
        int done = 0;
        int failures = 0;
        try {
            for (int i = 0; i < wanted; ++i) {
                this.ensureAlive();
                int hotbar = this.stage(stock.product);
                if (hotbar < 0) {
                    this.say(ModuleChat.body().warn("Could not prepare a hotbar slot").text(" (inventory full?)."));
                    break;
                }
                Listing result = this.listFrom(stock, hotbar);
                if (result == Listing.FULL) {
                    totalInAh[0] = Math.max(totalInAh[0], this.config.maxListings());
                    this.say(ModuleChat.body().warn("The AH says you have too many listings."));
                    break;
                }
                if (result == Listing.FAILED) {
                    if (++failures < 2) continue;
                    break;
                }
                ++done;
                ++stock.inAh;
                totalInAh[0] = totalInAh[0] + 1;
                stock.listed.incrementAndGet();
                if (this.config.listingDelayMs() <= 0L || i >= wanted - 1) continue;
                this.worker.sleep(this.config.listingDelayMs());
            }
        }
        finally {
            this.worker.run(() -> {
                LocalPlayer player = Gui.mc().player;
                if (player != null) {
                    player.getInventory().setSelectedSlot(previous);
                }
            });
        }
        if (done > 0) {
            this.say(ModuleChat.body().text("Listed ").value((Object)(done + "x")).text(" ").name(stock.product.displayName()).text(" at $").value((Object)ServerText.shortMoney(stock.price * (long)this.config.perListing())).text(" (in AH: ").value((Object)totalInAh[0]).text(")."));
        }
        return done;
    }

    private Listing listFrom(Stock stock, int hotbar) {
        Product product = stock.product;
        this.worker.run(() -> Gui.player().getInventory().setSelectedSlot(hotbar));
        this.worker.ticks(2);
        int before = this.worker.call(() -> Gui.player().getInventory().getItem(hotbar).getCount());
        long mark = System.currentTimeMillis();
        this.gui.command("ah sell " + ServerText.exactMoney(stock.price * (long)this.config.perListing()));
        long end = mark + 15000L;
        boolean[] confirmed = new boolean[]{false};
        Object[] pressedDialog = new Object[]{null};
        int[] pressedChest = new int[]{-1};
        while (System.currentTimeMillis() < end) {
            if (this.tooManyAt >= mark) {
                this.gui.closeAll();
                return Listing.FULL;
            }
            if (this.listedAt >= mark) {
                this.worker.sleep(150L);
                this.gui.closeAll();
                return Listing.LISTED;
            }
            if (this.errorAt >= mark) {
                this.gui.closeAll();
                return Listing.FAILED;
            }
            long elapsed = System.currentTimeMillis() - mark;
            int state = this.worker.call(() -> {
                Dialogs.View view = Dialogs.current(Gui.mc());
                if (view != null) {
                    Dialogs.Button yes;
                    if (view.dialog() != pressedDialog[0] && (view.titled("sure") || view.titled("confirm") || view.titled("sell")) && !view.titled("buy") && !view.titled("purchase") && (yes = view.labelled("yes", "confirm")) != null && Dialogs.press(Gui.mc(), view, yes, Map.of())) {
                        pressedDialog[0] = view.dialog();
                        confirmed[0] = true;
                        return 1;
                    }
                    return 0;
                }
                if (Gui.containerOpenNow()) {
                    boolean otherMenu;
                    LocalPlayer player = Gui.player();
                    int sync = player.containerMenu.containerId;
                    ItemStack button = Gui.slotNow(15);
                    String title = ServerText.normalize(Gui.mc().gui.screen().getTitle());
                    boolean bl = otherMenu = title.contains("buy") || title.contains("purchase") || title.contains("(page") || title.contains("order");
                    if (sync != pressedChest[0] && !otherMenu && !button.isEmpty() && BuiltInRegistries.ITEM.getKey(button.getItem()).getPath().contains("lime")) {
                        Gui.mc().gameMode.handleContainerInput(sync, 15, 0, ContainerInput.PICKUP, (Player)player);
                        pressedChest[0] = sync;
                        confirmed[0] = true;
                        return 1;
                    }
                    return 0;
                }
                ItemStack held = Gui.player().getInventory().getItem(hotbar);
                boolean gone = !product.matches(held) || held.getCount() < before;
                return gone && (confirmed[0] || elapsed > 2000L) ? 2 : 0;
            });
            if (state == 2) {
                this.gui.closeAll();
                return Listing.LISTED;
            }
            this.worker.sleep(state == 1 ? 250L : 50L);
        }
        this.gui.closeAll();
        this.say(ModuleChat.body().warn("Listing timed out").text("."));
        return Listing.FAILED;
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private int stage(Product product) {
        int n = this.config.perListing();
        boolean clicked = false;
        try {
            for (int attempt = 0; attempt < 12; ++attempt) {
                int n2;
                int[] plan = this.worker.call(() -> AuctionHouse.planStage(product, n));
                if (plan[0] == 0) {
                    n2 = plan[1];
                    return n2;
                }
                if (plan[0] < 0) {
                    n2 = -1;
                    return n2;
                }
                for (int i = 2; i < plan.length; i += 2) {
                    this.gui.clickInventory(plan[i] >> 1, plan[i] & 1, plan[i + 1] == 1 ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP);
                    clicked = true;
                    this.worker.ticks(1);
                }
            }
            int n3 = -1;
            return n3;
        }
        finally {
            if (clicked) {
                this.gui.closeInventory();
                this.worker.ticks(4);
            }
        }
    }

    private static int[] planStage(Product product, int n) {
        int h;
        LocalPlayer player = Gui.player();
        Inventory inventory = player.getInventory();
        ItemStack carried = player.inventoryMenu.getCarried();
        if (!carried.isEmpty()) {
            int[] nArray;
            int empty = AuctionHouse.emptyMain(inventory);
            if (empty < 0) {
                int[] nArray2 = new int[1];
                nArray = nArray2;
                nArray2[0] = -1;
            } else {
                int[] nArray3 = new int[4];
                nArray3[0] = 1;
                nArray3[1] = 0;
                nArray3[2] = AuctionHouse.click(AuctionHouse.menuSlot(empty), 0);
                nArray = nArray3;
                nArray3[3] = 0;
            }
            return nArray;
        }
        for (int h2 = 0; h2 < 9; ++h2) {
            ItemStack stack = inventory.getItem(h2);
            if (!product.matches(stack) || stack.getCount() != n) continue;
            return new int[]{0, h2};
        }
        int target = -1;
        for (h = 0; h < 9; ++h) {
            ItemStack stack = inventory.getItem(h);
            if (!product.matches(stack) || stack.getCount() >= n) continue;
            target = h;
            break;
        }
        if (target < 0) {
            for (h = 0; h < 9; ++h) {
                if (!inventory.getItem(h).isEmpty()) continue;
                target = h;
                break;
            }
        }
        if (target < 0) {
            int victim = -1;
            for (int h3 = 8; h3 >= 0; --h3) {
                if (product.matches(inventory.getItem(h3))) continue;
                victim = h3;
                break;
            }
            if (victim < 0) {
                victim = 8;
            }
            if (AuctionHouse.emptyMain(inventory) < 0) {
                return new int[]{-1};
            }
            return new int[]{1, 0, AuctionHouse.click(AuctionHouse.menuSlot(victim), 0), 1};
        }
        int have = product.matches(inventory.getItem(target)) ? inventory.getItem(target).getCount() : 0;
        int source = -1;
        int best = 0;
        for (int index = 0; index < 36; ++index) {
            ItemStack stack;
            if (index == target || !product.matches(stack = inventory.getItem(index)) || stack.getCount() <= best) continue;
            best = stack.getCount();
            source = index;
        }
        if (source < 0) {
            return new int[]{-1};
        }
        int need = n - have;
        int[] plan = new int[2 + 2 * (need + 2)];
        int at = 2;
        plan[0] = 1;
        plan[at++] = AuctionHouse.click(AuctionHouse.menuSlot(source), 0);
        plan[at++] = 0;
        if (best <= need) {
            plan[at++] = AuctionHouse.click(AuctionHouse.menuSlot(target), 0);
            plan[at++] = 0;
        } else {
            for (int i = 0; i < need; ++i) {
                plan[at++] = AuctionHouse.click(AuctionHouse.menuSlot(target), 1);
                plan[at++] = 0;
            }
            plan[at++] = AuctionHouse.click(AuctionHouse.menuSlot(source), 0);
            plan[at++] = 0;
        }
        return Arrays.copyOf(plan, at);
    }

    private static int click(int slot, int button) {
        return slot << 1 | button;
    }

    private static int menuSlot(int inventoryIndex) {
        return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
    }

    private static int emptyMain(Inventory inventory) {
        for (int index = 9; index < 36; ++index) {
            if (!inventory.getItem(index).isEmpty()) continue;
            return index;
        }
        return -1;
    }

    int countInventory(Product product) {
        return this.worker.call(() -> {
            LocalPlayer player = Gui.mc().player;
            if (player == null) {
                return 0;
            }
            int total = 0;
            for (int index = 0; index < 36; ++index) {
                ItemStack stack = player.getInventory().getItem(index);
                if (!product.matches(stack)) continue;
                total += stack.getCount();
            }
            return total;
        });
    }

    boolean inventoryCapReached(Product product) {
        return this.worker.call(() -> {
            Inventory inventory = Gui.player().getInventory();
            int used = 0;
            int empty = 0;
            for (int index = 0; index < 36; ++index) {
                ItemStack stack = inventory.getItem(index);
                if (stack.isEmpty()) {
                    ++empty;
                    continue;
                }
                if (!stack.is(product.item())) continue;
                ++used;
            }
            return used >= this.config.collectSlots() || empty <= 0;
        });
    }

    void ensureAlive() {
        boolean dead;
        if (!this.worker.call(() -> Gui.mc().player != null && Gui.mc().player.isDeadOrDying()).booleanValue()) {
            return;
        }
        this.say(ModuleChat.body().danger("You died").text(", respawning."));
        long end = System.currentTimeMillis() + 30000L;
        long next = System.currentTimeMillis() + 700L;
        while (System.currentTimeMillis() < end && (dead = this.worker.call(() -> Gui.mc().player != null && Gui.mc().player.isDeadOrDying()).booleanValue())) {
            if (System.currentTimeMillis() >= next) {
                this.worker.run(() -> {
                    LocalPlayer player = Gui.mc().player;
                    if (player != null) {
                        player.respawn();
                    }
                });
                next = System.currentTimeMillis() + 1500L;
            }
            this.worker.sleep(100L);
        }
        this.worker.run(() -> {
            if (Gui.mc().gui.screen() instanceof DeathScreen) {
                Gui.mc().setScreenAndShow(null);
            }
        });
        throw new Problem("Respawned");
    }

    void waitSalesCheck() {
        long until = System.currentTimeMillis() + this.config.salesCheckMs();
        while (System.currentTimeMillis() < until) {
            this.worker.sleep(Math.min(1000L, until - System.currentTimeMillis()));
        }
    }

    String onChat(String line, List<Stock> stocks) {
        int bought;
        long now = System.currentTimeMillis();
        if (line.contains("too many listed items")) {
            this.tooManyAt = now;
        }
        if (line.contains("already bought")) {
            this.alreadyBoughtAt = now;
        }
        if (line.startsWith("you listed")) {
            this.listedAt = now;
        }
        if (line.contains("you cannot do this") || line.contains("you need to wait another") || line.contains("don't have enough")) {
            this.errorAt = now;
        }
        if ((bought = line.indexOf(" bought your ")) <= 0) {
            return null;
        }
        String rest = line.substring(bought + " bought your ".length());
        int forAt = rest.lastIndexOf(" for ");
        String itemPart = forAt > 0 ? rest.substring(0, forAt) : rest;
        Stock stock = null;
        for (Stock candidate : stocks) {
            if (!candidate.product.nameMatches(itemPart, false)) continue;
            stock = candidate;
            break;
        }
        if (stock == null) {
            for (Stock candidate : stocks) {
                if (!candidate.product.nameMatches(itemPart, true)) continue;
                stock = candidate;
                break;
            }
        }
        if (stock == null) {
            return null;
        }
        List money = forAt > 0 ? ServerText.moneyAll(rest.substring(forAt)) : List.of();
        long amount = money.isEmpty() ? 0L : Math.round((Double)money.get(0));
        stock.sold.incrementAndGet();
        stock.chatSales.incrementAndGet();
        stock.earned.addAndGet(amount);
        this.say(ModuleChat.body().good("Sold").text(" ").name(stock.product.displayName()).text((String)(amount > 0L ? " for $" + ServerText.shortMoney(amount) : "")).text(" (total ").value((Object)stock.sold.get()).text(", ~$").value((Object)ServerText.shortMoney(stock.earned.get())).text(")."));
        return stock.product.displayName();
    }

    static String summary(List<Stock> stocks) {
        int listed = 0;
        int sold = 0;
        long earned = 0L;
        for (Stock stock : stocks) {
            listed += stock.listed.get();
            sold += stock.sold.get();
            earned += stock.earned.get();
        }
        return "Listed " + listed + ", sold " + sold + ", earned ~$" + ServerText.shortMoney(earned) + ".";
    }

    @Environment(value=EnvType.CLIENT)
    record Config(long undercut, long priceCheckMs, long salesCheckMs, int perListing, int maxListings, long listingDelayMs, double relistGap, double ignoreDrops, int collectSlots, long orderCheckMs) {
    }

    @Environment(value=EnvType.CLIENT)
    static final class Stock {
        final Product product;
        final boolean fixedPrice;
        long price = -1L;
        long lastPriceCheck;
        int inAh;
        final AtomicInteger listed = new AtomicInteger();
        final AtomicInteger sold = new AtomicInteger();
        final AtomicInteger chatSales = new AtomicInteger();
        final AtomicLong earned = new AtomicLong();

        Stock(Product product, boolean fixedPrice) {
            this.product = product;
            this.fixedPrice = fixedPrice;
            if (fixedPrice) {
                this.price = product.minPrice();
            }
        }
    }

    @Environment(value=EnvType.CLIENT)
    private static enum Listing {
        LISTED,
        FULL,
        FAILED;

    }

    @Environment(value=EnvType.CLIENT)
    static final class Problem
    extends RuntimeException {
        Problem(String message) {
            super(message, null, false, false);
        }
    }
}
