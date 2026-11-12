package dev.donut.addon.expansion.search.spawner;

/** Source-shaped fade, reveal, and outline-width calculations for spawner ESP. */
public final class SpawnerVisualStyle {
    private static final long REVEAL_NANOS = 380_000_000L;

    private SpawnerVisualStyle() {}

    public record Frame(float opacity, float scale, float lineWidth, boolean visible) {}

    public static Frame frame(double distance, long ageNanos, float baseLineWidth,
                              boolean distanceFade, boolean reveal) {
        double safeDistance = Math.max(0.0, distance);
        float t = reveal ? clamp(ageNanos / (float) REVEAL_NANOS) : 1.0f;
        float opacity = distanceFade ? distanceOpacity(safeDistance) : 1.0f;
        opacity *= easeOutCubic(t);
        float scale = reveal && t < 1.0f ? 0.55f + 0.45f * easeOutBack(t) : 1.0f;
        float widthScale = distanceFade ? 1.0f - 0.3f * (float) Math.min(1.0, safeDistance / 256.0) : 1.0f;
        float width = Math.max(0.0f, baseLineWidth) * widthScale;
        return new Frame(opacity, scale, width, opacity >= 0.02f);
    }

    private static float distanceOpacity(double distance) {
        if (distance <= 64.0) return 1.0f;
        float t = (float) Math.min(1.0, (distance - 64.0) / 192.0);
        return 1.0f - 0.55f * t * t;
    }

    private static float easeOutCubic(float t) {
        float inverse = 1.0f - t;
        return 1.0f - inverse * inverse * inverse;
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float u = t - 1.0f;
        return 1.0f + (c1 + 1.0f) * u * u * u + c1 * u * u;
    }

    private static float clamp(float value) { return Math.max(0.0f, Math.min(1.0f, value)); }
}
