package dev.donut.addon.mv.compat.events.render;

import dev.donut.addon.mv.WorldRenderer;
public final class Render3DEvent {
    public final WorldRenderer renderer;
    public final double tickDelta;
    public Render3DEvent(WorldRenderer renderer, double tickDelta) { this.renderer = renderer; this.tickDelta = tickDelta; }
}
