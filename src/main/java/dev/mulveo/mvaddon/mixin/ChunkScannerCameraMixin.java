package dev.mulveo.mvaddon.mixin;

import dev.mulveo.mvaddon.chunkscanner.light.LightCache;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class ChunkScannerCameraMixin {
   @Shadow @Final private Minecraft minecraft;
   @Shadow private float depthFar;
   @Shadow private float fov;
   @Shadow private void setupPerspective(float zNear, float zFar, float fov, float width, float height) {}

   @Inject(method = "update", at = @At("TAIL"))
   private void chunkScanner$extendFarPlaneForPersistentMarkers(DeltaTracker tracker, CallbackInfo ci) {
      if (this.minecraft.player == null || !LightCache.hasAlertedChunks()) return;

      float extendedFarPlane = LightCache.requiredFarPlaneDistance(
         this.minecraft.player.getX(), this.minecraft.player.getZ(), this.depthFar
      );
      if (extendedFarPlane <= this.depthFar) return;

      Window window = this.minecraft.getWindow();
      this.depthFar = extendedFarPlane;
      this.setupPerspective(0.05F, extendedFarPlane, this.fov, window.getWidth(), window.getHeight());
   }
}
