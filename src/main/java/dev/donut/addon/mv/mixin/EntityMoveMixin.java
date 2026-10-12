package dev.donut.addon.mv.mixin;

import dev.donut.addon.mv.compat.MeteorClient;
import dev.donut.addon.mv.compat.events.entity.EntityMoveEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(Entity.class)
public abstract class EntityMoveMixin {
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
    private Vec3 mv$entityMovement(Vec3 movement) {
        Entity entity = (Entity)(Object)this;
        if (entity == net.minecraft.client.Minecraft.getInstance().player) return movement;
        return MeteorClient.EVENT_BUS.post(new EntityMoveEvent(entity, movement)).movement;
    }
}
