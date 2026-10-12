package dev.donut.addon.mv.compat.utils.player;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/** Movement-vector helpers matching the source addon's blocks-per-second units. */
public final class PlayerUtils {
    private static final double DIAGONAL_SCALE = 1.0 / Math.sqrt(2.0);

    private PlayerUtils() {
    }

    public static Vec3 getHorizontalVelocity(double blocksPerSecond) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return Vec3.ZERO;

        float yaw = minecraft.player.getYRot();
        Vec3 forward = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 right = Vec3.directionFromRotation(0.0F, yaw + 90.0F);
        Input keys = minecraft.player.input.keyPresses;
        double x = 0.0;
        double z = 0.0;
        boolean movingForwardOrBack = false;
        boolean movingSideways = false;
        double perTickSpeed = blocksPerSecond / 20.0;

        if (keys.forward()) {
            x += forward.x * perTickSpeed;
            z += forward.z * perTickSpeed;
            movingForwardOrBack = true;
        }
        if (keys.backward()) {
            x -= forward.x * perTickSpeed;
            z -= forward.z * perTickSpeed;
            movingForwardOrBack = true;
        }
        if (keys.right()) {
            x += right.x * perTickSpeed;
            z += right.z * perTickSpeed;
            movingSideways = true;
        }
        if (keys.left()) {
            x -= right.x * perTickSpeed;
            z -= right.z * perTickSpeed;
            movingSideways = true;
        }
        if (movingForwardOrBack && movingSideways) {
            x *= DIAGONAL_SCALE;
            z *= DIAGONAL_SCALE;
        }

        return new Vec3(x, 0.0, z);
    }
}
