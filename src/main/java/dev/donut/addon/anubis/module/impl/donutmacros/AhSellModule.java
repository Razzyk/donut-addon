// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.event.EventHandler;
import dev.donut.addon.anubis.event.impl.PacketReceiveNettyEvent;
import dev.donut.addon.anubis.module.ClientModule;
import dev.donut.addon.anubis.module.ModuleCategory;
import dev.donut.addon.anubis.module.impl.donutmacros.AhSellBot;
import dev.donut.addon.anubis.module.impl.donutmacros.AuctionHouse;
import dev.donut.addon.anubis.module.impl.donutmacros.Dialogs;
import dev.donut.addon.anubis.module.impl.donutmacros.MacroWorker;
import dev.donut.addon.anubis.module.impl.donutmacros.Product;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import dev.donut.addon.anubis.module.setting.ModeSetting;
import dev.donut.addon.anubis.module.setting.NumberSetting;
import dev.donut.addon.anubis.module.setting.Setting;
import dev.donut.addon.anubis.module.setting.StringSetting;
import dev.donut.addon.anubis.util.ItemEnchants;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.network.protocol.Packet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(value=EnvType.CLIENT)
public final class AhSellModule
extends ClientModule {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"Anubis");
    private static final int SLOTS = 9;
    private static final String FIXED = "Fixed";
    private static final String AUTO = "Auto (beat cheapest)";
    private final ModeSetting count = (ModeSetting)this.addSetting("items", (Setting)new ModeSetting("Items", "1", new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9"}));
    private final ModeSetting pricing = (ModeSetting)this.addSetting("pricing", (Setting)new ModeSetting("Pricing", "Fixed", new String[]{"Fixed", "Auto (beat cheapest)"}));
    private final StringSetting[] items = new StringSetting[9];
    private final StringSetting[] enchants = new StringSetting[9];
    private final StringSetting[] prices = new StringSetting[9];
    private final StringSetting undercut;
    private final NumberSetting priceCheck;
    private final NumberSetting relistGap;
    private final NumberSetting ignoreDrops;
    private final NumberSetting salesCheck;
    private final NumberSetting perListing;
    private final NumberSetting maxListings;
    private final NumberSetting listingDelay;
    private final MacroWorker worker = new MacroWorker("Anubis AH Sell");
    private volatile AhSellBot bot;

    public AhSellModule() {
        super("ah-sell", "AH Sell", "Lists up to 9 items from your inventory on the Auction House at your price or the cheapest price.", ModuleCategory.DONUT_MACROS);
        this.usesNotifications();
        for (int i = 0; i < 9; ++i) {
            int slot = i + 1;
            int index = i;
            this.items[i] = (StringSetting)this.addSetting("item-" + slot, (Setting)new StringSetting("Item " + slot, "").hint("e.g. diamond_block").itemPicker());
            this.enchants[i] = (StringSetting)this.addSetting("enchants-" + slot, (Setting)new StringSetting("Enchantments " + slot, "").enchantsFor(this.items[i]));
            this.prices[i] = (StringSetting)this.addSetting("price-" + slot, (Setting)new StringSetting("Price " + slot + " (each)", "").hint("e.g. 5k (Auto: lowest price)"));
            this.items[i].visibleWhen(() -> slot <= this.slotCount());
            this.enchants[i].visibleWhen(() -> slot <= this.slotCount() && ItemEnchants.supports((Item)Product.resolveItem(this.items[index].get())));
            this.prices[i].visibleWhen(() -> slot <= this.slotCount());
        }
        this.undercut = (StringSetting)this.addSetting("undercut", (Setting)new StringSetting("Undercut By", "100").hint("e.g. 100"));
        this.priceCheck = (NumberSetting)this.addSetting("price-check", (Setting)new NumberSetting("Price Check (s)", 120.0f, 10.0f, 600.0f).step(10.0f));
        this.relistGap = (NumberSetting)this.addSetting("relist-gap", (Setting)new NumberSetting("Relist Higher At (x undercut)", 3.0f, 1.0f, 20.0f).step(0.5f));
        this.ignoreDrops = (NumberSetting)this.addSetting("ignore-drops", (Setting)new NumberSetting("Ignore Drops Over (x undercut)", 20.0f, 1.0f, 100.0f).step(1.0f));
        this.salesCheck = (NumberSetting)this.addSetting("sales-check", (Setting)new NumberSetting("Sales Check (s)", 20.0f, 5.0f, 300.0f).step(5.0f));
        this.perListing = (NumberSetting)this.addSetting("per-listing", (Setting)new NumberSetting("Items Per Listing", 1.0f, 1.0f, 64.0f).step(1.0f));
        this.maxListings = (NumberSetting)this.addSetting("max-listings", (Setting)new NumberSetting("Max Listings", 25.0f, 1.0f, 50.0f).step(1.0f));
        this.listingDelay = (NumberSetting)this.addSetting("listing-delay", (Setting)new NumberSetting("Listing Delay (s)", 0.0f, 0.0f, 5.0f).step(0.25f));
        this.undercut.visibleWhen(this::auto);
        this.priceCheck.visibleWhen(this::auto);
        this.relistGap.visibleWhen(this::auto);
        this.ignoreDrops.visibleWhen(this::auto);
    }

    private int slotCount() {
        return Integer.parseInt(this.count.get());
    }

    private boolean auto() {
        return this.pricing.is(AUTO);
    }

    public boolean persistEnabled() {
        return false;
    }

    protected void onEnable() {
        AhSellBot created;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getUser() == null) {
            this.info(ModuleChat.body().warn("Join a server first."));
            this.requestDisable();
            return;
        }
        ArrayList<Product> products = new ArrayList<Product>();
        String problem = this.buildProducts(products);
        if (problem != null) {
            this.info(ModuleChat.body().warn(problem));
            this.requestDisable();
            return;
        }
        AuctionHouse.Config config = new AuctionHouse.Config(Math.max(0L, ServerText.parseMoney(this.undercut.get())), (long)(this.priceCheck.get() * 1000.0f), (long)(this.salesCheck.get() * 1000.0f), this.perListing.getInt(), this.maxListings.getInt(), (long)(this.listingDelay.get() * 1000.0f), this.relistGap.get(), this.ignoreDrops.get(), 36, 15000L);
        this.bot = created = new AhSellBot(this, this.worker, products, !this.auto(), config, mc.getUser().getName());
        this.worker.start(created::run, failure -> {
            LOGGER.error("[Anubis] AH Sell stopped", failure);
            this.info(ModuleChat.body().danger("Stopped").text(": " + String.valueOf(failure)));
            mc.execute(() -> {
                if (this.bot == created) {
                    this.setEnabled(false);
                }
            });
        });
    }

    protected void onDisable() {
        AhSellBot stopped = this.bot;
        this.bot = null;
        this.worker.stop();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (stopped != null && player != null) {
            if (player.containerMenu != player.inventoryMenu) {
                player.closeContainer();
            } else {
                Dialogs.close(mc);
            }
            this.info(ModuleChat.body().text(stopped.summary()));
        }
    }

    @EventHandler
    public void onPacket(PacketReceiveNettyEvent event) {
        ClientboundSystemChatPacket chat;
        Packet class_25962;
        AhSellBot current = this.bot;
        if (current != null && (class_25962 = event.packet) instanceof ClientboundSystemChatPacket && (chat = (ClientboundSystemChatPacket)class_25962).content() != null) {
            current.onChat(chat.content().getString());
        }
    }

    private String buildProducts(List<Product> out) {
        if (!this.auto() || ServerText.parseMoney(this.undercut.get()) >= 0L) {
            HashSet<Item> seen = new HashSet<Item>();
            for (int i = 0; i < this.slotCount(); ++i) {
                String raw = this.items[i].get().trim();
                if (raw.isEmpty()) continue;
                Item item = Product.resolveItem(raw);
                if (item == null) {
                    return "Item " + (i + 1) + ": unknown item '" + raw + "'.";
                }
                if (!seen.add(item)) {
                    return "Item " + (i + 1) + ": " + raw + " is listed twice.";
                }
                long price = ServerText.parseMoney(this.prices[i].get());
                if (price <= 0L) {
                    return "Item " + (i + 1) + ": set a price like 5k" + (this.auto() ? " (the lowest price Auto may use)." : ".");
                }
                List<Product.Enchant> wanted = ItemEnchants.supports((Item)item) ? Product.parseEnchants(this.enchants[i].get()) : List.of();
                out.add(new Product(item, raw, Product.searchText(item), new ItemStack((ItemLike)item).getHoverName().getString(), price, wanted, false, 1, true, 1L, 1L, 0L));
            }
            return out.isEmpty() ? "Set at least one item." : null;
        }
        return "Undercut By must be a price like 100.";
    }

    private void info(ModuleChat.Body body) {
        ModuleChat.send((ClientModule)this, (ModuleChat.Body)body);
    }
}
