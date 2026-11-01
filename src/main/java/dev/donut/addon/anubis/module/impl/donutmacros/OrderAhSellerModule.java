// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.List;
import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.event.EventHandler;
import dev.donut.addon.anubis.event.impl.PacketReceiveNettyEvent;
import dev.donut.addon.anubis.module.ClientModule;
import dev.donut.addon.anubis.module.ModuleCategory;
import dev.donut.addon.anubis.module.impl.donutmacros.AuctionHouse;
import dev.donut.addon.anubis.module.impl.donutmacros.Dialogs;
import dev.donut.addon.anubis.module.impl.donutmacros.MacroWorker;
import dev.donut.addon.anubis.module.impl.donutmacros.OrderAhBot;
import dev.donut.addon.anubis.module.impl.donutmacros.Product;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import dev.donut.addon.anubis.module.setting.BooleanSetting;
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
public final class OrderAhSellerModule
extends ClientModule {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"Anubis");
    private static final int PRODUCTS = 5;
    private static final String PRICE_BEAT = "Beat Top Order";
    private static final String PRICE_FIXED = "Fixed";
    private final ModeSetting productSlot = (ModeSetting)this.addSetting("product", (Setting)new ModeSetting("Product", "1", new String[]{"1", "2", "3", "4", "5"}));
    private final StringSetting[] items = new StringSetting[5];
    private final StringSetting[] minPrices = new StringSetting[5];
    private final StringSetting[] enchants = new StringSetting[5];
    private final BooleanSetting[] restock = new BooleanSetting[5];
    private final StringSetting[] orderAmounts = new StringSetting[5];
    private final ModeSetting[] priceModes = new ModeSetting[5];
    private final StringSetting[] fixedPrices = new StringSetting[5];
    private final StringSetting[] bidIncrements = new StringSetting[5];
    private final StringSetting[] maxPrices = new StringSetting[5];
    private final StringSetting undercut;
    private final NumberSetting priceCheck;
    private final NumberSetting salesCheck;
    private final NumberSetting perListing;
    private final NumberSetting maxListings;
    private final NumberSetting listingDelay;
    private final NumberSetting relistGap;
    private final NumberSetting ignoreDrops;
    private final NumberSetting collectSlots;
    private final NumberSetting orderCheck;
    private final MacroWorker worker = new MacroWorker("Anubis Order AH Trader");
    private volatile OrderAhBot bot;

    public OrderAhSellerModule() {
        super("order-ah-seller", "Order AH Trader", "Sells an item on the Auction House, undercuts other sellers and restocks through orders.", ModuleCategory.DONUT_MACROS);
        this.usesNotifications();
        for (int i = 0; i < 5; ++i) {
            Setting[] all;
            int slot = i + 1;
            String id = "product-" + slot + "-";
            this.items[i] = (StringSetting)this.addSetting(id + "item", (Setting)new StringSetting("Item", slot == 1 ? "barrel" : "").hint("e.g. barrel or diamond_chestplate").itemPicker());
            this.minPrices[i] = (StringSetting)this.addSetting(id + "min-price", (Setting)new StringSetting("Min Price (each)", slot == 1 ? "3k" : "1k").hint("e.g. 3k"));
            this.enchants[i] = (StringSetting)this.addSetting(id + "enchants", (Setting)new StringSetting("Enchantments", "").enchantsFor(this.items[i]));
            this.restock[i] = (BooleanSetting)this.addSetting(id + "restock", (Setting)new BooleanSetting("Restock With Orders", false));
            this.orderAmounts[i] = (StringSetting)this.addSetting(id + "order-amount", (Setting)new StringSetting("Order Amount", "64").hint("e.g. 64"));
            this.priceModes[i] = (ModeSetting)this.addSetting(id + "order-price", (Setting)new ModeSetting("Order Price", PRICE_BEAT, new String[]{PRICE_BEAT, PRICE_FIXED}));
            this.fixedPrices[i] = (StringSetting)this.addSetting(id + "fixed-price", (Setting)new StringSetting("Fixed Order Price (each)", "100").hint("e.g. 150k"));
            this.bidIncrements[i] = (StringSetting)this.addSetting(id + "bid-increment", (Setting)new StringSetting("Beat By", "1").hint("e.g. 1 or 100"));
            this.maxPrices[i] = (StringSetting)this.addSetting(id + "max-price", (Setting)new StringSetting("Max Order Price (each)", "").hint("Empty = no limit"));
            int index = i;
            for (Setting setting : all = new Setting[]{this.items[i], this.minPrices[i], this.enchants[i], this.restock[i], this.orderAmounts[i], this.priceModes[i], this.fixedPrices[i], this.bidIncrements[i], this.maxPrices[i]}) {
                setting.visibleWhen(() -> this.selected() == index);
            }
            this.enchants[i].visibleWhen(() -> this.selected() == index && ItemEnchants.supports((Item)Product.resolveItem(this.items[index].get())));
            this.orderAmounts[i].visibleWhen(() -> this.selected() == index && this.restock[index].get());
            this.priceModes[i].visibleWhen(() -> this.selected() == index && this.restock[index].get());
            this.fixedPrices[i].visibleWhen(() -> this.selected() == index && this.restock[index].get() && this.priceModes[index].is(PRICE_FIXED));
            this.bidIncrements[i].visibleWhen(() -> this.selected() == index && this.restock[index].get() && this.priceModes[index].is(PRICE_BEAT));
            this.maxPrices[i].visibleWhen(() -> this.selected() == index && this.restock[index].get());
        }
        this.undercut = (StringSetting)this.addSetting("undercut", (Setting)new StringSetting("Undercut By", "100").hint("e.g. 100"));
        this.priceCheck = (NumberSetting)this.addSetting("price-check", (Setting)new NumberSetting("Price Check (s)", 120.0f, 10.0f, 600.0f).step(10.0f));
        this.salesCheck = (NumberSetting)this.addSetting("sales-check", (Setting)new NumberSetting("Sales Check (s)", 20.0f, 5.0f, 300.0f).step(5.0f));
        this.perListing = (NumberSetting)this.addSetting("per-listing", (Setting)new NumberSetting("Items Per Listing", 1.0f, 1.0f, 64.0f).step(1.0f));
        this.maxListings = (NumberSetting)this.addSetting("max-listings", (Setting)new NumberSetting("Max Listings", 25.0f, 1.0f, 50.0f).step(1.0f));
        this.listingDelay = (NumberSetting)this.addSetting("listing-delay", (Setting)new NumberSetting("Listing Delay (s)", 0.0f, 0.0f, 5.0f).step(0.25f));
        this.relistGap = (NumberSetting)this.addSetting("relist-gap", (Setting)new NumberSetting("Relist Higher At (x undercut)", 3.0f, 1.0f, 20.0f).step(0.5f));
        this.ignoreDrops = (NumberSetting)this.addSetting("ignore-drops", (Setting)new NumberSetting("Ignore Drops Over (x undercut)", 20.0f, 1.0f, 100.0f).step(1.0f));
        this.collectSlots = (NumberSetting)this.addSetting("collect-slots", (Setting)new NumberSetting("Collect Up To (slots)", 18.0f, 1.0f, 36.0f).step(1.0f));
        this.orderCheck = (NumberSetting)this.addSetting("order-check", (Setting)new NumberSetting("Order Check (s)", 15.0f, 5.0f, 300.0f).step(5.0f));
        this.collectSlots.visibleWhen(this::anyRestock);
        this.orderCheck.visibleWhen(this::anyRestock);
    }

    private int selected() {
        return Integer.parseInt(this.productSlot.get()) - 1;
    }

    private boolean anyRestock() {
        return this.restock[this.selected()].get();
    }

    public boolean persistEnabled() {
        return false;
    }

    protected void onEnable() {
        OrderAhBot created;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getUser() == null) {
            this.info(ModuleChat.body().warn("Join a server first."));
            this.requestDisable();
            return;
        }
        String problem = this.validate();
        if (problem != null) {
            this.info(ModuleChat.body().warn(problem));
            this.requestDisable();
            return;
        }
        this.bot = created = new OrderAhBot(this, this.worker, this.buildProduct(), this.buildConfig(), mc.getUser().getName());
        this.worker.start(created::run, failure -> {
            LOGGER.error("[Anubis] Order AH Trader stopped", failure);
            this.info(ModuleChat.body().danger("Stopped").text(": " + String.valueOf(failure)));
            mc.execute(() -> {
                if (this.bot == created) {
                    this.setEnabled(false);
                }
            });
        });
    }

    protected void onDisable() {
        OrderAhBot stopped = this.bot;
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
        OrderAhBot current = this.bot;
        if (current != null && (class_25962 = event.packet) instanceof ClientboundSystemChatPacket && (chat = (ClientboundSystemChatPacket)class_25962).content() != null) {
            current.onChat(chat.content().getString());
        }
    }

    private String validate() {
        int i = this.selected();
        Item item = Product.resolveItem(this.items[i].get());
        if (item == null) {
            return "Product " + (i + 1) + ": unknown item '" + this.items[i].get() + "'.";
        }
        if (ServerText.parseMoney(this.minPrices[i].get()) <= 0L) {
            return "Product " + (i + 1) + ": Min Price must be a price like 3k.";
        }
        if (ServerText.parseMoney(this.undercut.get()) < 0L) {
            return "Undercut By must be a price like 100.";
        }
        if (!this.restock[i].get()) {
            return null;
        }
        if (OrderAhSellerModule.parseAmount(this.orderAmounts[i].get()) <= 0) {
            return "Product " + (i + 1) + ": Order Amount must be a whole number.";
        }
        if (this.priceModes[i].is(PRICE_FIXED) && ServerText.parseMoney(this.fixedPrices[i].get()) <= 0L) {
            return "Product " + (i + 1) + ": Fixed Order Price must be a price like 150k.";
        }
        if (this.priceModes[i].is(PRICE_BEAT) && ServerText.parseMoney(this.bidIncrements[i].get()) <= 0L) {
            return "Product " + (i + 1) + ": Beat By must be a price like 1.";
        }
        if (!this.maxPrices[i].get().isBlank() && ServerText.parseMoney(this.maxPrices[i].get()) <= 0L) {
            return "Product " + (i + 1) + ": Max Order Price must be empty or a price like 200k.";
        }
        return null;
    }

    private Product buildProduct() {
        int i = this.selected();
        Item item = Product.resolveItem(this.items[i].get());
        long max = this.maxPrices[i].get().isBlank() ? 0L : ServerText.parseMoney(this.maxPrices[i].get());
        return new Product(item, this.items[i].get().trim(), Product.searchText(item), new ItemStack((ItemLike)item).getHoverName().getString(), ServerText.parseMoney(this.minPrices[i].get()), ItemEnchants.supports((Item)item) ? Product.parseEnchants(this.enchants[i].get()) : List.of(), this.restock[i].get(), OrderAhSellerModule.parseAmount(this.orderAmounts[i].get()), this.priceModes[i].is(PRICE_BEAT), Math.max(1L, ServerText.parseMoney(this.fixedPrices[i].get())), Math.max(1L, ServerText.parseMoney(this.bidIncrements[i].get())), Math.max(0L, max));
    }

    private AuctionHouse.Config buildConfig() {
        return new AuctionHouse.Config(Math.max(0L, ServerText.parseMoney(this.undercut.get())), (long)(this.priceCheck.get() * 1000.0f), (long)(this.salesCheck.get() * 1000.0f), this.perListing.getInt(), this.maxListings.getInt(), (long)(this.listingDelay.get() * 1000.0f), this.relistGap.get(), this.ignoreDrops.get(), this.collectSlots.getInt(), (long)(this.orderCheck.get() * 1000.0f));
    }

    private static int parseAmount(String raw) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : -1;
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    private void info(ModuleChat.Body body) {
        ModuleChat.send((ClientModule)this, (ModuleChat.Body)body);
    }
}
