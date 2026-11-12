package dev.donut.addon.expansion.search;

import dev.donut.addon.expansion.render.BlockClusterMesh;
import dev.donut.addon.mv.WorldRenderer;
import dev.donut.addon.mv.compat.settings.BoolSetting;
import dev.donut.addon.mv.compat.settings.DoubleSetting;
import dev.donut.addon.mv.compat.settings.EnumSetting;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.settings.SettingGroup;
import dev.donut.addon.mv.compat.utils.render.color.Color;
import dev.donut.addon.mv.compat.utils.render.color.SettingColor;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import java.util.Collection;

/** Shared eight-option marker styling for block search reports. */
public final class DonutSearchMarkerStyle {
    public enum Shape {
        Outline("Outline"), Boxes("Boxes");
        private final String label;
        Shape(String label) { this.label = label; }
        @Override public String toString() { return this.label; }
    }

    private static final long REVEAL_NANOS = 380_000_000L;
    private final Setting<Shape> shape;
    private final Setting<Double> boxSize;
    private final Setting<Double> fillAlpha;
    private final Setting<Double> lineWidth;
    private final Setting<Boolean> glow;
    private final Setting<Boolean> distanceFade;
    private final Setting<Boolean> reveal;
    private final Setting<Double> range;

    public DonutSearchMarkerStyle(SettingGroup group, Shape defaultShape, double defaultFillAlpha, double defaultRange) {
        this.shape = group.add(new EnumSetting.Builder<Shape>().name("shape")
            .description("Choose outline markers or filled block boxes.").defaultValue(defaultShape).build());
        this.boxSize = group.add(new DoubleSetting.Builder().name("box-size")
            .description("Size of markers when the shape uses block boxes.").defaultValue(1.0).range(0.1, 2.0)
            .sliderRange(0.1, 2.0).visible(() -> this.shape.get() == Shape.Boxes).build());
        this.fillAlpha = group.add(new DoubleSetting.Builder().name("fill-alpha")
            .description("Opacity of the marker fill.").defaultValue(defaultFillAlpha).range(0.0, 1.0)
            .sliderRange(0.0, 1.0).build());
        this.lineWidth = group.add(new DoubleSetting.Builder().name("line-width")
            .description("Stroke width for marker outlines.").defaultValue(1.5).range(0.5, 5.0)
            .sliderRange(0.5, 5.0).build());
        this.glow = group.add(new BoolSetting.Builder().name("glow")
            .description("Draw a soft outer stroke around markers.").defaultValue(true).build());
        this.distanceFade = group.add(new BoolSetting.Builder().name("distance-fade")
            .description("Fade markers with distance and soften their stroke at long range.").defaultValue(true).build());
        this.reveal = group.add(new BoolSetting.Builder().name("reveal")
            .description("Animate markers when their position is first discovered.").defaultValue(true).build());
        this.range = group.add(new DoubleSetting.Builder().name("range")
            .description("Maximum distance for marker rendering.").defaultValue(defaultRange).range(16.0, 2048.0)
            .sliderRange(16.0, 512.0).build());
    }

    /** Draws a connected report component using the source Outline or individual Boxes geometry. */
    public static void drawCluster(WorldRenderer renderer, Collection<BlockPos> positions, BlockClusterMesh.Mesh mesh,
                                   boolean boxes, double boxSize, Color sides, Color lines,
                                   float lineWidth, boolean glow, float scale) {
        if (!boxes) {
            renderer.styledCluster(mesh, sides, lines, lineWidth, glow, (float)clamp(scale));
            return;
        }
        double size = boxSize * clamp(scale), half = size * 0.5;
        for (BlockPos pos : positions) {
            double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
            renderer.styledBox(new AABB(x - half, y - half, z - half, x + half, y + half, z + half),
                sides, lines, lineWidth, glow);
        }
    }

    public boolean inRange(double distance) { return distance <= this.range.get(); }
    public double range() { return this.range.get(); }

    public void draw(BlockPos pos, SettingColor selected, double distance, long bornAtNanos, long nowNanos) {
        if (!this.inRange(distance)) return;
        double revealTime = this.reveal.get()
            ? Math.min(1.0, Math.max(0.0, (double)(nowNanos - bornAtNanos) / REVEAL_NANOS)) : 1.0;
        double alpha = (this.distanceFade.get() ? distanceFade(distance) : 1.0) * easeOutCubic(revealTime);
        if (alpha < 0.02) return;
        double growthScale = revealTime < 1.0 ? 0.55 + 0.45 * easeOutBack(revealTime) : 1.0;
        double size = (this.shape.get() == Shape.Boxes ? this.boxSize.get() : 1.0) * growthScale;
        double centerX = pos.getX() + 0.5, centerY = pos.getY() + 0.5, centerZ = pos.getZ() + 0.5;
        AABB bounds = new AABB(centerX - size / 2.0, centerY - size / 2.0, centerZ - size / 2.0,
            centerX + size / 2.0, centerY + size / 2.0, centerZ + size / 2.0);
        int base = argb(selected);
        int stroke = withAlpha(base, alpha);
        int fill = withAlpha(base, this.fillAlpha.get() * alpha);
        double width = this.lineWidth.get();
        if (this.distanceFade.get()) width *= 1.0 - 0.3 * Math.min(1.0, distance / 256.0);
        if (this.glow.get()) {
            int halo = scaleAlpha(stroke, 0.18);
            Gizmos.cuboid(bounds, GizmoStyle.stroke(halo, (float)(width * 3.0))).setAlwaysOnTop();
        }
        GizmoStyle style = (fill >>> 24) > 0
            ? GizmoStyle.strokeAndFill(stroke, (float)width, fill)
            : GizmoStyle.stroke(stroke, (float)width);
        Gizmos.cuboid(bounds, style).setAlwaysOnTop();
    }

    private static int argb(SettingColor color) {
        return color.a << 24 | color.r << 16 | color.g << 8 | color.b;
    }

    private static int withAlpha(int argb, double alpha) {
        int scaled = (int)Math.round((argb >>> 24) * clamp(alpha));
        return scaled << 24 | argb & 0x00ffffff;
    }

    private static int scaleAlpha(int argb, double scale) {
        int scaled = (int)Math.round((argb >>> 24) * clamp(scale));
        return scaled << 24 | argb & 0x00ffffff;
    }

    private static double distanceFade(double distance) {
        if (distance <= 64.0) return 1.0;
        double t = Math.min(1.0, (distance - 64.0) / 192.0);
        return 1.0 - 0.55 * t * t;
    }

    private static double easeOutCubic(double value) {
        double inv = 1.0 - value;
        return 1.0 - inv * inv * inv;
    }

    private static double easeOutBack(double value) {
        double c1 = 1.70158;
        double c3 = c1 + 1.0;
        double x = value - 1.0;
        return 1.0 + c3 * x * x * x + c1 * x * x;
    }

    private static double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }
}
