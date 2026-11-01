// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.core.component.DataComponents;

@Environment(value=EnvType.CLIENT)
final class ServerText {
    private static final Pattern FORMATTING = Pattern.compile("\u00a7.");
    private static final Pattern MONEY_EXACT = Pattern.compile("^\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmb])?$");
    private static final Pattern MONEY_ANY = Pattern.compile("\\$?[ \\t]*([0-9][0-9,]*(?:\\.[0-9]+)?)[ \\t]*([kmb])?(?![a-z])");
    private static final Pattern DOLLARS = Pattern.compile("\\$[ \\t]*([0-9][0-9,]*(?:\\.[0-9]+)?)[ \\t]*([kmb])?(?![a-z])");
    private static final String[] NOT_PRICES = new String[]{"balance", "you have", "your money", "wallet", "bank"};
    private static final String SMALL_CAPS = "\u1d00\u0299\u1d04\u1d05\u1d07\ua730\u0262\u029c\u026a\u1d0a\u1d0b\u029f\u1d0d\u0274\u1d0f\u1d18\u0280\u0455\ua731\u1d1b\u1d1c\u1d20\u1d21\u028f\u1d22";
    private static final String SMALL_CAPS_PLAIN = "abcdefghijklmnoprsstuvwyz";
    private static final String[] ROMAN = new String[]{"i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"};

    private ServerText() {
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = FORMATTING.matcher(raw).replaceAll("");
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            int small = SMALL_CAPS.indexOf(c);
            if (small >= 0) {
                out.append(SMALL_CAPS_PLAIN.charAt(small));
                continue;
            }
            if (c == '\u279c' || c == '\u2192') {
                out.append('-');
                continue;
            }
            out.append(c);
        }
        return out.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    static String normalize(Component component) {
        return component == null ? "" : ServerText.normalize(component.getString());
    }

