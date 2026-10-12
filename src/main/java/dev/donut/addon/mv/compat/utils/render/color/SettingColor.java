package dev.donut.addon.mv.compat.utils.render.color;

/** Mutable RGBA setting color; rainbow is retained for source compatibility. */
public class SettingColor extends Color {
    public boolean rainbow;

    public SettingColor(int r, int g, int b) { this(r, g, b, 255, false); }
    public SettingColor(int r, int g, int b, int a) { this(r, g, b, a, false); }
    public SettingColor(int r, int g, int b, int a, boolean rainbow) {
        super(r, g, b, a);
        this.rainbow = rainbow;
    }
    public SettingColor(Color color) {
        super(color);
        this.rainbow = color instanceof SettingColor sc && sc.rainbow;
    }
    @Override public SettingColor set(int r, int g, int b, int a) {
        super.set(r, g, b, a); return this;
    }
    @Override public SettingColor set(Color other) {
        super.set(other);
        if (other instanceof SettingColor sc) this.rainbow = sc.rainbow;
        return this;
    }
    @Override public SettingColor copy() { return new SettingColor(this); }
    @Override public boolean equals(Object other) {
        return other instanceof SettingColor color && super.equals(other) && rainbow == color.rainbow;
    }
    @Override public int hashCode() { return 31 * super.hashCode() + Boolean.hashCode(rainbow); }
}
