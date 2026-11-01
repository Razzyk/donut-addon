// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.donut.addon.anubis.chat.ModuleChat;
import dev.donut.addon.anubis.module.ClientModule;
import dev.donut.addon.anubis.module.impl.donutmacros.AhSniperModule;
import dev.donut.addon.anubis.module.impl.donutmacros.AuctionHouse;
import dev.donut.addon.anubis.module.impl.donutmacros.Dialogs;
import dev.donut.addon.anubis.module.impl.donutmacros.Gui;
import dev.donut.addon.anubis.module.impl.donutmacros.MacroWorker;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import dev.donut.addon.anubis.module.impl.donutmacros.SnipeTarget;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ItemLike;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.core.component.DataComponents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Environment(value=EnvType.CLIENT)
final class SniperBot {
    private static final Logger LOGGER = LoggerFactory.getLogger((String)"Anubis");
    private static final Pattern RATE_LIMIT = Pattern.compile("^you need to wait another ([\\d.]+) seconds?");
    private static final double MAX_WAIT_SECONDS = 30.0;
    private static final Pattern BOUGHT_COUNT = Pattern.compile("^(\\d{1,4})\\s*x?\\s+");
    private static final Pattern TIME = Pattern.compile("\\d+\\s*[dhms]\\b");
    private static final int LISTING_SLOTS = 45;
    private static final int DEFAULT_FILTER = 47;
    private static final int DEFAULT_REFRESH = 49;
    private static final long OPEN_TIMEOUT_MS = 10000L;
    private static final long CONTENT_TIMEOUT_MS = 3000L;
    private static final long SORT_TIMEOUT_MS = 3000L;
    private static final long SETTLE_MS = 150L;
    private static final long SETTLE_LIMIT_MS = 1500L;
    private static final int SETTLE_TICKS = 2;
    private static final long ANSWER_DEFAULT_MS = 1000L;
    private static final long ANSWER_MIN_MS = 700L;
    private static final long ANSWER_MAX_MS = 3000L;
    private static final long ANSWER_SLACK_MS = 300L;
    private static final double CONFIRM_PRICE_SLACK = 1.02;
    private static final long BUY_TIMEOUT_MS = 4000L;
    private static final long SCREEN_GRACE_MS = 400L;
    private static final long REOPEN_WAIT_MS = 1000L;
    private static final long LATE_PURCHASE_MS = 15000L;
    private static final long FAILED_SKIP_MS = 60000L;
    private static final long WRONG_SKIP_MS = 600000L;
    private static final long BOUGHT_SKIP_MS = 15000L;
    private static final long RECOVERY_MS = 2000L;
    private static final long POLL_MS = 2L;
    private static final int TIMER_SLACK_MINUTES = 2;
    private static final int STRIKES_TO_SKIP = 2;
    private static final int UNREADABLE_TO_STOP = 4;
    private static final Hit CLOSED = new Hit(-1, ItemStack.EMPTY, 0L, 0L, -1, "", "");
    private final AhSniperModule module;
    private final MacroWorker worker;
    private final Gui gui;
    private final SnipeTarget target;
    private final long refreshMs;
    private final long settleMs;
    private final long budget;
    private final boolean requireConfirm;
    private final int maxBuys;
    private final AuctionHouse ah;
    private final AuctionHouse.Stock stock;
    private final List<AuctionHouse.Stock> stocks;
    private final Map<String, Long> skip = new HashMap<String, Long>();
    private final Map<String, Integer> strikes = new HashMap<String, Integer>();
    private volatile Purchase purchase;
    private volatile long goneAt;
    private volatile long poorAt;
    private volatile long ownAt;
    private volatile long holdUntil;
    private long handledAt;
    private Hit lastHit;
    private long lastClickAt;
    private long pageKey = Long.MIN_VALUE;
    private long pageChangedAt;
    private int pageChangedTick;
    private boolean refreshPending;
    private long refreshSentAt;
    private long answerMs;
    private long nextRefresh;
    private volatile boolean confirmPressed;
    private boolean instantWarned;
    private int unreadable;
    private int bought;
    private int unsold;
    private long spent;
    private int wrong;
    private long wrongSpent;

