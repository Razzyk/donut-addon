package dev.donut.addon.mv.compat.events.entity.player;

import net.minecraft.world.phys.Vec3;
public final class PlayerMoveEvent {
    public Vec3 movement;
    public PlayerMoveEvent(Vec3 movement) { this.movement = movement; }
}
