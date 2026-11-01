package dev.vitrail.render.dlss;

import dev.vitrail.Vitrail;
import dev.vitrail.render.CapturedProjection;
import dev.vitrail.render.PackChain;
import dev.vitrail.render.RenderScale;
import dev.vitrail.render.reflex.ReflexBridge;
import dev.vitrail.render.streamline.StreamlineBridge;

import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * DLSS Frame Generation presentation engine, supporting both Streamline proxy presentation
 * and native presentation fallback.
 */
public final class VitrailFramePresenter {
	public static final VitrailFramePresenter INSTANCE = new VitrailFramePresenter();

	private static final long ACQUIRE_TIMEOUT_NS = 5_000_000_000L;
	private static final long LOG_INTERVAL_NS = 1_000_000_000L;

	public interface PresenterEncoder {
		VkCommandBuffer allocateAndBeginTransientCommandBuffer();
		void waitSemaphore(long semaphore, long value, long stageMask);
		void execute(VkCommandBuffer commandBuffer);
		void signalSemaphore(long semaphore, long value, long stageMask);
	}

	private long[] acquireSemaphores = new long[0];
	private int acquireCursor;
	private boolean failed;

	private int[] pendingImageIndex = new int[0];
	private long[] pendingPresentSem = new long[0];
	private int pendingCount;

	private long logWindowStartNs;
	private int realFramesInWindow;
	private int generatedFramesInWindow;
	private int interpOkInWindow;
	private int interpFallbackInWindow;

	private int monotonicFrameIndex = 1;
	private int measuredRealFps;
	private int measuredPresentedFps;
	private int realCount;
	private int totalCount;
	private long rateStartNs;
	private int lastReportedFgStatus = -999;
	private int lastFgStatus = 0;
	private int lastActuallyPresented = 0;
	private boolean hasLoggedFirstPrepare = false;
	private int diagnosticsInterval = 0;

	private boolean needsHistoryReset = false;
	private boolean wasFgActive = false;

	private VitrailFramePresenter() {
	}

	public boolean isActive() {
		Minecraft mc = Minecraft.getInstance();
		if (failed || mc == null || mc.level == null || mc.player == null || mc.isPaused()) {
			return false;
		}
		if (mc.gui != null && mc.gui.screen() != null && mc.gui.screen().isPauseScreen()) {
			return false;
		}
		return RenderScale.dlssFrameGeneration()
				&& (DlssBridge.isFrameGenerationSupported() || StreamlineBridge.isFgSupported())
				&& RenderScale.lastDlssEvaluated()
				&& PackChain.drawingPack();
	}

	public void onPackReload() {
		lastFgStatus = 0;
		lastActuallyPresented = 0;
		lastReportedFgStatus = -999;
		hasLoggedFirstPrepare = false;
		pendingCount = 0;
		needsHistoryReset = true;
		wasFgActive = false;
	}

	public void onLeaveWorld() {
		onPackReload();
		StreamlineBridge.disableFrame(0);
	}

	public int realFps() { return measuredRealFps; }
	public int presentedFps() { return measuredPresentedFps; }
	public int lastFgStatus() { return lastFgStatus; }
	public int lastActuallyPresented() { return lastActuallyPresented; }

