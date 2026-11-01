// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.event.EventHandler;
import dev.donut.addon.anubis.event.impl.PacketReceiveNettyEvent;
import dev.donut.addon.anubis.module.ClientModule;
import dev.donut.addon.anubis.module.ModuleCategory;
import dev.donut.addon.anubis.module.impl.donutmacros.Dialogs;
import dev.donut.addon.anubis.module.impl.donutmacros.MacroWorker;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import dev.donut.addon.anubis.module.impl.donutmacros.SnipeTarget;
import dev.donut.addon.anubis.module.impl.donutmacros.SniperBot;
import dev.donut.addon.anubis.module.setting.BooleanSetting;
import dev.donut.addon.anubis.module.setting.ModeSetting;
import dev.donut.addon.anubis.module.setting.NumberSetting;
import dev.donut.addon.anubis.module.setting.Setting;
import dev.donut.addon.anubis.module.setting.StringSetting;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.protocol.Packet;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(value=EnvType.CLIENT)
public final class AhSniperModule
extends ClientModule {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"Anubis");
    private static final String EXACT = "Exactly these";
    private static final String AT_LEAST = "At least these";
    private final StringSetting item;
    private final StringSetting maxPrice;
    private final StringSetting enchants;
    private final ModeSetting enchantMatch;
    private final NumberSetting minHours;
    private final NumberSetting refresh;
    private final NumberSetting settle;
    private final StringSetting budget;
    private final BooleanSetting requireConfirm;
    private final NumberSetting maxBuys;
    private final BooleanSetting autoSell;
    private final StringSetting sellPrice;
    private final MacroWorker worker;
    private volatile SniperBot bot;

    public AhSniperModule() {
        super("ah-sniper", "AH Sniper", "Refreshes the AH and instantly buys your item below your price, with optional auto selling.", ModuleCategory.DONUT_MACROS);
        this.item = (StringSetting)this.addSetting("item", (Setting)new StringSetting("Item", SnipeTarget.Preset.SHARD_AXE.value).presets(SnipeTarget.Preset.choices()));
        this.maxPrice = (StringSetting)this.addSetting("max-price", (Setting)new StringSetting("Buy Below (each)", "").hint("e.g. 50m"));
        this.enchants = (StringSetting)this.addSetting("enchants", (Setting)new StringSetting("Enchantments", "").hint("e.g. protection 2, unbreaking 3, mending"));
        this.enchantMatch = (ModeSetting)this.addSetting("enchant-match", (Setting)new ModeSetting("Enchant Match", EXACT, new String[]{EXACT, AT_LEAST}));
        this.minHours = (NumberSetting)this.addSetting("min-hours", (Setting)new NumberSetting("Min Time Left (h)", 0.0f, 0.0f, 48.0f).step(0.5f));
        this.refresh = (NumberSetting)this.addSetting("refresh", (Setting)new NumberSetting("Refresh Every (ms)", 300.0f, 100.0f, 2000.0f).step(50.0f));
        this.settle = (NumberSetting)this.addSetting("settle", (Setting)new NumberSetting("Wait After Page Change (ms)", 200.0f, 50.0f, 1000.0f).step(25.0f));
        this.budget = (StringSetting)this.addSetting("budget", (Setting)new StringSetting("Max Total Spend", "").hint("e.g. 500m, empty = no limit"));
        this.requireConfirm = (BooleanSetting)this.addSetting("require-confirm", (Setting)new BooleanSetting("Only Buy With Confirm", false));
        this.maxBuys = (NumberSetting)this.addSetting("max-buys", (Setting)new NumberSetting("Stop After (buys, 0 = never)", 0.0f, 0.0f, 100.0f).step(1.0f));
        this.autoSell = (BooleanSetting)this.addSetting("auto-sell", (Setting)new BooleanSetting("Auto Sell", false));
        this.sellPrice = (StringSetting)this.addSetting("sell-price", (Setting)new StringSetting("Sell Price (each)", "").hint("e.g. 60m"));
        this.worker = new MacroWorker("Anubis AH Sniper");
        this.usesNotifications();
        this.enchantMatch.visibleWhen(() -> !this.enchants.get().isBlank());
        this.sellPrice.visibleWhen(() -> ((BooleanSetting)this.autoSell).get());
    }

    public boolean persistEnabled() {
        return false;
    }

    protected void onEnable() {
        SniperBot created;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || mc.getUser() == null) {
            this.say(ModuleChat.body().warn("Join a server first."));
            this.requestDisable();
            return;
        }
        long max = ServerText.parseMoney(this.maxPrice.get());
        if (max <= 0L) {
            this.say(ModuleChat.body().warn("Set Buy Below to a price like 50m."));
            this.requestDisable();
            return;
        }
        SnipeTarget target = SnipeTarget.parse(this.item.get(), this.enchants.get(), this.enchantMatch.is(EXACT), max, Math.round(this.minHours.get() * 60.0f));
        if (target == null) {
            this.say(ModuleChat.body().warn("Pick an item to snipe first."));
            this.requestDisable();
            return;
        }
        long spendLimit = 0L;
        if (!this.budget.get().isBlank() && (spendLimit = ServerText.parseMoney(this.budget.get())) <= 0L) {
            this.say(ModuleChat.body().warn("Set Max Total Spend to a price like 500m, or leave it empty."));
            this.requestDisable();
            return;
        }
        long sell = 0L;
        if (this.autoSell.get()) {
            sell = ServerText.parseMoney(this.sellPrice.get());
            if (sell <= 0L) {
                this.say(ModuleChat.body().warn("Set Sell Price to a price like 60m, or turn Auto Sell off."));
                this.requestDisable();
                return;
            }
            if (sell <= max) {
                this.say(ModuleChat.body().warn("Sell Price must be higher than Buy Below").text(", or the sniper would buy your own listing back."));
                this.requestDisable();
                return;
            }
        }
        this.bot = created = new SniperBot(this, this.worker, target, this.refresh.getInt(), this.settle.getInt(), spendLimit, this.requireConfirm.get(), this.maxBuys.getInt(), sell, mc.getUser().getName());
        this.worker.start(created::run, failure -> {
            LOGGER.error("[Anubis] AH Sniper stopped", failure);
            this.say(ModuleChat.body().danger("Stopped").text(": " + String.valueOf(failure)));
            this.stopFromBot(created);
        });
    }

    void stopFromBot(SniperBot from) {
        Minecraft.getInstance().execute(() -> {
            if (this.bot == from && this.isEnabled()) {
                this.setEnabled(false);
            }
        });
    }

    protected void onDisable() {
        SniperBot stopped = this.bot;
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
            this.say(ModuleChat.body().text(stopped.summary()));
        }
    }

    @EventHandler
    public void onPacket(PacketReceiveNettyEvent event) {
        ClientboundSystemChatPacket chat;
        Packet class_25962;
        SniperBot current = this.bot;
        if (current != null && (class_25962 = event.packet) instanceof ClientboundSystemChatPacket && (chat = (ClientboundSystemChatPacket)class_25962).content() != null) {
            current.onChat(chat.content().getString());
        }
    }

    private void say(ModuleChat.Body body) {
        ModuleChat.send((ClientModule)this, (ModuleChat.Body)body);
    }
}
