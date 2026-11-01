// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.config.FriendStore;
import dev.donut.addon.anubis.event.EventHandler;
import dev.donut.addon.anubis.event.impl.PacketReceiveNettyEvent;
import dev.donut.addon.anubis.event.impl.TickEvent;
import dev.donut.addon.anubis.module.ClientModule;
import dev.donut.addon.anubis.module.ModuleCategory;
import dev.donut.addon.anubis.module.impl.combat.crystal.Crystals;
import dev.donut.addon.anubis.module.impl.combat.melee.MeleeAttacks;
import dev.donut.addon.anubis.module.impl.donutmacros.CompletionRequests;
import dev.donut.addon.anubis.module.impl.donutmacros.SellRoutine;
import dev.donut.addon.anubis.module.impl.donutmacros.TeleportConfirm;
import dev.donut.addon.anubis.module.impl.misc.AutoEatModule;
import dev.donut.addon.anubis.module.setting.BooleanSetting;
import dev.donut.addon.anubis.module.setting.ModeSetting;
import dev.donut.addon.anubis.module.setting.NumberSetting;
import dev.donut.addon.anubis.module.setting.Setting;
import dev.donut.addon.anubis.module.setting.StringListSetting;
import dev.donut.addon.anubis.module.setting.StringSetting;
import dev.donut.addon.anubis.util.CompletionChannel;
import dev.donut.addon.anubis.util.pvp.Rotation;
import dev.donut.addon.anubis.util.pvp.RotationUtils;
import dev.donut.addon.anubis.util.pvp.ServerRotation;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.Packet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;