    SniperBot(AhSniperModule module, MacroWorker worker, SnipeTarget target, long refreshMs, long settleMs, long budget, boolean requireConfirm, int maxBuys, long sellPrice, String ign) {
        this.module = module;
        this.worker = worker;
        this.gui = new Gui(worker);
        this.target = target;
        this.refreshMs = refreshMs;
        this.settleMs = settleMs;
        this.budget = budget;
        this.requireConfirm = requireConfirm;
        this.maxBuys = maxBuys;
        if (sellPrice > 0L) {
            this.ah = new AuctionHouse(module, worker, new AuctionHouse.Config(0L, 60000L, 20000L, 1, 1000, 0L, 3.0, 20.0, 36, 15000L), ign);
            this.stock = new AuctionHouse.Stock(target.product(sellPrice), true);
            this.stocks = List.of(this.stock);
        } else {
            this.ah = null;
            this.stock = null;
            this.stocks = List.of();
        }
    }

    private void say(ModuleChat.Body body) {
        ModuleChat.send((ClientModule)this.module, (ModuleChat.Body)body);
    }

    void run() {
        this.say(ModuleChat.body().text("Sniping ").name(this.target.describe()).text(" under $").value((Object)ServerText.shortMoney(this.target.maxPrice)).text((String)(this.target.minMinutes > 0 ? " with " + SniperBot.formatMinutes(this.target.minMinutes) + "+ left" : "")).text((String)(this.stock != null ? ", selling at $" + ServerText.shortMoney(this.stock.price) : "")).text((String)(this.budget > 0L ? ", spending at most $" + ServerText.shortMoney(this.budget) : "")).text("."));
        while (true) {
            this.worker.check();
            try {
                if (!this.worker.call(() -> Gui.mc().player != null && Gui.mc().getConnection() != null).booleanValue()) {
                    this.worker.sleep(2000L);
                    continue;
                }
                if (this.stock != null && this.unsold > 0) {
                    this.sellStock();
                }
                this.snipe();
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
                this.worker.sleep(2000L);
                continue;
            }
        }
    }

    private void snipe() {
        this.openAuction();
        this.ensureRecent();
        this.pageKey = Long.MIN_VALUE;
        this.refreshPending = false;
        this.nextRefresh = System.currentTimeMillis() + this.refreshMs;
        while (true) {
            this.worker.check();
            Purchase late = this.purchase;
            if (late != null && late.at() > this.handledAt) {
                if (this.lastHit != null && late.at() - this.lastClickAt <= 15000L) {
                    if (!this.bought(this.lastHit, late)) continue;
                    return;
                }
                this.handledAt = late.at();
            }
            long mark = System.currentTimeMillis();
            Hit hit = this.worker.call(this::pageTick);
            if (hit == CLOSED) {
                return;
            }
            if (hit != null) {
                this.lastHit = hit;
                this.lastClickAt = mark;
                if (this.handleBuy(hit, mark)) {
                    return;
                }
                this.pageKey = Long.MIN_VALUE;
                this.nextRefresh = System.currentTimeMillis() + this.refreshMs;
                continue;
            }
            this.worker.sleep(2L);
        }
    }

    private void openAuction() {
        this.gui.closeAll();
        long wait = this.holdUntil - System.currentTimeMillis();
        if (wait > 0L) {
            this.worker.sleep(wait);
        }
        this.gui.command("ah " + this.target.search);
        if (!this.worker.waitFor(SniperBot::auctionOpenNow, 10000L)) {
            throw new AuctionHouse.Problem("The AH did not open");
        }
        this.worker.waitFor(() -> {
            if (!SniperBot.auctionOpenNow()) {
                return true;
            }
            List<ItemStack> stacks = Gui.containerNow();
            return SniperBot.refreshSlot(stacks) >= 0 || SniperBot.filterSlot(stacks) >= 0;
        }, 3000L);
        this.settle();
    }

    private void settle() {
        long end = System.currentTimeMillis() + 1500L;
        int last = this.worker.call(SniperBot::stateNow);
        long stableSince = System.currentTimeMillis();
        while (System.currentTimeMillis() < end) {
            this.worker.sleep(25L);
            int state = this.worker.call(SniperBot::stateNow);
            long now = System.currentTimeMillis();
            if (state != last) {
                last = state;
                stableSince = now;
                continue;
            }
            if (now - stableSince < 150L) continue;
            return;
        }
    }

    private static int stateNow() {
        LocalPlayer player = Gui.mc().player;
        return player == null ? -1 : player.containerMenu.getStateId();
    }

