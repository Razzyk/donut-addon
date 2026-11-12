package dev.donut.addon.expansion.render.mixin;

/** The native client clock does not store its registry definition. */
public interface DonutClockIdentity {
    void donut$markOverworldClock(boolean overworldClock);
}
