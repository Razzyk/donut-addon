package dev.donut.addon.expansion.search.evidence;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure parsing and filtering for Block Signals display settings. */
public final class BlockSignalDisplayRules {
    public enum DetectionBand {
        ALL, BELOW_ZERO(0), BELOW_MINUS_20(-20), BELOW_MINUS_30(-30);

        private final Integer exclusiveCeiling;
        DetectionBand() { this.exclusiveCeiling = null; }
        DetectionBand(int exclusiveCeiling) { this.exclusiveCeiling = exclusiveCeiling; }
        public boolean includesY(int y) { return this.exclusiveCeiling == null || y < this.exclusiveCeiling; }
    }

    public enum BoxStyle { BOXES, OUTLINE }

    private BlockSignalDisplayRules() {}

    /** Comma, semicolon, or whitespace separated block / block-entity IDs. */
    public static Set<String> parseIds(String text) {
        if (text == null || text.isBlank()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : text.split("[,;\\s]+")) {
            String id = normalizeId(token);
            if (id != null) result.add(id);
        }
        return Set.copyOf(result);
    }

    /** Parses `id=#RRGGBB` or `id=#AARRGGBB` pairs separated by comma, semicolon, or newline. */
    public static Map<String, Integer> parseColors(String text) {
        if (text == null || text.isBlank()) return Map.of();
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (String entry : text.split("[,;\\r\\n]+")) {
            int separator = entry.indexOf('=');
            if (separator < 1) continue;
            String id = normalizeId(entry.substring(0, separator));
            Integer color = parseColor(entry.substring(separator + 1));
            if (id != null && color != null) result.put(id, color);
        }
        return Map.copyOf(result);
    }

    public static boolean allowsBlock(boolean filter, Set<String> includedIds,
                                      String reportedTypeId, String loadedBlockId) {
        if (!filter) return true;
        if (isChest(reportedTypeId) || isChest(loadedBlockId)) return true;
        Set<String> allowed = includedIds == null ? Set.of() : includedIds;
        String reported = normalizeId(reportedTypeId);
        String loaded = normalizeId(loadedBlockId);
        return reported != null && allowed.contains(reported) || loaded != null && allowed.contains(loaded);
    }

    public static int colorFor(String typeId, Map<String, Integer> colors, int fallbackArgb) {
        String normalized = normalizeId(typeId);
        if (normalized == null || colors == null) return fallbackArgb;
        return colors.getOrDefault(normalized, fallbackArgb);
    }

    /** Applies a source-style fill percentage and evidence-specific opacity while keeping values bounded. */
    public static int scaledFillOpacityPercent(int fillPercent, int evidencePercent) {
        int fill = Math.max(0, Math.min(100, fillPercent));
        int evidence = Math.max(0, Math.min(100, evidencePercent));
        return (fill * evidence + 50) / 100;
    }

    public static String normalizeId(String id) {
        if (id == null) return null;
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        if (normalized.indexOf(':') < 0) normalized = "minecraft:" + normalized;
        if (!normalized.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return null;
        return normalized;
    }

    private static boolean isChest(String id) {
        String normalized = normalizeId(id);
        if (normalized == null) return false;
        String path = normalized.substring(normalized.indexOf(':') + 1);
        return path.equals("chest") || path.equals("trapped_chest");
    }

    private static Integer parseColor(String value) {
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 6 && hex.length() != 8) return null;
        try {
            long parsed = Long.parseLong(hex, 16);
            return hex.length() == 6 ? (int)(0xff000000L | parsed) : (int)parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