    private static boolean auctionOpenNow() {
        if (!Gui.containerOpenNow()) {
            return false;
        }
        String title = ServerText.normalize(Gui.mc().gui.screen().getTitle());
        if (title.contains("order")) {
            return false;
        }
        return title.contains("auction") || SniperBot.refreshSlot(Gui.containerNow()) >= 0;
    }

    private void ensureRecent() {
        for (int attempt = 0; attempt < 4; ++attempt) {
            int[] state = this.worker.call(() -> {
                List<ItemStack> stacks = Gui.containerNow();
                int slot = SniperBot.filterSlot(stacks);
                return new int[]{slot, slot < 0 ? Sort.UNKNOWN.ordinal() : SniperBot.selectedSort(stacks.get(slot)).ordinal()};
            });
            if (state[0] < 0) {
                this.say(ModuleChat.body().warn("No Filter button found").text(", sniping with the current sorting."));
                return;
            }
            Sort sort = Sort.values()[state[1]];
            if (sort == Sort.RECENT) {
                return;
            }
            if (sort == Sort.UNKNOWN && attempt > 0) {
                this.say(ModuleChat.body().warn("Could not read the Filter").text(", make sure it is on Recently Listed."));
                return;
            }
            String before = this.gui.fingerprint();
            this.gui.click(state[0], 0, ContainerInput.PICKUP);
            if (sort == Sort.UNKNOWN) {
                this.gui.waitChange(before, 3000L);
            } else {
                this.worker.waitFor(() -> {
                    Sort now = SniperBot.sortNow();
                    return now != Sort.UNKNOWN && now != sort;
                }, 3000L);
            }
            this.settle();
        }
    }

    private static Sort sortNow() {
        LocalPlayer player = Gui.mc().player;
        if (player == null || !SniperBot.auctionOpenNow() || !player.containerMenu.getCarried().isEmpty()) {
            return Sort.UNKNOWN;
        }
        List<ItemStack> stacks = Gui.containerNow();
        int slot = SniperBot.filterSlot(stacks);
        return slot < 0 ? Sort.UNKNOWN : SniperBot.selectedSort(stacks.get(slot));
    }

