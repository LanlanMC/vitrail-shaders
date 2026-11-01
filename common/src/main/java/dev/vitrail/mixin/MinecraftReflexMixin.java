package dev.vitrail.mixin;

import dev.vitrail.render.reflex.ReflexBridge;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Paces the client frame loop with NVIDIA Reflex 2, delaying input sampling and simulation
 * start until optimal alignment with GPU presentation.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftReflexMixin {

	@Inject(method = "runTick", at = @At("HEAD"))
	private void vitrail$reflexFrameStart(boolean renderLevel, CallbackInfo ci) {
		ReflexBridge.beginFrame();
		ReflexBridge.beginSimulation();
	}

	@Inject(method = "runTick",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
	private void vitrail$reflexRenderStart(boolean renderLevel, CallbackInfo ci) {
		ReflexBridge.endSimulation();
		ReflexBridge.beginRenderSubmit();
	}
}
