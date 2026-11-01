package dev.vitrail.render.reflex;

import dev.vitrail.Vitrail;
import dev.vitrail.render.RenderScale;
import dev.vitrail.render.streamline.StreamlineBridge;

import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.NVLowLatency2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGetLatencyMarkerInfoNV;
import org.lwjgl.vulkan.VkLatencySleepInfoNV;
import org.lwjgl.vulkan.VkLatencySleepModeInfoNV;
import org.lwjgl.vulkan.VkLatencyTimingsFrameReportNV;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSetLatencyMarkerInfoNV;

import java.nio.LongBuffer;

/**
 * Java bridge managing NVIDIA Reflex 2 low latency frame pacing and telemetry via VK_NV_low_latency2.
 */
public final class ReflexBridge {

	private static volatile VkDevice device;
	private static volatile long swapchain = 0L;
	private static volatile long timelineSemaphore = 0L;
	private static volatile long timelineValue = 0L;
	private static volatile long frameId = 0L;
	private static volatile boolean supported = false;
	private static volatile ReflexMode mode = ReflexMode.OFF;
	private static volatile float lastLatencyMs = 0.0F;
	private static int timingsCounter = 0;

	private ReflexBridge() {
	}

	public static VkDevice device() {
		return device;
	}

	public static void initDevice(VkDevice vkDevice, boolean isSupported) {
		device = vkDevice;
		supported = isSupported;
		timelineSemaphore = 0L;
		timelineValue = 0L;
		frameId = 0L;

		if (supported && device != null && device.getCapabilities().vkSetLatencySleepModeNV != 0L) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkSemaphoreTypeCreateInfo timelineInfo = VkSemaphoreTypeCreateInfo.calloc(stack)
						.sType$Default()
						.semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE)
						.initialValue(0L);

				VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack)
						.sType$Default()
						.pNext(timelineInfo);

				LongBuffer pSem = stack.mallocLong(1);
				if (VK10.vkCreateSemaphore(device, createInfo, null, pSem) == VK10.VK_SUCCESS) {
					timelineSemaphore = pSem.get(0);
					Vitrail.logger().info("[Vitrail Reflex] Initialized NVIDIA Reflex 2 (timeline semaphore 0x{})",
							Long.toHexString(timelineSemaphore));
				} else {
					Vitrail.logger().warn("[Vitrail Reflex] Failed to create timeline semaphore for Reflex");
				}
			} catch (Throwable t) {
				Vitrail.logger().error("[Vitrail Reflex] Failed to initialize timeline semaphore", t);
			}
		}

		if (swapchain != 0L && mode != ReflexMode.OFF) {
			applySleepMode();
		}
	}

	public static void setSwapchain(long handle) {
		if (swapchain == handle) {
			return;
		}
		long oldHandle = swapchain;
		swapchain = handle;
		if (oldHandle != 0L && isSupported()) {
			disableSleepMode(oldHandle);
		}
		if (swapchain != 0L && isSupported()) {
			applySleepMode();
		}
	}

	public static ReflexMode mode() {
		return mode;
	}

	public static void mode(ReflexMode newMode) {
		mode = newMode == null ? ReflexMode.OFF : newMode;
		if (isSupported() && swapchain != 0L) {
			applySleepMode();
		}
	}

	public static boolean isSupported() {
		return supported && device != null && device.getCapabilities().vkSetLatencySleepModeNV != 0L;
	}

	public static float lastLatencyMs() {
		return lastLatencyMs;
	}

	public static long currentSimFrameId() {
		return frameId;
	}

	public static void applySleepMode() {
		if (!isSupported() || swapchain == 0L) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkLatencySleepModeInfoNV modeInfo = VkLatencySleepModeInfoNV.calloc(stack)
					.sType$Default()
					.lowLatencyMode(mode.enabled())
					.lowLatencyBoost(mode.boost())
					.minimumIntervalUs(0);

			int res = NVLowLatency2.vkSetLatencySleepModeNV(device, swapchain, modeInfo);
			if (res == VK10.VK_SUCCESS) {
				Vitrail.logger().info("[Vitrail Reflex] Applied sleep mode: enabled={}, boost={}",
						mode.enabled(), mode.boost());
			} else {
				Vitrail.logger().warn("[Vitrail Reflex] vkSetLatencySleepModeNV returned {}", res);
			}
		} catch (Throwable t) {
			Vitrail.logger().error("[Vitrail Reflex] Error applying sleep mode", t);
		}
	}

	public static void disableSleepMode(long targetSwapchain) {
		if (device == null || targetSwapchain == 0L || !supported) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkLatencySleepModeInfoNV modeInfo = VkLatencySleepModeInfoNV.calloc(stack)
					.sType$Default()
					.lowLatencyMode(false)
					.lowLatencyBoost(false)
					.minimumIntervalUs(0);

			NVLowLatency2.vkSetLatencySleepModeNV(device, targetSwapchain, modeInfo);
		} catch (Throwable ignored) {
		}
	}

	public static void beginFrame() {
		frameId++;
		int curFrameIndex = (int) (frameId & 0x7FFFFFFF);

		if (StreamlineBridge.usesStreamline()) {
			int reflexModeOrdinal = mode.ordinal();
			if (RenderScale.dlssFrameGeneration() && reflexModeOrdinal == 0) {
				reflexModeOrdinal = ReflexMode.ON.ordinal();
			}
			int frameLimitUs = 0;
			try {
				int limit = Minecraft.getInstance().options.framerateLimit().get();
				if (limit > 0 && limit < 260) {
					frameLimitUs = (int) (1_000_000.0 / limit);
				}
			} catch (Throwable ignored) {
			}
			StreamlineBridge.beginFrame(curFrameIndex, reflexModeOrdinal, frameLimitUs);
			StreamlineBridge.setMarker(curFrameIndex, StreamlineBridge.MARKER_INPUT_SAMPLE);
			return;
		}

		if (!isSupported() || !mode.enabled() || swapchain == 0L || timelineSemaphore == 0L) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			long value = ++timelineValue;
			VkLatencySleepInfoNV sleepInfo = VkLatencySleepInfoNV.calloc(stack)
					.sType$Default()
					.signalSemaphore(timelineSemaphore)
					.value(value);

			int res = NVLowLatency2.vkLatencySleepNV(device, swapchain, sleepInfo);
			if (res == VK10.VK_SUCCESS) {
				VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack)
						.sType$Default()
						.pSemaphores(stack.longs(timelineSemaphore))
						.pValues(stack.longs(value));

				VK12.vkWaitSemaphores(device, waitInfo, 100_000_000L);
			}
		} catch (Throwable t) {
			// Sleep should never crash the game
		}

		setMarker(NVLowLatency2.VK_LATENCY_MARKER_INPUT_SAMPLE_NV);
	}

	public static void beginSimulation() {
		setMarker(NVLowLatency2.VK_LATENCY_MARKER_SIMULATION_START_NV);
	}

	public static void endSimulation() {
		setMarker(NVLowLatency2.VK_LATENCY_MARKER_SIMULATION_END_NV);
	}

	public static void beginRenderSubmit() {
		setMarker(NVLowLatency2.VK_LATENCY_MARKER_RENDERSUBMIT_START_NV);
	}

	public static void endRenderSubmit() {
		setMarker(NVLowLatency2.VK_LATENCY_MARKER_RENDERSUBMIT_END_NV);
	}

	public static void beginPresent() {
		setMarker(NVLowLatency2.VK_LATENCY_MARKER_PRESENT_START_NV);
	}

	public static void endPresent() {
		setMarker(NVLowLatency2.VK_LATENCY_MARKER_PRESENT_END_NV);
		updateLatencyTimings();
	}

	public static void beginOutOfBandPresent() {
		setMarker(NVLowLatency2.VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_START_NV);
	}

	public static void endOutOfBandPresent() {
		setMarker(NVLowLatency2.VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_END_NV);
	}

	private static void setMarker(int markerType) {
		if (StreamlineBridge.usesStreamline()) {
			int slMarker = switch (markerType) {
				case NVLowLatency2.VK_LATENCY_MARKER_SIMULATION_START_NV -> StreamlineBridge.MARKER_SIMULATION_START;
				case NVLowLatency2.VK_LATENCY_MARKER_SIMULATION_END_NV -> StreamlineBridge.MARKER_SIMULATION_END;
				case NVLowLatency2.VK_LATENCY_MARKER_RENDERSUBMIT_START_NV -> StreamlineBridge.MARKER_RENDERSUBMIT_START;
				case NVLowLatency2.VK_LATENCY_MARKER_RENDERSUBMIT_END_NV -> StreamlineBridge.MARKER_RENDERSUBMIT_END;
				case NVLowLatency2.VK_LATENCY_MARKER_PRESENT_START_NV -> StreamlineBridge.MARKER_PRESENT_START;
				case NVLowLatency2.VK_LATENCY_MARKER_PRESENT_END_NV -> StreamlineBridge.MARKER_PRESENT_END;
				case NVLowLatency2.VK_LATENCY_MARKER_INPUT_SAMPLE_NV -> StreamlineBridge.MARKER_INPUT_SAMPLE;
				case NVLowLatency2.VK_LATENCY_MARKER_TRIGGER_FLASH_NV -> StreamlineBridge.MARKER_TRIGGER_FLASH;
				case NVLowLatency2.VK_LATENCY_MARKER_OUT_OF_BAND_RENDERSUBMIT_START_NV -> StreamlineBridge.MARKER_OUT_OF_BAND_RENDERSUBMIT_START;
				case NVLowLatency2.VK_LATENCY_MARKER_OUT_OF_BAND_RENDERSUBMIT_END_NV -> StreamlineBridge.MARKER_OUT_OF_BAND_RENDERSUBMIT_END;
				case NVLowLatency2.VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_START_NV -> StreamlineBridge.MARKER_OUT_OF_BAND_PRESENT_START;
				case NVLowLatency2.VK_LATENCY_MARKER_OUT_OF_BAND_PRESENT_END_NV -> StreamlineBridge.MARKER_OUT_OF_BAND_PRESENT_END;
				default -> -1;
			};
			if (slMarker >= 0) {
				StreamlineBridge.setMarker((int) (frameId & 0x7FFFFFFF), slMarker);
			}
			return;
		}

		if (!isSupported() || !mode.enabled() || swapchain == 0L) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkSetLatencyMarkerInfoNV markerInfo = VkSetLatencyMarkerInfoNV.calloc(stack)
					.sType$Default()
					.presentID(frameId)
					.marker(markerType);

			NVLowLatency2.vkSetLatencyMarkerNV(device, swapchain, markerInfo);
		} catch (Throwable t) {
			// Ignored
		}
	}

	private static void updateLatencyTimings() {
		if (!isSupported() || !mode.enabled() || swapchain == 0L) {
			return;
		}
		if (++timingsCounter < 10) {
			return;
		}
		timingsCounter = 0;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkLatencyTimingsFrameReportNV.Buffer reports = VkLatencyTimingsFrameReportNV.calloc(16, stack);
			for (int i = 0; i < 16; i++) {
				reports.get(i).sType$Default();
			}
			VkGetLatencyMarkerInfoNV getInfo = VkGetLatencyMarkerInfoNV.calloc(stack)
					.sType$Default()
					.timingCount(16)
					.pTimings(reports);

			NVLowLatency2.vkGetLatencyTimingsNV(device, swapchain, getInfo);
			int count = getInfo.timingCount();
			for (int i = 0; i < count; i++) {
				VkLatencyTimingsFrameReportNV report = reports.get(i);
				if (report.presentID() > 0 && report.inputSampleTimeUs() > 0) {
					long endUs = report.gpuRenderEndTimeUs() > 0 ? report.gpuRenderEndTimeUs() : report.presentEndTimeUs();
					if (endUs > report.inputSampleTimeUs()) {
						lastLatencyMs = (float) (endUs - report.inputSampleTimeUs()) / 1000.0F;
					}
				}
			}
		} catch (Throwable t) {
			// Ignored
		}
	}

	public static void destroy() {
		if (device != null) {
			if (swapchain != 0L && supported) {
				disableSleepMode(swapchain);
				swapchain = 0L;
			}
			if (timelineSemaphore != 0L) {
				try {
					VK10.vkDeviceWaitIdle(device);
				} catch (Throwable ignored) {
				}
				try {
					VK10.vkDestroySemaphore(device, timelineSemaphore, null);
				} catch (Throwable ignored) {
				}
				timelineSemaphore = 0L;
			}
		}
		swapchain = 0L;
		device = null;
		supported = false;
		timelineValue = 0L;
		frameId = 0L;
	}
}
