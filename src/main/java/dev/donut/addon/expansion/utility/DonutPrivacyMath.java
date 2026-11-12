package dev.donut.addon.expansion.utility;

import java.util.SplittableRandom;

/** Pure presentation rules shared by the status panel and tests. */
public final class DonutPrivacyMath {
    private DonutPrivacyMath() { }

    public static String pseudoName(long seed) {
        return "Player-" + String.format(java.util.Locale.ROOT, "%03d", new SplittableRandom(seed).nextInt(1000));
    }

    public static String pseudoValue(String label, long seed) {
        SplittableRandom random = new SplittableRandom(seed ^ (label == null ? 0 : label.hashCode() * 0x9E3779B97F4A7C15L));
        int digits = 3 + random.nextInt(4);
        StringBuilder value = new StringBuilder(digits);
        value.append((char) ('1' + random.nextInt(9)));
        while (value.length() < digits) value.append((char) ('0' + random.nextInt(10)));
        return value.toString();
    }

    /** Replace digit runs while preserving separators and the visual width of ordinary values. */
    public static String obscureNumbers(String text, long seed) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder result = new StringBuilder(text.length());
        int numberIndex = 0;
        for (int cursor = 0; cursor < text.length();) {
            char current = text.charAt(cursor);
            if (!Character.isDigit(current)) {
                result.append(current);
                cursor++;
                continue;
            }
            int end = cursor;
            while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
            long salt = seed ^ ((long) numberIndex++ * 0xD6E8FEB86659FD93L);
            SplittableRandom random = new SplittableRandom(salt);
            for (int i = cursor; i < end; i++) {
                int digit = random.nextInt(10);
                if (i == cursor && end - cursor > 1 && digit == 0) digit = 1 + random.nextInt(9);
                result.append((char) ('0' + digit));
            }
            cursor = end;
        }
        return result.toString();
    }
}