@Environment(value=EnvType.CLIENT)
public final class AutoTpaHereModule
extends ClientModule {
    private static final String ORDER_AZ = "A to Z";
    private static final String ORDER_ZA = "Z to A";
    private static final String ORDER_RANDOM = "Random";
    private static final String EXTRA_CHARS = "0123456789_";
    private static final String SUB_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789_";
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9._]{1,32}");
    private static final Pattern FORMATTING = Pattern.compile("\u00a7.");
    private static final Pattern RATE_LIMIT = Pattern.compile("^you need to wait another ([\\d.]+) seconds? to execute a command");
    private static final Pattern NOT_ONLINE = Pattern.compile("(that|this) (user|player) is not online|player is not online");
    private static final Pattern SENT_REQUEST = Pattern.compile("^you sent (\\S+) (a )?teleport( here)? request");
    private static final int SUGGEST_CAP = 990;
    private static final long DETECT_TIMEOUT_MS = 5000L;
    private static final long LETTER_TIMEOUT_MS = 5000L;
    private static final long LETTER_TIMEOUT_LOCAL_MS = 3000L;
    private static final long PREFIX_TIMEOUT_MS = 4000L;
    private static final int SPLIT_MAX_INFLIGHT = 6;
    private static final long PROBE_WAIT_MS = 700L;
    private static final long PROBE_MIN_WAIT_MS = 100L;
    private static final long PROBE_STALE_MS = 4000L;
    private static final int PROBE_GIVE_UP = 25;
    private static final long REPLY_WINDOW_MS = 2500L;
    private static final long JOIN_REPEAT_MS = 3600000L;
    private static final long SWEEP_GAP_MS = 200L;
    private static final long SWEEP_MIN_ROUND_MS = 45000L;
    private static final int SWEEP_MAX_INFLIGHT = 4;
    private static final int JOIN_QUEUE_MAX = 300;
    private static final long RANDOM_RESCAN_MS = 180000L;
    private static final long RESPAWN_FIRST_MS = 700L;
    private static final long RESPAWN_RETRY_MS = 600L;
    private static final int RESPAWN_COMMAND_DELAY_TICKS = 20;
    private static final int EAT_MAX_TICKS = 40;
    private static final long PRUNE_EVERY_MS = 60000L;
    private static final int CHAT_QUEUE_MAX = 256;
    private static final long SELL_COMMAND_GAP_MS = 400L;
    private static final String[] SWORD_RANK = new String[]{"netherite", "diamond", "iron", "stone", "golden", "wooden"};
    private final ModeSetting command = (ModeSetting)this.addSetting("command", (Setting)new ModeSetting("Command", "tpahere", new String[]{"tpahere", "tpa"}));
    private final ModeSetting order = (ModeSetting)this.addSetting("order", (Setting)new ModeSetting("Order", "A to Z", new String[]{"A to Z", "Z to A", "Random"}));
    private final StringSetting letters = (StringSetting)this.addSetting("letters", (Setting)new StringSetting("Letters", "").hint("All letters, or e.g. abc or k-m"));
    private final StringSetting startLetter = (StringSetting)this.addSetting("start-letter", (Setting)new StringSetting("Start Letter", "").hint("First letter in the order"));
    private final BooleanSetting numbers = (BooleanSetting)this.addSetting("numbers", (Setting)new BooleanSetting("Include 0-9 and _", false));
    private final BooleanSetting bedrock = (BooleanSetting)this.addSetting("bedrock", (Setting)new BooleanSetting("Bedrock Players Only", false));
    private final BooleanSetting repeat = (BooleanSetting)this.addSetting("repeat", (Setting)new BooleanSetting("Repeat Forever", true));
    private final NumberSetting delay = (NumberSetting)this.addSetting("delay", (Setting)new NumberSetting("Delay (s)", 1.0f, 0.25f, 30.0f).step(0.25f));
    private final BooleanSetting skipOffline = (BooleanSetting)this.addSetting("skip-offline", (Setting)new BooleanSetting("Skip Offline Players", true));
    private final BooleanSetting joinersFirst = (BooleanSetting)this.addSetting("joiners-first", (Setting)new BooleanSetting("New Players First", false));
    private final StringListSetting neverRequest = (StringListSetting)this.addSetting("never-request", (Setting)new StringListSetting("Never Request", new String[0]));
    private final NumberSetting sendMinutes = (NumberSetting)this.addSetting("send-minutes", (Setting)new NumberSetting("Send For (min, 0 = always)", 0.0f, 0.0f, 240.0f).step(1.0f));
    private final NumberSetting restMinutes = (NumberSetting)this.addSetting("rest-minutes", (Setting)new NumberSetting("Then Rest (min)", 0.0f, 0.0f, 240.0f).step(1.0f));
    private final NumberSetting pauseRadius = (NumberSetting)this.addSetting("pause-radius", (Setting)new NumberSetting("Pause Radius", 6.0f, 1.0f, 64.0f).step(0.5f));
    private final NumberSetting resumeAfter = (NumberSetting)this.addSetting("resume-after", (Setting)new NumberSetting("Resume After (s)", 2.5f, 0.0f, 60.0f).step(0.5f));
    private final BooleanSetting attack = (BooleanSetting)this.addSetting("attack", (Setting)new BooleanSetting("Attack", true));
    private final NumberSetting attackRadius = (NumberSetting)this.addSetting("attack-radius", (Setting)new NumberSetting("Attack Radius", 1.5f, 0.5f, 6.0f).step(0.1f));
    private final NumberSetting swordSlot = (NumberSetting)this.addSetting("sword-slot", (Setting)new NumberSetting("Sword Slot (0 = auto)", 0.0f, 0.0f, 9.0f).step(1.0f));
    private final NumberSetting eatBelow = (NumberSetting)this.addSetting("eat-below", (Setting)new NumberSetting("Eat Below (hearts, 0 = never)", 5.0f, 0.0f, 10.0f).step(0.5f));
    private final NumberSetting appleSlot = (NumberSetting)this.addSetting("apple-slot", (Setting)new NumberSetting("Apple Slot (0 = auto)", 0.0f, 0.0f, 9.0f).step(1.0f));
    private final NumberSetting eatCooldown = (NumberSetting)this.addSetting("eat-cooldown", (Setting)new NumberSetting("Eat Cooldown (s)", 2.5f, 0.5f, 30.0f).step(0.5f));
    private final BooleanSetting enchantedFirst = (BooleanSetting)this.addSetting("enchanted-first", (Setting)new BooleanSetting("Enchanted Apples First", false));
    private final BooleanSetting autoRespawn = (BooleanSetting)this.addSetting("auto-respawn", (Setting)new BooleanSetting("Auto Respawn", true));
    private final StringSetting respawnCommand = (StringSetting)this.addSetting("respawn-command", (Setting)new StringSetting("Respawn Command", "").hint("e.g. /home"));
    private final BooleanSetting autoSell = (BooleanSetting)this.addSetting("auto-sell", (Setting)new BooleanSetting("Auto Sell", true));
    private final StringListSetting neverSell = (StringListSetting)this.addSetting("never-sell", (Setting)new StringListSetting("Never Sell", new String[]{"diamond_sword", "golden_apple", "totem_of_undying"}).itemPicker());
    private final SellRoutine sell = new SellRoutine(this, this.neverSell, this::keepForBot, this::onSellCommand);
    private final TeleportConfirm teleportConfirm = new TeleportConfirm(this);
    private final CompletionRequests completions = new CompletionRequests();
    private final Queue<String> chatLines = new ConcurrentLinkedQueue<String>();
    private boolean progressReset = true;
    private String letter = "";
    private final Set<String> sentThisLetter = new HashSet<String>();
    private final Map<String, Long> msgAt = new HashMap<String, Long>();
    private final Map<String, Long> joinMsgAt = new HashMap<String, Long>();
    private final Set<String> splitLetters = new HashSet<String>();
    private final ArrayDeque<String> retryQueue = new ArrayDeque();
    private Phase phase = Phase.DETECT;
    private int detectSlash = -1;
    private int detectBare = -1;
    private long detectUntil;
    private Collector collector;
    private List<String> queue = List.of();
    private int queueIndex;
    private Delivery delivery;
    private final Map<String, Bucket> buckets = new HashMap<String, Bucket>();
    private String lastRandomLetter = "";
    private boolean sweepOn;
    private List<String> sweepLetters = List.of();
    private List<String> sweepList = List.of();
    private int sweepIndex;
    private final List<Inflight> sweepInflight = new ArrayList<Inflight>();
    private long sweepLastFire;
    private int sweepRounds;
    private long sweepRoundAt;
    private final Map<String, Set<String>> prefixCache = new HashMap<String, Set<String>>();
    private final ArrayDeque<String> joinQueue = new ArrayDeque();
    private final Set<String> joinQueued = new HashSet<String>();
    private String selfName = "";
    private boolean onlineCheckOff;
    private int probeMisses;
    private long lastSendAt;
    private long rateHoldUntil;
    private String lastTarget = "";
    private long lastTargetAt;
    private boolean lastTargetCounted = true;
    private long lastPruneAt;
    private final List<Near> nearList = new ArrayList<Near>();
    private long lastNearAt;
    private boolean wasHolding;
    private long cycleStartedAt;
    private boolean wasResting;
    private boolean eating;
    private int eatTicks;
    private boolean eatStarted;
    private float eatStartHealth;
    private long lastEatAt;
    private boolean warnedNoSword;
    private boolean warnedNoApple;
    private boolean wasDead;
    private long deadSince;
    private long respawnTriedAt;
    private int respawnCommandIn;
    private int totalSent;
    private int totalOffline;
    private int totalDisabled;
    private int totalJoiners;
    private int totalArrivals;
    private int totalHits;
    private int totalEaten;
    private int totalDeaths;

    public AutoTpaHereModule() {
        super("auto-tpahere", "Tpahere Kill Bot", "Sends tpahere requests to online players one by one, pauses when someone arrives and fights them. Servers can detect this.", ModuleCategory.DONUT_MACROS);
        Setting[] eatSettings;
        this.usesNotifications();
        this.letters.onChange(this::lettersChanged);
        this.startLetter.onChange(this::lettersChanged);
        this.order.onChange(this::lettersChanged);
        this.numbers.onChange(this::lettersChanged);
        this.bedrock.onChange(this::lettersChanged);
        this.startLetter.visibleWhen(() -> !this.order.is(ORDER_RANDOM));
        this.restMinutes.visibleWhen(() -> this.sendMinutes.get() > 0.0f);
        this.attackRadius.visibleWhen(() -> ((BooleanSetting)this.attack).get());
        this.swordSlot.visibleWhen(() -> ((BooleanSetting)this.attack).get());
        for (Setting setting : eatSettings = new Setting[]{this.appleSlot, this.eatCooldown, this.enchantedFirst}) {
            setting.visibleWhen(() -> this.eatBelow.get() > 0.0f);
        }
        this.respawnCommand.visibleWhen(() -> ((BooleanSetting)this.autoRespawn).get());
        this.neverSell.visibleWhen(() -> ((BooleanSetting)this.autoSell).get());
        this.autoSell.onChange(() -> {
            if (!this.autoSell.get()) {
                this.sell.abort(Minecraft.getInstance());
            }
        });
        this.neverRequest.privateValue();
        this.respawnCommand.privateValue();
    }

    public boolean persistEnabled() {
        return false;
    }

    protected void onEnable() {
        this.resetRun();
        if (this.progressReset || !this.letterList().contains(this.letter)) {
            this.letter = this.firstLetter();
            this.sentThisLetter.clear();
            this.progressReset = false;
        }
        this.info(ModuleChat.body().text("Started ").value((Object)("/" + this.command.get())).text(", ").value((Object)this.orderLabel()).text((String)(this.order.is(ORDER_RANDOM) ? "" : " from " + this.letter.toUpperCase(Locale.ROOT))).text(", every ").value((Object)AutoTpaHereModule.formatSeconds(this.delayMs())).text(this.attack.get() ? ", attacking" : ""));
    }

    protected void onDisable() {
        this.sell.abort(Minecraft.getInstance());
        this.stopEating(Minecraft.getInstance());
        this.completions.clear();
        this.chatLines.clear();
        this.delivery = null;
        this.collector = null;
        if (this.totalSent > 0 || this.totalArrivals > 0) {
            this.info(ModuleChat.body().text(this.statsText()));
        }
    }

    private void lettersChanged() {
        this.progressReset = true;
        if (this.isEnabled()) {
            this.resetRun();
            this.letter = this.firstLetter();
            this.sentThisLetter.clear();
            this.progressReset = false;
        }
    }

    private void resetRun() {
        long now = System.currentTimeMillis();
        this.completions.clear();
        this.chatLines.clear();
        this.msgAt.clear();
        this.joinMsgAt.clear();
        this.splitLetters.clear();
        this.retryQueue.clear();
        this.phase = Phase.DETECT;
        this.detectSlash = -1;
        this.detectBare = -1;
        this.collector = null;
        this.queue = List.of();
        this.queueIndex = 0;
        this.delivery = null;
        this.buckets.clear();
        this.lastRandomLetter = "";
        this.sweepOn = false;
        this.sweepInflight.clear();
        this.sweepList = List.of();
        this.sweepIndex = 0;
        this.sweepRounds = 0;
        this.prefixCache.clear();
        this.joinQueue.clear();
        this.joinQueued.clear();
        Minecraft mc = Minecraft.getInstance();
        this.selfName = mc.getUser() == null ? "" : AutoTpaHereModule.normalizeIgn(mc.getUser().getName());
        this.onlineCheckOff = false;
        this.probeMisses = 0;
        this.lastSendAt = 0L;
        this.rateHoldUntil = 0L;
        this.lastTarget = "";
        this.lastTargetCounted = true;
        this.lastPruneAt = now;
        this.nearList.clear();
        this.lastNearAt = 0L;
        this.wasHolding = false;
        this.cycleStartedAt = now;
        this.wasResting = false;
        this.eating = false;
        this.lastEatAt = 0L;
        this.warnedNoSword = false;
        this.warnedNoApple = false;
        this.wasDead = false;
        this.respawnCommandIn = 0;
        this.sell.reset();
        this.teleportConfirm.reset();
        this.totalJoiners = 0;
        this.totalDisabled = 0;
        this.totalOffline = 0;
        this.totalSent = 0;
        this.totalDeaths = 0;
        this.totalEaten = 0;
        this.totalHits = 0;
        this.totalArrivals = 0;
    }

    @EventHandler
    public void onPacket(PacketReceiveNettyEvent event) {
        ClientboundSystemChatPacket chat;
        Packet class_25962 = event.packet;
        if (class_25962 instanceof ClientboundSystemChatPacket && (chat = (ClientboundSystemChatPacket)class_25962).content() != null && this.chatLines.size() < 256) {
            this.chatLines.offer(chat.content().getString());
        }
    }

    @EventHandler
    public void onTick(TickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientPacketListener connection = mc.getConnection();
        if (player == null || mc.level == null || connection == null || mc.gameMode == null) {
            this.sell.abort(mc);
            this.stopEating(mc);
            this.stall();
            return;
        }
        this.teleportConfirm.tick(mc);
        long now = System.currentTimeMillis();
        this.drainChat(now);
        if (now - this.lastPruneAt > 60000L) {
            this.prune(now);
        }
        if (this.handleDeath(mc, player, now)) {
            this.sell.abort(mc);
            this.stall();
            return;
        }
        this.tickRespawnCommand(connection);
        this.scanNear(mc, player);
        if (this.sell.active() && (!this.nearList.isEmpty() || this.wantsToEat(player, now))) {
            this.sell.abort(mc);
        }
        if (this.tickEating(mc, player, now) || this.startEating(mc, player, now)) {
            this.stall();
            return;
        }
        this.fight(mc, player);
        this.pumpSweep(now);
        if (this.holding(now)) {
            this.stall();
            return;
        }
        if (this.autoSell.get() && this.sell.step(mc, player, connection, now, now >= this.rateHoldUntil && now - this.lastSendAt >= 400L)) {
            this.stall();
            return;
        }
        if (this.resting(now) || now < this.rateHoldUntil) {
            return;
        }
        this.step(connection, now);
    }

    private void stall() {
        if (this.delivery != null) {
            this.delivery.stale = true;
        }
    }

    private void step(ClientPacketListener connection, long now) {
        switch (this.phase.ordinal()) {
            case 0: {
                this.stepDetect(now);
                break;
            }
            case 1: {
                this.stepCollect(connection, now);
                break;
            }
            case 2: {
                this.stepSend(connection, now);
                break;
            }
            case 3: {
                this.stepPick(connection, now);
            }
        }
    }

    private void stepDetect(long now) {
        String first;
        String string = first = this.order.is(ORDER_RANDOM) ? "a" : this.letter;
        if (this.detectSlash < 0 && this.detectBare < 0) {
            this.detectSlash = this.completions.fire(this.partialFor(first), CompletionChannel.Form.SLASH);
            this.detectBare = this.completions.fire(this.partialFor(first), CompletionChannel.Form.BARE);
            this.detectUntil = now + 5000L;
            return;
        }
        if (this.completions.poll(this.detectSlash) != null) {
            this.completions.form(CompletionChannel.Form.SLASH);
        } else if (this.completions.poll(this.detectBare) != null) {
            this.completions.form(CompletionChannel.Form.BARE);
        } else if (now < this.detectUntil) {
            return;
        }
        this.completions.forget(this.detectSlash);
        this.completions.forget(this.detectBare);
        if (this.joinersFirst.get()) {
            this.startSweep();
        }
        if (this.order.is(ORDER_RANDOM)) {
            this.info(ModuleChat.body().text("Random order, letters ").value((Object)String.join((CharSequence)"", this.letterList()).toUpperCase(Locale.ROOT)));
            this.phase = Phase.PICK;
        } else {
            this.beginLetter();
        }
    }

    private void beginLetter() {
        this.collector = new Collector(this.letter);
        this.phase = Phase.COLLECT;
    }

    private void stepCollect(ClientPacketListener connection, long now) {
        if (!this.collector.step(connection, now)) {
            return;
        }
        Collector done = this.collector;
        this.collector = null;
        if (this.order.is(ORDER_RANDOM)) {
            Bucket bucket = this.bucket(done.letter);
            bucket.scanned = true;
            bucket.at = now;
            bucket.names = done.names;
            bucket.unused = this.unusedFromNames(done.names);
            if (done.names.isEmpty()) {
                this.info(ModuleChat.body().text(done.timedOut ? "No answer for " : "No names for ").value((Object)done.letter.toUpperCase(Locale.ROOT)));
            }
            this.phase = Phase.PICK;
            return;
        }
        this.queue = this.unusedFromNames(done.names);
        this.queueIndex = 0;
        if (this.queue.isEmpty()) {
            this.info(ModuleChat.body().text(done.timedOut ? "No answer for " : "No new names for ").value((Object)done.letter.toUpperCase(Locale.ROOT)).text(done.timedOut ? ", next letter" : ""));
        } else {
            this.info(ModuleChat.body().text(AutoTpaHereModule.isLetter(done.letter) ? "Letter " : "Prefix ").value((Object)done.letter.toUpperCase(Locale.ROOT)).text(": ").value((Object)this.queue.size()).text(this.queue.size() == 1 ? " player" : " players").text(done.split ? " (past the 1000 cap)" : ""));
        }
        this.phase = Phase.SEND;
    }

    private void stepSend(ClientPacketListener connection, long now) {
        if (this.delivery == null) {
            this.delivery = this.nextPriority();
        }
        while (this.delivery == null && this.queueIndex < this.queue.size()) {
            String name;
            String key;
            if (this.sentThisLetter.contains(key = AutoTpaHereModule.nameKey(name = this.queue.get(this.queueIndex++))) || this.recentlyJoinMessaged(key, now) || this.excluded(name)) continue;
            this.delivery = new Delivery(name, false);
        }
        if (this.delivery == null) {
            this.advanceLetter();
            return;
        }
        if (this.delivery.step(connection, now) != Result.WAIT) {
            this.delivery = null;
        }
    }

    private void stepPick(ClientPacketListener connection, long now) {
        if (this.delivery == null) {
            this.delivery = this.nextPriority();
        }
        if (this.delivery == null) {
            List<String> pool = this.letterList();
            for (int tries = 0; tries < pool.size() + 4 && this.delivery == null; ++tries) {
                String picked = this.pickRandomLetter(pool, now);
                if (picked == null) {
                    if (!this.repeat.get()) {
                        this.finish("Finished the random pass.");
                        return;
                    }
                    this.reshuffle(pool);
                    picked = this.pickRandomLetter(pool, now);
                    if (picked == null) {
                        this.finish("No names found for these letters.");
                        return;
                    }
                    this.info(ModuleChat.body().text("Random pass done, starting over."));
                }
                Bucket bucket = this.bucket(picked);
                if (!bucket.scanned || AutoTpaHereModule.stale(bucket, now)) {
                    this.collector = new Collector(picked);
                    this.phase = Phase.COLLECT;
                    return;
                }
                if (bucket.unused.isEmpty()) continue;
                List<String> unused = bucket.unused;
                int index = ThreadLocalRandom.current().nextInt(unused.size());
                String name = unused.get(index);
                unused.set(index, unused.get(unused.size() - 1));
                unused.remove(unused.size() - 1);
                this.lastRandomLetter = picked;
                if (this.excluded(name) || this.sentThisLetter.contains(AutoTpaHereModule.nameKey(name))) continue;
                this.delivery = new Delivery(name, false);
            }
            if (this.delivery == null) {
                return;
            }
        }
        if (this.delivery.step(connection, now) != Result.WAIT) {
            this.delivery = null;
        }
    }

    private String pickRandomLetter(List<String> pool, long now) {
        ArrayList<String> eligible = new ArrayList<String>();
        for (String candidate : pool) {
            Bucket bucket = this.buckets.get(candidate);
            if (bucket != null && bucket.scanned && bucket.unused.isEmpty() && (!this.repeat.get() || !AutoTpaHereModule.stale(bucket, now))) continue;
            eligible.add(candidate);
        }
        if (eligible.size() > 1 && !this.lastRandomLetter.isEmpty()) {
            ArrayList mixed = new ArrayList(eligible);
            mixed.remove(this.lastRandomLetter);
            if (!mixed.isEmpty()) {
                eligible = mixed;
            }
        }
        return eligible.isEmpty() ? null : (String)eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
    }

    private void reshuffle(List<String> pool) {
        this.sentThisLetter.clear();
        for (String candidate : pool) {
            Bucket bucket = this.buckets.get(candidate);
            if (bucket == null || !bucket.scanned) continue;
            bucket.unused = this.unusedFromNames(bucket.names);
        }
    }

    private Bucket bucket(String key) {
        return this.buckets.computeIfAbsent(key, k -> new Bucket());
    }

    private static boolean stale(Bucket bucket, long now) {
        return now - bucket.at > 180000L;
    }

    private void advanceLetter() {
        List<String> list = this.letterList();
        int index = list.indexOf(this.letter);
        boolean wrapped = index >= 0 && index >= list.size() - 1;
        this.letter = index < 0 || wrapped ? list.get(0) : list.get(index + 1);
        this.sentThisLetter.clear();
        if (wrapped && !this.repeat.get()) {
            this.progressReset = true;
            this.finish("Finished " + this.orderLabel() + ".");
            return;
        }
        this.beginLetter();
    }

    private void finish(String message) {
        this.info(ModuleChat.body().text(message));
        this.requestDisable();
    }

    private Delivery nextPriority() {
        while (!this.retryQueue.isEmpty()) {
            String name = this.retryQueue.poll();
            if (this.excluded(name)) continue;
            return new Delivery(name, false);
        }
        if (!this.sweepOn) {
            return null;
        }
        long now = System.currentTimeMillis();
        while (!this.joinQueue.isEmpty()) {
            String name = this.joinQueue.poll();
            this.joinQueued.remove(AutoTpaHereModule.nameKey(name));
            if (!this.joinEligible(name, now)) continue;
            return new Delivery(name, true);
        }
        return null;
    }

    private void send(ClientPacketListener connection, String name, boolean joiner, long now) {
        this.lastTarget = name;
        this.lastTargetAt = now;
        this.lastTargetCounted = false;
        connection.sendCommand(this.command.get() + " " + name);
        this.teleportConfirm.sent();
        this.lastSendAt = now;
        ++this.totalSent;
        if (joiner) {
            ++this.totalJoiners;
        }
        String key = AutoTpaHereModule.nameKey(name);
        this.msgAt.put(key, now);
        if (joiner) {
            this.joinMsgAt.put(key, now);
        }
        this.sentThisLetter.add(key);
    }

    private Boolean probeAnswer(List<String> answer, String name) {
        if (answer == null) {
            return null;
        }
        for (String raw : answer) {
            if (!AutoTpaHereModule.sameIgn(this.stripCommand(raw), name)) continue;
            return Boolean.TRUE;
        }
        return Boolean.FALSE;
    }

    private void noteProbe(Boolean online) {
        if (!Boolean.FALSE.equals(online)) {
            this.probeMisses = 0;
            return;
        }
        if (++this.probeMisses >= 25 && !this.onlineCheckOff) {
            this.onlineCheckOff = true;
            this.info(ModuleChat.body().warn("Online check off").text(": 25 players in a row looked offline, the server may not confirm names."));
        }
    }

    private List<String> localNames(ClientPacketListener connection, String prefix) {
        ArrayList<String> out = new ArrayList<String>();
        for (PlayerInfo info : connection.getOnlinePlayers()) {
            String name = info.getProfile() == null ? null : info.getProfile().name();
            if (name == null || !AutoTpaHereModule.validName(name) || !this.matchesLetter(name, prefix)) continue;
            out.add(name);
        }
        return out;
    }

    private void startSweep() {
        this.sweepLetters = this.letterList();
        this.sweepList = List.of();
        this.sweepIndex = 0;
        this.sweepRounds = 0;
        this.sweepInflight.clear();
        this.sweepOn = !this.sweepLetters.isEmpty();
    }

    private void pumpSweep(long now) {
        if (!this.sweepOn) {
            return;
        }
        this.sweepInflight.removeIf(request -> {
            List<String> answer = this.completions.poll(request.id());
            if (answer != null) {
                this.sweepResult(request.prefix(), answer, now);
            } else if (now - request.at() <= 4000L) {
                return false;
            }
            this.completions.forget(request.id());
            return true;
        });
        if (this.sweepInflight.size() >= 4 || now - this.sweepLastFire < 200L) {
            return;
        }
        if (this.sweepIndex >= this.sweepList.size()) {
            if (this.sweepRounds > 0 && now - this.sweepRoundAt < 45000L) {
                return;
            }
            ArrayList<String> list = new ArrayList<String>();
            for (String l : this.sweepLetters) {
                if (this.splitLetters.contains(l)) {
                    list.addAll(AutoTpaHereModule.subPrefixes(l));
                    continue;
                }
                list.add(l);
            }
            this.sweepList = list;
            this.sweepIndex = 0;
            ++this.sweepRounds;
            this.sweepRoundAt = now;
        }
        if (this.sweepList.isEmpty()) {
            return;
        }
        String prefix = this.sweepList.get(this.sweepIndex++);
        int id = this.completions.fire(this.partialFor(prefix));
        this.sweepLastFire = now;
        if (id >= 0) {
            this.sweepInflight.add(new Inflight(id, prefix, now));
        }
    }

    private void sweepResult(String prefix, List<String> answer, long now) {
        String first = prefix.substring(0, 1);
        if (answer.size() >= 990) {
            if (prefix.length() == 1 && this.splitLetters.add(first)) {
                this.prefixCache.remove(prefix);
            }
            return;
        }
        HashMap<String, String> current = new HashMap<String, String>();
        for (String name : this.namesForPrefix(answer, prefix)) {
            String clean = AutoTpaHereModule.cleanName(this.stripCommand(name));
            if (!AutoTpaHereModule.validName(clean)) continue;
            current.put(AutoTpaHereModule.nameKey(clean), clean);
        }
        Set<String> previous = this.prefixCache.get(prefix);
        if (previous != null && !previous.isEmpty() && current.isEmpty()) {
            return;
        }
        this.prefixCache.put(prefix, new HashSet(current.keySet()));
        if (previous == null) {
            return;
        }
        ArrayList<String> fresh = new ArrayList<String>();
        for (Map.Entry entry : current.entrySet()) {
            if (previous.contains(entry.getKey())) continue;
            fresh.add((String)entry.getValue());
        }
        if (fresh.size() > 15 && (double)fresh.size() > (double)previous.size() * 0.3) {
            return;
        }
        for (String name : fresh) {
            this.queueJoiner(name, now);
        }
    }

    private void queueJoiner(String name, long now) {
        String key = AutoTpaHereModule.nameKey(name);
        if (key.isEmpty() || this.joinQueued.contains(key) || !this.joinEligible(name, now)) {
            return;
        }
        if (this.joinQueue.size() >= 300) {
            this.joinQueued.remove(AutoTpaHereModule.nameKey(this.joinQueue.poll()));
        }
        this.joinQueue.add(name);
        this.joinQueued.add(key);
    }

    private boolean joinEligible(String name, long now) {
        String key = AutoTpaHereModule.nameKey(name);
        if (key.isEmpty() || this.excluded(name) || this.sentThisLetter.contains(key)) {
            return false;
        }
        Long at = this.msgAt.get(key);
        return at == null || now - at >= 3600000L;
    }

    private boolean recentlyJoinMessaged(String key, long now) {
        Long at = this.joinMsgAt.get(key);
        return at != null && now - at < 3600000L;
    }

    private List<String> unusedFromNames(List<String> names) {
        long now = System.currentTimeMillis();
        ArrayList<String> unused = new ArrayList<String>();
        for (String name : names) {
            String key = AutoTpaHereModule.nameKey(name);
            if (key.isEmpty() || this.excluded(name) || this.sentThisLetter.contains(key) || this.recentlyJoinMessaged(key, now)) continue;
            unused.add(name);
        }
        return unused;
    }

    private void drainChat(long now) {
        String line;
        while ((line = this.chatLines.poll()) != null) {
            String low = FORMATTING.matcher(line).replaceAll("").trim().toLowerCase(Locale.ROOT);
            if (low.isEmpty()) continue;
            if (low.contains("disabled tpahere")) {
                if (!this.claimReply(now)) continue;
                ++this.totalDisabled;
                continue;
            }
            if (NOT_ONLINE.matcher(low).find()) {
                if (!this.claimReply(now)) continue;
                ++this.totalOffline;
                continue;
            }
            Matcher wait = RATE_LIMIT.matcher(low);
            if (wait.find()) {
                try {
                    this.rateHoldUntil = now + (long)Math.ceil(Math.min(30.0, Double.parseDouble(wait.group(1))) * 1000.0) + 80L;
                }
                catch (NumberFormatException numberFormatException) {
                    // empty catch block
                }
                this.sell.commandRejected(this.rateHoldUntil);
                if (!this.claimReply(now)) continue;
                this.totalSent = Math.max(0, this.totalSent - 1);
                if (this.retryQueue.contains(this.lastTarget)) continue;
                this.retryQueue.add(this.lastTarget);
                continue;
            }
            Matcher sent = SENT_REQUEST.matcher(low);
            if (!sent.find() || !AutoTpaHereModule.sameIgn(this.lastTarget, sent.group(1))) continue;
            this.lastTargetCounted = true;
        }
    }

    private boolean claimReply(long now) {
        if (this.lastTarget.isEmpty() || this.lastTargetCounted || now - this.lastTargetAt > 2500L) {
            return false;
        }
        this.lastTargetCounted = true;
        return true;
    }

    private void prune(long now) {
        this.lastPruneAt = now;
        this.msgAt.values().removeIf(at -> now - at > 3600000L);
        this.joinMsgAt.values().removeIf(at -> now - at > 3600000L);
        this.completions.prune(now);
    }

    private boolean handleDeath(Minecraft mc, LocalPlayer player, long now) {
        if (player.isDeadOrDying()) {
            if (!this.wasDead) {
                this.wasDead = true;
                this.deadSince = now;
                this.respawnTriedAt = 0L;
                ++this.totalDeaths;
                this.stopEating(mc);
                this.info(ModuleChat.body().danger("You died."));
            }
            if (this.autoRespawn.get() && now - this.deadSince >= 700L && now - this.respawnTriedAt >= 600L) {
                this.respawnTriedAt = now;
                player.respawn();
            }
            return true;
        }
        if (this.wasDead) {
            this.wasDead = false;
            this.nearList.clear();
            this.lastNearAt = 0L;
            if (mc.gui.screen() instanceof DeathScreen) {
                mc.setScreenAndShow(null);
            }
            this.info(ModuleChat.body().good("Respawned."));
            if (!this.respawnCommand.get().isBlank()) {
                this.respawnCommandIn = 20;
            }
        }
        return false;
    }

    private void tickRespawnCommand(ClientPacketListener connection) {
        if (this.respawnCommandIn <= 0 || --this.respawnCommandIn > 0) {
            return;
        }
        String text = this.respawnCommand.get().trim();
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        if (!text.isEmpty()) {
            connection.sendCommand(text);
        }
    }

    private void scanNear(Minecraft mc, LocalPlayer player) {
        this.nearList.clear();
        double radius = this.pauseRadius.get();
        for (AbstractClientPlayer other : mc.level.players()) {
            double distance;
            String name;
            if (other == player || other.getUUID().equals(player.getUUID()) || (name = other.getGameProfile().name()) == null || this.isSelf(name) || AutoTpaHereModule.isFriend(name) || !((distance = player.position().distanceTo(other.position())) <= radius)) continue;
            this.nearList.add(new Near(other, name, distance));
        }
        this.nearList.sort((a, b) -> Double.compare(a.distance(), b.distance()));
    }

    private boolean holding(long now) {
        boolean holding;
        if (!this.nearList.isEmpty()) {
            this.lastNearAt = now;
        }
        boolean bl = holding = !this.nearList.isEmpty() || this.lastNearAt > 0L && now - this.lastNearAt < (long)(this.resumeAfter.get() * 1000.0f);
        if (holding && !this.wasHolding && !this.nearList.isEmpty()) {
            Near first = this.nearList.get(0);
            ++this.totalArrivals;
            this.info(ModuleChat.body().name(first.name()).text(" is here (").value((Object)String.format(Locale.ROOT, "%.1f", first.distance())).text(" blocks). Requests paused."));
        } else if (!holding && this.wasHolding) {
            this.info(ModuleChat.body().text("Alone again, resuming."));
        }
        this.wasHolding = holding;
        return holding;
    }

    private boolean resting(long now) {
        boolean resting;
        long onMs = (long)(this.sendMinutes.get() * 60000.0f);
        long offMs = (long)(this.restMinutes.get() * 60000.0f);
        boolean bl = resting = onMs > 0L && offMs > 0L && (now - this.cycleStartedAt) % (onMs + offMs) >= onMs;
        if (resting != this.wasResting) {
            this.wasResting = resting;
            this.info(resting ? ModuleChat.body().text("Resting for ").value((Object)(this.restMinutes.getInt() + " min")) : ModuleChat.body().text("Rest over, sending again."));
        }
        return resting;
    }

    private void fight(Minecraft mc, LocalPlayer player) {
        Vec3 at;
        if (!this.attack.get() || this.eating || !AutoTpaHereModule.canFight(mc, player)) {
            return;
        }
        AbstractClientPlayer target = null;
        for (Near near : this.nearList) {
            if (near.distance() > (double)this.attackRadius.get()) break;
            if (!MeleeAttacks.isEnemy((LocalPlayer)player, (Entity)near.player())) continue;
            target = near.player();
            break;
        }
        if (target == null) {
            return;
        }
        if (this.equipSword(player)) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        AABB box = target.getBoundingBox();
        Rotation aim = RotationUtils.fromTo((Vec3)eye, (Vec3)box.getCenter());
        if (MeleeAttacks.charge((Player)player) < 1.0f) {
            if (MeleeAttacks.ticksToFull((Player)player) <= 1) {
                ServerRotation.request((Rotation)aim, this);
            }
            return;
        }
        Rotation look = ServerRotation.sent();
        if (look == null) {
            look = RotationUtils.entityRotation((Entity)player);
        }
        if ((at = AutoTpaHereModule.hitPoint(player, eye, box, look)) == null) {
            ServerRotation.request((Rotation)aim, this);
            return;
        }
        if (MeleeAttacks.attack((Minecraft)mc, (EntityHitResult)new EntityHitResult((Entity)target, at))) {
            MeleeAttacks.claimTick((Minecraft)mc);
            ++this.totalHits;
        }
    }

    private static boolean canFight(Minecraft mc, LocalPlayer player) {
        return mc.level != null && mc.gameMode != null && !player.isSpectator() && player.containerMenu == player.inventoryMenu && !AutoEatModule.isEating();
    }

    private static Vec3 hitPoint(LocalPlayer player, Vec3 eye, AABB box, Rotation look) {
        double reach = player.entityInteractionRange();
        Vec3 direction = Vec3.directionFromRotation((float)look.pitch(), (float)look.yaw());
        Vec3 at = box.clip(eye, eye.add(direction.scale(reach))).orElse(box.contains(eye) ? eye : null);
        return at != null && eye.distanceTo(at) <= reach && MeleeAttacks.lineIsClear((LocalPlayer)player, (Vec3)eye, (Vec3)at) ? at : null;
    }

    private boolean equipSword(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int slot = this.findSword(inventory);
        if (slot < 0) {
            if (!this.warnedNoSword) {
                this.warnedNoSword = true;
                this.info(ModuleChat.body().warn("No sword in the hotbar").text(", attacking with the held item."));
            }
            return false;
        }
        if (inventory.getSelectedSlot() == slot) {
            return false;
        }
        inventory.setSelectedSlot(slot);
        return true;
    }

    private int findSword(Inventory inventory) {
        if (this.swordSlot.getInt() >= 1) {
            return this.swordSlot.getInt() - 1;
        }
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int slot = 0; slot < Inventory.getSelectionSize(); ++slot) {
            String path = AutoTpaHereModule.itemPath(inventory.getItem(slot));
            if (!path.endsWith("_sword")) continue;
            int rank = SWORD_RANK.length;
            for (int i = 0; i < SWORD_RANK.length; ++i) {
                if (!path.startsWith(SWORD_RANK[i])) continue;
                rank = i;
                break;
            }
            if (rank >= bestRank) continue;
            bestRank = rank;
            best = slot;
        }
        return best;
    }

    private int findApple(Inventory inventory) {
        ItemStack stack;
        int chosen = this.appleSlot.getInt() - 1;
        if (chosen >= 0 && chosen < Inventory.getSelectionSize() && ((stack = inventory.getItem(chosen)).is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE))) {
            return chosen;
        }
        int normal = -1;
        int enchanted = -1;
        for (int slot = 0; slot < Inventory.getSelectionSize(); ++slot) {
            ItemStack stack2 = inventory.getItem(slot);
            if (stack2.is(Items.ENCHANTED_GOLDEN_APPLE) && enchanted < 0) {
                enchanted = slot;
                continue;
            }
            if (!stack2.is(Items.GOLDEN_APPLE) || normal >= 0) continue;
            normal = slot;
        }
        if (this.enchantedFirst.get()) {
            return enchanted >= 0 ? enchanted : normal;
        }
        return normal >= 0 ? normal : enchanted;
    }

    private static String itemPath(ItemStack stack) {
        return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private boolean wantsToEat(LocalPlayer player, long now) {
        float health = player.getHealth();
        return this.eatBelow.get() > 0.0f && health > 0.0f && health < this.eatBelow.get() * 2.0f && now - this.lastEatAt >= (long)(this.eatCooldown.get() * 1000.0f) && this.findApple(player.getInventory()) >= 0;
    }

    private boolean keepForBot(int index, ItemStack stack) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (this.attack.get() && player != null && index == this.findSword(player.getInventory())) {
            return true;
        }
        return this.eatBelow.get() > 0.0f && (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE));
    }

    private void onSellCommand() {
        this.lastSendAt = System.currentTimeMillis();
        this.lastTargetCounted = true;
    }

    private boolean startEating(Minecraft mc, LocalPlayer player, long now) {
        if (this.eating || this.eatBelow.get() <= 0.0f || mc.gui.screen() != null) {
            return false;
        }
        float health = player.getHealth();
        if (health <= 0.0f || health >= this.eatBelow.get() * 2.0f || now - this.lastEatAt < (long)(this.eatCooldown.get() * 1000.0f)) {
            return false;
        }
        this.lastEatAt = now;
        int slot = this.findApple(player.getInventory());
        if (slot < 0) {
            if (!this.warnedNoApple) {
                this.warnedNoApple = true;
                this.info(ModuleChat.body().warn("Low health but no golden apple in the hotbar."));
            }
            return false;
        }
        player.getInventory().setSelectedSlot(slot);
        this.eating = true;
        this.eatTicks = 0;
        this.eatStarted = false;
        this.eatStartHealth = health;
        mc.options.keyUse.setDown(true);
        return true;
    }

    private boolean tickEating(Minecraft mc, LocalPlayer player, long now) {
        if (!this.eating) {
            return false;
        }
        ++this.eatTicks;
        if (player.isUsingItem()) {
            this.eatStarted = true;
            if (this.eatTicks < 40) {
                mc.options.keyUse.setDown(true);
                return true;
            }
        } else if (!this.eatStarted && this.eatTicks < 40) {
            mc.options.keyUse.setDown(true);
            return true;
        }
        this.stopEating(mc);
        this.lastEatAt = now;
        if (this.eatStarted) {
            ++this.totalEaten;
            this.info(ModuleChat.body().text("Ate a golden apple (").value((Object)String.format(Locale.ROOT, "%.1f", Float.valueOf(this.eatStartHealth / 2.0f))).text(" -> ").value((Object)String.format(Locale.ROOT, "%.1f", Float.valueOf(player.getHealth() / 2.0f))).text(" hearts)."));
        }
        if (this.attack.get()) {
            this.equipSword(player);
        }
        return false;
    }

    private void stopEating(Minecraft mc) {
        if (!this.eating) {
            return;
        }
        this.eating = false;
        mc.options.keyUse.setDown(Crystals.isPhysicallyDown((Minecraft)mc, (KeyMapping)mc.options.keyUse));
    }

    private List<String> letterList() {
        List<String> parsed = AutoTpaHereModule.parseLetters(this.letters.get());
        if (this.order.is(ORDER_ZA)) {
            Collections.reverse(parsed);
        }
        if (!this.numbers.get()) {
            return parsed;
        }
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < EXTRA_CHARS.length(); ++i) {
            out.add(String.valueOf(EXTRA_CHARS.charAt(i)));
        }
        out.addAll(parsed);
        return out;
    }

    static List<String> parseLetters(String raw) {
        String spec = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        for (String part : spec.split(",")) {
            if (part.length() == 3 && part.charAt(1) == '-' && AutoTpaHereModule.isAz(part.charAt(0)) && AutoTpaHereModule.isAz(part.charAt(2))) {
                char to;
                char from = part.charAt(0);
                int step = from <= (to = part.charAt(2)) ? 1 : -1;
                char c = from;
                while (c != to + step) {
                    out.add(String.valueOf(c));
                    c = (char)(c + step);
                }
                continue;
            }
            for (int i = 0; i < part.length(); ++i) {
                if (!AutoTpaHereModule.isAz(part.charAt(i))) continue;
                out.add(String.valueOf(part.charAt(i)));
            }
        }
        if (out.isEmpty()) {
            for (char c = 'a'; c <= 'z'; c = (char)(c + '\u0001')) {
                out.add(String.valueOf(c));
            }
        }
        return new ArrayList<String>(out);
    }

    private static boolean isAz(char c) {
        return c >= 'a' && c <= 'z';
    }

    private String firstLetter() {
        String first;
        List<String> list = this.letterList();
        String wanted = this.startLetter.get().trim().toLowerCase(Locale.ROOT);
        if (!wanted.isEmpty() && list.contains(first = wanted.substring(0, 1))) {
            return first;
        }
        return list.get(0);
    }

    private static List<String> subPrefixes(String letter) {
        ArrayList<String> out = new ArrayList<String>(SUB_CHARS.length());
        for (int i = 0; i < SUB_CHARS.length(); ++i) {
            out.add(letter + SUB_CHARS.charAt(i));
        }
        return out;
    }

    private String partialFor(String prefix) {
        return this.command.get() + " " + (this.bedrock.get() ? "." : "") + prefix;
    }

    private List<String> namesForPrefix(List<String> answer, String prefix) {
        String wanted = AutoTpaHereModule.stripDots(prefix).toLowerCase(Locale.ROOT);
        ArrayList<String> out = new ArrayList<String>();
        for (String raw : answer) {
            String name = AutoTpaHereModule.cleanName(this.stripCommand(raw));
            if (!AutoTpaHereModule.validName(name) || !AutoTpaHereModule.stripDots(name).toLowerCase(Locale.ROOT).startsWith(wanted)) continue;
            out.add(name);
        }
        return out;
    }

    private boolean matchesLetter(String name, String prefix) {
        String core;
        String string = core = this.bedrock.get() ? AutoTpaHereModule.stripDots(name) : name;
        if (this.bedrock.get() && core.length() == name.length()) {
            return false;
        }
        return !core.isEmpty() && core.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    private String stripCommand(String text) {
        int space;
        String t = text == null ? "" : text.trim();
        String low = t.toLowerCase(Locale.ROOT);
        String cmd = this.command.get();
        for (String prefix : new String[]{"/" + cmd + " ", cmd + " ", "/tpahere ", "tpahere ", "/tpa ", "tpa "}) {
            if (!low.startsWith(prefix)) continue;
            t = t.substring(prefix.length()).trim();
            break;
        }
        if ((space = t.indexOf(32)) > 0) {
            t = t.substring(0, space);
        }
        return t.replaceAll("^[\"']|[\"']$", "");
    }

    private static String cleanName(String name) {
        return FORMATTING.matcher(name == null ? "" : name).replaceAll("").trim();
    }

    private static boolean validName(String name) {
        return name != null && VALID_NAME.matcher(name).matches() && !AutoTpaHereModule.stripDots(name).isEmpty();
    }

    private static boolean isLetter(String prefix) {
        return !prefix.isEmpty() && AutoTpaHereModule.isAz(prefix.charAt(0));
    }

    private static String stripDots(String name) {
        int i;
        for (i = 0; i < name.length() && name.charAt(i) == '.'; ++i) {
        }
        return name.substring(i);
    }

    private static String normalizeIgn(String name) {
        return name == null ? "" : AutoTpaHereModule.stripDots(AutoTpaHereModule.cleanName(name)).toLowerCase(Locale.ROOT);
    }

    private static String nameKey(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean sameIgn(String a, String b) {
        String x = AutoTpaHereModule.normalizeIgn(a);
        return !x.isEmpty() && x.equals(AutoTpaHereModule.normalizeIgn(b));
    }

    private boolean isSelf(String name) {
        return !this.selfName.isEmpty() && this.selfName.equals(AutoTpaHereModule.normalizeIgn(name));
    }

    private static boolean isFriend(String name) {
        FriendStore friends = FriendStore.getInstance();
        return friends.isFriend(name) || friends.isFriend(AutoTpaHereModule.stripDots(name)) || friends.isFriend("." + AutoTpaHereModule.stripDots(name));
    }

    private boolean blacklisted(String name) {
        String wanted = AutoTpaHereModule.normalizeIgn(name);
        if (wanted.isEmpty()) {
            return false;
        }
        for (String entry : this.neverRequest.get()) {
            if (!wanted.equals(AutoTpaHereModule.normalizeIgn(entry))) continue;
            return true;
        }
        return false;
    }

    private boolean excluded(String name) {
        return name == null || this.isSelf(name) || AutoTpaHereModule.isFriend(name) || this.blacklisted(name);
    }

    private long delayMs() {
        return (long)(this.delay.get() * 1000.0f);
    }

    private String orderLabel() {
        String head;
        List<String> parsed = AutoTpaHereModule.parseLetters(this.letters.get());
        String string = head = this.numbers.get() ? "0-9_ " : "";
        if (this.order.is(ORDER_RANDOM)) {
            return "random " + head + (parsed.size() < 26 ? String.join((CharSequence)"", parsed).toUpperCase(Locale.ROOT) : "A-Z");
        }
        if (parsed.size() < 26) {
            if (this.order.is(ORDER_ZA)) {
                Collections.reverse(parsed);
            }
            return head + String.join((CharSequence)"", parsed).toUpperCase(Locale.ROOT);
        }
        return head + (this.order.is(ORDER_ZA) ? ORDER_ZA : ORDER_AZ);
    }

    private static String formatSeconds(long ms) {
        return ms % 1000L == 0L ? ms / 1000L + "s" : String.format(Locale.ROOT, "%.2fs", (double)ms / 1000.0);
    }

    private String statsText() {
        StringBuilder text = new StringBuilder("Sent ").append(this.totalSent).append(", offline ").append(this.totalOffline).append(", disabled ").append(this.totalDisabled);
        if (this.totalJoiners > 0) {
            text.append(", new players ").append(this.totalJoiners);
        }
        if (this.totalArrivals > 0) {
            text.append(", arrivals ").append(this.totalArrivals);
        }
        if (this.totalHits > 0) {
            text.append(", hits ").append(this.totalHits);
        }
        if (this.totalEaten > 0) {
            text.append(", apples ").append(this.totalEaten);
        }
        if (this.totalDeaths > 0) {
            text.append(", deaths ").append(this.totalDeaths);
        }
        return text.append('.').toString();
    }

    private void info(ModuleChat.Body body) {
        ModuleChat.send((ClientModule)this, (ModuleChat.Body)body);
    }

    @Environment(value=EnvType.CLIENT)
    private static enum Phase {
        DETECT,
        COLLECT,
        SEND,
        PICK;

    }

    @Environment(value=EnvType.CLIENT)
    private final class Delivery {
        final String name;
        final boolean joiner;
        boolean stale;
        int probeId = -1;
        long probeAt;
        boolean finalWait;
        long finalUntil;

        Delivery(String name, boolean joiner) {
            this.name = name;
            this.joiner = joiner;
        }

        Result step(ClientPacketListener connection, long now) {
            if (this.stale) {
                this.stale = false;
                AutoTpaHereModule.this.completions.forget(this.probeId);
                this.probeId = -1;
                this.finalWait = false;
            }
            if (AutoTpaHereModule.this.excluded(this.name)) {
                return Result.SKIPPED;
            }
            if (AutoTpaHereModule.this.skipOffline.get() && !AutoTpaHereModule.this.onlineCheckOff && this.probeId < 0 && !this.finalWait) {
                this.probeId = AutoTpaHereModule.this.completions.fire(AutoTpaHereModule.this.command.get() + " " + this.name);
                this.probeAt = now;
            }
            if (now < AutoTpaHereModule.this.lastSendAt + AutoTpaHereModule.this.delayMs()) {
                if (this.probeId >= 0 && Boolean.FALSE.equals(AutoTpaHereModule.this.probeAnswer(AutoTpaHereModule.this.completions.poll(this.probeId), this.name))) {
                    AutoTpaHereModule.this.completions.forget(this.probeId);
                    ++AutoTpaHereModule.this.totalOffline;
                    AutoTpaHereModule.this.noteProbe(false);
                    return Result.SKIPPED;
                }
                return Result.WAIT;
            }
            if (this.probeId >= 0) {
                List<String> answer;
                if (!this.finalWait) {
                    this.finalWait = true;
                    if (now - this.probeAt > 4000L) {
                        AutoTpaHereModule.this.completions.forget(this.probeId);
                        this.probeId = AutoTpaHereModule.this.completions.fire(AutoTpaHereModule.this.command.get() + " " + this.name);
                        this.probeAt = now;
                        this.finalUntil = now + 700L;
                    } else {
                        this.finalUntil = now + Math.max(100L, 700L - (now - this.probeAt));
                    }
                }
                if ((answer = AutoTpaHereModule.this.completions.poll(this.probeId)) == null && now < this.finalUntil) {
                    return Result.WAIT;
                }
                AutoTpaHereModule.this.completions.forget(this.probeId);
                this.probeId = -1;
                Boolean online = AutoTpaHereModule.this.probeAnswer(answer, this.name);
                AutoTpaHereModule.this.noteProbe(online);
                if (Boolean.FALSE.equals(online)) {
                    ++AutoTpaHereModule.this.totalOffline;
                    return Result.SKIPPED;
                }
            }
            AutoTpaHereModule.this.send(connection, this.name, this.joiner, now);
            return Result.SENT;
        }
    }

    @Environment(value=EnvType.CLIENT)
    private final class Collector {
        final String letter;
        boolean split;
        boolean timedOut;
        List<String> names = List.of();
        private boolean started;
        private int mainId = -1;
        private long mainUntil;
        private final List<String> raw = new ArrayList<String>();
        private List<String> subs;
        private int subIndex;
        private final List<Inflight> inflight = new ArrayList<Inflight>();

        Collector(String letter) {
            this.letter = letter;
        }

        boolean step(ClientPacketListener connection, long now) {
            if (!this.started) {
                this.started = true;
                this.split = AutoTpaHereModule.this.splitLetters.contains(this.letter);
                if (!this.split) {
                    this.mainId = AutoTpaHereModule.this.completions.fire(AutoTpaHereModule.this.partialFor(this.letter));
                    this.mainUntil = now + (AutoTpaHereModule.this.localNames(connection, this.letter).isEmpty() ? 5000L : 3000L);
                    if (this.mainId < 0) {
                        this.timedOut = true;
                    }
                }
            }
            if (this.mainId >= 0) {
                List<String> answer = AutoTpaHereModule.this.completions.poll(this.mainId);
                if (answer == null && now < this.mainUntil) {
                    return false;
                }
                AutoTpaHereModule.this.completions.forget(this.mainId);
                this.mainId = -1;
                if (answer == null) {
                    this.timedOut = true;
                } else {
                    this.raw.addAll(AutoTpaHereModule.this.namesForPrefix(answer, this.letter));
                    if (answer.size() >= 990) {
                        this.split = true;
                        AutoTpaHereModule.this.splitLetters.add(this.letter);
                    }
                }
            }
            if (this.split) {
                if (this.subs == null) {
                    this.subs = AutoTpaHereModule.subPrefixes(this.letter);
                }
                if (!this.stepSplit(now)) {
                    return false;
                }
                this.timedOut = this.timedOut && this.raw.isEmpty();
            }
            this.raw.addAll(AutoTpaHereModule.this.localNames(connection, this.letter));
            HashSet<String> seen = new HashSet<String>();
            ArrayList<String> out = new ArrayList<String>();
            for (String candidate : this.raw) {
                String name = AutoTpaHereModule.cleanName(AutoTpaHereModule.this.stripCommand(candidate));
                if (!AutoTpaHereModule.validName(name) || !AutoTpaHereModule.this.matchesLetter(name, this.letter) || !seen.add(AutoTpaHereModule.nameKey(name))) continue;
                out.add(name);
            }
            this.names = out;
            return true;
        }

        private boolean stepSplit(long now) {
            while (this.subIndex < this.subs.size() && this.inflight.size() < 6) {
                String prefix;
                int id;
                if ((id = AutoTpaHereModule.this.completions.fire(AutoTpaHereModule.this.partialFor(prefix = this.subs.get(this.subIndex++)))) < 0) continue;
                this.inflight.add(new Inflight(id, prefix, now));
            }
            this.inflight.removeIf(request -> {
                List<String> answer = AutoTpaHereModule.this.completions.poll(request.id());
                if (answer != null) {
                    this.raw.addAll(AutoTpaHereModule.this.namesForPrefix(answer, request.prefix()));
                } else if (now - request.at() <= 4000L) {
                    return false;
                }
                AutoTpaHereModule.this.completions.forget(request.id());
                return true;
            });
            return this.subIndex >= this.subs.size() && this.inflight.isEmpty();
        }
    }

    @Environment(value=EnvType.CLIENT)
    private static final class Bucket {
        List<String> names = List.of();
        List<String> unused = new ArrayList<String>();
        boolean scanned;
        long at;

        private Bucket() {
        }
    }

    @Environment(value=EnvType.CLIENT)
    private static enum Result {
        WAIT,
        SENT,
        SKIPPED;

    }

    @Environment(value=EnvType.CLIENT)
    private record Inflight(int id, String prefix, long at) {
    }

    @Environment(value=EnvType.CLIENT)
    private record Near(AbstractClientPlayer player, String name, double distance) {
    }
}
