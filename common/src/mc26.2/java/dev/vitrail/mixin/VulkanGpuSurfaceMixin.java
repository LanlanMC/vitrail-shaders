package dev.vitrail.mixin;

import dev.vitrail.Vitrail;
import dev.vitrail.render.RenderScale;
import dev.vitrail.render.dlss.VitrailFramePresenter;
import dev.vitrail.render.reflex.ReflexBridge;
import dev.vitrail.render.streamline.StreamlineBridge;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import it.unimi.dsi.fastutil.longs.LongList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;
import org.lwjgl.vulkan.VkSwapchainLatencyCreateInfoNV;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * Tracks swapchain creation/destruction, presents DLSS-G generated frames via Streamline proxy,
 * and brackets frame presentation with NVIDIA Reflex markers.
 */
@Mixin(VulkanGpuSurface.class)
public abstract class VulkanGpuSurfaceMixin {

	@Shadow
	@Final
	private VulkanDevice device;

	@Shadow
	@Final
	private VkQueue presentQueue;

	@Shadow
	private long swapchain;

	@Shadow
	@Final
	private LongList swapchainImages;

	@Shadow
	private int currentImageIndex;

	@Shadow
	private int swapchainWidth;

	@Shadow
	private int swapchainHeight;

	@Shadow
	private int swapchainImageFormat;

	@Shadow
	private long[] presentSemaphores;

