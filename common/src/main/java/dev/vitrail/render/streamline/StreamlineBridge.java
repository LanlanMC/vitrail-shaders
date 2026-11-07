package dev.vitrail.render.streamline;

import dev.vitrail.Vitrail;
import dev.vitrail.render.RenderScale;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * NVIDIA Streamline SDK integration bridge for Vulkan.
 * <p>
 * Provides proxy swapchain and asynchronous presentation pacer (SL Pacer)
 * to deliver buttery-smooth DLSS Frame Generation with precise hardware VBlank pacing.
 * <p>
 * Decoupled from upscaler passes (FSR/XeSS) and presentation:
 * When DLSS-G is disabled or when another upscaler/frame-generator (e.g. FSR 3)
 * is selected, Streamline proxies are completely bypassed and standard Vulkan
 * presentation is retained.
 */
@SuppressWarnings("restricted")
public final class StreamlineBridge {

	private static final String SHIM_DLL = "ngxshim.dll";
	private static final List<String> STREAMLINE_DLLS = List.of(
			"ngxshim.dll",
			"sl.interposer.dll",
			"sl.common.dll",
			"sl.dlss_g.dll",
			"sl.reflex.dll",
			"sl.pcl.dll",
			"NvLowLatencyVk.dll",
			"nvngx_dlss.dll",
			"nvngx_dlssg.dll"
	);

	private static boolean candidate = false;
	private static boolean prepared = false;
	private static boolean initialized = false;
	private static boolean initFailed = false;
	private static boolean usingStreamlineDevice = false;
	private static boolean fgSupported = false;
	private static boolean reflexSupported = false;

	private static Path nativesDir = null;
	private static SymbolLookup shimLookup = null;

	// Downcall MethodHandles
	private static MethodHandle fnSlInit;
	private static MethodHandle fnSlMfgConfigure;
	private static MethodHandle fnSlMfgRequest;
	private static MethodHandle fnSlMfgReady;
	private static MethodHandle fnSlMfgPath;
	private static MethodHandle fnSlAmperePrepare;
	private static MethodHandle fnSlInitialized;
	private static MethodHandle fnSlShutdown;
	private static MethodHandle fnSlFgSupported;
	private static MethodHandle fnSlReflexSupported;
	private static MethodHandle fnSlVkCreateInstance;
	private static MethodHandle fnSlVkEnumeratePhysicalDevices;
	private static MethodHandle fnSlVkCreateDevice;
	private static MethodHandle fnSlVkCreateWin32Surface;
	private static MethodHandle fnSlVkDestroySurface;
	private static MethodHandle fnSlVkCreateSwapchain;
	private static MethodHandle fnSlVkDestroySwapchain;
	private static MethodHandle fnSlVkGetSwapchainImages;
	private static MethodHandle fnSlVkAcquireNextImage;
	private static MethodHandle fnSlVkDeviceWaitIdle;
	private static MethodHandle fnSlBeginFrame;
	private static MethodHandle fnSlMarker;
	private static MethodHandle fnSlPrepareFrame;
	private static MethodHandle fnSlDisableFrame;
	private static MethodHandle fnSlVkQueuePresent;
	private static MethodHandle fnSlFgState;

	// State tracking
	public record FgState(int status, int actuallyPresented, int maxGenerated) {}

	private static final Matrix4f prevClipToClip = new Matrix4f();
	private static final Matrix4f prevViewProj = new Matrix4f();
	private static boolean hasPrevViewProj = false;
	private static int prepareErrorCount = 0;

	// Multi-Frame Generation (MFG) tracking
	private static boolean mfgReady = false;
	private static int mfgPath = 0; // 0 = None/Standard 2x, 1 = RTX 50 Native, 2 = RTX 40 Ada Patched, 3 = RTX 30 Ampere
	private static int requestedMultiplier = 1; // Number of intermediate generated frames (1 = 2x, 2 = 3x, 3 = 4x)
	private static int appliedMultiplier = 1;
	private static boolean mfgRejected = false;

