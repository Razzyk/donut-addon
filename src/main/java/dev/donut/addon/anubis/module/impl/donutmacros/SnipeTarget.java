// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import dev.donut.addon.anubis.module.impl.donutmacros.Product;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import dev.donut.addon.anubis.module.setting.StringSetting;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.core.registries.BuiltInRegistries;

@Environment(value=EnvType.CLIENT)
final class SnipeTarget {
    private static final Pattern DURATION = Pattern.compile("(?<!\\d)(\\d{1,5})\\s*([dhms])");
    final Item item;
    final boolean shard;
    final Preset preset;
    final String displayName;
    final String search;
    final List<Product.Enchant> enchants;
    final boolean exactEnchants;
    final long maxPrice;
    final int minMinutes;

    private SnipeTarget(Item item, boolean shard, Preset preset, String displayName, String search, List<Product.Enchant> enchants, boolean exactEnchants, long maxPrice, int minMinutes) {
        this.item = item;
        this.shard = shard;
        this.preset = preset;
        this.displayName = displayName;
        this.search = search;
        this.enchants = enchants;
        this.exactEnchants = exactEnchants;
        this.maxPrice = maxPrice;
        this.minMinutes = minMinutes;
    }

    static SnipeTarget parse(String rawItem, String rawEnchants, boolean exact, long maxPrice, int minMinutes) {
        boolean shard;
        Item item;
        String text;
        List<Product.Enchant> enchants = Product.parseEnchants(rawEnchants);
        Preset preset = Preset.of(rawItem);
        if (preset != null) {
            return new SnipeTarget(preset.item, true, preset, preset.label, preset.search, enchants, exact, maxPrice, minMinutes);
        }
        String string = text = rawItem == null ? "" : rawItem.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (text.startsWith("minecraft:")) {
            text = text.substring("minecraft:".length());
        }
        if ((item = Product.resolveItem((String)((shard = text.startsWith("shard_")) ? "netherite_" + text.substring("shard_".length()) : text))) == null) {
            return null;
        }
        Object name = shard ? "Shard " + SnipeTarget.capitalize(text.substring("shard_".length())) : new ItemStack((ItemLike)item).getHoverName().getString();
        return new SnipeTarget(item, shard, null, (String)name, Product.searchText(item), enchants, exact, maxPrice, shard ? minMinutes : 0);
    }

    private static String capitalize(String path) {
        StringBuilder out = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    boolean sameKind(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(this.item)) {
            return false;
        }
        String name = ServerText.key(stack.getHoverName().getString());
        if (this.preset != null) {
            if (!name.contains(this.preset.keyword)) {
                return false;
            }
            if (!this.preset.exclude.isEmpty() && name.contains(this.preset.exclude)) {
                return false;
            }
        } else if (name.contains("shard") != this.shard) {
            return false;
        }
        return this.enchants.isEmpty() || Product.enchantsMatch(stack, this.enchants, this.exactEnchants);
    }

    boolean timed() {
        return this.preset != null || this.shard;
    }

    boolean matches(ItemStack stack) {
        if (!this.sameKind(stack)) {
            return false;
        }
        if (this.preset != null) {
            return SnipeTarget.minutesLeft(stack) >= Math.max(0, this.minMinutes);
        }
        return this.minMinutes <= 0 || SnipeTarget.minutesLeft(stack) >= this.minMinutes;
    }

    static int minutesLeft(ItemStack stack) {
        ArrayList<String> lines = new ArrayList<String>();
        for (String line : ServerText.lore(stack)) {
            lines.add(ServerText.normalize(line));
        }
        ArrayList<Integer> listing = new ArrayList<Integer>();
        for (int i = 0; i < lines.size(); ++i) {
            if (!((String)lines.get(i)).contains("$") && !((String)lines.get(i)).contains("seller")) continue;
            listing.add(i);
        }
        int best = -1;
        int bestGap = -1;
        int timers = 0;
        for (int i = 0; i < lines.size(); ++i) {
            int minutes;
            String line = (String)lines.get(i);
            boolean destruct = line.contains("destruct");
            if (!destruct && !line.contains("expire") || (minutes = SnipeTarget.duration(line)) < 0) continue;
            if (destruct) {
                return minutes;
            }
            ++timers;
            int gap = Integer.MAX_VALUE;
            Iterator iterator = listing.iterator();
            while (iterator.hasNext()) {
                int index = (Integer)iterator.next();
                gap = Math.min(gap, Math.abs(index - i));
            }
            if (gap <= bestGap) continue;
            bestGap = gap;
            best = minutes;
        }
        if (best < 0) {
            return -1;
        }
        return listing.isEmpty() || timers >= 2 || bestGap >= 2 ? best : -1;
    }

    private static int duration(String line) {
        Matcher matcher = DURATION.matcher(line);
        int minutes = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            int value = Integer.parseInt(matcher.group(1));
            switch (matcher.group(2)) {
                case "d": {
                    minutes += value * 1440;
                    break;
                }
                case "h": {
                    minutes += value * 60;
                    break;
                }
                case "m": {
                    minutes += value;
                    break;
                }
            }
        }
        return found ? minutes : -1;
    }

    Product product(long sellPrice) {
        return new Product(this.item, BuiltInRegistries.ITEM.getKey(this.item).getPath(), this.search, this.displayName, sellPrice, this.enchants, false, 1, false, 1L, 1L, 0L, this::sameKind);
    }

    String describe() {
        StringBuilder text = new StringBuilder(this.displayName);
        if (!this.enchants.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (Product.Enchant enchant : this.enchants) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(enchant.label());
            }
            text.append(" (").append(this.exactEnchants ? "exactly " : "at least ").append((CharSequence)names).append(')');
        }
        return text.toString();
    }

    @Environment(value=EnvType.CLIENT)
    static enum Preset {
        SELL_AXE("sell_axe", "Sell Axe", Items.NETHERITE_AXE, "sell axe", "sell", ""),
        DRILL("drill", "Drill", Items.NETHERITE_PICKAXE, "drill", "drill", ""),
        SHARD_AXE("shard_axe", "Shard Axe", Items.NETHERITE_AXE, "shard axe", "shard", "sell");

        final String value;
        final String label;
        final Item item;
        final String search;
        final String keyword;
        final String exclude;

        private Preset(String value, String label, Item item, String search, String keyword, String exclude) {
            this.value = value;
            this.label = label;
            this.item = item;
            this.search = search;
            this.keyword = keyword;
            this.exclude = exclude;
        }

        static Preset of(String raw) {
            String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            for (Preset preset : Preset.values()) {
                if (!preset.value.equals(text)) continue;
                return preset;
            }
            return null;
        }

        static List<StringSetting.Preset> choices() {
            ArrayList<StringSetting.Preset> out = new ArrayList<StringSetting.Preset>();
            for (Preset preset : Preset.values()) {
                out.add(new StringSetting.Preset(preset.value, preset.label, BuiltInRegistries.ITEM.getKey(preset.item).getPath(), true));
            }
            return out;
        }
    }
}
