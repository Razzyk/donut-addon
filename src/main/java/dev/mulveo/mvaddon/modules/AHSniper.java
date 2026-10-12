package dev.mulveo.mvaddon.modules;

import dev.mulveo.mvaddon.MVAddon;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.donut.addon.mv.compat.events.game.ReceiveMessageEvent;
import dev.donut.addon.mv.compat.events.world.TickEvent.Post;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.SettingGroup;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.settings.EnchantmentListSetting;
import dev.donut.addon.mv.compat.settings.EnumSetting;
import dev.donut.addon.mv.compat.settings.IntSetting;
import dev.donut.addon.mv.compat.settings.ItemSetting;
import dev.donut.addon.mv.compat.settings.StringSetting;
import dev.donut.addon.mv.compat.systems.modules.Module;
import dev.donut.addon.mv.compat.utils.player.InvUtils;
import dev.donut.addon.mv.compat.events.EventHandler;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item.TooltipContext;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.ShulkerBoxBlock;

public final class AHSniper extends Module {
   private static final int AUCTION_ROWS = 6;
   private static final int CONFIRM_ROWS = 3;
   private static final int LISTING_END_SLOT = 45;
   private static final int REFRESH_SLOT = 49;
   private static final int CONFIRM_BUTTON_SLOT = 15;
   private static final int PURCHASE_CONFIRM_TIMEOUT_TICKS = 20;
   private static final int SELL_DELAY_TICKS = 2;
   private static final int MAX_STAGNANT_TICKS = 2200;
   private static final Pattern[] PRICE_PATTERNS = new Pattern[]{
      Pattern.compile("\\$\\s*([\\d,]+(?:\\.[\\d]+)?)([kmb])?", 2),
      Pattern.compile("(?:price|buy\\s+for)\\s*:?\\s*\\$?\\s*([\\d,]+(?:\\.[\\d]+)?)([kmb])?", 2),
      Pattern.compile("([\\d,]+(?:\\.[\\d]+)?)([kmb])?\\s*(?:coins?|\\$)", 2),
      Pattern.compile("\\b([\\d,]+(?:\\.[\\d]+)?)([kmb])\\b", 2)
   };
   private static final Pattern SELF_DESTRUCT_PATTERN = Pattern.compile("self\\s*destruct[:\\s]*([\\d\\s+d+h+m+s]+)", 2);
   private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
   private final SettingGroup sgSingle = this.settings.createGroup("Single Snipe");
   private final SettingGroup sgMulti = this.settings.createGroup("Multi Snipe");
   private final SettingGroup sgAutoSell = this.settings.createGroup("Auto Sell");
   private final SettingGroup sgWebhook = this.settings.createGroup("Webhook");
   private final Setting<AHSniper.SnipeMode> snipeMode = this.sgGeneral
      .add(
         new EnumSetting.Builder<AHSniper.SnipeMode>().name("snipe-mode").description("Snipes one configured item or up to five configured items.")
               .defaultValue(AHSniper.SnipeMode.Single)
            .build()
      );
   private final Setting<Boolean> filterLowTime = this.sgGeneral
      .add(
         new BoolSetting.Builder()
                     .name("filter-low-time")
                  .description("Ignores listings that expire sooner than the configured minimum.")
               .defaultValue(true)
            .build()
      );
   private final Setting<Double> minTimeHours = this.sgGeneral
      .add(
         new DoubleSetting.Builder()
                     .name("min-time-hours")
                  .description("Minimum number of hours remaining on a listing.")
               .defaultValue(24.0)
               .range(1.0, 72.0)
               .sliderRange(1.0, 72.0)
               .decimalPlaces(0)
               .visible(this.filterLowTime::get)
            .build()
      );
   private final Setting<Boolean> topLeftOnly = this.sgGeneral
      .add(
         new BoolSetting.Builder()
                        .name("top-left-only")
                     .description("Checks only the newest top-left listing in single mode.")
                  .defaultValue(false)
               .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Single)
            .build()
      );
   private final Setting<Boolean> notifications = this.sgGeneral
      .add(
         new BoolSetting.Builder()
                     .name("notifications")
                  .description("Shows purchase status messages and plays a success sound.")
               .defaultValue(true)
            .build()
      );
   private final Setting<Boolean> autoStop = this.sgGeneral
      .add(
         new BoolSetting.Builder()
                     .name("auto-stop")
                  .description("Disables AH Sniper after one successful purchase.")
               .defaultValue(true)
            .build()
      );
   private final Setting<Integer> purchaseConfirmSlot = this.sgGeneral
      .add(
         new IntSetting.Builder()
                     .name("purchase-confirm-slot")
                  .description("Fallback slot clicked on the purchase confirmation screen.")
               .defaultValue(15)
            .min(0)
            .max(53)
            .sliderMax(53)
            .build()
      );
   private final Setting<Integer> purchaseConfirmDelay = this.sgGeneral
      .add(
         new IntSetting.Builder()
                     .name("yes-click-delay")
                  .description("Ticks to wait before pressing the custom confirmation screen's Yes button.")
               .defaultValue(0)
            .min(0)
            .max(40)
            .sliderMax(20)
            .build()
      );
   private final Setting<Item> snipingItem = this.sgSingle
      .add(
         new ItemSetting.Builder()
                        .name("sniping-item")
                     .description("Item to buy in single mode.")
                  .defaultValue(Items.AIR)
               .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Single)
            .build()
      );
   private final Setting<String> targetItemName = this.sgSingle
      .add(
         new StringSetting.Builder()
                        .name("item-name")
                     .description("Optional custom text appended to /ah instead of the registry item name.")
                  .defaultValue("")
               .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Single)
            .build()
      );
   private final Setting<String> minPrice = this.sgSingle
      .add(
         new StringSetting.Builder()
                        .name("min-price")
                     .description("Minimum accepted price. Supports k, m, and b suffixes.")
                  .defaultValue("0")
               .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Single)
            .build()
      );
   private final Setting<String> maxPrice = this.sgSingle
      .add(
         new StringSetting.Builder()
                        .name("max-price")
                     .description("Maximum accepted price. Supports k, m, and b suffixes.")
                  .defaultValue("1k")
               .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Single)
            .build()
      );
   private final Setting<AHSniper.PriceMode> priceMode = this.sgSingle
      .add(
         new EnumSetting.Builder<AHSniper.PriceMode>().name("price-mode")
                     .description("Per Stack requires one full stack and checks its total price. Per Item checks one item's price.")
                  .defaultValue(AHSniper.PriceMode.PerStack)
               .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Single)
            .build()
      );
   private final Setting<Boolean> enchantmentMode = this.sgSingle
      .add(
         new BoolSetting.Builder()
                        .name("enchantment-mode")
                     .description("Requires selected enchantments in single mode.")
                  .defaultValue(false)
               .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Single)
            .build()
      );
   private final Setting<Set<ResourceKey<Enchantment>>> requiredEnchantments = this.sgSingle
      .add(
         new EnchantmentListSetting.Builder()
                     .name("enchants")
                  .description("Enchantments required on the item.")
               .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Single && this.enchantmentMode.get())
            .build()
      );
   private final Setting<Boolean> exactEnchantments = this.sgSingle
      .add(
         new BoolSetting.Builder()
                        .name("exact-enchantments")
                     .description("Requires the item to have exactly the selected enchantments.")
                  .defaultValue(false)
               .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Single && this.enchantmentMode.get())
            .build()
      );
   private final Setting<Item> multiItem1 = this.multiItem("item-1", "First item to buy.");
   private final Setting<String> multiMinPrice1 = this.multiPrice("min-price-1", "0", this.multiItem1);
   private final Setting<String> multiMaxPrice1 = this.multiPrice("max-price-1", "1k", this.multiItem1);
   private final Setting<AHSniper.PriceMode> multiPriceMode1 = this.multiPriceMode("price-mode-1", this.multiItem1);
   private final Setting<Set<ResourceKey<Enchantment>>> multiEnchantments1 = this.multiEnchantments("enchants-1", this.multiItem1);
   private final Setting<Item> multiItem2 = this.multiItem("item-2", "Second item to buy.");
   private final Setting<String> multiMinPrice2 = this.multiPrice("min-price-2", "0", this.multiItem2);
   private final Setting<String> multiMaxPrice2 = this.multiPrice("max-price-2", "1k", this.multiItem2);
   private final Setting<AHSniper.PriceMode> multiPriceMode2 = this.multiPriceMode("price-mode-2", this.multiItem2);
   private final Setting<Set<ResourceKey<Enchantment>>> multiEnchantments2 = this.multiEnchantments("enchants-2", this.multiItem2);
   private final Setting<Item> multiItem3 = this.multiItem("item-3", "Third item to buy.");
   private final Setting<String> multiMinPrice3 = this.multiPrice("min-price-3", "0", this.multiItem3);
   private final Setting<String> multiMaxPrice3 = this.multiPrice("max-price-3", "1k", this.multiItem3);
   private final Setting<AHSniper.PriceMode> multiPriceMode3 = this.multiPriceMode("price-mode-3", this.multiItem3);
   private final Setting<Set<ResourceKey<Enchantment>>> multiEnchantments3 = this.multiEnchantments("enchants-3", this.multiItem3);
   private final Setting<Item> multiItem4 = this.multiItem("item-4", "Fourth item to buy.");
   private final Setting<String> multiMinPrice4 = this.multiPrice("min-price-4", "0", this.multiItem4);
   private final Setting<String> multiMaxPrice4 = this.multiPrice("max-price-4", "1k", this.multiItem4);
   private final Setting<AHSniper.PriceMode> multiPriceMode4 = this.multiPriceMode("price-mode-4", this.multiItem4);
   private final Setting<Set<ResourceKey<Enchantment>>> multiEnchantments4 = this.multiEnchantments("enchants-4", this.multiItem4);
   private final Setting<Item> multiItem5 = this.multiItem("item-5", "Fifth item to buy.");
   private final Setting<String> multiMinPrice5 = this.multiPrice("min-price-5", "0", this.multiItem5);
   private final Setting<String> multiMaxPrice5 = this.multiPrice("max-price-5", "1k", this.multiItem5);
   private final Setting<AHSniper.PriceMode> multiPriceMode5 = this.multiPriceMode("price-mode-5", this.multiItem5);
   private final Setting<Set<ResourceKey<Enchantment>>> multiEnchantments5 = this.multiEnchantments("enchants-5", this.multiItem5);
   private final Setting<Boolean> autoSell = this.sgAutoSell
      .add(
         new BoolSetting.Builder()
                     .name("auto-sell")
                  .description("Lists a successfully purchased item back on the auction house.")
               .defaultValue(false)
            .build()
      );
   private final Setting<String> sellPrice = this.sgAutoSell
      .add(
         new StringSetting.Builder()
                        .name("sell-price")
                     .description("Price used by /ah sell. Supports k, m, and b suffixes.")
                  .defaultValue("14m")
               .visible(this.autoSell::get)
            .build()
      );
   private final Setting<Boolean> webhookEnabled = this.sgWebhook
      .add(
         new BoolSetting.Builder()
                     .name("webhook-enabled")
                  .description("Sends successful purchase alerts to a Discord webhook.")
               .defaultValue(false)
            .build()
      );
   private final Setting<String> webhookUrl = this.sgWebhook
      .add(
         new StringSetting.Builder()
                        .name("webhook-url")
                     .description("Discord webhook URL.")
                  .defaultValue("")
               .visible(this.webhookEnabled::get)
            .wide()
            .build()
      );
   private final Setting<Boolean> selfPing = this.sgWebhook
      .add(
         new BoolSetting.Builder()
                        .name("self-ping")
                     .description("Mentions the configured Discord user on a successful purchase.")
                  .defaultValue(false)
               .visible(this.webhookEnabled::get)
            .build()
      );
   private final Setting<String> discordId = this.sgWebhook
      .add(
         new StringSetting.Builder()
                        .name("discord-id")
                     .description("Discord user ID to mention.")
                  .defaultValue("")
               .visible(() -> this.webhookEnabled.get() && this.selfPing.get())
            .build()
      );
   private final List<AHSniper.SnipeItemConfig> multiSnipeConfigs = new ArrayList<>();
   private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10L)).build();
   private boolean waitingForConfirmation;
   private boolean purchaseAttempted;
   private boolean commandSent;
   private boolean hasSetSort;
   private boolean hasClickedBuy;
   private boolean pageJustRefreshed;
   private boolean sellingPhase;
   private boolean sellConfirmPending;
   private boolean stopAfterAutoSell;
   private boolean purchaseUnavailableMessage;
   private String attemptedItemName = "";
   private String attemptedEnchantments = "None";
   private double attemptedActualPrice;
   private int attemptedQuantity;
   private int previousItemCount;
   private int inventoryCheckTicks;
   private int navigationDelayCounter;
   private int purchaseTimeoutTicks;
   private int purchaseListingSyncId;
   private int attemptedListingSlot;
   private int purchaseClickRetries;
   private int confirmationDelayCounter;
   private int sellingDelayCounter;
   private int sellConfirmTicks;
   private int lastActionTicks;
   private String purchaseListingTitle = "";
   private Item currentSnipedItem;

   public AHSniper() {
      super(MVAddon.UTILITY, "ah-sniper", "Automatically searches auctions and purchases matching listings.");
   }

   private Setting<Item> multiItem(String var1, String var2) {
      return this.sgMulti
         .add(
            new ItemSetting.Builder()
                           .name(var1)
                        .description(var2)
                     .defaultValue(Items.AIR)
                  .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Multi)
               .build()
         );
   }

   private Setting<String> multiPrice(String var1, String var2, Setting<Item> var3) {
      return this.sgMulti
         .add(
            new StringSetting.Builder()
                           .name(var1)
                        .description("Accepted price. Supports k, m, and b suffixes.")
                     .defaultValue(var2)
                  .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Multi && var3.get() != Items.AIR)
               .build()
         );
   }

   private Setting<AHSniper.PriceMode> multiPriceMode(String var1, Setting<Item> var2) {
      return this.sgMulti
         .add(
            new EnumSetting.Builder<AHSniper.PriceMode>().name(var1)
                        .description("Per Stack requires one full stack and checks its total price. Per Item checks one item's price.")
                     .defaultValue(AHSniper.PriceMode.PerStack)
                  .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Multi && var2.get() != Items.AIR)
               .build()
         );
   }

   private Setting<Set<ResourceKey<Enchantment>>> multiEnchantments(String var1, Setting<Item> var2) {
      return this.sgMulti
         .add(
            new EnchantmentListSetting.Builder()
                        .name(var1)
                     .description("Enchantments required on this item.")
                  .visible(() -> this.snipeMode.get() == AHSniper.SnipeMode.Multi && var2.get() != Items.AIR)
               .build()
         );
   }

   public void onActivate() {
      if (this.mc.player == null) {
         this.error("Join a world before enabling AH Sniper.");
         this.disable();
      } else {
         this.multiSnipeConfigs.clear();
         if (this.snipeMode.get() == AHSniper.SnipeMode.Multi) {
            this.addMultiConfig(this.multiItem1, this.multiMinPrice1, this.multiMaxPrice1, this.multiPriceMode1, this.multiEnchantments1);
            this.addMultiConfig(this.multiItem2, this.multiMinPrice2, this.multiMaxPrice2, this.multiPriceMode2, this.multiEnchantments2);
            this.addMultiConfig(this.multiItem3, this.multiMinPrice3, this.multiMaxPrice3, this.multiPriceMode3, this.multiEnchantments3);
            this.addMultiConfig(this.multiItem4, this.multiMinPrice4, this.multiMaxPrice4, this.multiPriceMode4, this.multiEnchantments4);
            this.addMultiConfig(this.multiItem5, this.multiMinPrice5, this.multiMaxPrice5, this.multiPriceMode5, this.multiEnchantments5);
            if (this.multiSnipeConfigs.isEmpty()) {
               this.error("No multi-snipe items are configured.");
               this.disable();
               return;
            }
         } else if (this.snipingItem.get() == Items.AIR) {
            this.error("Select an item to snipe first.");
            this.disable();
            return;
         }

         this.resetState();
         this.currentSnipedItem = this.snipeMode.get() == AHSniper.SnipeMode.Single ? this.snipingItem.get() : null;
         this.previousItemCount = this.countItemInInventory();
      }
   }

   public void onDeactivate() {
      this.resetState();
      this.multiSnipeConfigs.clear();
   }

   private void addMultiConfig(
      Setting<Item> var1, Setting<String> var2, Setting<String> var3, Setting<AHSniper.PriceMode> var4, Setting<Set<ResourceKey<Enchantment>>> var5
   ) {
      if (var1.get() != Items.AIR) {
         this.multiSnipeConfigs
            .add(
               new AHSniper.SnipeItemConfig(
                  (Item)var1.get(),
                  (String)var2.get(),
                  (String)var3.get(),
                  (AHSniper.PriceMode)var4.get(),
                  Set.copyOf((Collection<? extends ResourceKey<Enchantment>>)var5.get())
               )
            );
      }
   }

   private void resetState() {
      this.waitingForConfirmation = false;
      this.purchaseAttempted = false;
      this.commandSent = false;
      this.hasSetSort = false;
      this.hasClickedBuy = false;
      this.pageJustRefreshed = false;
      this.sellingPhase = false;
      this.sellConfirmPending = false;
      this.stopAfterAutoSell = false;
      this.purchaseUnavailableMessage = false;
      this.inventoryCheckTicks = 0;
      this.navigationDelayCounter = 0;
      this.purchaseTimeoutTicks = 0;
      this.purchaseListingSyncId = -1;
      this.attemptedListingSlot = -1;
      this.purchaseClickRetries = 0;
      this.confirmationDelayCounter = -1;
      this.sellingDelayCounter = 0;
      this.sellConfirmTicks = 0;
      this.lastActionTicks = 0;
      this.attemptedItemName = "";
      this.attemptedEnchantments = "None";
      this.attemptedActualPrice = 0.0;
      this.attemptedQuantity = 0;
      this.purchaseListingTitle = "";
      this.currentSnipedItem = null;
   }

   @EventHandler
   private void onTick(Post var1) {
      if (this.mc.player != null && this.mc.level != null && this.mc.gameMode != null) {
         if (++this.lastActionTicks >= 2200) {
            this.recoverAuction();
         } else {
            if (this.navigationDelayCounter > 0) {
               this.navigationDelayCounter--;
               if (this.navigationDelayCounter > 0) {
                  return;
               }

               this.pageJustRefreshed = false;
            }

            if (this.sellingDelayCounter > 0) {
               this.sellingDelayCounter--;
               if (this.sellingDelayCounter == 0) {
                  this.performAutoSell();
               }
            } else if (!this.sellConfirmPending || !this.handleSellConfirmation()) {
               AbstractContainerMenu var2 = this.mc.player.containerMenu;
               if (this.waitingForConfirmation) {
                  this.handleAwaitingPurchase(var2);
               } else if (this.purchaseAttempted) {
                  this.handlePurchaseCheck();
               } else if (!this.sellingPhase) {
                  if (var2 instanceof ChestMenu var3) {
                     this.commandSent = true;
                     if (var3.getRowCount() == 6) {
                        if (this.snipeMode.get() == AHSniper.SnipeMode.Multi) {
                           this.processMultiSnipeAuction(var3);
                        } else if (this.topLeftOnly.get()) {
                           this.processTopLeftOnly(var3);
                        } else {
                           this.processSingleSnipeAuction(var3);
                        }
                     }
                  } else {
                     if (this.commandSent && !this.hasClickedBuy) {
                        this.commandSent = false;
                        this.hasSetSort = false;
                     }

                     if (!this.commandSent) {
                        this.openAuctionHouse();
                        this.commandSent = true;
                     }
                  }
               }
            }
         }
      }
   }

   @EventHandler
   private void onMessage(ReceiveMessageEvent var1) {
      if (this.waitingForConfirmation) {
         String var2 = var1.getMessage().getString().toLowerCase(Locale.ROOT);
         if (this.isUnavailableText(var2)) {
            this.purchaseUnavailableMessage = true;
         }
      }
   }

   private void processTopLeftOnly(ChestMenu var1) {
      if ((this.hasSetSort || !this.selectRecentlyListed(var1)) && !this.pageJustRefreshed && !this.purchaseAttempted && !this.hasClickedBuy) {
         ItemStack var2 = var1.getSlot(0).getItem();
         double var3 = this.getActualPrice(var2);
         if (this.matchesTarget(var2, this.snipingItem.get()) && this.isPurchasableListing(var2) && this.isValidAuctionItem(var2) && var3 >= 0.0) {
            this.beginPurchase(var1, 0, var2, this.snipingItem.get(), var3);
         } else {
            this.refreshAuction(var1, ContainerInput.QUICK_MOVE, 1);
         }
      }
   }

   private void processSingleSnipeAuction(ChestMenu var1) {
      if ((this.hasSetSort || !this.selectRecentlyListed(var1)) && !this.pageJustRefreshed && !this.purchaseAttempted && !this.hasClickedBuy) {
         for (int var2 = 0; var2 < 45; var2++) {
            ItemStack var3 = var1.getSlot(var2).getItem();
            if (this.matchesTarget(var3, this.snipingItem.get())
               && this.isPurchasableListing(var3)
               && (this.isShulkerBox(var3) || !this.hasCursedEnchantments(var3))) {
               double var4 = this.getActualPrice(var3);
               if (this.isValidAuctionItem(var3) && !(var4 < 0.0)) {
                  this.beginPurchase(var1, var2, var3, this.snipingItem.get(), var4);
                  return;
               }
            }
         }

         this.refreshAuction(var1, ContainerInput.QUICK_MOVE, 1);
      }
   }

   private void processMultiSnipeAuction(ChestMenu var1) {
      if ((this.hasSetSort || !this.selectRecentlyListed(var1)) && !this.pageJustRefreshed && !this.purchaseAttempted && !this.hasClickedBuy) {
         for (int var2 = 0; var2 < 45; var2++) {
            ItemStack var3 = var1.getSlot(var2).getItem();
            if (!var3.isEmpty() && (this.isShulkerBox(var3) || !this.hasCursedEnchantments(var3))) {
               for (AHSniper.SnipeItemConfig var5 : this.multiSnipeConfigs) {
                  if (this.matchesTarget(var3, var5.item) && this.isPurchasableListing(var3)) {
                     double var6 = this.getActualPrice(var3);
                     if (this.isValidMultiSnipeItem(var3, var5) && !(var6 < 0.0)) {
                        this.beginPurchase(var1, var2, var3, var5.item, var6);
                        return;
                     }
                  }
               }
            }
         }

         this.refreshAuction(var1, ContainerInput.QUICK_MOVE, 1);
      }
   }

   private void beginPurchase(ChestMenu var1, int var2, ItemStack var3, Item var4, double var5) {
      int var7 = this.getTargetQuantity(var3, var4);
      if (var7 > 0) {
         this.currentSnipedItem = var4;
         this.attemptedItemName = var3.getHoverName().getString();
         this.attemptedActualPrice = var5;
         this.attemptedQuantity = var7;
         this.attemptedEnchantments = this.getEnchantmentsString(var3);
         this.previousItemCount = this.countItemInInventory();
         this.hasClickedBuy = true;
         this.waitingForConfirmation = true;
         this.purchaseAttempted = false;
         this.inventoryCheckTicks = 0;
         this.purchaseTimeoutTicks = 0;
         this.purchaseListingSyncId = var1.containerId;
         this.attemptedListingSlot = var2;
         this.purchaseClickRetries = 0;
         this.purchaseUnavailableMessage = false;
         this.purchaseListingTitle = this.screenTitle();
         this.confirmationDelayCounter = -1;
         this.lastActionTicks = 0;
         this.clickSlot(var1, var2, 0, ContainerInput.PICKUP);
         this.notifyUser("Attempting to buy %dx %s.", var7, this.attemptedItemName);
      }
   }

   private void handleAwaitingPurchase(AbstractContainerMenu var1) {
      this.purchaseTimeoutTicks++;
      AbstractButton var2 = this.findScreenConfirmationButton();
      if (var2 != null) {
         if (this.confirmationDelayElapsed() && this.clickScreenConfirmationButton(var2)) {
            this.markPurchaseAttempted();
         }
      } else if (this.countItemInInventory() > this.previousItemCount) {
         this.markPurchaseAttempted();
      } else if (this.purchaseUnavailableMessage) {
         this.notifyUser("The listing was already bought or is no longer purchasable. Refreshing.");
         this.resumeAfterUnavailableListing(var1);
      } else if (this.purchaseTimeoutTicks >= 20) {
         this.warning("The clicked listing did not open a purchasable confirmation. Refreshing.");
         this.resumeAfterUnavailableListing(var1);
      } else if (var1 instanceof ChestMenu var3 && this.isPurchaseConfirmation(var3)) {
         if (this.confirmationDelayElapsed()) {
            int var9 = this.findPurchaseConfirmationSlot(var3);
            if (var9 >= 0) {
               this.clickSlot(var3, var9, 0, ContainerInput.PICKUP);
               this.markPurchaseAttempted();
            }
         }
      } else if (var1 instanceof ChestMenu var8
         && var8.containerId == this.purchaseListingSyncId
         && var8.getRowCount() == 6
         && this.screenTitle().equals(this.purchaseListingTitle)
         && this.attemptedListingSlot >= 0
         && this.attemptedListingSlot < 45) {
         ItemStack var4 = var8.getSlot(this.attemptedListingSlot).getItem();
         double var5 = this.getActualPrice(var4);
         boolean var7 = this.matchesTarget(var4, this.currentSnipedItem)
            && this.isPurchasableListing(var4)
            && var5 >= 0.0
            && Double.compare(var5, this.attemptedActualPrice) == 0;
         if (this.purchaseTimeoutTicks >= 2 && !var7) {
            this.notifyUser("The selected listing was bought before it could be confirmed. Refreshing.");
            this.resumeAfterUnavailableListing(var1);
            return;
         }

         if (this.purchaseTimeoutTicks >= 2 && this.purchaseClickRetries == 0 && var7) {
            this.purchaseClickRetries++;
            this.clickSlot(var8, this.attemptedListingSlot, 0, ContainerInput.PICKUP);
            this.lastActionTicks = 0;
         }
      }
   }

   private void resumeAfterUnavailableListing(AbstractContainerMenu var1) {
      this.waitingForConfirmation = false;
      this.purchaseAttempted = false;
      this.hasClickedBuy = false;
      this.purchaseTimeoutTicks = 0;
      this.inventoryCheckTicks = 0;
      this.confirmationDelayCounter = -1;
      this.attemptedListingSlot = -1;
      this.purchaseClickRetries = 0;
      this.purchaseUnavailableMessage = false;
      this.lastActionTicks = 0;
      if (var1 instanceof ChestMenu var2
         && var2.getRowCount() == 6
         && var2.containerId == this.purchaseListingSyncId
         && this.screenTitle().equals(this.purchaseListingTitle)) {
         this.refreshAuction(var2, ContainerInput.QUICK_MOVE, 1);
      } else {
         this.recoverAuction();
      }
   }

   private boolean isPurchaseConfirmation(ChestMenu var1) {
      String var2 = this.screenTitle();
      if (var1.containerId == this.purchaseListingSyncId && var1.getRowCount() == 6 && var2.equals(this.purchaseListingTitle)) {
         return false;
      } else if (!var2.contains("confirm") && !var2.contains("are you sure") && !var2.contains("purchase") && !var2.contains("buy")) {
         return var1.getRowCount() == 3
            ? true
            : var1.containerId != this.purchaseListingSyncId && !var2.equals(this.purchaseListingTitle) && var1.getRowCount() != 6;
      } else {
         return true;
      }
   }

   private boolean confirmationDelayElapsed() {
      if (this.confirmationDelayCounter < 0) {
         this.confirmationDelayCounter = this.purchaseConfirmDelay.get();
      }

      if (this.confirmationDelayCounter > 0) {
         this.confirmationDelayCounter--;
         return false;
      } else {
         return true;
      }
   }

   private int findPurchaseConfirmationSlot(ChestMenu var1) {
      int var2 = Math.min(var1.getRowCount() * 9, var1.slots.size());

      for (int var3 = 0; var3 < var2; var3++) {
         if (this.isConfirmButton(var1.getSlot(var3).getItem())) {
            return var3;
         }
      }

      int var5 = this.purchaseConfirmSlot.get();
      if (var5 >= 0 && var5 < var2) {
         ItemStack var4 = var1.getSlot(var5).getItem();
         if (!var4.isEmpty() && !this.isDeclineButton(var4)) {
            return var5;
         }
      }

      return -1;
   }

   private boolean isConfirmButton(ItemStack var1) {
      if (var1.isEmpty()) {
         return false;
      } else if (!var1.is(Items.STAINED_GLASS_PANE.pick(DyeColor.LIME))
         && !var1.is(Items.STAINED_GLASS_PANE.pick(DyeColor.GREEN))
         && !var1.is(Items.DYE.pick(DyeColor.LIME))
         && !var1.is(Items.DYE.pick(DyeColor.GREEN))) {
         String var2 = var1.getHoverName().getString().toLowerCase(Locale.ROOT);
         return !var2.equals("no") && !var2.contains("cancel") && !var2.contains("decline")
            ? var2.contains("yes") || var2.contains("confirm") || var2.contains("accept") || var2.contains("buy") || var2.contains("purchase")
            : false;
      } else {
         return true;
      }
   }

   private boolean isDeclineButton(ItemStack var1) {
      if (var1.isEmpty()) {
         return false;
      } else if (!var1.is(Items.STAINED_GLASS_PANE.pick(DyeColor.RED)) && !var1.is(Items.BARRIER)) {
         String var2 = var1.getHoverName().getString().toLowerCase(Locale.ROOT);
         return var2.equals("no") || var2.contains("cancel") || var2.contains("decline");
      } else {
         return true;
      }
   }

   private AbstractButton findScreenConfirmationButton() {
      if (this.mc.gui.screen() == null) {
         return null;
      } else {
         ArrayList var1 = new ArrayList();
         this.collectScreenButtons(this.mc.gui.screen().children(), var1);
         AbstractButton var2 = this.largestMatchingButton(var1, true);
         return var2 != null ? var2 : this.largestMatchingButton(var1, false);
      }
   }

   private void collectScreenButtons(List<? extends GuiEventListener> var1, List<AbstractButton> var2) {
      for (GuiEventListener var4 : var1) {
         if (var4 instanceof AbstractButton var5 && var5.active && var5.visible) {
            var2.add(var5);
         }

         if (var4 instanceof ContainerEventHandler var6) {
            this.collectScreenButtons(var6.children(), var2);
         }
      }
   }

   private AbstractButton largestMatchingButton(List<AbstractButton> var1, boolean var2) {
      AbstractButton var3 = null;
      int var4 = -1;

      for (AbstractButton var6 : var1) {
         String var7 = var6.getMessage().getString().toLowerCase(Locale.ROOT).trim();
         String var8 = " " + var7.replaceAll("[^a-z0-9]+", " ").trim() + " ";
         boolean var9 = var2
            ? var8.contains(" yes ")
            : var7.contains("confirm") || var7.contains("accept") || var7.contains("buy") || var7.contains("purchase") || var7.equals("ok");
         if (var9) {
            int var10 = var6.getWidth() * var6.getHeight();
            if (var10 > var4) {
               var3 = var6;
               var4 = var10;
            }
         }
      }

      return var3;
   }

   private boolean clickScreenConfirmationButton(AbstractButton var1) {
      Screen var2 = this.mc.gui.screen();
      if (var2 == null) {
         return false;
      } else {
         try {
            double var3 = var1.getX() + var1.getWidth() / 2.0;
            double var5 = var1.getY() + var1.getHeight() / 2.0;
            MouseButtonEvent var7 = new MouseButtonEvent(var3, var5, new MouseButtonInfo(0, 0));
            boolean var8 = var2.mouseClicked(var7, false);
            if (!var8 && this.mc.gui.screen() == var2) {
               var1.onPress(var7);
            }

            return true;
         } catch (Throwable var91) {
            return false;
         }
      }
   }

   private void markPurchaseAttempted() {
      this.waitingForConfirmation = false;
      this.purchaseAttempted = true;
      this.purchaseTimeoutTicks = 0;
      this.inventoryCheckTicks = 0;
      this.confirmationDelayCounter = -1;
      this.lastActionTicks = 0;
   }

   private boolean selectRecentlyListed(ChestMenu var1) {
      int var2 = this.findRecentlyListedSlot(var1);
      if (var2 < 0) {
         this.hasSetSort = true;
         return false;
      } else {
         this.clickSlot(var1, var2, 0, ContainerInput.PICKUP);
         this.hasSetSort = true;
         this.navigationDelayCounter = 1;
         this.lastActionTicks = 0;
         return true;
      }
   }

   private int findRecentlyListedSlot(ChestMenu var1) {
      int var2 = Math.min(45, var1.slots.size() - 1);
      if (var2 >= 0 && this.isRecentlyListed(var1.getSlot(var2).getItem())) {
         return var2;
      } else {
         for (int var3 = 0; var3 < var1.slots.size(); var3++) {
            if (this.isRecentlyListed(var1.getSlot(var3).getItem())) {
               return var3;
            }
         }

         return -1;
      }
   }

   private boolean isRecentlyListed(ItemStack var1) {
      return !var1.isEmpty() && var1.getHoverName().getString().toLowerCase(Locale.ROOT).contains("recently listed");
   }

   private void refreshAuction(ChestMenu var1, ContainerInput var2, int var3) {
      if (var1.slots.size() > 49) {
         this.clickSlot(var1, 49, var3, var2);
         this.navigationDelayCounter = 1;
         this.pageJustRefreshed = true;
         this.lastActionTicks = 0;
      }
   }

   private void clickSlot(AbstractContainerMenu var1, int var2, int var3, ContainerInput var4) {
      if (this.mc.gameMode != null && this.mc.player != null) {
         this.mc.gameMode.handleContainerInput(var1.containerId, var2, var3, var4, this.mc.player);
      }
   }

   private void openAuctionHouse() {
      if (this.mc.getConnection() != null) {
         this.mc.getConnection().sendCommand(this.buildAuctionCommand());
         this.navigationDelayCounter = 3;
         this.lastActionTicks = 0;
      }
   }

   private String buildAuctionCommand() {
      if (this.snipeMode.get() == AHSniper.SnipeMode.Multi) {
         return "ah";
      } else {
         String var1 = (this.targetItemName.get()).trim();
         return !var1.isEmpty() ? "ah " + var1 : "ah " + BuiltInRegistries.ITEM.getKey(this.snipingItem.get()).getPath().replace('_', ' ');
      }
   }

   private boolean isPurchasableListing(ItemStack var1) {
      if (var1.isEmpty()) {
         return false;
      } else if (this.isUnavailableText(var1.getHoverName().getString())) {
         return false;
      } else {
         List<Component> var2 = this.getTooltip(var1);

         for (Component var4 : var2) {
            if (this.isUnavailableText(var4.getString())) {
               return false;
            }
         }

         return this.parseTooltipPrice(var2) >= 0.0;
      }
   }

   private boolean isUnavailableText(String var1) {
      String var2 = var1.toLowerCase(Locale.ROOT);
      return var2.contains("already bought")
         || var2.contains("already purchased")
         || var2.contains("has been bought")
         || var2.contains("has been purchased")
         || var2.contains("purchased by")
         || var2.contains("listing sold")
         || var2.contains("sold out")
         || var2.contains("no longer available")
         || var2.contains("not available")
         || var2.contains("unavailable")
         || var2.contains("listing expired")
         || var2.contains("listing cancelled")
         || var2.contains("listing canceled")
         || var2.contains("someone else bought")
         || var2.contains("could not purchase")
         || var2.contains("cannot purchase")
         || var2.contains("can't purchase");
   }

   private boolean isValidAuctionItem(ItemStack var1) {
      if (var1.isEmpty() || !this.matchesTarget(var1, this.snipingItem.get()) || !this.isPurchasableListing(var1)) {
         return false;
      } else if (!this.passesTimeFilter(var1)) {
         return false;
      } else if (!this.enchantmentMode.get()
         || !this.isShulkerBox(var1)
            && this.hasEnchantments(var1, (Set<ResourceKey<Enchantment>>)this.requiredEnchantments.get(), this.exactEnchantments.get())) {
         double var2 = this.getActualPrice(var1);
         double var4 = this.parsePrice(this.maxPrice.get());
         double var6 = this.parsePrice(this.minPrice.get());
         int var8 = this.getTargetQuantity(var1, this.snipingItem.get());
         if (var4 < 0.0 || var2 < 0.0 || var8 <= 0) {
            return false;
         } else if (this.priceMode.get() == AHSniper.PriceMode.PerStack && var8 != this.getStackLimit(this.snipingItem.get())) {
            return false;
         } else {
            double var9 = this.priceMode.get() == AHSniper.PriceMode.PerItem ? var2 / var8 : var2;
            return var9 <= var4 && (var6 <= 0.0 || var9 >= var6);
         }
      } else {
         return false;
      }
   }

   private boolean isValidMultiSnipeItem(ItemStack var1, AHSniper.SnipeItemConfig var2) {
      if (var1.isEmpty() || !this.matchesTarget(var1, var2.item) || !this.isPurchasableListing(var1)) {
         return false;
      } else if (!this.passesTimeFilter(var1)) {
         return false;
      } else if (var2.enchantments.isEmpty() || !this.isShulkerBox(var1) && this.hasEnchantments(var1, var2.enchantments, false)) {
         double var3 = this.getActualPrice(var1);
         double var5 = this.parsePrice(var2.maxPrice);
         double var7 = this.parsePrice(var2.minPrice);
         int var9 = this.getTargetQuantity(var1, var2.item);
         if (var5 < 0.0 || var3 < 0.0 || var9 <= 0) {
            return false;
         } else if (var2.priceMode == AHSniper.PriceMode.PerStack && var9 != this.getStackLimit(var2.item)) {
            return false;
         } else {
            double var10 = var2.priceMode == AHSniper.PriceMode.PerItem ? var3 / var9 : var3;
            return var10 <= var5 && (var7 <= 0.0 || var10 >= var7);
         }
      } else {
         return false;
      }
   }

   private int getStackLimit(Item var1) {
      return Math.max(1, var1.getDefaultInstance().getMaxStackSize());
   }

   private boolean passesTimeFilter(ItemStack var1) {
      if (!this.filterLowTime.get()) {
         return true;
      } else {
         double var2 = this.parseSelfDestructTime(var1);
         return var2 < 0.0 || var2 >= this.minTimeHours.get();
      }
   }

   private boolean hasEnchantments(ItemStack var1, Set<ResourceKey<Enchantment>> var2, boolean var3) {
      ItemEnchantments var4 = (ItemEnchantments)var1.get(DataComponents.ENCHANTMENTS);
      if (var4 != null && !var4.isEmpty()) {
         if (var3 && var4.size() != var2.size()) {
            return false;
         } else {
            for (ResourceKey var6 : var2) {
               boolean var7 = false;

               for (Holder var9 : var4.keySet()) {
                  if (var9.is(var6)) {
                     var7 = true;
                     break;
                  }
               }

               if (!var7) {
                  return false;
               }
            }

            return true;
         }
      } else {
         return var2.isEmpty();
      }
   }

   private boolean hasCursedEnchantments(ItemStack var1) {
      ItemEnchantments var2 = (ItemEnchantments)var1.get(DataComponents.ENCHANTMENTS);
      if (var2 != null && !var2.isEmpty()) {
         for (Holder<Enchantment> var4 : var2.keySet()) {
            if (var4.unwrapKey().map(var0 -> var0.identifier().getPath().contains("curse")).orElse(false)) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private String getEnchantmentsString(ItemStack var1) {
      ItemEnchantments var2 = (ItemEnchantments)var1.get(DataComponents.ENCHANTMENTS);
      if (var2 != null && !var2.isEmpty()) {
         ArrayList var3 = new ArrayList();

         for (Holder<Enchantment> var5 : var2.keySet()) {
            String var6 = var5.unwrapKey().map(var0 -> var0.identifier().getPath()).orElse("unknown");
            var3.add(var6 + " " + var2.getLevel(var5));
         }

         return var3.isEmpty() ? "None" : String.join("\n", var3);
      } else {
         return "None";
      }
   }

   private List<Component> getTooltip(ItemStack var1) {
      return var1.getTooltipLines(TooltipContext.of(this.mc.level), this.mc.player, TooltipFlag.NORMAL);
   }

   private double getActualPrice(ItemStack var1) {
      return var1.isEmpty() ? -1.0 : this.parseTooltipPrice(this.getTooltip(var1));
   }

   private double parseTooltipPrice(List<Component> var1) {
      if (var1 != null && !var1.isEmpty()) {
         for (Component var3 : var1) {
            String var4 = var3.getString();

            for (Pattern var8 : PRICE_PATTERNS) {
               Matcher var9 = var8.matcher(var4);
               if (var9.find()) {
                  try {
                     double var10 = Double.parseDouble(var9.group(1).replace(",", ""));
                     String var12 = var9.group(2) == null ? "" : var9.group(2).toLowerCase(Locale.ROOT);

                     return var10 * switch (var12) {
                        case "k" -> 1000.0;
                        case "m" -> 1000000.0;
                        case "b" -> 1.0E9;
                        default -> 1.0;
                     };
                  } catch (NumberFormatException var15) {
                  }
               }
            }
         }

         return -1.0;
      } else {
         return -1.0;
      }
   }

   private double parseSelfDestructTime(ItemStack var1) {
      StringBuilder var2 = new StringBuilder();

      for (Component var4 : this.getTooltip(var1)) {
         var2.append('\n').append(var4.getString());
      }

      Matcher var5 = SELF_DESTRUCT_PATTERN.matcher(var2);
      return var5.find() ? this.parseTimeString(var5.group(1)) : -1.0;
   }

   private double parseTimeString(String var1) {
      double var2 = 0.0;
      String var4 = var1.toLowerCase(Locale.ROOT);
      Matcher var5 = Pattern.compile("(\\d+)\\s*d").matcher(var4);
      if (var5.find()) {
         var2 += Double.parseDouble(var5.group(1)) * 24.0;
      }

      Matcher var6 = Pattern.compile("(\\d+)\\s*h").matcher(var4);
      if (var6.find()) {
         var2 += Double.parseDouble(var6.group(1));
      }

      return var2 > 0.0 ? var2 : -1.0;
   }

   private boolean matchesTarget(ItemStack var1, Item var2) {
      return var2 != null && (var1.is(var2) || this.shulkerHasItem(var1, var2));
   }

   private int getTargetQuantity(ItemStack var1, Item var2) {
      if (var2 == null) {
         return 0;
      } else {
         return var1.is(var2) ? var1.getCount() : this.countItemInShulker(var1, var2);
      }
   }

   private int countItemInInventory() {
      if (this.mc.player != null && this.currentSnipedItem != null && this.currentSnipedItem != Items.AIR) {
         int var1 = 0;

         for (int var2 = 0; var2 < this.mc.player.getInventory().getContainerSize(); var2++) {
            ItemStack var3 = this.mc.player.getInventory().getItem(var2);
            if (var3.is(this.currentSnipedItem)) {
               var1 += var3.getCount();
            } else {
               var1 += this.countItemInShulker(var3, this.currentSnipedItem);
            }
         }

         return var1;
      } else {
         return 0;
      }
   }

   private boolean isShulkerBox(ItemStack var1) {
      return var1.getItem() instanceof BlockItem var2 && var2.getBlock() instanceof ShulkerBoxBlock;
   }

   private boolean shulkerHasItem(ItemStack var1, Item var2) {
      return this.countItemInShulker(var1, var2) > 0;
   }

   private int countItemInShulker(ItemStack var1, Item var2) {
      if (!this.isShulkerBox(var1)) {
         return 0;
      } else {
         ItemContainerContents var3 = (ItemContainerContents)var1.get(DataComponents.CONTAINER);
         if (var3 == null) {
            return 0;
         } else {
            int var4 = 0;

            for (var itemTemplate : var3.nonEmptyItems()) {
               ItemStack item = itemTemplate.create();
               if (item.is(var2)) {
                  var4 += item.getCount();
               }
            }

            return var4;
         }
      }
   }

   private void handlePurchaseCheck() {
      this.inventoryCheckTicks++;
      if (this.inventoryCheckTicks >= 2) {
         int var1 = this.countItemInInventory();
         if (var1 > this.previousItemCount) {
            int var2 = var1 - this.previousItemCount;
            this.purchaseAttempted = false;
            this.hasClickedBuy = false;
            this.waitingForConfirmation = false;
            this.notifyUser("Got %dx %s for %s.", var2, this.attemptedItemName, this.formatPrice(this.attemptedActualPrice));
            this.sendSuccessWebhook(this.attemptedItemName, this.attemptedActualPrice, var2, this.attemptedEnchantments);
            if (this.notifications.get()) {
               this.mc.level.playSound(this.mc.player, this.mc.player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
            }

            this.previousItemCount = var1;
            this.closeHandledScreen();
            if (this.autoSell.get()) {
               this.stopAfterAutoSell = this.autoStop.get();
               this.sellingPhase = true;
               this.sellingDelayCounter = 2;
            } else if (this.autoStop.get()) {
               this.notifyUser("Auto-stop: disabling after the successful purchase.");
               this.disable();
            } else {
               this.commandSent = false;
               this.hasSetSort = false;
            }
         } else if (this.inventoryCheckTicks >= 30) {
            this.notifyUser("Purchase timed out.");
            this.recoverAuction();
         }
      }
   }

   private void performAutoSell() {
      if (this.mc.player != null && this.mc.getConnection() != null && this.currentSnipedItem != null) {
         double var1 = this.parsePrice(this.sellPrice.get());
         if (var1 <= 0.0) {
            this.error("Invalid auto-sell price: %s", new Object[]{this.sellPrice.get()});
            this.sellingPhase = false;
            this.finishAutoSellCycle();
         } else {
            int var3 = this.findTargetInventorySlot();
            if (var3 < 0) {
               this.warning("Could not find the purchased item for auto-sell.");
               this.sellingPhase = false;
               this.finishAutoSellCycle();
            } else {
               int var4 = this.mc.player.getInventory().getSelectedSlot();
               if (var3 < 9) {
                  InvUtils.swap(var3, false);
               } else {
                  InvUtils.move().from(var3).toHotbar(var4);
               }

               this.mc.getConnection().sendCommand("ah sell " + (long)var1);
               this.sellingPhase = false;
               this.sellConfirmPending = true;
               this.sellConfirmTicks = 0;
               this.navigationDelayCounter = 1;
               this.lastActionTicks = 0;
            }
         }
      } else {
         this.sellingPhase = false;
         this.finishAutoSellCycle();
      }
   }

   private int findTargetInventorySlot() {
      for (int var1 = 0; var1 < this.mc.player.getInventory().getContainerSize(); var1++) {
         ItemStack var2 = this.mc.player.getInventory().getItem(var1);
         if (var2.is(this.currentSnipedItem) || this.shulkerHasItem(var2, this.currentSnipedItem)) {
            return var1;
         }
      }

      return -1;
   }

   private boolean handleSellConfirmation() {
      this.sellConfirmTicks++;
      if (this.sellConfirmTicks > 60) {
         this.warning("Auto-sell confirmation timed out.");
         this.sellConfirmPending = false;
         this.commandSent = false;
         this.finishAutoSellCycle();
         return false;
      } else {
         AbstractButton var1 = this.findScreenConfirmationButton();
         if (var1 != null) {
            if (!this.clickScreenConfirmationButton(var1)) {
               return true;
            } else {
               this.completeAutoSellConfirmation();
               return true;
            }
         } else if (this.mc.player.containerMenu instanceof ChestMenu var2) {
            String var5 = this.screenTitle();
            if (!var5.contains("confirm") && !var5.contains("sell") && !var5.contains("sure")) {
               return true;
            } else {
               int var4 = this.findConfirmationButton(var2);
               if (var4 < 0) {
                  return true;
               } else {
                  this.clickSlot(var2, var4, 0, ContainerInput.PICKUP);
                  this.closeHandledScreen();
                  this.completeAutoSellConfirmation();
                  return true;
               }
            }
         } else {
            return true;
         }
      }
   }

   private void completeAutoSellConfirmation() {
      this.sellConfirmPending = false;
      this.commandSent = false;
      this.hasSetSort = false;
      this.navigationDelayCounter = 5;
      this.lastActionTicks = 0;
      this.finishAutoSellCycle();
   }

   private void finishAutoSellCycle() {
      if (this.stopAfterAutoSell) {
         this.stopAfterAutoSell = false;
         this.notifyUser("Auto-stop: disabling after the completed purchase.");
         this.disable();
      }
   }

   private int findConfirmationButton(ChestMenu var1) {
      int var2 = Math.min(var1.getRowCount() * 9, var1.slots.size());

      for (int var3 = 0; var3 < var2; var3++) {
         ItemStack var4 = var1.getSlot(var3).getItem();
         if (!var4.isEmpty()) {
            String var5 = var4.getHoverName().getString().toLowerCase(Locale.ROOT);
            if (var5.contains("confirm") || var5.equals("yes") || var5.contains("list item")) {
               return var3;
            }
         }
      }

      return var1.getRowCount() == 3 && var1.slots.size() > 15 ? 15 : -1;
   }

   private void recoverAuction() {
      this.closeHandledScreen();
      boolean var1 = this.snipeMode.get() == AHSniper.SnipeMode.Single;
      this.resetState();
      if (var1) {
         this.currentSnipedItem = this.snipingItem.get();
      }

      this.openAuctionHouse();
      this.commandSent = true;
   }

   private void closeHandledScreen() {
      if (this.mc.player != null && this.mc.player.containerMenu != this.mc.player.inventoryMenu) {
         this.mc.player.closeContainer();
      }
   }

   private String screenTitle() {
      return this.mc.gui.screen() == null ? "" : this.mc.gui.screen().getTitle().getString().toLowerCase(Locale.ROOT);
   }

   private void notifyUser(String var1, Object... var2) {
      if (this.notifications.get()) {
         this.info(var1, var2);
      }
   }

   private void sendSuccessWebhook(String var1, double var2, int var4, String var5) {
      if (this.webhookEnabled.get() && !(this.webhookUrl.get()).isBlank()) {
         String var6 = this.selfPing.get() && !(this.discordId.get()).isBlank() ? "<@" + (this.discordId.get()).trim() + "> " : "";
         String var7 = String.format(Locale.ROOT, "Sniped %dx %s for %s", var4, var1, this.formatPrice(var2));
         String var8 = String.format(
            Locale.ROOT,
            "{\"content\":\"%s\",\"embeds\":[{\"title\":\"AH Sniper Alert\",\"description\":\"%s\",\"color\":8388736,\"fields\":[{\"name\":\"Item\",\"value\":\"%s x%d\",\"inline\":true},{\"name\":\"Price\",\"value\":\"%s\",\"inline\":true},{\"name\":\"Enchants\",\"value\":\"%s\",\"inline\":false}],\"timestamp\":\"%s\"}]}",
            this.escapeJson(var6 + var7),
            this.escapeJson(var7),
            this.escapeJson(var1),
            var4,
            this.escapeJson(this.formatPrice(var2)),
            this.escapeJson(var5),
            Instant.now()
         );
         this.sendWebhookMessage(var8);
      }
   }

   private void sendWebhookMessage(String var1) {
      try {
         HttpRequest var2 = HttpRequest.newBuilder()
            .uri(URI.create(this.webhookUrl.get()))
            .timeout(Duration.ofSeconds(10L))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(var1, StandardCharsets.UTF_8))
            .build();
         this.httpClient.sendAsync(var2, BodyHandlers.discarding());
      } catch (IllegalArgumentException var31) {
         this.error("Invalid webhook URL.");
      }
   }

   private String escapeJson(String var1) {
      return var1.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
   }

   private String formatPrice(double var1) {
      if (var1 >= 1.0E9) {
         return String.format(Locale.ROOT, "%.1fB", var1 / 1.0E9);
      } else if (var1 >= 1000000.0) {
         return String.format(Locale.ROOT, "%.1fM", var1 / 1000000.0);
      } else {
         return var1 >= 1000.0 ? String.format(Locale.ROOT, "%.1fK", var1 / 1000.0) : String.format(Locale.ROOT, "%.0f", var1);
      }
   }

   private double parsePrice(String var1) {
      String var2 = var1.trim().toLowerCase(Locale.ROOT).replace(",", "");
      double var3 = 1.0;
      if (var2.endsWith("b")) {
         var3 = 1.0E9;
         var2 = var2.substring(0, var2.length() - 1);
      } else if (var2.endsWith("m")) {
         var3 = 1000000.0;
         var2 = var2.substring(0, var2.length() - 1);
      } else if (var2.endsWith("k")) {
         var3 = 1000.0;
         var2 = var2.substring(0, var2.length() - 1);
      }

      try {
         return Double.parseDouble(var2) * var3;
      } catch (NumberFormatException var6) {
         return -1.0;
      }
   }

   private static enum PriceMode {
      PerItem,
      PerStack;
   }

   private static final class SnipeItemConfig {
      private final Item item;
      private final String minPrice;
      private final String maxPrice;
      private final AHSniper.PriceMode priceMode;
      private final Set<ResourceKey<Enchantment>> enchantments;

      private SnipeItemConfig(Item var1, String var2, String var3, AHSniper.PriceMode var4, Set<ResourceKey<Enchantment>> var5) {
         this.item = var1;
         this.minPrice = var2;
         this.maxPrice = var3;
         this.priceMode = var4;
         this.enchantments = var5;
      }
   }

   private static enum SnipeMode {
      Single,
      Multi;
   }
}