	private StreamlineBridge() {
	}

	public static boolean isWindows() {
		String os = System.getProperty("os.name", "").toLowerCase();
		return os.contains("win");
	}

	/**
	 * Determines if Streamline is a valid candidate for this system and settings.
	 */
	public static boolean isCandidate() {
		if (initFailed || !isWindows()) {
			return false;
		}
		return isNvidiaCandidate();
	}

	private static boolean isNvidiaCandidate() {
		try {
			var adapters = net.caffeinemc.mods.sodium.client.compatibility.environment.probe.GraphicsAdapterProbe.getAdapters();
			if (adapters != null && !adapters.isEmpty()) {
				for (var adapter : adapters) {
					if (adapter.vendor() == net.caffeinemc.mods.sodium.client.compatibility.environment.probe.GraphicsAdapterVendor.NVIDIA) {
						return true;
					}
				}
				return false;
			}
		} catch (Throwable ignored) {
		}
		return true;
	}

	public static boolean usesStreamline() {
		return usingStreamlineDevice && initialized && !initFailed;
	}

	public static boolean isFgSupported() {
		return fgSupported;
	}

	public static boolean isReflexSupported() {
		return reflexSupported;
	}

	public static boolean isMfgReady() {
		if (!initialized || mfgRejected) {
			return false;
		}
		if (!mfgReady && fnSlMfgReady != null) {
			try {
				mfgReady = ((int) fnSlMfgReady.invokeExact() == 1);
				if (mfgReady && fnSlMfgPath != null) {
					mfgPath = (int) fnSlMfgPath.invokeExact();
					Vitrail.logger().info("[Vitrail DLSS-G] Multi-Frame Generation ready: path={} [{}]",
							mfgPath, getMfgPathName());
				}
			} catch (Throwable ignored) {
			}
		}
		return mfgReady;
	}

	public static int getMfgPath() {
		return mfgPath;
	}

	public static String getMfgPathName() {
		return switch (mfgPath) {
			case 1 -> "Ada MFG";
			case 2 -> "RTX 50 Native";
			case 3 -> "Ampere SM86";
			default -> "Standard 2x";
		};
	}

	public static int getRequestedMultiplier() {
		return requestedMultiplier;
	}

	public static void setRequestedMultiplier(int count) {
		requestedMultiplier = Math.max(1, count);
	}

	public static int getEffectiveMultiplier() {
		return appliedMultiplier;
	}

	/**
	 * Request intermediate generated frame count from Streamline DLSS-G.
	 *
	 * @param generatedCount number of generated frames per tick:
	 *                       1 = 2x presentation (1 real + 1 generated)
	 *                       2 = 3x presentation (1 real + 2 generated)
	 *                       3 = 4x presentation (1 real + 3 generated)
	 * @return effective number of generated frames admitted by Streamline DLSS-G
	 */
	public static int requestMfgCount(int generatedCount) {
		if (!usesStreamline() || fnSlMfgRequest == null) {
			return 1;
		}
		if (mfgRejected || !mfgReady) {
			return 1;
		}
		try {
			int effective = (int) fnSlMfgRequest.invokeExact(generatedCount);
			return Math.max(1, effective);
		} catch (Throwable t) {
			Vitrail.logger().warn("Streamline slMfgRequest failed: {}", t.getMessage());
			return 1;
		}
	}

	public static void rejectMfg() {
		if (!mfgRejected) {
			mfgRejected = true;
			Vitrail.logger().warn("[Vitrail DLSS-G] Multi-Frame Generation rejected by runtime, reverting to standard 2x");
			if (fnSlMfgRequest != null) {
				try {
					fnSlMfgRequest.invokeExact(1);
				} catch (Throwable ignored) {
				}
			}
			appliedMultiplier = 1;
		}
	}

