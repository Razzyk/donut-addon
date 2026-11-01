// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.Enchantment;

@Environment(value=EnvType.CLIENT)
record Product(Item item, String id, String search, String displayName, long minPrice, List<Enchant> enchants, boolean restock, int orderAmount, boolean beatTopOrder, long fixedPrice, long bidIncrement, long maxPrice, Predicate<ItemStack> filter) {
    Product(Item item, String id, String search, String displayName, long minPrice, List<Enchant> enchants, boolean restock, int orderAmount, boolean beatTopOrder, long fixedPrice, long bidIncrement, long maxPrice) {
        this(item, id, search, displayName, minPrice, enchants, restock, orderAmount, beatTopOrder, fixedPrice, bidIncrement, maxPrice, null);
    }

    static Item resolveItem(String raw) {
        Identifier id;
        Object text;
        Object object = text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (((String)text).isEmpty()) {
            return null;
        }
        if (!((String)text).contains(":")) {
            text = "minecraft:" + (String)text;
        }
        if ((id = Identifier.tryParse((String)text)) == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        Item item = (Item)BuiltInRegistries.ITEM.getValue(id);
        return item == Items.AIR ? null : item;
    }

    static String searchText(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath().replace('_', ' ');
    }

    static List<Enchant> parseEnchants(String raw) {
        ArrayList<Enchant> out = new ArrayList<Enchant>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[,;]")) {
            String key;
            String last;
            int parsed;
            String text = part.trim();
            if (text.isEmpty()) continue;
            int level = 1;
            int space = text.lastIndexOf(32);
            if (space > 0 && (parsed = Product.parseLevel(last = text.substring(space + 1).trim())) > 0) {
                level = parsed;
                text = text.substring(0, space).trim();
            }
            if ((key = ServerText.key(text)).isEmpty()) continue;
            out.add(new Enchant(key, text, level));
        }
        return out;
    }

    private static int parseLevel(String raw) {
        try {
            int value = Integer.parseInt(raw);
            return value >= 1 && value <= 10 ? value : 0;
        }
        catch (NumberFormatException e) {
            return ServerText.roman(raw);
        }
    }

    boolean matches(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(this.item)) {
            return false;
        }
        if (this.filter != null) {
            return this.filter.test(stack);
        }
        if (this.enchants.isEmpty()) {
            return true;
        }
        ItemEnchantments present = EnchantmentHelper.getEnchantmentsForCrafting((ItemStack)stack);
        for (Enchant wanted : this.enchants) {
            if (Product.levelOf(present, wanted.key()) >= wanted.level()) continue;
            return false;
        }
        return true;
    }

    static boolean enchantsMatch(ItemStack stack, List<Enchant> wanted, boolean exact) {
        ItemEnchantments present = EnchantmentHelper.getEnchantmentsForCrafting((ItemStack)stack);
        if (exact && present.size() != wanted.size()) {
            return false;
        }
        for (Enchant enchant : wanted) {
            int level = Product.levelOf(present, enchant.key());
            if (!(exact ? level != enchant.level() : level < enchant.level())) continue;
            return false;
        }
        return true;
    }

    private static int levelOf(ItemEnchantments present, String key) {
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : present.entrySet()) {
            String path = entry.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("");
            if (!ServerText.key(path).equals(key)) continue;
            return entry.getIntValue();
        }
        return 0;
    }

    boolean nameMatches(String label, boolean loose) {
        String key = ServerText.key(label);
        String byId = ServerText.key(BuiltInRegistries.ITEM.getKey(this.item).getPath());
        String byName = ServerText.key(this.displayName);
        if (key.equals(byId) || key.equals(byName)) {
            return true;
        }
        return loose && (key.endsWith(byId) || !byName.isEmpty() && key.endsWith(byName));
    }

    String describe() {
        StringBuilder text = new StringBuilder(this.displayName);
        if (!this.enchants.isEmpty()) {
            ArrayList<String> names = new ArrayList<String>();
            for (Enchant enchant : this.enchants) {
                names.add(enchant.label());
            }
            text.append(" (").append(String.join((CharSequence)", ", names)).append(')');
        }
        return text.toString();
    }

    @Environment(value=EnvType.CLIENT)
    record Enchant(String key, String name, int level) {
        String label() {
            return this.name + (String)(this.level > 1 ? " " + this.level : "");
        }
    }
}
