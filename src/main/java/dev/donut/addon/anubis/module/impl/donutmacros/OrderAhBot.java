// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.List;
import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.module.impl.donutmacros.AuctionHouse;
import dev.donut.addon.anubis.module.impl.donutmacros.Gui;
import dev.donut.addon.anubis.module.impl.donutmacros.MacroWorker;
import dev.donut.addon.anubis.module.impl.donutmacros.OrderAhSellerModule;
import dev.donut.addon.anubis.module.impl.donutmacros.OrderRestock;
import dev.donut.addon.anubis.module.impl.donutmacros.Product;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(value=EnvType.CLIENT)
final class OrderAhBot {
    private static final int MAX_LISTINGS_PER_PASS = 9;
    private static final long RECOVERY_MS = 3000L;
    final AuctionHouse ah;
    final MacroWorker worker;
    final Gui gui;
    final Product product;
    final AuctionHouse.Config config;
    private final AuctionHouse.Stock stock;
    private final List<AuctionHouse.Stock> stocks;
    private final OrderRestock restock;
    private final int[] inAh = new int[]{0};
    private boolean warnedFull;
    volatile long orderedAt;

    OrderAhBot(OrderAhSellerModule module, MacroWorker worker, Product product, AuctionHouse.Config config, String ign) {
        this.ah = new AuctionHouse(module, worker, config, ign);
        this.worker = worker;
        this.gui = this.ah.gui;
        this.product = product;
        this.config = config;
        this.stock = new AuctionHouse.Stock(product, false);
        this.stocks = List.of(this.stock);
        this.restock = new OrderRestock(this);
    }

    void say(ModuleChat.Body body) {
        this.ah.say(body);
    }

    boolean inventoryCapReached() {
        return this.ah.inventoryCapReached(this.product);
    }

    long sellPrice() {
        return this.stock.price;
    }

    void run() {
        this.say(ModuleChat.body().text("Selling ").name(this.product.describe()).text(this.product.restock() ? ", restocking with orders." : "."));
        try {
            this.gui.closeAll();
            if (this.ah.refreshListings(this.stocks)) {
                this.inAh[0] = this.stock.inAh;
            }
        }
        catch (MacroWorker.Cancelled cancelled) {
            throw cancelled;
        }
        catch (RuntimeException failure) {
            this.gui.closeAll();
        }
        while (true) {
            this.worker.check();
            try {
                this.cycle();
                continue;
            }
            catch (MacroWorker.Cancelled cancelled) {
                throw cancelled;
            }
            catch (RuntimeException failure) {
                String reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                this.say(ModuleChat.body().warn(reason).text(", trying again."));
                this.worker.check();
                this.gui.closeAll();
                this.worker.sleep(3000L);
                continue;
            }
        }
    }

    private void cycle() {
        int more;
        if (!this.ah.inWorld()) {
            this.worker.sleep(2000L);
            return;
        }
        this.ah.ensureAlive();
        this.ah.ensurePrice(this.stock);
        this.inAh[0] = this.stock.inAh;
        if (this.stock.price <= 0L) {
            this.worker.sleep(Math.min(5000L, this.config.priceCheckMs()));
            return;
        }
        int count = this.ah.countInventory(this.product);
        if (count < this.config.perListing() && this.product.restock() && this.inAh[0] < this.config.maxListings() && this.restock.run()) {
            count = this.ah.countInventory(this.product);
        }
        if (count >= this.config.perListing()) {
            int room = this.config.maxListings() - this.inAh[0];
            if (room <= 0) {
                if (!this.warnedFull) {
                    this.say(ModuleChat.body().text("AH full (").value((Object)(this.inAh[0] + "/" + this.config.maxListings())).text("), waiting for sales."));
                }
                this.warnedFull = true;
            } else {
                this.warnedFull = false;
                int listedNow = this.ah.listSome(this.stock, Math.min(Math.min(count / this.config.perListing(), room), 9), this.inAh);
                if (listedNow > 0 && this.ah.countInventory(this.product) >= this.config.perListing() && this.inAh[0] < this.config.maxListings()) {
                    this.worker.sleep(500L);
                    return;
                }
            }
        }
        this.ah.waitSalesCheck();
        if (this.ah.refreshListings(this.stocks)) {
            this.inAh[0] = this.stock.inAh;
        }
        if ((more = this.ah.countInventory(this.product)) >= this.config.perListing() && this.inAh[0] < this.config.maxListings()) {
            return;
        }
        if (more < this.config.perListing() && this.product.restock() && this.inAh[0] < this.config.maxListings()) {
            this.restock.run();
        }
    }

    void onChat(String raw) {
        String line = ServerText.normalize(raw);
        if (line.isEmpty()) {
            return;
        }
        if (line.startsWith("you ordered")) {
            this.orderedAt = System.currentTimeMillis();
        }
        this.ah.onChat(line, this.stocks);
    }

    String summary() {
        return AuctionHouse.summary(this.stocks);
    }
}