    static String key(String raw) {
        String text = ServerText.normalize(raw);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if ((c < 'a' || c > 'z') && (c < '0' || c > '9')) continue;
            out.append(c);
        }
        return out.toString();
    }

    static long parseMoney(String raw) {
        if (raw == null) {
            return -1L;
        }
        Matcher matcher = MONEY_EXACT.matcher(raw.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return -1L;
        }
        return Math.round(ServerText.scale(matcher.group(1), matcher.group(2)));
    }

    static List<Double> moneyAll(String raw) {
        ArrayList<Double> out = new ArrayList<Double>();
        if (raw == null) {
            return out;
        }
        Matcher matcher = MONEY_ANY.matcher(ServerText.normalize(raw));
        while (matcher.find()) {
            double value = ServerText.scale(matcher.group(1), matcher.group(2));
            if (!(value > 0.0)) continue;
            out.add(value);
        }
        return out;
    }

    static List<Double> dollars(String raw) {
        ArrayList<Double> out = new ArrayList<Double>();
        if (raw == null) {
            return out;
        }
        Matcher matcher = DOLLARS.matcher(ServerText.normalize(raw));
        while (matcher.find()) {
            double value = ServerText.scale(matcher.group(1), matcher.group(2));
            if (!(value > 0.0)) continue;
            out.add(value);
        }
        return out;
    }

    static double askingPrice(List<String> lines) {
        double worst = -1.0;
        for (String line : lines) {
            String clean = ServerText.normalize(line);
            boolean skip = false;
            for (String word : NOT_PRICES) {
                if (!clean.contains(word)) continue;
                skip = true;
                break;
            }
            if (skip) continue;
            List<Double> values = clean.contains("$") ? ServerText.dollars(clean) : (clean.contains("price") || clean.contains("cost") ? ServerText.moneyAll(clean) : List.of());
            for (double value : values) {
                if (!(value > worst)) continue;
                worst = value;
            }
        }
        return worst;
    }

    static double askingPrice(ItemStack stack) {
        return ServerText.askingPrice(ServerText.lore(stack));
    }

    static String moneyToken(String raw) {
        Matcher matcher = MONEY_ANY.matcher(ServerText.normalize(raw));
        if (!matcher.find()) {
            return null;
        }
        String suffix = matcher.group(2);
        return matcher.group(1).replace(",", "") + (suffix == null ? "" : suffix);
    }

    static double displayStep(String raw) {
        Matcher matcher = MONEY_ANY.matcher(ServerText.normalize(raw));
        if (!matcher.find()) {
            return 1.0;
        }
        String number = matcher.group(1);
        int dot = number.indexOf(46);
        int decimals = dot < 0 ? 0 : number.length() - dot - 1;
        return Math.max(1.0, ServerText.scale("1", matcher.group(2)) / Math.pow(10.0, decimals));
    }

    private static double scale(String number, String suffix) {
        double value;
        try {
            value = Double.parseDouble(number.replace(",", ""));
        }
        catch (NumberFormatException e) {
            return -1.0;
        }
        if (suffix == null) {
            return value;
        }
        return switch (suffix) {
            case "k" -> value * 1000.0;
            case "m" -> value * 1000000.0;
            case "b" -> value * 1.0E9;
            default -> value;
        };
    }

    static String exactMoney(long value) {
        if (value >= 1000000000L && value % 1000000000L == 0L) {
            return value / 1000000000L + "b";
        }
        if (value >= 1000000L && value % 1000000L == 0L) {
            return value / 1000000L + "m";
        }
        if (value >= 1000000L && value % 100000L == 0L) {
            return value / 1000000L + "." + value % 1000000L / 100000L + "m";
        }
        if (value >= 1000L && value % 1000L == 0L) {
            return value / 1000L + "k";
        }
        return Long.toString(value);
    }

    static String shortMoney(double value) {
        if (value >= 1.0E9) {
            return ServerText.trim(value / 1.0E9) + "b";
        }
        if (value >= 1000000.0) {
            return ServerText.trim(value / 1000000.0) + "m";
        }
        if (value >= 1000.0) {
            return ServerText.trim(value / 1000.0) + "k";
        }
        return Long.toString(Math.round(value));
    }

    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        return text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
    }

    static int roman(String raw) {
        String text = ServerText.normalize(raw);
        for (int i = 0; i < ROMAN.length; ++i) {
            if (!ROMAN[i].equals(text)) continue;
            return i + 1;
        }
        return 0;
    }

    static String withoutRoman(String raw) {
        String text = ServerText.normalize(raw);
        int space = text.lastIndexOf(32);
        if (space > 0 && ServerText.roman(text.substring(space + 1)) > 0) {
            return text.substring(0, space);
        }
        return text;
    }

    static List<String> lore(ItemStack stack) {
        ArrayList<String> out = new ArrayList<String>();
        for (Component line : ((ItemLore)stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY)).lines()) {
            out.add(line.getString());
        }
        return out;
    }

    static double listingPrice(ItemStack stack) {
        List<String> lore = ServerText.lore(stack);
        for (String line : lore) {
            String clean = ServerText.withoutBullet(ServerText.normalize(line));
            if (!clean.startsWith("price")) continue;
            List<Double> values = clean.contains("$") ? ServerText.dollars(clean) : ServerText.moneyAll(clean.substring("price".length()));
            return values.isEmpty() ? -1.0 : values.get(0);
        }
        double found = -1.0;
        for (String line : lore) {
            String clean = ServerText.normalize(line);
            if (!clean.contains("$") || clean.contains("balance")) continue;
            for (double value : ServerText.dollars(clean)) {
                if (found > 0.0 && Math.abs(found - value) >= 1.0) {
                    return -1.0;
                }
                found = value;
            }
        }
        return found;
    }

    private static String withoutBullet(String text) {
        int start;
        for (start = 0; start < text.length() && !Character.isLetterOrDigit(text.charAt(start)); ++start) {
        }
        return text.substring(start);
    }

    static double orderPrice(ItemStack stack) {
        List<String> lore = ServerText.lore(stack);
        double best = -1.0;
        for (String line : lore) {
            String clean = ServerText.normalize(line);
            if (!line.contains("$") || !clean.contains("price") && !clean.contains("each") && !clean.contains("per item") && !clean.contains("pay")) continue;
            for (double value : ServerText.moneyAll(line)) {
                if (!(value > 0.0) || !(best < 0.0) && !(value < best)) continue;
                best = value;
            }
        }
        if (best > 0.0) {
            return best;
        }
        for (String line : lore) {
            List<Double> values;
            if (!line.contains("$") || (values = ServerText.moneyAll(line)).isEmpty()) continue;
            return values.get(0);
        }
        return -1.0;
    }
}
