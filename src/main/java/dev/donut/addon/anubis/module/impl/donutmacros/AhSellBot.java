// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.ArrayList;
import java.util.List;
import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.module.impl.donutmacros.AhSellModule;
import dev.donut.addon.anubis.module.impl.donutmacros.AuctionHouse;
import dev.donut.addon.anubis.module.impl.donutmacros.MacroWorker;
import dev.donut.addon.anubis.module.impl.donutmacros.Product;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(value=EnvType.CLIENT)
final class AhSellBot {
    private static final int MAX_LISTINGS_PER_PASS = 9;
    private static final long RECOVERY_MS = 3000L;
    private final AuctionHouse ah;
    private final MacroWorker worker;
    private final AuctionHouse.Config config;
    private final List<AuctionHouse.Stock> stocks;
    private final int[] inAh = new int[]{0};
    private boolean warnedFull;
    private boolean warnedEmpty;

    AhSellBot(AhSellModule module, MacroWorker worker, List<Product> products, boolean fixed, AuctionHouse.Config config, String ign) {
        this.ah = new AuctionHouse(module, worker, config, ign);
        this.worker = worker;
        this.config = config;
        ArrayList<AuctionHouse.Stock> list = new ArrayList<AuctionHouse.Stock>();
        for (Product product : products) {
            list.add(new AuctionHouse.Stock(product, fixed));
        }
        this.stocks = List.copyOf(list);
    }

    void run() {
        ArrayList<String> names = new ArrayList<>();
        for (AuctionHouse.Stock stock : this.stocks) {
            names.add(stock.product.displayName() + (stock.fixedPrice ? " ($" + ServerText.shortMoney(stock.price) + ")" : ""));
        }
        this.ah.say(ModuleChat.body().text("Selling ").name(String.join((CharSequence)", ", names)).text("."));
        try {
            this.ah.gui.closeAll();
            if (this.ah.refreshListings(this.stocks)) {
                this.inAh[0] = this.total();
            }
        }
        catch (MacroWorker.Cancelled cancelled) {
            throw cancelled;
        }
        catch (RuntimeException failure) {
            this.ah.gui.closeAll();
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
                this.ah.say(ModuleChat.body().warn(reason).text(", trying again."));
                this.worker.check();
                this.ah.gui.closeAll();
                this.worker.sleep(3000L);
                continue;
            }
        }
    }

    private void cycle() {
        if (!this.ah.inWorld()) {
            this.worker.sleep(2000L);
            return;
        }
        this.ah.ensureAlive();
        boolean listedAny = false;
        boolean anyInInventory = false;
        for (AuctionHouse.Stock stock : this.stocks) {
            this.worker.check();
            int count = this.ah.countInventory(stock.product);
            if (count < this.config.perListing() && stock.inAh == 0) continue;
            this.ah.ensurePrice(stock);
            if (stock.price <= 0L || count < this.config.perListing()) continue;
            anyInInventory = true;
            this.inAh[0] = this.total();
            int room = this.config.maxListings() - this.inAh[0];
            if (room <= 0) {
                if (!this.warnedFull) {
                    this.ah.say(ModuleChat.body().text("AH full (").value((Object)(this.inAh[0] + "/" + this.config.maxListings())).text("), waiting for sales."));
                }
                this.warnedFull = true;
                break;
            }
            this.warnedFull = false;
            int wanted = Math.min(Math.min(count / this.config.perListing(), room), 9);
            if (this.ah.listSome(stock, wanted, this.inAh) <= 0) continue;
            listedAny = true;
        }
        if (!anyInInventory && this.total() == 0) {
            if (!this.warnedEmpty) {
                this.ah.say(ModuleChat.body().text("Nothing to sell right now, waiting for items."));
            }
            this.warnedEmpty = true;
        } else {
            this.warnedEmpty = false;
        }
        if (listedAny && this.total() < this.config.maxListings() && this.moreToList()) {
            this.worker.sleep(500L);
            return;
        }
        this.ah.waitSalesCheck();
        if (this.ah.refreshListings(this.stocks)) {
            this.inAh[0] = this.total();
        }
    }

    private boolean moreToList() {
        for (AuctionHouse.Stock stock : this.stocks) {
            if (stock.price <= 0L || this.ah.countInventory(stock.product) < this.config.perListing()) continue;
            return true;
        }
        return false;
    }

    private int total() {
        int sum = 0;
        for (AuctionHouse.Stock stock : this.stocks) {
            sum += stock.inAh;
        }
        return sum;
    }

    void onChat(String raw) {
        String line = ServerText.normalize(raw);
        if (!line.isEmpty()) {
            this.ah.onChat(line, this.stocks);
        }
    }

    String summary() {
        return AuctionHouse.summary(this.stocks);
    }
}
