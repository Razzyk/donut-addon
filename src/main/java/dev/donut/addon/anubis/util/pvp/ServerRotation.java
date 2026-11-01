package dev.donut.addon.anubis.util.pvp;
import net.ccbluex.liquidbounce.event.EventListener;
import net.ccbluex.liquidbounce.utils.aiming.RotationManager;
import net.ccbluex.liquidbounce.utils.aiming.RotationTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
public final class ServerRotation {
    private static Rotation requested;
    private static long requestTick;
    private static Object requestLevel;
    private ServerRotation() {}
    public static boolean canRotate(LocalPlayer p) { return p != null && p.connection != null && Minecraft.getInstance().gui.screen() == null; }
    public static void request(Rotation rotation, EventListener provider) {
        var p = Minecraft.getInstance().player;
        if (!canRotate(p) || provider == null) return;
        requested = rotation;
        requestLevel = p.level();
        requestTick = p.level().getGameTime();
        var nativeRotation = new net.ccbluex.liquidbounce.utils.aiming.data.Rotation(rotation.yaw(), rotation.pitch());
        RotationManager.INSTANCE.setRotationTarget(new RotationTarget(nativeRotation), 30, provider);
    }
    public static Rotation sent() {
        if (Minecraft.getInstance().player == null) { requested = null; return null; }
        var rotation = RotationManager.INSTANCE.getServerRotation();
        return new Rotation(rotation.yaw(), rotation.pitch());
    }
    public static boolean pending() {
        if (requested == null) return false;
        var player = Minecraft.getInstance().player;
        if (player == null || player.level() != requestLevel || player.level().getGameTime() > requestTick + 4) { requested = null; return false; }
        var actual = sent();
        if (actual == null || Math.abs(net.minecraft.util.Mth.wrapDegrees(actual.yaw()-requested.yaw())) < 1f && Math.abs(actual.pitch()-requested.pitch()) < 1f) { requested = null; return false; }
        return true;
    }
}
