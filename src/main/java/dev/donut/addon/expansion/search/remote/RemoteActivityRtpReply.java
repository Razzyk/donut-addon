package dev.donut.addon.expansion.search.remote;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Server reply classifier for RTP cooldown, warmup, cancellation, and terminal errors. */
final class RemoteActivityRtpReply {
    private static final Pattern FORMATTING = Pattern.compile("§.");
    private static final Pattern DURATION = Pattern.compile(
        "(\\d+(?:[.,]\\d+)?)\\s*(hours?|hrs?|saat|h|minutes?|mins?|dakika|dk|m|seconds?|secs?|saniye|sn|s)(?![a-z])"
    );
    private static final Pattern CLOCK = Pattern.compile("(?<![\\d:])(\\d{1,2}):(\\d{2})(?![\\d:])");
    private static final Pattern BRACKET_TIME = Pattern.compile("[\\[(<]\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:[ap]m)?\\s*[\\])>]");
    private static final Pattern LEADING_TIME = Pattern.compile("^\\d{1,2}:\\d{2}(?::\\d{2})?(?:\\s?[ap]m)?(?![\\d:])(?!\\s*(?:left|remaining|kaldi|kalan))");
    private static final Pattern SENTENCE = Pattern.compile("\\.(?!\\d)|[!?|;]");
    private static final String[] SMALL_CAPS = {"ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀѕꜱᴛᴜᴠᴡʏᴢ", "abcdefghijklmnopqrsstuvwyz"};
    private static final String[] TURKISH = {"ıİşŞğĞüÜöÖçÇ", "iissgguuoocc"};
    private static final String[] UNKNOWN = {"unknown command", "unknown or incomplete command", "bilinmeyen komut", "komut bulunamadi", "boyle bir komut yok"};
    private static final String[] NO_PERMISSION = {"no permission", "don't have permission", "dont have permission", "do not have permission", "not have permission", "insufficient permission", "not allowed to use", "yetkin yok", "yetkiniz yok", "iznin yok", "izniniz yok", "yetkiniz bulunmuyor", "izniniz bulunmuyor"};
    private static final String[] COMBAT = {"combat", "savasta", "savas modunda", "pvp modunda", "pvp'de", "pvpde", "kombat"};
    private static final String[] COMBAT_STRONG = {"in combat", "combat tag", "combat-tag", "combattag", "savasta", "savas modunda", "pvp modunda", "pvp'de", "pvpde"};
    private static final String[] COMBAT_OVER = {"no longer in combat", "out of combat", "left combat", "not in combat", "savastan ciktin", "artik savasta degil"};
    private static final String[] COOLDOWN_STRONG = {"cooldown", "cool down", "again", "another", "next teleport", "next rtp", "next use", "tekrar", "yeniden", "bekleme suresi"};
    private static final String[] COOLDOWN_WEAK = {"wait", "bekle", "before", "until", "remaining", "kalan", "sonra"};
    private static final String[] WARMUP = {"teleporting in", "teleporting you in", "teleport in", "teleported in", "teleportation in", "teleportation will", "teleport will", "will be teleported", "will teleport", "before teleporting", "before you teleport", "before being teleported", "don't move", "dont move", "do not move", "stand still", "isinlaniyor", "isinlanacak", "isinlanmaya", "hareket etme"};
    private static final String[] PROGRESS = {"teleporting", "searching", "looking for a", "finding a", "araniyor", "konum bul"};
    private static final String[] CANCEL = {"cancel", "iptal", "you moved", "hareket ettin", "aborted"};

    private RemoteActivityRtpReply() {}