	public int presentStreamline(
			VkQueue queue,
			VkPresentInfoKHR presentInfo,
			int swapchainImagesCount,
			int swapchainWidth,
			int swapchainHeight,
			int swapchainImageFormat
	) {
		int frameIndex = (int) (ReflexBridge.currentSimFrameId() & 0x7FFFFFFF);
		if (frameIndex == 0) {
			frameIndex = monotonicFrameIndex++;
		}

		boolean fgActive = isActive();
		boolean prepared = false;

		if (fgActive) {
			if (!wasFgActive) {
				needsHistoryReset = true;
			}
			Minecraft mc = Minecraft.getInstance();
			Camera mainCamera = mc.gameRenderer != null ? mc.gameRenderer.mainCamera() : null;
			Matrix4fc viewToClip = CapturedProjection.current();
			if (viewToClip == null && mc.gameRenderer != null) {
				var grs = mc.gameRenderer.gameRenderState();
				if (grs != null && grs.levelRenderState != null && grs.levelRenderState.cameraRenderState != null) {
					viewToClip = grs.levelRenderState.cameraRenderState.projectionMatrix;
				}
			}

			long hudlessVk = RenderScale.cachedHudlessVk();
			long depthVk = RenderScale.cachedDepthVk();
			long mvVk = RenderScale.cachedMvVk();

			if (mainCamera != null && viewToClip != null && hudlessVk != 0L && depthVk != 0L && mvVk != 0L) {
				Matrix4f cameraToWorld = new Matrix4f().rotation(mainCamera.rotation()).invert();
				Vector3f up = cameraToWorld.transformDirection(new Vector3f(0, 1, 0)).normalize();
				Vector3f right = cameraToWorld.transformDirection(new Vector3f(1, 0, 0)).normalize();
				Vector3f forward = cameraToWorld.transformDirection(new Vector3f(0, 0, -1)).normalize();

				float m11 = viewToClip.m11();
				float m00 = viewToClip.m00();
				float m22 = viewToClip.m22();
				float m32 = viewToClip.m32();
				float near = m32 / (m22 + 1.0f);
				float far = m22 > 0 ? (m32 / m22) : Float.MAX_VALUE;
				float fov = 2.0f * (float) Math.atan(1.0f / Math.abs(m11));
				float aspect = Math.abs(m11 / m00);

				float camX = (float) mainCamera.position().x;
				float camY = (float) mainCamera.position().y;
				float camZ = (float) mainCamera.position().z;

				float jitterX = -DlssJitter.jitterX();
				float jitterY = DlssJitter.jitterY();

				int renderW = RenderScale.lastRenderWidth();
				int renderH = RenderScale.lastRenderHeight();
				if (renderW <= 0 || renderH <= 0) {
					renderW = RenderScale.cachedScaledWidth();
					renderH = RenderScale.cachedScaledHeight();
				}
				float mvScaleX = renderW > 0 ? (1.0f / renderW) : 1.0f;
				float mvScaleY = renderH > 0 ? (1.0f / renderH) : 1.0f;

				int hudlessW = RenderScale.cachedTargetWidth();
				int hudlessH = RenderScale.cachedTargetHeight();
				if (hudlessW <= 0 || hudlessH <= 0) {
					hudlessW = swapchainWidth;
					hudlessH = swapchainHeight;
				}

				boolean resetHistory = needsHistoryReset;
				needsHistoryReset = false;

				prepared = dev.vitrail.render.streamline.StreamlineBridge.prepareFrame(
						frameIndex,
						true,
						swapchainImagesCount,
						depthVk, RenderScale.cachedDepthViewVk(), RenderScale.cachedDepthFormat(), renderW, renderH,
						mvVk, RenderScale.cachedMvViewVk(), RenderScale.cachedMvFormat(), renderW, renderH,
						hudlessVk, RenderScale.cachedHudlessViewVk(), RenderScale.cachedHudlessFormat(), hudlessW, hudlessH,
						0L, 0L, 0, 0, 0,
						0, swapchainWidth, swapchainHeight, swapchainImageFormat,
						jitterX, jitterY,
						mvScaleX, mvScaleY,
						camX, camY, camZ,
						up.x, up.y, up.z,
						right.x, right.y, right.z,
						forward.x, forward.y, forward.z,
						near, far, fov, aspect,
						resetHistory,
						viewToClip
				);
				if (prepared && !hasLoggedFirstPrepare) {
					hasLoggedFirstPrepare = true;
					Vitrail.logger().info("[Vitrail DLSS-G] First frame prepared successfully (frameIndex={}, render={}x{}, hudless={}x{}, swapchain={}x{})",
							frameIndex, renderW, renderH, hudlessW, hudlessH, swapchainWidth, swapchainHeight);
				}
			} else {
				if (diagnosticsInterval++ % 300 == 0) {
					Vitrail.logger().debug("[Vitrail DLSS-G] prepareFrame skipped: mainCamera={}, viewToClip={}, hudlessVk=0x{}, depthVk=0x{}, mvVk=0x{}",
							mainCamera != null, viewToClip != null, Long.toHexString(hudlessVk), Long.toHexString(depthVk), Long.toHexString(mvVk));
				}
			}
		}

		if (!prepared) {
			dev.vitrail.render.streamline.StreamlineBridge.disableFrame(frameIndex);
		}

		int result;
		try {
			result = dev.vitrail.render.streamline.StreamlineBridge.streamlineQueuePresent(queue.address(), presentInfo.address());
		} catch (Throwable t) {
			Vitrail.logger().error("Streamline vkQueuePresent failed, falling back to native presentation", t);
			return KHRSwapchain.vkQueuePresentKHR(queue, presentInfo);
		}

		dev.vitrail.render.streamline.StreamlineBridge.FgState fgState = dev.vitrail.render.streamline.StreamlineBridge.queryFgState();
		if (fgState != null) {
			lastFgStatus = fgState.status();
			lastActuallyPresented = fgState.actuallyPresented();
			if (fgActive && fgState.status() != lastReportedFgStatus) {
				lastReportedFgStatus = fgState.status();
				if (fgState.status() != 0) {
					Vitrail.logger().warn("[Vitrail DLSS-G] Status: {}", StreamlineBridge.decodeFgStatus(fgState.status()));
				} else {
					Vitrail.logger().info("[Vitrail DLSS-G] Status: OK (actually presented={}, max generated={})",
							fgState.actuallyPresented(), fgState.maxGenerated());
				}
			}
		}
		recordFrame(prepared, fgState);
		wasFgActive = prepared;

		return result;
	}

