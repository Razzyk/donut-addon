package dev.donut.addon.anubis.util.pvp;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
public final class RotationUtils {
    private RotationUtils() {}
    public static Rotation fromTo(Vec3 from, Vec3 to) {
        Vec3 v = to.subtract(from);
        return new Rotation((float)Math.toDegrees(Math.atan2(v.z, v.x)) - 90f,
            (float)-Math.toDegrees(Math.atan2(v.y, Math.hypot(v.x, v.z))));
    }
    public static Rotation entityRotation(Entity e) { return new Rotation(e.getYRot(), e.getXRot()); }
}