    static Reply parse(String raw) {
        String text = normalize(raw);
        if (text.isEmpty()) return Reply.NONE;
        if (containsAny(text, UNKNOWN)) return new Reply(Kind.UNKNOWN_COMMAND, 0L, -1L);
        if (containsAny(text, NO_PERMISSION)) return new Reply(Kind.NO_PERMISSION, 0L, -1L);

        text = LEADING_TIME.matcher(BRACKET_TIME.matcher(text).replaceAll(" ").trim()).replaceFirst("").trim();
        long any = -1L;
        long strong = -1L;
        long warm = -1L;
        long weak = -1L;
        for (String sentence : SENTENCE.split(text)) {
            long millis = durationMs(sentence);
            if (millis < 0L) continue;
            if (any < 0L) any = millis;
            if (containsAny(sentence, COOLDOWN_STRONG)) {
                if (strong < 0L) strong = millis;
            } else if (containsAny(sentence, WARMUP)) {
                if (warm < 0L) warm = millis;
            } else if (containsAny(sentence, COOLDOWN_WEAK) && weak < 0L) {
                weak = millis;
            }
        }

        boolean warmupWords = containsAny(text, WARMUP);
        if (!containsAny(text, COMBAT) || containsAny(text, COMBAT_OVER) || warmupWords && (warm >= 0L || !containsAny(text, COMBAT_STRONG))) {
            boolean cancelled = containsAny(text, CANCEL);
            if (any < 0L) {
                if (cancelled) return new Reply(Kind.CANCELLED, 0L, -1L);
                return !warmupWords && !containsAny(text, PROGRESS) ? Reply.NONE : new Reply(Kind.WARMUP, 0L, -1L);
            }
            if (warm >= 0L && strong >= 0L) return new Reply(Kind.WARMUP, warm, strong);
            if (containsAny(text, COOLDOWN_STRONG)) return new Reply(Kind.COOLDOWN, strong >= 0L ? strong : any, -1L);
            if (warmupWords) return new Reply(Kind.WARMUP, warm >= 0L ? warm : any, -1L);
            if (cancelled) return new Reply(Kind.CANCELLED, 0L, -1L);
            return containsAny(text, COOLDOWN_WEAK) ? new Reply(Kind.COOLDOWN, weak >= 0L ? weak : any, -1L) : Reply.NONE;
        }
        return new Reply(Kind.COMBAT, 0L, -1L);
    }

    static long durationMs(String normalized) {
        Matcher matcher = DURATION.matcher(normalized);
        double total = 0.0;
        boolean found = false;
        while (matcher.find()) {
            try {
                total += Double.parseDouble(matcher.group(1).replace(',', '.')) * unitMs(matcher.group(2));
                found = true;
            } catch (NumberFormatException ignored) {
                // Ignore one malformed numeric component and continue with the rest.
            }
        }
        if (found) return Math.round(total);
        Matcher clock = CLOCK.matcher(normalized);
        return clock.find() ? (Integer.parseInt(clock.group(1)) * 60L + Integer.parseInt(clock.group(2))) * 1000L : -1L;
    }

    static boolean looksLikePlayerChat(String raw, Iterable<String> names) {
        String text = normalize(raw);
        if (text.isEmpty()) return false;
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            String needle = name.toLowerCase(Locale.ROOT);
            for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
                int end = at + needle.length();
                if ((at <= 0 || !nameChar(text.charAt(at - 1))) && (end >= text.length() || !nameChar(text.charAt(end))) && separatorAfter(text, end)) return true;
            }
        }
        return false;
    }

    static String normalize(String raw) {
        if (raw == null) return "";
        String text = FORMATTING.matcher(raw).replaceAll("");
        StringBuilder normalized = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int small = SMALL_CAPS[0].indexOf(c);
            int turkish = TURKISH[0].indexOf(c);
            if (small >= 0) normalized.append(SMALL_CAPS[1].charAt(small));
            else if (turkish >= 0) normalized.append(TURKISH[1].charAt(turkish));
            else if (c == 8217 || c == 8216) normalized.append('\'');
            else if (c != 775) normalized.append(c);
        }
        return normalized.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static long unitMs(String unit) {
        return switch (unit) {
            case "h", "hr", "hrs", "hour", "hours", "saat" -> 3_600_000L;
            case "m", "min", "mins", "minute", "minutes", "dakika", "dk" -> 60_000L;
            default -> 1_000L;
        };
    }

    private static boolean nameChar(char c) { return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_'; }

    private static boolean separatorAfter(String text, int from) {
        int index = from;
        while (index < text.length() && index - from < 3 && " ])}".indexOf(text.charAt(index)) >= 0) index++;
        if (index >= text.length()) return false;
        char c = text.charAt(index);
        return ":>»›≫➤➜→⇒|".indexOf(c) >= 0 || (c == '-' || c == '=') && index + 1 < text.length() && text.charAt(index + 1) == '>';
    }

    private static boolean containsAny(String text, String[] words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    record Reply(Kind kind, long millis, long nextUseMs) {
        private static final Reply NONE = new Reply(Kind.NONE, 0L, -1L);
    }

    enum Kind { NONE, COOLDOWN, WARMUP, CANCELLED, UNKNOWN_COMMAND, NO_PERMISSION, COMBAT }
}