	private void recordFrame(boolean prepared, dev.vitrail.render.streamline.StreamlineBridge.FgState state) {
		long now = System.nanoTime();
		if (rateStartNs == 0L) {
			rateStartNs = now;
		}
		realCount++;
		int framesPresentedThisTick = 1;
		if (prepared && state != null && state.status() == 0 && state.actuallyPresented() > 0) {
			framesPresentedThisTick = state.actuallyPresented();
		}
		totalCount += framesPresentedThisTick;

		long elapsed = now - rateStartNs;
		if (elapsed >= 1_000_000_000L) {
			double sec = elapsed / 1_000_000_000.0;
			measuredRealFps = (int) Math.round(realCount / sec);
			measuredPresentedFps = (int) Math.round(totalCount / sec);
			realCount = 0;
			totalCount = 0;
			rateStartNs = now;
		}
	}

	public void prepareExtraFrames(
			PresenterEncoder enc,
			VkDevice device,
			long swapchain,
			LongList swapchainImages,
			long[] presentSemaphores,
			int swapW,
			int swapH,
			long backbufferView,
			long srcImage,
			int srcFormat,
			int srcW,
			int srcH,
			int generatedCount
	) {
		pendingCount = 0;
		if (failed || swapchain == 0L || srcImage == 0L || generatedCount <= 0) {
			return;
		}

		try {
			ensureCapacity(device, swapchainImages.size() + 1, generatedCount);

			for (int i = 0; i < generatedCount; i++) {
				// 1. Evaluate DLSS Frame Generation
				long interpImage = RenderScale.evaluateDlssgIntoInterp(enc, swapW, swapH, backbufferView, srcImage, srcFormat);
				if (interpImage != 0L) {
					interpOkInWindow++;
				} else {
					interpFallbackInWindow++;
					interpImage = srcImage;
				}

				long blitSrc = interpImage;
				int copyW = Math.min(swapW, srcW);
				int copyH = Math.min(swapH, srcH);

				long acquireSem = acquireSemaphores[acquireCursor];
				acquireCursor = (acquireCursor + 1) % acquireSemaphores.length;

				int imageIndex;
				try (MemoryStack stack = MemoryStack.stackPush()) {
					IntBuffer pIndex = stack.callocInt(1);
					int r = KHRSwapchain.vkAcquireNextImageKHR(device, swapchain, ACQUIRE_TIMEOUT_NS, acquireSem, 0L, pIndex);
					if (r != VK10.VK_SUCCESS && r != 1000001003 /* SUBOPTIMAL */) {
						return;
					}
					imageIndex = pIndex.get(0);
				}

				long dstImage = swapchainImages.getLong(imageIndex);
				long presentSem = presentSemaphores[imageIndex];
				recordBlit(enc, blitSrc, dstImage, copyW, copyH, acquireSem, presentSem);

				pendingImageIndex[pendingCount] = imageIndex;
				pendingPresentSem[pendingCount] = presentSem;
				pendingCount++;
			}
		} catch (Throwable t) {
			failed = true;
			pendingCount = 0;
			Vitrail.logger().error("DLSS-FG present-record failed; frame generation disabled for session", t);
		}
	}

