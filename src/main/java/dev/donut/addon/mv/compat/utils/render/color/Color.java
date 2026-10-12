package dev.donut.addon.mv.compat.utils.render.color;

import java.util.Objects;

/** Mutable RGBA color compatible with the source addon renderer API. */
public class Color {
    public int r;
    public int g;
    public int b;
    public int a;

    public Color(int r, int g, int b) { this(r, g, b, 255); }
    public Color(int r, int g, int b, int a) { set(r, g, b, a); }
    public Color(Color other) { this(other.r, other.g, other.b, other.a); }

    public Color set(int r, int g, int b, int a) {
        this.r = channel(r); this.g = channel(g); this.b = channel(b); this.a = channel(a); return this;
    }
    public Color set(Color other) { return set(other.r, other.g, other.b, other.a); }
    public Color r(int r) { this.r = channel(r); return this; }
    public Color g(int g) { this.g = channel(g); return this; }
    public Color b(int b) { this.b = channel(b); return this; }
    public Color a(int a) { this.a = channel(a); return this; }
    public int getPacked() { return fromRGBA(r, g, b, a); }
    public int toInteger() { return getPacked(); }
    public Color copy() { return new Color(this); }

    public static int fromRGBA(int r, int g, int b, int a) {
        return (channel(a) << 24) | (channel(r) << 16) | (channel(g) << 8) | channel(b);
    }
    public static Color fromPacked(int argb) {
        return new Color((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, (argb >>> 24) & 255);
    }
    private static int channel(int value) { return Math.max(0, Math.min(255, value)); }

    @Override public boolean equals(Object other) {
        return other instanceof Color c && r == c.r && g == c.g && b == c.b && a == c.a;
    }
    @Override public int hashCode() { return Objects.hash(r, g, b, a); }
    @Override public String toString() { return "Color{" + r + "," + g + "," + b + "," + a + '}'; }
}
