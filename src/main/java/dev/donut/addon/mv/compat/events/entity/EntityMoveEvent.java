package dev.donut.addon.mv.compat.events.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
public final class EntityMoveEvent {
    public final Entity entity;
    public Vec3 movement;
    public EntityMoveEvent(Entity entity, Vec3 movement) { this.entity = entity; this.movement = movement; }
}