	public void flushPendingPresents(long swapchain, VkQueue presentQueue) {
		int presentedThisFrame = 0;
		if (!failed && pendingCount != 0) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				for (int i = 0; i < pendingCount; i++) {
					VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default();
					present.pWaitSemaphores(stack.longs(pendingPresentSem[i]));
					present.swapchainCount(1);
					present.pSwapchains(stack.longs(swapchain));
					present.pImageIndices(stack.ints(pendingImageIndex[i]));
					ReflexBridge.beginOutOfBandPresent();
					KHRSwapchain.vkQueuePresentKHR(presentQueue, present);
					ReflexBridge.endOutOfBandPresent();
					presentedThisFrame++;
				}
			} catch (Throwable t) {
				failed = true;
				Vitrail.logger().error("DLSS-FG present failed; frame generation disabled", t);
			} finally {
				pendingCount = 0;
			}
		}
		if (RenderScale.dlssFrameGeneration()) {
			logPresentRate(presentedThisFrame);
		}
	}

	private void logPresentRate(int generatedThisFrame) {
		realFramesInWindow++;
		generatedFramesInWindow += generatedThisFrame;
		long now = System.nanoTime();
		if (logWindowStartNs == 0L) {
			logWindowStartNs = now;
			return;
		}
		long elapsed = now - logWindowStartNs;
		if (elapsed < LOG_INTERVAL_NS) {
			return;
		}
		double seconds = elapsed / 1.0e9;
		double realFps = realFramesInWindow / seconds;
		double totalFps = (realFramesInWindow + generatedFramesInWindow) / seconds;
		Vitrail.logger().debug("[DLSS-FG Present-Rate] real={} gen={} realFps={} totalFps={} ok={} fallback={}",
				realFramesInWindow, generatedFramesInWindow,
				String.format("%.1f", realFps), String.format("%.1f", totalFps),
				interpOkInWindow, interpFallbackInWindow);
		logWindowStartNs = now;
		realFramesInWindow = 0;
		generatedFramesInWindow = 0;
		interpOkInWindow = 0;
		interpFallbackInWindow = 0;
	}

	private void recordBlit(
			PresenterEncoder enc,
			long srcImage,
			long dstImage,
			int copyW,
			int copyH,
			long acquireSem,
			long presentSem
	) {
		VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// Swapchain UNDEFINED -> TRANSFER_DST (stage/access values mirror MC's blitFromTexture)
			VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
			toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
					.oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
					.srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
			toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);

			// Make the source's prior writes visible to the blit read
			VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
			srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
			VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
			KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

			// Blit final frame (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla
			VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
			region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.get(0).srcOffsets(1).set(copyW, copyH, 1);
			region.get(0).dstOffsets(0).set(0, copyH, 0);
			region.get(0).dstOffsets(1).set(copyW, 0, 1);
			VK10.vkCmdBlitImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL, dstImage,
					VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

			// Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002)
			VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
			toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
					.oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
					.srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
			toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
			VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
			mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
			VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
			KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);
		}

		if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
			throw new IllegalStateException("vkEndCommandBuffer(fg blit) failed");
		}
		enc.waitSemaphore(acquireSem, 0L, 65536L);
		enc.execute(cmd);
		enc.signalSemaphore(presentSem, 0L, 4096L);
	}

	private void ensureCapacity(VkDevice device, int semaphoreCount, int generatedCount) {
		if (pendingImageIndex.length < generatedCount) {
			pendingImageIndex = new int[generatedCount];
			pendingPresentSem = new long[generatedCount];
		}
		if (acquireSemaphores.length >= semaphoreCount) {
			return;
		}
		destroy(device);
		acquireSemaphores = new long[semaphoreCount];
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
			LongBuffer p = stack.mallocLong(1);
			for (int i = 0; i < semaphoreCount; i++) {
				if (VK10.vkCreateSemaphore(device, sci, null, p) != VK10.VK_SUCCESS) {
					throw new IllegalStateException("vkCreateSemaphore(fg acquire) failed");
				}
				acquireSemaphores[i] = p.get(0);
			}
		}
		acquireCursor = 0;
	}

	public void destroy(VkDevice device) {
		if (device != null) {
			for (long sem : acquireSemaphores) {
				if (sem != 0L) {
					VK10.vkDestroySemaphore(device, sem, null);
				}
			}
		}
		acquireSemaphores = new long[0];
		acquireCursor = 0;
		pendingCount = 0;
	}
}