    private static int filterSlot(List<ItemStack> stacks) {
        for (int i = 45; i < stacks.size(); ++i) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !stack.is(Items.HOPPER) && !Gui.nameOf(stack).contains("filter")) continue;
            return i;
        }
        return stacks.size() > 47 && !stacks.get(47).isEmpty() ? 47 : -1;
    }

    private static int refreshSlot(List<ItemStack> stacks) {
        for (int i = 45; i < stacks.size(); ++i) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !stack.is(Items.ANVIL)) continue;
            return i;
        }
        return stacks.size() > 49 && stacks.get(49).is(Items.ANVIL) ? 49 : -1;
    }

    static Sort selectedSort(ItemStack filter) {
        ArrayList<Sort> options = new ArrayList<Sort>();
        ArrayList<Integer> colors = new ArrayList<Integer>();
        for (Component line : ((ItemLore)filter.getOrDefault(DataComponents.LORE, ItemLore.EMPTY)).lines()) {
            String text = ServerText.normalize(Dialogs.visibleText(line));
            Sort option = text.contains("recent") ? Sort.RECENT : (text.contains("lowest") ? Sort.LOWEST : (text.contains("highest") ? Sort.HIGHEST : Sort.UNKNOWN));
            if (option == Sort.UNKNOWN) continue;
            options.add(option);
            colors.add(SniperBot.color(line));
        }
        if (options.size() < 2) {
            return Sort.UNKNOWN;
        }
        for (int i = 0; i < options.size(); ++i) {
            boolean unique = true;
            boolean othersAgree = true;
            int other = -2;
            for (int j = 0; j < options.size(); ++j) {
                if (j == i) continue;
                if (((Integer)colors.get(j)).equals(colors.get(i))) {
                    unique = false;
                }
                if (other == -2) {
                    other = (Integer)colors.get(j);
                    continue;
                }
                if (other == (Integer)colors.get(j)) continue;
                othersAgree = false;
            }
            if (!unique || !othersAgree) continue;
            return (Sort)((Object)options.get(i));
        }
        int best = -1;
        Sort pick = Sort.UNKNOWN;
        for (int i = 0; i < options.size(); ++i) {
            int rgb = (Integer)colors.get(i);
            int brightness = (rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF);
            if (brightness > best) {
                best = brightness;
                pick = (Sort)((Object)options.get(i));
                continue;
            }
            if (brightness != best) continue;
            pick = Sort.UNKNOWN;
        }
        return pick;
    }

    private static int color(Component line) {
        int[] value = new int[]{-1};
        line.visit((style, text) -> {
            if (value[0] >= 0 || text.isBlank() || text.trim().equals("\u2022") || text.trim().equals("\u25aa")) {
                return Optional.empty();
            }
            TextColor color = style.getColor();
            value[0] = color == null ? 0xAA00AA : color.getValue();
            return Optional.empty();
        }, Style.EMPTY);
        return value[0];
    }

    private Hit pageTick() {
        LocalPlayer player = Gui.mc().player;
        if (player == null || !SniperBot.auctionOpenNow()) {
            return CLOSED;
        }
        long now = System.currentTimeMillis();
        long key = (long)player.containerMenu.containerId << 32 | (long)player.containerMenu.getStateId() & 0xFFFFFFFFL;
        if (key != this.pageKey) {
            this.pageKey = key;
            this.pageChangedAt = now;
            this.pageChangedTick = player.tickCount;
            if (this.refreshPending) {
                this.refreshPending = false;
                this.learnAnswer(now - this.refreshSentAt);
            }
            return null;
        }
        if (this.refreshPending) {
            if (now - this.refreshSentAt < this.answerTimeout(player)) {
                return null;
            }
            this.refreshPending = false;
        }
        if (now - this.pageChangedAt < this.settleMs || player.tickCount - this.pageChangedTick < 2) {
            return null;
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            return null;
        }
        String title = ServerText.normalize(Gui.mc().gui.screen().getTitle());
        if (title.contains("(page") && !title.contains("(page 1)")) {
            return null;
        }
        this.skip.values().removeIf(until -> until < now);
        List<ItemStack> stacks = Gui.containerNow();
        for (int i = 0; i < 45 && i < stacks.size(); ++i) {
            String fingerprint;
            long unit;
            double total;
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || !this.target.matches(stack) || (total = ServerText.askingPrice(stack)) <= 0.0 || (unit = Math.round(total / (double)Math.max(1, stack.getCount()))) > this.target.maxPrice || this.budget > 0L && this.spent + this.wrongSpent + Math.round(total) > this.budget || this.skip.containsKey(fingerprint = SniperBot.fingerprint(stack, total))) continue;
            this.confirmPressed = false;
            Gui.mc().gameMode.handleContainerInput(player.containerMenu.containerId, i, 0, ContainerInput.PICKUP, (Player)player);
            return new Hit(i, stack, Math.round(total), unit, this.target.timed() ? SnipeTarget.minutesLeft(stack) : -1, fingerprint, stack.getHoverName().getString());
        }
        if (now >= this.nextRefresh) {
            int slot = SniperBot.refreshSlot(stacks);
            if (slot >= 0) {
                Gui.mc().gameMode.handleContainerInput(player.containerMenu.containerId, slot, 0, ContainerInput.PICKUP, (Player)player);
                this.refreshPending = true;
                this.refreshSentAt = now;
            }
            this.nextRefresh = now + this.refreshMs;
        }
        return null;
    }

    private void learnAnswer(long sample) {
        if (sample <= 0L || sample > 3000L) {
            return;
        }
        this.answerMs = this.answerMs <= 0L ? sample : (this.answerMs * 3L + sample) / 4L;
    }

    private long answerTimeout(LocalPlayer player) {
        PlayerInfo info;
        long base = this.answerMs > 0L ? this.answerMs * 3L : 1000L;
        PlayerInfo ServerMetricsSamplersProvider = info = Gui.mc().getConnection() == null ? null : Gui.mc().getConnection().getPlayerInfo(player.getUUID());
        if (info != null) {
            base = Math.max(base, (long)info.getLatency() * 2L);
        }
        return Math.clamp(base + 300L, 700L, 3000L);
    }

    private static String fingerprint(ItemStack stack, double total) {
        StringBuilder text = new StringBuilder();
        text.append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append('|').append(stack.getCount()).append('|').append(ServerText.key(stack.getHoverName().getString())).append('|').append(Math.round(total)).append('|').append(stack.getDamageValue()).append('|').append(EnchantmentHelper.getEnchantmentsForCrafting((ItemStack)stack));
        for (String line : ServerText.lore(stack)) {
            String clean = ServerText.normalize(line);
            if (TIME.matcher(clean).find()) continue;
            text.append('|').append(clean);
        }
        return text.toString();
    }

    private boolean handleBuy(Hit hit, long mark) {
        Object[] pressedDialog = new Object[]{null};
        int[] pressedChest = new int[]{-1};
        long[] seen = new long[]{0L, Long.MIN_VALUE};
        boolean[] verified = new boolean[]{false};
        Problem[] problem = new Problem[]{null};
        long end = System.currentTimeMillis() + 4000L;
        while (System.currentTimeMillis() < end) {
            Purchase done = this.purchase;
            if (done != null && done.at() >= mark && done.at() > this.handledAt) {
                return this.bought(hit, done);
            }
            if (this.poorAt >= mark) {
                this.say(ModuleChat.body().danger("Not enough money").text(" for ").name(hit.name()).text(" at $").value((Object)ServerText.shortMoney(hit.total())).text(", stopping."));
                this.module.stopFromBot(this);
                throw new MacroWorker.Cancelled();
            }
            if (this.goneAt >= mark || this.ownAt >= mark) {
                this.skip.put(hit.fingerprint(), System.currentTimeMillis() + 60000L);
                return false;
            }
            Step step = this.worker.call(() -> this.confirmStep(hit, pressedDialog, pressedChest, seen, verified, problem));
            if (step == Step.WRONG) {
                if (this.strikes.merge(hit.fingerprint(), 1, Integer::sum) >= 2) {
                    this.skip.put(hit.fingerprint(), System.currentTimeMillis() + 600000L);
                }
                this.say(ModuleChat.body().warn("Did not buy").text(" ").name(hit.name()).text(": " + problem[0].reason() + "."));
                if (problem[0].fatal()) {
                    this.unreadable = 0;
                } else if (++this.unreadable >= 4) {
                    this.say(ModuleChat.body().danger("The purchase menu could not be checked").text(" " + this.unreadable + " times in a row, so AH Sniper stopped without buying. Send a screenshot of the menu to the Anubis team."));
                    this.module.stopFromBot(this);
                    throw new MacroWorker.Cancelled();
                }
                return !this.worker.waitFor(SniperBot::auctionOpenNow, 1000L);
            }
            if (step == Step.PRESSED) {
                end = Math.max(end, System.currentTimeMillis() + 4000L);
            }
            this.worker.sleep(2L);
        }
        this.skip.put(hit.fingerprint(), System.currentTimeMillis() + 60000L);
        return this.worker.call(SniperBot::auctionOpenNow) == false;
    }

    private Step confirmStep(Hit hit, Object[] pressedDialog, int[] pressedChest, long[] seen, boolean[] verified, Problem[] problem) {
        Problem wrong;
        LocalPlayer player = Gui.mc().player;
        if (player == null) {
            return Step.WAIT;
        }
        Dialogs.View view = Dialogs.current(Gui.mc());
        if (view != null) {
            if (view.dialog() == pressedDialog[0]) {
                return Step.WAIT;
            }
            if (!(view.titled("buy") || view.titled("sure") || view.titled("purchase") || view.titled("confirm"))) {
                return Step.WAIT;
            }
            Dialogs.Button yes = view.labelled("yes", "confirm", "buy");
            if (yes == null) {
                return Step.WAIT;
            }
            Problem wrong2 = this.dialogProblem(view, hit, verified[0]);
            if (wrong2 != null) {
                LOGGER.info("[Anubis] AH Sniper cancelled a purchase dialog ({}): {} body={} items={}", new Object[]{wrong2.reason(), view.describe(), view.body(), SniperBot.describe(view.items())});
                Dialogs.close(Gui.mc());
                problem[0] = wrong2;
                return Step.WRONG;
            }
            if (!Dialogs.press(Gui.mc(), view, yes, Map.of())) {
                return Step.WAIT;
            }
            pressedDialog[0] = view.dialog();
            verified[0] = true;
            this.confirmPressed = true;
            return Step.PRESSED;
        }
        if (!Gui.containerOpenNow() || SniperBot.auctionOpenNow()) {
            return Step.WAIT;
        }
        int sync = player.containerMenu.containerId;
        if (sync == pressedChest[0]) {
            return Step.WAIT;
        }
        List<ItemStack> stacks = Gui.containerNow();
        int confirm = SniperBot.confirmSlot(stacks, hit);
        if (confirm < 0) {
            return Step.WAIT;
        }
        long now = System.currentTimeMillis();
        if (seen[1] != (long)sync) {
            seen[0] = now;
            seen[1] = sync;
        }
        if ((wrong = this.chestProblem(stacks, confirm, hit, verified[0])) != null) {
            if (!wrong.fatal() && now - seen[0] < 400L) {
                return Step.WAIT;
            }
            LOGGER.info("[Anubis] AH Sniper cancelled a purchase menu ({}): '{}' {}", new Object[]{wrong.reason(), ServerText.normalize(Gui.mc().gui.screen().getTitle()), SniperBot.describe(stacks)});
            player.closeContainer();
            problem[0] = wrong;
            return Step.WRONG;
        }
        Gui.mc().gameMode.handleContainerInput(sync, confirm, 0, ContainerInput.PICKUP, (Player)player);
        pressedChest[0] = sync;
        verified[0] = true;
        this.confirmPressed = true;
        return Step.PRESSED;
    }

    private static int confirmSlot(List<ItemStack> stacks, Hit hit) {
        int fallback = -1;
        for (int i = 0; i < stacks.size(); ++i) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty() || stack.is(hit.stack().getItem()) || !BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains("lime")) continue;
            String name = Gui.nameOf(stack);
            if (name.contains("confirm") || name.contains("buy") || name.contains("purchase") || name.contains("yes")) {
                return i;
            }
            if (fallback >= 0) continue;
            fallback = i;
        }
        return fallback;
    }

    private Problem chestProblem(List<ItemStack> stacks, int confirm, Hit hit, boolean verified) {
        boolean shown = false;
        double price = -1.0;
        for (int i = 0; i < stacks.size(); ++i) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty()) continue;
            double asked = ServerText.askingPrice(SniperBot.textOf(stack));
            if (asked > price) {
                price = asked;
            }
            if (i == confirm) continue;
            if (this.sameListing(stack, hit)) {
                shown = true;
                continue;
            }
            if (SniperBot.decoration(stack) || !(asked > 0.0)) continue;
            return new Problem("the menu was for " + stack.getHoverName().getString(), true);
        }
        return this.verdict(shown, price, hit, verified);
    }

    private Problem dialogProblem(Dialogs.View view, Hit hit, boolean verified) {
        ArrayList<String> text = new ArrayList<String>();
        text.add(view.title());
        text.addAll(view.body());
        for (Dialogs.Button button : view.buttons()) {
            text.add(button.label());
        }
        double price = ServerText.askingPrice(text);
        boolean shown = false;
        for (ItemStack item : view.items()) {
            if (item.isEmpty()) continue;
            double asked = ServerText.askingPrice(SniperBot.textOf(item));
            if (asked > price) {
                price = asked;
            }
            if (this.sameListing(item, hit)) {
                shown = true;
                continue;
            }
            if (SniperBot.decoration(item)) continue;
            return new Problem("the menu was for " + item.getHoverName().getString(), true);
        }
        if (!shown && view.items().isEmpty()) {
            String want = ServerText.key(hit.name());
            shown = !want.isEmpty() && ServerText.key(String.join((CharSequence)" ", text)).contains(want);
        }
        return this.verdict(shown, price, hit, verified);
    }

    private Problem verdict(boolean shown, double price, Hit hit, boolean verified) {
        if (price > this.limit(hit)) {
            return new Problem("the menu asked $" + ServerText.shortMoney(price), true);
        }
        if (verified) {
            return null;
        }
        if (!shown) {
            return new Problem("the menu did not show this listing", false);
        }
        if (price <= 0.0) {
            return new Problem("the menu did not show the price", false);
        }
        return null;
    }

    private double limit(Hit hit) {
        double max = (double)this.target.maxPrice * (double)Math.max(1, hit.stack().getCount());
        return Math.min(max, (double)hit.total() * 1.02 + 1.0);
    }

    private boolean sameListing(ItemStack shown, Hit hit) {
        ItemStack want = hit.stack();
        if (!shown.is(want.getItem()) || shown.getCount() != want.getCount() || shown.getDamageValue() != want.getDamageValue()) {
            return false;
        }
        String wantName = ServerText.key(want.getHoverName().getString());
        if (!ServerText.key(shown.getHoverName().getString()).contains(wantName)) {
            return false;
        }
        if (!EnchantmentHelper.getEnchantmentsForCrafting((ItemStack)shown).equals((Object)EnchantmentHelper.getEnchantmentsForCrafting((ItemStack)want))) {
            return false;
        }
        if (!this.target.sameKind(shown)) {
            return false;
        }
        if (hit.minutes() < 0) {
            return true;
        }
        int minutes = SnipeTarget.minutesLeft(shown);
        return minutes >= 0 && Math.abs(minutes - hit.minutes()) <= 2;
    }

    private static boolean decoration(ItemStack stack) {
        boolean colored;
        String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (path.contains("glass_pane") || path.endsWith("stained_glass") || path.equals("glass") || path.equals("barrier") || path.equals("structure_void") || path.equals("light") || path.endsWith("_head") || path.endsWith("_skull")) {
            return true;
        }
        boolean bl = colored = path.startsWith("lime_") || path.startsWith("green_") || path.startsWith("red_");
        if (colored && (path.endsWith("_dye") || path.endsWith("_wool") || path.endsWith("_concrete") || path.endsWith("_terracotta") || path.endsWith("_carpet"))) {
            return true;
        }
        String name = Gui.nameOf(stack);
        return name.contains("confirm") || name.contains("cancel") || name.contains("go back") || name.contains("close");
    }

    private static List<String> textOf(ItemStack stack) {
        ArrayList<String> out = new ArrayList<String>();
        out.add(stack.getHoverName().getString());
        out.addAll(ServerText.lore(stack));
        return out;
    }

    private static String describe(List<ItemStack> stacks) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < stacks.size() && text.length() < 2000; ++i) {
            ItemStack stack = stacks.get(i);
            if (stack.isEmpty()) continue;
            text.append(" [").append(i).append(' ').append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()).append(" x").append(stack.getCount()).append(" '").append(stack.getHoverName().getString()).append('\'');
            for (String line : ServerText.lore(stack)) {
                text.append(" / ").append(line);
            }
            text.append(']');
        }
        return text.toString();
    }

    private boolean bought(Hit hit, Purchase done) {
        this.handledAt = done.at();
        int count = Math.max(1, done.count());
        long total = done.total() > 0L ? done.total() : hit.total();
        boolean rightItem = done.name().isEmpty() || SniperBot.namesMatch(done.name(), hit.stack());
        boolean rightPrice = total <= this.target.maxPrice * (long)count;
        this.skip.put(hit.fingerprint(), System.currentTimeMillis() + 15000L);
        if (!rightItem || !rightPrice) {
            ++this.wrong;
            this.wrongSpent += total;
            LOGGER.warn("[Anubis] AH Sniper bought {}x '{}' for {} while sniping '{}' under {}", new Object[]{count, done.name(), total, hit.name(), this.target.maxPrice});
            this.say(ModuleChat.body().danger("Bought the wrong listing").text(": " + count + "x ").name(done.name().isEmpty() ? hit.name() : done.name()).text(" for $").value((Object)ServerText.shortMoney(total)).text(". AH Sniper stopped to keep your money safe."));
            this.module.stopFromBot(this);
            throw new MacroWorker.Cancelled();
        }
        ++this.bought;
        this.spent += total;
        this.unreadable = 0;
        ++this.unsold;
        this.say(ModuleChat.body().good("Sniped").text(" ").name(hit.name()).text(" for $").value((Object)ServerText.shortMoney(total)).text(" (").value((Object)this.bought).text(this.bought == 1 ? " buy" : " buys").text(", $").value((Object)ServerText.shortMoney(this.spent)).text(" spent)."));
        if (!this.confirmPressed) {
            if (this.requireConfirm) {
                this.say(ModuleChat.body().danger("Your AH buy confirm menu is off").text(", so that click bought the listing right away. AH Sniper stopped because Only Buy With Confirm is on. Turn the buy confirm menu on in /settings."));
                this.module.stopFromBot(this);
                throw new MacroWorker.Cancelled();
            }
            if (!this.instantWarned) {
                this.instantWarned = true;
                this.say(ModuleChat.body().warn("Your AH buy confirm menu is off").text(", so a click buys right away. AH Sniper only clicks once the page has been still for " + this.settleMs + "ms. Turn the confirm menu on in /settings to have every purchase checked before you pay."));
            }
        }
        if (this.budget > 0L && this.spent + this.wrongSpent >= this.budget) {
            if (this.stock != null) {
                this.worker.sleep(300L);
                this.sellStock();
            }
            this.say(ModuleChat.body().text("Spent $").value((Object)ServerText.shortMoney(this.spent + this.wrongSpent)).text(" of your $").value((Object)ServerText.shortMoney(this.budget)).text(" limit, stopping."));
            this.module.stopFromBot(this);
            throw new MacroWorker.Cancelled();
        }
        if (this.maxBuys > 0 && this.bought >= this.maxBuys) {
            if (this.stock != null) {
                this.worker.sleep(300L);
                this.sellStock();
            }
            this.say(ModuleChat.body().text("Reached ").value((Object)this.maxBuys).text(this.maxBuys == 1 ? " buy" : " buys").text(", stopping."));
            this.module.stopFromBot(this);
            throw new MacroWorker.Cancelled();
        }
        if (this.stock == null) {
            return this.worker.call(SniperBot::auctionOpenNow) == false;
        }
        this.worker.sleep(300L);
        this.sellStock();
        return true;
    }

    private static boolean namesMatch(String said, ItemStack stack) {
        String text = ServerText.key(said);
        if (text.isEmpty()) {
            return true;
        }
        String shown = ServerText.key(stack.getHoverName().getString());
        String plain = ServerText.key(new ItemStack((ItemLike)stack.getItem()).getHoverName().getString());
        String id = ServerText.key(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
        return !shown.isEmpty() && (text.contains(shown) || shown.contains(text)) || !plain.isEmpty() && text.contains(plain) || !id.isEmpty() && text.contains(id);
    }

    private void sellStock() {
        int count = Math.min(this.unsold, this.ah.countInventory(this.stock.product));
        if (count <= 0) {
            return;
        }
        this.unsold -= this.ah.listSome(this.stock, Math.min(count, 9), new int[]{0});
    }

    void onChat(String raw) {
        String line = ServerText.normalize(raw);
        if (line.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Matcher wait = RATE_LIMIT.matcher(line);
        if (wait.find()) {
            try {
                this.holdUntil = now + (long)Math.ceil(Math.min(30.0, Double.parseDouble(wait.group(1))) * 1000.0) + 100L;
            }
            catch (NumberFormatException numberFormatException) {
                // empty catch block
            }
        }
        if (line.startsWith("you bought")) {
            this.purchase = SniperBot.parsePurchase(line, now);
        }
        if (line.contains("already bought") || line.contains("no longer available") || line.contains("not available")) {
            this.goneAt = now;
        }
        if (line.contains("enough money")) {
            this.poorAt = now;
        }
        if (line.contains("your own")) {
            this.ownAt = now;
        }
        if (this.ah != null) {
            this.ah.onChat(line, this.stocks);
        }
    }

    private static Purchase parsePurchase(String line, long now) {
        List<Double> money;
        int forAt;
        String rest = line.substring("you bought".length()).trim();
        int count = 1;
        Matcher amount = BOUGHT_COUNT.matcher(rest);
        if (amount.find()) {
            count = Math.max(1, Integer.parseInt(amount.group(1)));
            rest = rest.substring(amount.end());
        }
        String name = ((forAt = rest.lastIndexOf(" for ")) >= 0 ? rest.substring(0, forAt) : rest).trim();
        long total = -1L;
        if (forAt >= 0 && !(money = ServerText.moneyAll(rest.substring(forAt + 5))).isEmpty()) {
            total = Math.round(money.get(0));
        }
        return new Purchase(now, count, name, total);
    }

    String summary() {
        return "Sniped " + this.bought + (this.bought == 1 ? " item" : " items") + " for $" + ServerText.shortMoney(this.spent) + (String)(this.wrong > 0 ? ", plus " + this.wrong + " wrong " + (this.wrong == 1 ? "listing" : "listings") + " for $" + ServerText.shortMoney(this.wrongSpent) : "") + (String)(this.stock != null ? ". " + AuctionHouse.summary(this.stocks) : ".");
    }

    private static String formatMinutes(int minutes) {
        return minutes % 60 == 0 ? minutes / 60 + "h" : minutes / 60 + "h " + minutes % 60 + "m";
    }

    @Environment(value=EnvType.CLIENT)
    private record Purchase(long at, int count, String name, long total) {
    }

    @Environment(value=EnvType.CLIENT)
    private record Hit(int slot, ItemStack stack, long total, long unit, int minutes, String fingerprint, String name) {
    }

    @Environment(value=EnvType.CLIENT)
    static enum Sort {
        UNKNOWN,
        LOWEST,
        HIGHEST,
        RECENT;

    }

    @Environment(value=EnvType.CLIENT)
    private record Problem(String reason, boolean fatal) {
    }

    @Environment(value=EnvType.CLIENT)
    private static enum Step {
        WAIT,
        PRESSED,
        WRONG;

    }
}