	@WrapOperation(method = "<init>",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/sdl/SDLVulkan;SDL_Vulkan_CreateSurface(JLorg/lwjgl/vulkan/VkInstance;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)Z"))
	private boolean vitrail$createSurface(
			long window,
			VkInstance instance,
			VkAllocationCallbacks allocator,
			LongBuffer pSurface,
			Operation<Boolean> original
	) {
		if (StreamlineBridge.usesStreamline() && StreamlineBridge.isWindows()) {
			int props = org.lwjgl.sdl.SDLVideo.SDL_GetWindowProperties(window);
			long hwnd = org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty(props, "SDL.window.win32.hwnd", 0L);
			if (hwnd != 0L) {
				try {
					long allocAddr = allocator != null ? allocator.address() : 0L;
					int r = StreamlineBridge.streamlineCreateWin32Surface(instance.address(), hwnd, allocAddr, MemoryUtil.memAddress(pSurface));
					if (r == 0) {
						Vitrail.logger().info("Streamline createWin32Surface succeeded");
						return true;
					}
					Vitrail.logger().warn("Streamline createWin32Surface failed with code 0x{}, falling back to SDL", Integer.toHexString(r));
				} catch (Throwable t) {
					Vitrail.logger().warn("Streamline createWin32Surface exception: {}", t.getMessage());
				}
			}
		}
		return original.call(window, instance, allocator, pSurface);
	}

	@WrapOperation(method = "close",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSurface;vkDestroySurfaceKHR(Lorg/lwjgl/vulkan/VkInstance;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
	private void vitrail$destroySurface(
			VkInstance instance,
			long surface,
			VkAllocationCallbacks allocator,
			Operation<Void> original
	) {
		if (StreamlineBridge.usesStreamline()) {
			try {
				long allocAddr = allocator != null ? allocator.address() : 0L;
				StreamlineBridge.streamlineDestroySurface(instance.address(), surface, allocAddr);
				return;
			} catch (Throwable t) {
				Vitrail.logger().warn("Streamline destroySurface exception: {}", t.getMessage());
			}
		}
		original.call(instance, surface, allocator);
	}

	@WrapOperation(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkCreateSwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkSwapchainCreateInfoKHR;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int vitrail$createSwapchainWithReflex(
			VkDevice device,
			VkSwapchainCreateInfoKHR pCreateInfo,
			VkAllocationCallbacks pAllocator,
			LongBuffer pSwapchain,
			Operation<Integer> original
	) {
		int targetW = pCreateInfo.imageExtent().width();
		int targetH = pCreateInfo.imageExtent().height();
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc != null && mc.getWindow() != null) {
			int winW = mc.getWindow().getWidth();
			int winH = mc.getWindow().getHeight();
			if (winW > 0 && winH > 0 && (winW != targetW || winH != targetH)) {
				pCreateInfo.imageExtent().set(winW, winH);
			}
		}
		if (StreamlineBridge.usesStreamline()) {
			try {
				long allocAddr = pAllocator != null ? pAllocator.address() : 0L;
				boolean fg = StreamlineBridge.isFgSupported() || RenderScale.dlssFrameGeneration();
				return StreamlineBridge.streamlineCreateSwapchain(device.address(), pCreateInfo.address(), allocAddr, MemoryUtil.memAddress(pSwapchain), fg);
			} catch (Throwable t) {
				Vitrail.logger().warn("Streamline vkCreateSwapchain failed, falling back to native: {}", t.getMessage());
			}
		}
		if (ReflexBridge.isSupported() && ReflexBridge.mode().enabled()) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkSwapchainLatencyCreateInfoNV latency = VkSwapchainLatencyCreateInfoNV.calloc(stack).sType$Default();
				latency.pNext(pCreateInfo.pNext());
				latency.latencyModeEnable(true);
				pCreateInfo.pNext(latency.address());
				return original.call(device, pCreateInfo, pAllocator, pSwapchain);
			}
		}
		return original.call(device, pCreateInfo, pAllocator, pSwapchain);
	}

	@WrapOperation(method = "destroySwapchain",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkDestroySwapchainKHR(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkAllocationCallbacks;)V"))
	private void vitrail$destroySwapchain(
			VkDevice device,
			long swapchain,
			VkAllocationCallbacks pAllocator,
			Operation<Void> original
	) {
		if (StreamlineBridge.usesStreamline()) {
			try {
				StreamlineBridge.disableFrame(0);
				long allocAddr = pAllocator != null ? pAllocator.address() : 0L;
				StreamlineBridge.streamlineDestroySwapchain(device.address(), swapchain, allocAddr);
				return;
			} catch (Throwable t) {
				Vitrail.logger().warn("Streamline vkDestroySwapchain failed: {}", t.getMessage());
			}
		}
		original.call(device, swapchain, pAllocator);
	}

	@WrapOperation(method = "configure",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkGetSwapchainImagesKHR(Lorg/lwjgl/vulkan/VkDevice;JLjava/nio/IntBuffer;Ljava/nio/LongBuffer;)I"))
	private int vitrail$getSwapchainImages(
			VkDevice device,
			long swapchain,
			IntBuffer pCount,
			LongBuffer pImages,
			Operation<Integer> original
	) {
		if (StreamlineBridge.usesStreamline()) {
			try {
				long countAddr = MemoryUtil.memAddress(pCount);
				long imagesAddr = pImages != null ? MemoryUtil.memAddress(pImages) : 0L;
				return StreamlineBridge.streamlineGetSwapchainImages(device.address(), swapchain, countAddr, imagesAddr);
			} catch (Throwable t) {
				Vitrail.logger().warn("Streamline vkGetSwapchainImages failed: {}", t.getMessage());
			}
		}
		return original.call(device, swapchain, pCount, pImages);
	}

	@WrapOperation(method = "acquireNextTexture",
			at = @At(value = "INVOKE",
					target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkAcquireNextImageKHR(Lorg/lwjgl/vulkan/VkDevice;JJJJLjava/nio/IntBuffer;)I"))
	private int vitrail$acquireNextImage(
			VkDevice device,
			long swapchain,
			long timeout,
			long semaphore,
			long fence,
			IntBuffer pImageIndex,
			Operation<Integer> original
	) {
		if (StreamlineBridge.usesStreamline()) {
			try {
				long indexAddr = MemoryUtil.memAddress(pImageIndex);
				return StreamlineBridge.streamlineAcquireNextImage(device.address(), swapchain, timeout, semaphore, fence, indexAddr);
			} catch (Throwable t) {
				Vitrail.logger().warn("Streamline vkAcquireNextImage failed: {}", t.getMessage());
			}
		}
		return original.call(device, swapchain, timeout, semaphore, fence, pImageIndex);
	}

	@Inject(method = "configure", at = @At("TAIL"))
	private void vitrail$onConfigure(CallbackInfo ci) {
		ReflexBridge.setSwapchain(this.swapchain);
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc != null && mc.getWindow() != null) {
			int winW = mc.getWindow().getWidth();
			int winH = mc.getWindow().getHeight();
			if (winW > 0 && winH > 0 && (winW != this.swapchainWidth || winH != this.swapchainHeight)) {
				this.swapchainWidth = winW;
				this.swapchainHeight = winH;
			}
		}
	}

	@Inject(method = "destroySwapchain", at = @At("HEAD"))
	private void vitrail$onDestroySwapchain(CallbackInfo ci) {
		ReflexBridge.setSwapchain(0L);
		VitrailFramePresenter.INSTANCE.destroy(this.device.vkDevice());
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void vitrail$onClose(CallbackInfo ci) {
		ReflexBridge.setSwapchain(0L);
		VitrailFramePresenter.INSTANCE.destroy(this.device.vkDevice());
	}

	@Inject(method = "close", at = @At("TAIL"))
	private void vitrail$onCloseTail(CallbackInfo ci) {
		StreamlineBridge.shutdown();
	}

	@Inject(method = "blitFromTexture", at = @At("TAIL"))
	private void vitrail$presentGeneratedFrames(
			CommandEncoderBackend commandEncoder,
			GpuTextureView textureView,
			CallbackInfo ci
	) {
		if (StreamlineBridge.usesStreamline()) {
			return;
		}
		if (this.currentImageIndex < 0 || !VitrailFramePresenter.INSTANCE.isActive()) {
			return;
		}
		long srcImage = textureView.texture() instanceof VulkanGpuTexture t ? t.vkImage() : 0L;
		long srcView = textureView instanceof VulkanGpuTextureView v ? v.vkImageView() : 0L;
		int srcFormat = textureView.texture() instanceof VulkanGpuTexture t ? VulkanConst.toVk(t.getFormat()) : 37;
		if (srcImage == 0L || srcView == 0L) {
			return;
		}
		VulkanCommandEncoder enc = (VulkanCommandEncoder) commandEncoder;
		VitrailFramePresenter.INSTANCE.prepareExtraFrames(
				vitrail$encoderAdapter(enc),
				this.device.vkDevice(),
				this.swapchain,
				this.swapchainImages,
				this.presentSemaphores,
				this.swapchainWidth,
				this.swapchainHeight,
				srcView,
				srcImage,
				srcFormat,
				textureView.getWidth(0),
				textureView.getHeight(0),
				1
		);
	}

	@Inject(method = "present", at = @At("HEAD"))
	private void vitrail$flushGeneratedPresents(CallbackInfo ci) {
		if (!StreamlineBridge.usesStreamline()) {
			VitrailFramePresenter.INSTANCE.flushPendingPresents(this.swapchain, this.presentQueue);
		}
	}

	@WrapOperation(method = "present", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/KHRSwapchain;vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I"))
	private int vitrail$wrapPresent(VkQueue queue, VkPresentInfoKHR presentInfo, Operation<Integer> original) {
		ReflexBridge.endRenderSubmit();
		ReflexBridge.beginPresent();
		int result;
		if (StreamlineBridge.usesStreamline()) {
			result = VitrailFramePresenter.INSTANCE.presentStreamline(
					queue,
					presentInfo,
					this.swapchainImages.size(),
					this.swapchainWidth,
					this.swapchainHeight,
					this.swapchainImageFormat
			);
		} else {
			result = original.call(queue, presentInfo);
		}
		ReflexBridge.endPresent();
		return result;
	}

	@Unique
	private static VitrailFramePresenter.PresenterEncoder vitrail$encoderAdapter(VulkanCommandEncoder enc) {
		return new VitrailFramePresenter.PresenterEncoder() {
			@Override
			public VkCommandBuffer allocateAndBeginTransientCommandBuffer() {
				return enc.allocateAndBeginTransientCommandBuffer();
			}

			@Override
			public void waitSemaphore(long semaphore, long value, long stageMask) {
				enc.waitSemaphore(semaphore, value, stageMask);
			}

			@Override
			public void execute(VkCommandBuffer commandBuffer) {
				enc.execute(commandBuffer);
			}

			@Override
			public void signalSemaphore(long semaphore, long value, long stageMask) {
				enc.signalSemaphore(semaphore, value, stageMask);
			}
		};
	}
}