	public static synchronized boolean prepareStreamline() {
		if (prepared) {
			return initialized && !initFailed;
		}
		if (!isWindows() || initFailed) {
			return false;
		}

		prepared = true;
		try {
			nativesDir = extractNatives();
			if (nativesDir == null) {
				Vitrail.logger().warn("Failed to extract Streamline native binaries");
				initFailed = true;
				return false;
			}

			Path shimPath = nativesDir.resolve(SHIM_DLL);
			if (!Files.exists(shimPath)) {
				Vitrail.logger().warn("Streamline shim {} missing in {}", SHIM_DLL, nativesDir);
				initFailed = true;
				return false;
			}

			shimLookup = SymbolLookup.libraryLookup(shimPath.toAbsolutePath(), Arena.global());
			bindFunctions(shimLookup);

			// Initialize Streamline
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment pluginPathSeg = wideString(arena, nativesDir.toAbsolutePath().toString());
				Path logDir = Vitrail.platform().gameDirectory().resolve("logs");
				Files.createDirectories(logDir);
				MemorySegment logPathSeg = wideString(arena, logDir.toAbsolutePath().toString());

				if (fnSlMfgConfigure != null) {
					int targetMode = 3; // Request capacity for up to 4x (3 intermediate generated frames)
					try {
						int mfgR = (int) fnSlMfgConfigure.invokeExact(pluginPathSeg, targetMode);
						Vitrail.logger().debug("Streamline slMfgConfigure({}x) returned 0x{}", targetMode + 1, Integer.toHexString(mfgR));
						if (mfgR != 0) {
							Vitrail.logger().warn("Streamline slMfgConfigure failed for {}x (0x{}), retrying with standard 2x",
									targetMode + 1, Integer.toHexString(mfgR));
							fnSlMfgConfigure.invokeExact(pluginPathSeg, 1);
						}
					} catch (Throwable t) {
						Vitrail.logger().warn("Streamline slMfgConfigure failed, falling back to 2x", t);
						try {
							fnSlMfgConfigure.invokeExact(pluginPathSeg, 1);
						} catch (Throwable ignored) {
						}
					}
				}

				int r = (int) fnSlInit.invokeExact(pluginPathSeg, logPathSeg);
				if (r != 0 && r != 39 /* already init */) {
					Vitrail.logger().warn("Streamline slInit returned error 0x{}, retrying with standard 2x fallback", Integer.toHexString(r));
					if (fnSlShutdown != null) {
						try {
							fnSlShutdown.invokeExact();
						} catch (Throwable ignored) {
						}
					}
					if (fnSlMfgConfigure != null) {
						try {
							fnSlMfgConfigure.invokeExact(pluginPathSeg, 1);
						} catch (Throwable ignored) {
						}
					}
					r = (int) fnSlInit.invokeExact(pluginPathSeg, logPathSeg);
					if (r != 0 && r != 39) {
						Vitrail.logger().warn("Streamline slInit fallback failed with error 0x{}", Integer.toHexString(r));
						initFailed = true;
						return false;
					}
				}
			}

			int isInit = (int) fnSlInitialized.invokeExact();
			if (isInit == 0) {
				Vitrail.logger().warn("Streamline slInitialized returned false");
				initFailed = true;
				return false;
			}

			initialized = true;

			if (fnSlMfgReady != null) {
				try {
					mfgReady = ((int) fnSlMfgReady.invokeExact() == 1);
				} catch (Throwable t) {
					mfgReady = false;
				}
			}
			if (fnSlMfgPath != null) {
				try {
					mfgPath = (int) fnSlMfgPath.invokeExact();
				} catch (Throwable t) {
					mfgPath = 0;
				}
			}

			Vitrail.logger().info("NVIDIA Streamline SDK initialized successfully from {} (MFG ready={}, path={} [{}])",
					nativesDir, mfgReady, mfgPath, getMfgPathName());
			return true;
		} catch (Throwable t) {
			Vitrail.logger().error("Failed to initialize Streamline SDK bridge", t);
			initFailed = true;
			initialized = false;
			return false;
		}
	}

	public static void rejectStreamline() {
		usingStreamlineDevice = false;
		initFailed = true;
	}

	private static boolean isAmpereGpu() {
		try {
			var adapters = net.caffeinemc.mods.sodium.client.compatibility.environment.probe.GraphicsAdapterProbe.getAdapters();
			if (adapters != null) {
				for (var adapter : adapters) {
					if (adapter.vendor() == net.caffeinemc.mods.sodium.client.compatibility.environment.probe.GraphicsAdapterVendor.NVIDIA) {
						String desc = adapter.name();
						if (desc != null && (desc.contains("RTX 30") || desc.contains("GeForce RTX 30"))) {
							return true;
						}
					}
				}
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	public static void onDeviceCreated(VkDevice device) {
		usingStreamlineDevice = true;
		if (!fgSupported) {
			probeSupport(device.getPhysicalDevice().address());
		}
	}

	public static void probeSupport(long physicalDevice) {
		if (!initialized || fnSlFgSupported == null) return;
		try {
			// slAmperePrepare sets s_amperePrepared in ngxshim, which bypasses and suppresses the Ada MFG
			// patch path if called on Ada (RTX 40) or Blackwell (RTX 50) GPUs. Only call on Ampere (RTX 30).
			if (fnSlAmperePrepare != null && isAmpereGpu()) {
				try {
					int ampR = (int) fnSlAmperePrepare.invokeExact(physicalDevice, 0L);
					Vitrail.logger().info("Streamline slAmperePrepare returned 0x{}", Integer.toHexString(ampR));
				} catch (Throwable t) {
					Vitrail.logger().debug("Streamline slAmperePrepare skipped or not applicable", t);
				}
			}
			int fgResult = (int) fnSlFgSupported.invokeExact(physicalDevice);
			Path dlssg = getDlssDir().resolve("nvngx_dlssg.dll");
			fgSupported = (fgResult == 0 || fgResult == 39) && Files.exists(dlssg);
			int reflexResult = (int) fnSlReflexSupported.invokeExact(physicalDevice);
			reflexSupported = (reflexResult == 0 || reflexResult == 39);

			// Refresh MFG ready flag and path now that adapter features have been probed/patched by ngxshim
			if (fgSupported && fnSlMfgReady != null) {
				try {
					mfgReady = ((int) fnSlMfgReady.invokeExact() == 1);
				} catch (Throwable t) {
					mfgReady = false;
				}
			} else {
				mfgReady = false;
			}
			if (fnSlMfgPath != null) {
				try {
					mfgPath = (int) fnSlMfgPath.invokeExact();
				} catch (Throwable t) {
					mfgPath = 0;
				}
			}

			Vitrail.logger().info("Streamline capabilities: DLSS-G supported={}, Reflex supported={}, MFG ready={} [{}]",
					fgSupported, reflexSupported, mfgReady, getMfgPathName());
		} catch (Throwable t) {
			Vitrail.logger().warn("Failed to query Streamline feature support", t);
		}
	}

	public static int streamlineCreateInstance(long pCreateInfo, long pAllocator, long pOutInstance) throws Throwable {
		return (int) fnSlVkCreateInstance.invokeExact(pCreateInfo, pAllocator, pOutInstance);
	}

	public static int streamlineEnumeratePhysicalDevices(long instance, long pCount, long pOutDevices) throws Throwable {
		return (int) fnSlVkEnumeratePhysicalDevices.invokeExact(instance, pCount, pOutDevices);
	}

	public static int streamlineCreateDevice(long physicalDevice, long pCreateInfo, long pAllocator, long pOutDevice) throws Throwable {
		return (int) fnSlVkCreateDevice.invokeExact(physicalDevice, pCreateInfo, pAllocator, pOutDevice);
	}

	public static int streamlineCreateWin32Surface(long instance, long hwnd, long pAllocator, long pOutSurface) throws Throwable {
		return (int) fnSlVkCreateWin32Surface.invokeExact(instance, hwnd, pAllocator, pOutSurface);
	}

	public static void streamlineDestroySurface(long instance, long surface, long pAllocator) throws Throwable {
		fnSlVkDestroySurface.invokeExact(instance, surface, pAllocator);
	}

	public static int streamlineCreateSwapchain(long device, long pCreateInfo, long pAllocator, long pOutSwapchain, boolean fgEnabled) throws Throwable {
		return (int) fnSlVkCreateSwapchain.invokeExact(device, pCreateInfo, pAllocator, pOutSwapchain, fgEnabled ? 1 : 0);
	}

	public static void streamlineDestroySwapchain(long device, long swapchain, long pAllocator) throws Throwable {
		fnSlVkDestroySwapchain.invokeExact(device, swapchain, pAllocator);
	}

	public static int streamlineGetSwapchainImages(long device, long swapchain, long pCount, long pOutImages) throws Throwable {
		return (int) fnSlVkGetSwapchainImages.invokeExact(device, swapchain, pCount, pOutImages);
	}

	public static int streamlineAcquireNextImage(long device, long swapchain, long timeout, long semaphore, long fence, long pImageIndex) throws Throwable {
		return (int) fnSlVkAcquireNextImage.invokeExact(device, swapchain, timeout, semaphore, fence, pImageIndex);
	}

	public static int streamlineQueuePresent(long queue, long pPresentInfo) throws Throwable {
		return (int) fnSlVkQueuePresent.invokeExact(queue, pPresentInfo);
	}

	public static int streamlineDeviceWaitIdle(long device) {
		if (!usesStreamline() || fnSlVkDeviceWaitIdle == null) return -1;
		try {
			return (int) fnSlVkDeviceWaitIdle.invokeExact(device);
		} catch (Throwable t) {
			Vitrail.logger().warn("Streamline vkDeviceWaitIdle failed: {}", t.getMessage());
			return -1;
		}
	}

	public static final int MARKER_SIMULATION_START = 0;
	public static final int MARKER_SIMULATION_END = 1;
	public static final int MARKER_RENDERSUBMIT_START = 2;
	public static final int MARKER_RENDERSUBMIT_END = 3;
	public static final int MARKER_PRESENT_START = 4;
	public static final int MARKER_PRESENT_END = 5;
	public static final int MARKER_INPUT_SAMPLE = 13;
	public static final int MARKER_TRIGGER_FLASH = 7;
	public static final int MARKER_OUT_OF_BAND_RENDERSUBMIT_START = 9;
	public static final int MARKER_OUT_OF_BAND_RENDERSUBMIT_END = 10;
	public static final int MARKER_OUT_OF_BAND_PRESENT_START = 11;
	public static final int MARKER_OUT_OF_BAND_PRESENT_END = 12;

	public static int beginFrame(int frameIndex, int reflexMode, int frameLimitUs) {
		if (!usesStreamline() || fnSlBeginFrame == null) return -1;
		try {
			return (int) fnSlBeginFrame.invokeExact(frameIndex, reflexMode, frameLimitUs);
		} catch (Throwable t) {
			Vitrail.logger().warn("Streamline beginFrame failed: {}", t.getMessage());
			return -1;
		}
	}

	public static int setMarker(int frameIndex, int markerType) {
		if (!usesStreamline() || fnSlMarker == null) return -1;
		try {
			return (int) fnSlMarker.invokeExact(frameIndex, markerType);
		} catch (Throwable t) {
			return -1;
		}
	}

	public static String decodeFgStatus(int status) {
		if (status == 0) return "OK";
		java.util.List<String> reasons = new java.util.ArrayList<>();
		if ((status & (1 << 0)) != 0) reasons.add("ResolutionTooLow");
		if ((status & (1 << 1)) != 0) reasons.add("ReflexNotDetected");
		if ((status & (1 << 2)) != 0) reasons.add("HdrFormatNotSupported");
		if ((status & (1 << 3)) != 0) reasons.add("CommonConstantsInvalid");
		if ((status & (1 << 4)) != 0) reasons.add("GetCurrentBackBufferIndexNotCalled");
		return String.join("|", reasons) + " (0x" + Integer.toHexString(status) + ")";
	}

	public static void disableFrame(int frameIndex) {
		if (!usesStreamline() || fnSlDisableFrame == null) return;
		try {
			int r = (int) fnSlDisableFrame.invokeExact(frameIndex);
		} catch (Throwable ignored) {
		}
	}

	public static boolean prepareFrame(
			int frameIndex,
			boolean enableFg,
			int numBackBuffers,
			long depthImage, long depthView, int depthFormat, int depthW, int depthH,
			long mvecImage, long mvecView, int mvecFormat, int mvecW, int mvecH,
			long hudlessImage, long hudlessView, int hudlessFormat, int hudlessW, int hudlessH,
			long uiImage, long uiView, int uiFormat, int uiW, int uiH,
			int uiUsage, int colorW, int colorH, int colorFormat,
			float jitterX, float jitterY,
			float mvScaleX, float mvScaleY,
			float cameraX, float cameraY, float cameraZ,
			float upX, float upY, float upZ,
			float rightX, float rightY, float rightZ,
			float forwardX, float forwardY, float forwardZ,
			float near, float far, float fov, float aspect,
			boolean reset,
			Matrix4fc viewToClipMatrix
	) {
		if (!usesStreamline() || fnSlPrepareFrame == null) return false;

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment viewToClipSeg = matrixToSegment(arena, viewToClipMatrix);
			Matrix4f clipToView = new Matrix4f(viewToClipMatrix).invert();
			MemorySegment clipToViewSeg = matrixToSegment(arena, clipToView);

			Matrix4f clipToPrevClip = new Matrix4f();
			if (hasPrevViewProj && !reset) {
				clipToPrevClip.set(prevViewProj).mul(clipToView);
			} else {
				clipToPrevClip.identity();
			}
			MemorySegment clipToPrevClipSeg = matrixToSegment(arena, clipToPrevClip);
			Matrix4f prevClipToClipMat = new Matrix4f(clipToPrevClip).invert();
			MemorySegment prevClipToClipSeg = matrixToSegment(arena, prevClipToClipMat);

			prevViewProj.set(viewToClipMatrix);
			hasPrevViewProj = true;

			// Synchronize dynamic MFG multiplier if requested and ready
			if (enableFg && isMfgReady()) {
				FgState state = queryFgState();
				int maxGen = state.maxGenerated() > 0 ? state.maxGenerated() : 3;

				int targetCount;
				if (dev.vitrail.render.RenderScale.isDynamicFrameGeneration()) {
					targetCount = dev.vitrail.render.dlss.DynamicMfgController.INSTANCE.update(System.nanoTime(), maxGen);
				} else {
					targetCount = Math.clamp(requestedMultiplier, 1, maxGen);
				}

				if (appliedMultiplier != targetCount) {
					int effective = requestMfgCount(targetCount);
					if (appliedMultiplier != effective) {
						appliedMultiplier = effective;
						String modeStr = dev.vitrail.render.RenderScale.isDynamicFrameGeneration()
								? "Dynamic " + (effective + 1) + "x"
								: (targetCount + 1) + "x";
						Vitrail.logger().info("[Vitrail DLSS-G] Frame generation multiplier updated: {} ({})",
								modeStr, getMfgPathName());
					}
				}
			}

			int r = (int) fnSlPrepareFrame.invokeExact(
					frameIndex,
					enableFg ? 1 : 0,
					numBackBuffers,
					depthImage, depthView, depthFormat, depthW, depthH,
					mvecImage, mvecView, mvecFormat, mvecW, mvecH,
					hudlessImage, hudlessView, hudlessFormat, hudlessW, hudlessH,
					uiImage, uiView, uiFormat, uiW, uiH,
					uiUsage, colorW, colorH, colorFormat,
					jitterX, jitterY,
					mvScaleX, mvScaleY,
					cameraX, cameraY, cameraZ,
					upX, upY, upZ,
					rightX, rightY, rightZ,
					forwardX, forwardY, forwardZ,
					near, far, fov, aspect,
					reset ? 1 : 0,
					viewToClipSeg,
					clipToViewSeg,
					clipToPrevClipSeg,
					prevClipToClipSeg
			);

			if (r != 0 && r != 39) {
				if (prepareErrorCount++ % 120 == 0) {
					Vitrail.logger().warn("[Vitrail DLSS-G] Streamline prepareFrame error: 0x{} ({})",
							Integer.toHexString(r), r);
				}
				return false;
			}
			return true;
		} catch (Throwable t) {
			Vitrail.logger().warn("Streamline prepareFrame failed: {}", t.getMessage());
			return false;
		}
	}

	public static FgState queryFgState() {
		if (!usesStreamline() || fnSlFgState == null) {
			return new FgState(0, 0, 0);
		}
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment pStatus = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment pPresented = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment pMax = arena.allocate(ValueLayout.JAVA_INT);

			int r = (int) fnSlFgState.invokeExact(pStatus, pPresented, pMax);
			if (r == 0 || r == 39) {
				return new FgState(
						pStatus.get(ValueLayout.JAVA_INT, 0),
						pPresented.get(ValueLayout.JAVA_INT, 0),
						pMax.get(ValueLayout.JAVA_INT, 0)
				);
			}
		} catch (Throwable ignored) {
		}
		return new FgState(0, 0, 0);
	}

	public static synchronized void shutdown() {
		if (initialized && fnSlShutdown != null) {
			try {
				int r = (int) fnSlShutdown.invokeExact();
			} catch (Throwable ignored) {
			}
			initialized = false;
			usingStreamlineDevice = false;
		}
	}

	private static MemorySegment matrixToSegment(Arena arena, Matrix4fc mat) {
		MemorySegment seg = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
		// Streamline float4x4 is row-major:
		// Row 0:
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 0, mat.m00());
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 1, mat.m10());
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 2, mat.m20());
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 3, mat.m30());
		// Row 1:
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 4, mat.m01());
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 5, mat.m11());
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 6, mat.m21());
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 7, mat.m31());
		// Row 2:
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 8, mat.m02());
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 9, mat.m12());
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 10, mat.m22());
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 11, mat.m32());
		// Row 3:
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 12, mat.m03());
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 13, mat.m13());
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 14, mat.m23());
		seg.setAtIndex(ValueLayout.JAVA_FLOAT, 15, mat.m33());
		return seg;
	}

	private static MemorySegment wideString(Arena arena, String str) {
		byte[] bytes = str.getBytes(StandardCharsets.UTF_16LE);
		MemorySegment seg = arena.allocate(bytes.length + 2, 2);
		MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0, bytes.length);
		seg.set(ValueLayout.JAVA_SHORT, bytes.length, (short) 0);
		return seg;
	}

	public static Path getDlssDir() {
		return Vitrail.platform().gameDirectory().resolve("vitrail").resolve("dlss");
	}

	private static Path extractNatives() {
		try {
			Path targetDir = getDlssDir();
			Files.createDirectories(targetDir);

			for (String dllName : STREAMLINE_DLLS) {
				String resPath = "/natives/windows/x86_64/" + dllName;
				try (InputStream in = StreamlineBridge.class.getResourceAsStream(resPath)) {
					if (in != null) {
						Path dest = targetDir.resolve(dllName);
						byte[] bytes = in.readAllBytes();
						if (!Files.exists(dest) || !java.util.Arrays.equals(Files.readAllBytes(dest), bytes)) {
							Files.write(dest, bytes);
						}
					}
				}
			}
			return targetDir;
		} catch (Exception e) {
			Vitrail.logger().error("Failed to extract Streamline natives", e);
			return null;
		}
	}

	private static void bindFunctions(SymbolLookup lookup) {
		Linker linker = Linker.nativeLinker();

		fnSlInit = downcall(linker, lookup, "ngxshim_sl_init",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		fnSlMfgConfigure = downcall(linker, lookup, "ngxshim_sl_mfg_configure",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
		fnSlMfgRequest = downcall(linker, lookup, "ngxshim_sl_mfg_request",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		fnSlMfgReady = downcall(linker, lookup, "ngxshim_sl_mfg_ready",
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
		fnSlMfgPath = downcall(linker, lookup, "ngxshim_sl_mfg_path",
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
		fnSlAmperePrepare = downcall(linker, lookup, "ngxshim_sl_ampere_prepare",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
		fnSlInitialized = downcall(linker, lookup, "ngxshim_sl_initialized",
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
		fnSlShutdown = downcall(linker, lookup, "ngxshim_sl_shutdown",
				FunctionDescriptor.of(ValueLayout.JAVA_INT));
		fnSlFgSupported = downcall(linker, lookup, "ngxshim_sl_fg_supported",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
		fnSlReflexSupported = downcall(linker, lookup, "ngxshim_sl_reflex_supported",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

		fnSlVkCreateInstance = downcall(linker, lookup, "ngxshim_sl_vk_create_instance",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
		fnSlVkEnumeratePhysicalDevices = downcall(linker, lookup, "ngxshim_sl_vk_enumerate_physical_devices",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
		fnSlVkCreateDevice = downcall(linker, lookup, "ngxshim_sl_vk_create_device",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

		fnSlVkCreateWin32Surface = downcall(linker, lookup, "ngxshim_sl_vk_create_win32_surface",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
		fnSlVkDestroySurface = downcall(linker, lookup, "ngxshim_sl_vk_destroy_surface",
				FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

		fnSlVkCreateSwapchain = downcall(linker, lookup, "ngxshim_sl_vk_create_swapchain",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
		fnSlVkDestroySwapchain = downcall(linker, lookup, "ngxshim_sl_vk_destroy_swapchain",
				FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
		fnSlVkGetSwapchainImages = downcall(linker, lookup, "ngxshim_sl_vk_get_swapchain_images",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
		fnSlVkAcquireNextImage = downcall(linker, lookup, "ngxshim_sl_vk_acquire_next_image",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
		fnSlVkDeviceWaitIdle = downcall(linker, lookup, "ngxshim_sl_vk_device_wait_idle",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

		fnSlBeginFrame = downcall(linker, lookup, "ngxshim_sl_begin_frame",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		fnSlMarker = downcall(linker, lookup, "ngxshim_sl_marker",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

		fnSlPrepareFrame = downcall(linker, lookup, "ngxshim_sl_prepare_frame",
				FunctionDescriptor.of(
						ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
						ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
						ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
						ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
						ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
						ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
						ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
						ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
						ValueLayout.JAVA_INT,
						ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS
				));

		fnSlDisableFrame = downcall(linker, lookup, "ngxshim_sl_disable_frame",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
		fnSlVkQueuePresent = downcall(linker, lookup, "ngxshim_sl_vk_queue_present",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
		fnSlFgState = downcall(linker, lookup, "ngxshim_sl_fg_state",
				FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
	}

	private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor desc) {
		return lookup.find(name)
				.map(addr -> linker.downcallHandle(addr, desc))
				.orElse(null);
	}
}
