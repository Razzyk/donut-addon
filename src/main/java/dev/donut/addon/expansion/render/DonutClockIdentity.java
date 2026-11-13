package dev.donut.addon.expansion.render;

/** Runtime clock bridge; must remain outside packages reserved by Mixin configurations. */
public interface DonutClockIdentity {
    void donut$markOverworldClock(boolean overworldClock);
}
