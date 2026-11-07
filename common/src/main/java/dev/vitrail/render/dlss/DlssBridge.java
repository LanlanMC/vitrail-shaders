package dev.vitrail.render.dlss;

import dev.vitrail.Vitrail;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Java Foreign Function and Memory (FFM) bridge to the native {@code vitrail_dlss.dll}.
 * <p>
 * Manages runtime capability detection, parameter initialization, feature lifecycle and command
 * evaluation for NVIDIA DLSS Super Resolution on Vulkan.
 */
@SuppressWarnings("restricted")
public final class DlssBridge {

	private static final String DLL_NAME = "vitrail_dlss.dll";

	private static boolean available = false;
	private static boolean initialized = false;
	private static boolean featureCreated = false;
	private static boolean initFailed = false;
	private static int currentRenderWidth = 0;
	private static int currentRenderHeight = 0;
	private static int currentTargetWidth = 0;
	private static int currentTargetHeight = 0;
	private static DlssPreset currentPreset = DlssPreset.DEFAULT;
	private static int evalFailureCount = 0;

	// DLSS Frame Generation (DLSS-G) state
	private static boolean frameGenerationSupported = false;
	private static boolean frameGenerationCreated = false;
	private static int currentDlssgWidth = 0;
	private static int currentDlssgHeight = 0;
	private static int dlssgEvalFailureCount = 0;
	private static Path dlssGDllPath = null;
	private static String dlssGVersion = null;

	private static MethodHandle fnIsSupported;
	private static MethodHandle fnInit;
	private static MethodHandle fnInitV2;
	private static MethodHandle fnGetOptimalSettings;
	private static MethodHandle fnCreateFeature;
	private static MethodHandle fnEvaluate;
	private static MethodHandle fnReleaseFeature;
	private static MethodHandle fnDlssgIsSupported;
	private static MethodHandle fnDlssgCreateFeature;
	private static MethodHandle fnDlssgEvaluate;
	private static MethodHandle fnDlssgEvaluateV2;
	private static MethodHandle fnDlssgReleaseFeature;
	private static MethodHandle fnShutdown;

	static {
		loadBridge();
	}

	private DlssBridge() {
	}

	public static Path getDlssDir() {
		return Vitrail.platform().gameDirectory().resolve("vitrail").resolve("dlss");
	}

	private static void loadBridge() {
		try {
			Path dlssDir = getDlssDir();
			ensureDlssExtracted();
			Path nvngxDlss = dlssDir.resolve("nvngx_dlss.dll");
			if (!Files.exists(nvngxDlss)) {
				Vitrail.logger().info("NVIDIA DLSS library (nvngx_dlss.dll) not found in {}; DLSS disabled", dlssDir);
				available = false;
				return;
			}

			SymbolLookup lookup = findSymbolLookup();
			if (lookup == null) {
				Vitrail.logger().info("DLSS native library ({}) not found; DLSS will be disabled", DLL_NAME);
				return;
			}

			Linker linker = Linker.nativeLinker();

			fnIsSupported = downcall(linker, lookup, "vitrail_dlss_is_supported",
					FunctionDescriptor.of(ValueLayout.JAVA_INT));
			fnInit = downcall(linker, lookup, "vitrail_dlss_init",
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
							ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			fnInitV2 = downcall(linker, lookup, "vitrail_dlss_init_v2",
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
							ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			fnGetOptimalSettings = downcall(linker, lookup, "vitrail_dlss_get_optimal_settings",
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
							ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
							ValueLayout.ADDRESS, ValueLayout.ADDRESS));
			fnCreateFeature = downcall(linker, lookup, "vitrail_dlss_create_feature",
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
							ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
							ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT,
							ValueLayout.JAVA_INT));
			fnEvaluate = downcall(linker, lookup, "vitrail_dlss_evaluate",
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
							ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
							ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
							ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
							ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
							ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
							ValueLayout.JAVA_INT));
			fnReleaseFeature = downcall(linker, lookup, "vitrail_dlss_release_feature",
					FunctionDescriptor.ofVoid());
			fnDlssgIsSupported = downcall(linker, lookup, "vitrail_dlssg_is_supported",
					FunctionDescriptor.of(ValueLayout.JAVA_INT));
			fnDlssgCreateFeature = downcall(linker, lookup, "vitrail_dlssg_create_feature",
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
							ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
			fnDlssgEvaluate = downcall(linker, lookup, "vitrail_dlssg_evaluate",
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
							ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
							ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
							ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
							ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
							ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
							ValueLayout.JAVA_INT));
			fnDlssgEvaluateV2 = downcall(linker, lookup, "vitrail_dlssg_evaluate_v2",
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
							ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
							ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
							ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
							ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
							ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
							ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
							ValueLayout.JAVA_FLOAT, ValueLayout.JAVA_FLOAT,
							ValueLayout.JAVA_INT));
			fnDlssgReleaseFeature = downcall(linker, lookup, "vitrail_dlssg_release_feature",
					FunctionDescriptor.ofVoid());
			fnShutdown = downcall(linker, lookup, "vitrail_dlss_shutdown",
					FunctionDescriptor.ofVoid());

			if (fnDlssgIsSupported != null) {
				Vitrail.logger().debug("DLSS bridge DLSS-G symbols resolved successfully");
			}

			int supported = (int) fnIsSupported.invokeExact();
			available = (supported != 0);

			if (available) {
				Vitrail.logger().info("NVIDIA DLSS runtime detected and initialized successfully");
			} else {
				Vitrail.logger().info("DLSS native bridge loaded, but system/driver does not support DLSS");
			}
		} catch (Throwable t) {
			Vitrail.logger().debug("Could not initialize DLSS bridge: {}", t.getMessage());
			available = false;
		}
	}

	private static void trySyncDevDll(Path sourceDll) {
		try {
			Path targetDir = getDlssDir();
			Files.createDirectories(targetDir);
			Path target = targetDir.resolve(DLL_NAME);
			if (!Files.exists(target) || Files.size(target) != Files.size(sourceDll)) {
				Files.copy(sourceDll, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception ignored) {
		}
	}

	private static SymbolLookup findSymbolLookup() {
		Path targetDir = getDlssDir();
		Path localDll = targetDir.resolve(DLL_NAME);
		if (Files.isRegularFile(localDll)) {
			try {
				SymbolLookup lookup = SymbolLookup.libraryLookup(localDll.toAbsolutePath(), Arena.global());
				if (lookup.find("vitrail_dlssg_is_supported").isPresent()) {
					return lookup;
				}
			} catch (Exception ignored) {
			}
		}

		// 1. Check local build output from CMake build directory (development mode)
		List<Path> devSearchPaths = List.of(
				Path.of("native/dlss/build/Release", DLL_NAME),
				Path.of("../../native/dlss/build/Release", DLL_NAME),
				Path.of("../native/dlss/build/Release", DLL_NAME),
				Vitrail.platform().gameDirectory().resolve("../../native/dlss/build/Release").resolve(DLL_NAME).normalize()
		);
		for (Path devBuildDll : devSearchPaths) {
			if (Files.isRegularFile(devBuildDll)) {
				try {
					SymbolLookup lookup = SymbolLookup.libraryLookup(devBuildDll.toAbsolutePath(), Arena.global());
					if (lookup.find("vitrail_dlssg_is_supported").isPresent()) {
						Vitrail.logger().debug("Loaded DLSS native bridge DLL from CMake build: {}", devBuildDll.toAbsolutePath());
						trySyncDevDll(devBuildDll);
						return lookup;
					}
				} catch (Exception ignored) {
				}
			}
		}

		// 2. Try extracting from jar resources (natives/windows/x86_64/vitrail_dlss.dll)
		try {
			String resPath = "/natives/windows/x86_64/" + DLL_NAME;
			try (var in = DlssBridge.class.getResourceAsStream(resPath)) {
				if (in != null) {
					Files.createDirectories(targetDir);
					Path extracted = targetDir.resolve(DLL_NAME);
					byte[] resBytes = in.readAllBytes();
					if (!Files.exists(extracted) || Files.size(extracted) != resBytes.length) {
						Files.write(extracted, resBytes);
					}
					SymbolLookup lookup = SymbolLookup.libraryLookup(extracted.toAbsolutePath(), Arena.global());
					if (lookup.find("vitrail_dlssg_is_supported").isPresent()) {
						Vitrail.logger().debug("Loaded DLSS native bridge DLL from extracted resources: {}", extracted.toAbsolutePath());
						return lookup;
					}
				}
			}
		} catch (Exception ignored) {
		}

		return null;
	}

	private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
		Optional<MemorySegment> symbol = lookup.find(name);
		return symbol.map(segment -> linker.downcallHandle(segment, descriptor)).orElse(null);
	}

	public static boolean isAvailable() {
		return available;
	}

	public static boolean isInitialized() {
		return initialized;
	}

	public static boolean isFeatureCreated() {
		return featureCreated;
	}

	public static boolean isFeatureMatching(int renderW, int renderH, int targetW, int targetH, DlssPreset preset) {
		return featureCreated && currentRenderWidth == renderW && currentRenderHeight == renderH
				&& currentTargetWidth == targetW && currentTargetHeight == targetH
				&& currentPreset == (preset == null ? DlssPreset.DEFAULT : preset);
	}

	private static long lastWarningTime = 0L;
	private static String lastWarningMessage = "";

	public static void warnUser(String message) {
		long now = System.currentTimeMillis();
		if (!message.equals(lastWarningMessage) || (now - lastWarningTime > 5000L)) {
			lastWarningMessage = message;
			lastWarningTime = now;
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.gui != null && mc.gui.hud != null && mc.gui.hud.getChat() != null) {
				mc.gui.hud.getChat().addClientSystemMessage(
						Component.literal("[Vitrail DLSS Warning] " + message)
								.withStyle(ChatFormatting.RED)
				);
			}
		}
	}

	public static void resetInitFailure() {
		initFailed = false;
	}

	private static void ensureDlssExtracted() {
		try {
			Path extractDir = getDlssDir();
			Files.createDirectories(extractDir);
			Path target = extractDir.resolve("nvngx_dlss.dll");
			if (!Files.exists(target)) {
				String resPath = "/natives/windows/x86_64/nvngx_dlss.dll";
				try (var in = DlssBridge.class.getResourceAsStream(resPath)) {
					if (in != null) {
						Files.write(target, in.readAllBytes());
					}
				}
			}
			Path targetG = extractDir.resolve("nvngx_dlssg.dll");
			if (!Files.exists(targetG)) {
				String resGPath = "/natives/windows/x86_64/nvngx_dlssg.dll";
				try (var in = DlssBridge.class.getResourceAsStream(resGPath)) {
					if (in != null) {
						Files.write(targetG, in.readAllBytes());
					}
				}
			}
		} catch (Exception ignored) {
		}
	}

	public static boolean init(VulkanDevice device, Path logDir) {
		if (!available || fnInit == null || initFailed) return false;
		if (initialized) return true;

		ensureDlssExtracted();

		long vkDevice = DlssVulkanHandles.vkDevice(device);
		long vkPhysical = DlssVulkanHandles.vkPhysicalDevice(device);
		long vkInstance = DlssVulkanHandles.vkInstance(device);

		if (vkDevice == 0L || vkPhysical == 0L || vkInstance == 0L) {
			Vitrail.logger().warn("DLSS init failed: invalid Vulkan handles");
			warnUser("初始化失败：Vulkan 句柄无效");
			initFailed = true;
			return false;
		}

		try (Arena arena = Arena.ofConfined()) {
			String pathStr = logDir == null ? "" : logDir.toAbsolutePath().toString();
			MemorySegment pathSeg = arena.allocateFrom(pathStr, java.nio.charset.StandardCharsets.UTF_16LE);
			MemorySegment devSeg = MemorySegment.ofAddress(vkDevice);
			MemorySegment pdSeg = MemorySegment.ofAddress(vkPhysical);
			MemorySegment instSeg = MemorySegment.ofAddress(vkInstance);

			int res;
			if (fnInitV2 != null) {
				Path dlssGDll = getDlssGDllPath();
				String dlssGPathStr = dlssGDll != null ? dlssGDll.toAbsolutePath().toString() : "";
				MemorySegment dlssGSeg = arena.allocateFrom(dlssGPathStr, java.nio.charset.StandardCharsets.UTF_16LE);
				res = (int) fnInitV2.invokeExact(0x2602L, pathSeg, instSeg, pdSeg, devSeg, dlssGSeg);
			} else {
				res = (int) fnInit.invokeExact(0x2602L, pathSeg, instSeg, pdSeg, devSeg);
			}

			if (res == 0 || res == 1) {
				initialized = true;
				initFailed = false;
				Vitrail.logger().info("NVIDIA NGX Vulkan initialized successfully for Vitrail");

				if (fnDlssgIsSupported != null) {
					try {
						frameGenerationSupported = ((int) fnDlssgIsSupported.invokeExact()) != 0;
						Vitrail.logger().info("NVIDIA DLSS Frame Generation support detected: {}", frameGenerationSupported);
					} catch (Throwable t) {
						Vitrail.logger().debug("Failed to query DLSS-G support: {}", t.getMessage());
					}
				}
				return true;
			} else {
				Vitrail.logger().warn("NVIDIA NGX Vulkan init returned error code {}", res);
				warnUser("初始化失败 (错误码: 0x" + Integer.toHexString(res) + ")");
				initFailed = true;
			}
		} catch (Throwable t) {
			Vitrail.logger().error("Exception during DLSS initialization", t);
			warnUser("初始化异常: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
			initFailed = true;
		}
		return false;
	}

	public static DlssOptimalSettings queryOptimalSettings(int targetWidth, int targetHeight, DlssMode mode) {
		if (!available || !initialized || fnGetOptimalSettings == null || !mode.enabled()) {
			int fbW = Math.round(targetWidth * mode.fallbackScale());
			int fbH = Math.round(targetHeight * mode.fallbackScale());
			return new DlssOptimalSettings(fbW, fbH, 0.0F, fbW, fbH);
		}

		try (Arena arena = Arena.ofConfined()) {
			MemorySegment renderWSeg = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment renderHSeg = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment sharpSeg = arena.allocate(ValueLayout.JAVA_FLOAT);
			MemorySegment minWSeg = arena.allocate(ValueLayout.JAVA_INT);
			MemorySegment minHSeg = arena.allocate(ValueLayout.JAVA_INT);

			int res = (int) fnGetOptimalSettings.invokeExact(
					targetWidth, targetHeight, mode.ngxPerfQualityValue(),
					renderWSeg, renderHSeg, sharpSeg, minWSeg, minHSeg
			);

			if (res == 0 || res == 1) {
				return new DlssOptimalSettings(
						renderWSeg.get(ValueLayout.JAVA_INT, 0),
						renderHSeg.get(ValueLayout.JAVA_INT, 0),
						sharpSeg.get(ValueLayout.JAVA_FLOAT, 0),
						minWSeg.get(ValueLayout.JAVA_INT, 0),
						minHSeg.get(ValueLayout.JAVA_INT, 0)
				);
			}
		} catch (Throwable t) {
			Vitrail.logger().error("Failed to query DLSS optimal settings", t);
		}

		int fbW = Math.round(targetWidth * mode.fallbackScale());
		int fbH = Math.round(targetHeight * mode.fallbackScale());
		return new DlssOptimalSettings(fbW, fbH, 0.0F, fbW, fbH);
	}

	public static boolean createFeature(
			long cmdBuffer, int renderW, int renderH, int targetW, int targetH,
			int perfQualityValue, boolean isHdr, boolean depthInverted, boolean autoExposure, float sharpness,
			DlssPreset preset
	) {
		if (!available || !initialized || fnCreateFeature == null || cmdBuffer == 0L) return false;

		try {
			int presetVal = preset == null ? 0 : preset.ngxValue();
			int res = (int) fnCreateFeature.invokeExact(
					MemorySegment.ofAddress(cmdBuffer),
					renderW, renderH, targetW, targetH,
					perfQualityValue,
					isHdr ? 1 : 0, depthInverted ? 1 : 0, autoExposure ? 1 : 0, sharpness,
					presetVal
			);
			featureCreated = (res == 0 || res == 1);
			if (featureCreated) {
				currentRenderWidth = renderW;
				currentRenderHeight = renderH;
				currentTargetWidth = targetW;
				currentTargetHeight = targetH;
				currentPreset = preset == null ? DlssPreset.DEFAULT : preset;
				evalFailureCount = 0;
				Vitrail.logger().info("DLSS feature created successfully: {}x{} -> {} (preset={}, sharpness={})",
						renderW, renderH, targetW + "x" + targetH, currentPreset, sharpness);
			} else {
				currentRenderWidth = 0;
				currentRenderHeight = 0;
				currentTargetWidth = 0;
				currentTargetHeight = 0;
				currentPreset = DlssPreset.DEFAULT;
				Vitrail.logger().warn("DLSS create feature failed with error code 0x{}", Integer.toHexString(res));
				warnUser("创建特征失败 " + renderW + "x" + renderH + " -> " + targetW + "x" + targetH
						+ " (错误码: 0x" + Integer.toHexString(res) + ")");
			}
			return featureCreated;
		} catch (Throwable t) {
			Vitrail.logger().error("Exception during DLSS create feature", t);
			featureCreated = false;
			currentRenderWidth = 0;
			currentRenderHeight = 0;
			currentTargetWidth = 0;
			currentTargetHeight = 0;
			currentPreset = DlssPreset.DEFAULT;
			warnUser("创建特征异常: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
			return false;
		}
	}

	public static boolean evaluate(
			long cmdBuffer,
			long inColorView, long inColorImage, int inColorFormat,
			long outColorView, long outColorImage, int outColorFormat,
			long inDepthView, long inDepthImage, int inDepthFormat,
			long inMvView, long inMvImage, int inMvFormat,
			long inReactiveView, long inReactiveImage, int inReactiveFormat,
			float jitterX, float jitterY,
			float mvScaleX, float mvScaleY,
			float sharpness,
			boolean resetAccumulation
	) {
		if (!available || !featureCreated || fnEvaluate == null || cmdBuffer == 0L) return false;

		try {
			MemorySegment cmdSeg = MemorySegment.ofAddress(cmdBuffer);
			MemorySegment inColorViewSeg = MemorySegment.ofAddress(inColorView);
			MemorySegment outColorViewSeg = MemorySegment.ofAddress(outColorView);
			MemorySegment inDepthViewSeg = MemorySegment.ofAddress(inDepthView);
			MemorySegment inMvViewSeg = inMvView == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(inMvView);
			MemorySegment inReactiveViewSeg = inReactiveView == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(inReactiveView);

			int res = (int) fnEvaluate.invokeExact(
					cmdSeg,
					inColorViewSeg, inColorImage, inColorFormat,
					outColorViewSeg, outColorImage, outColorFormat,
					inDepthViewSeg, inDepthImage, inDepthFormat,
					inMvViewSeg, inMvImage, inMvFormat,
					inReactiveViewSeg, inReactiveImage, inReactiveFormat,
					jitterX, jitterY,
					mvScaleX, mvScaleY,
					sharpness,
					resetAccumulation ? 1 : 0
			);
			if (res != 0 && res != 1) {
				if (evalFailureCount++ < 5) {
					Vitrail.logger().warn("DLSS evaluate failed with error code 0x{}", Integer.toHexString(res));
				}
				warnUser("渲染评估失败 (错误码: 0x" + Integer.toHexString(res) + ")，已回退到基础缩放");
				return false;
			}
			evalFailureCount = 0;
			return true;
		} catch (Throwable t) {
			Vitrail.logger().error("Exception during DLSS evaluate", t);
			warnUser("渲染评估异常: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()) + "，已回退到基础缩放");
			return false;
		}
	}

	public static void releaseFeature() {
		if (featureCreated && fnReleaseFeature != null) {
			try {
				fnReleaseFeature.invokeExact();
			} catch (Throwable ignored) {
			}
			featureCreated = false;
			currentRenderWidth = 0;
			currentRenderHeight = 0;
			currentTargetWidth = 0;
			currentTargetHeight = 0;
			currentPreset = DlssPreset.DEFAULT;
		}
	}

	public static boolean isFrameGenerationSupported() {
		if (!available) return false;
		Path dlssg = getDlssDir().resolve("nvngx_dlssg.dll");
		if (!Files.exists(dlssg)) return false;
		if (initialized) {
			return frameGenerationSupported;
		}
		return fnDlssgIsSupported != null;
	}

	public static boolean isFrameGenerationCreated() {
		return frameGenerationCreated;
	}

	public static boolean isFrameGenerationMatching(int targetW, int targetH) {
		return frameGenerationCreated && currentDlssgWidth == targetW && currentDlssgHeight == targetH;
	}

	public static String getDlssGVersion() {
		return "v3.x";
	}

	public static Path getDlssGDllPath() {
		Path target = getDlssDir().resolve("nvngx_dlssg.dll");
		return Files.isRegularFile(target) ? target : null;
	}

	public static Path swapperDlssGPath() {
		return getDlssGDllPath();
	}

	public static boolean createFrameGenerationFeature(long cmdBuffer, int width, int height, boolean isHdr, boolean depthInverted, int backbufferFormat) {
		if (!available || !initialized || fnDlssgCreateFeature == null) return false;

		try {
			MemorySegment cmdSeg = MemorySegment.ofAddress(cmdBuffer);
			int res = (int) fnDlssgCreateFeature.invokeExact(
					cmdSeg, width, height,
					isHdr ? 1 : 0,
					depthInverted ? 1 : 0,
					backbufferFormat
			);

			if (res == 0 || res == 1) {
				frameGenerationCreated = true;
				currentDlssgWidth = width;
				currentDlssgHeight = height;
				dlssgEvalFailureCount = 0;
				Vitrail.logger().info("NVIDIA DLSS Frame Generation feature created ({}x{})", width, height);
				return true;
			} else {
				Vitrail.logger().warn("DLSS Frame Generation createFeature failed (code: 0x{})", Integer.toHexString(res));
				frameGenerationCreated = false;
				return false;
			}
		} catch (Throwable t) {
			Vitrail.logger().error("Exception during DLSS-G createFeature", t);
			frameGenerationCreated = false;
			return false;
		}
	}

	public static boolean evaluateFrameGeneration(
			long cmdBuffer,
			long inColorView, long inColorImage, int inColorFormat,
			long inHudlessView, long inHudlessImage, int inHudlessFormat,
			long outInterpolatedView, long outInterpolatedImage, int outInterpolatedFormat,
			long inDepthView, long inDepthImage, int inDepthFormat,
			long inMvView, long inMvImage, int inMvFormat,
			int targetWidth, int targetHeight,
			int mvecWidth, int mvecHeight,
			float mvScaleX, float mvScaleY,
			boolean resetAccumulation
	) {
		if (!available || !initialized || !frameGenerationCreated) {
			return false;
		}

		try {
			MemorySegment cmdSeg = MemorySegment.ofAddress(cmdBuffer);
			MemorySegment inColorViewSeg = MemorySegment.ofAddress(inColorView);
			MemorySegment outInterpolatedViewSeg = MemorySegment.ofAddress(outInterpolatedView);
			MemorySegment inDepthViewSeg = MemorySegment.ofAddress(inDepthView);
			MemorySegment inMvViewSeg = inMvView == 0L ? MemorySegment.NULL : MemorySegment.ofAddress(inMvView);

			int res;
			if (fnDlssgEvaluateV2 != null && inHudlessView != 0L && inHudlessImage != 0L) {
				MemorySegment inHudlessViewSeg = MemorySegment.ofAddress(inHudlessView);
				res = (int) fnDlssgEvaluateV2.invokeExact(
						cmdSeg,
						inColorViewSeg, inColorImage, inColorFormat,
						inHudlessViewSeg, inHudlessImage, inHudlessFormat,
						outInterpolatedViewSeg, outInterpolatedImage, outInterpolatedFormat,
						inDepthViewSeg, inDepthImage, inDepthFormat,
						inMvViewSeg, inMvImage, inMvFormat,
						targetWidth, targetHeight,
						mvecWidth, mvecHeight,
						mvScaleX, mvScaleY,
						resetAccumulation ? 1 : 0
				);
			} else if (fnDlssgEvaluate != null) {
				res = (int) fnDlssgEvaluate.invokeExact(
						cmdSeg,
						inColorViewSeg, inColorImage, inColorFormat,
						outInterpolatedViewSeg, outInterpolatedImage, outInterpolatedFormat,
						inDepthViewSeg, inDepthImage, inDepthFormat,
						inMvViewSeg, inMvImage, inMvFormat,
						targetWidth, targetHeight,
						mvecWidth, mvecHeight,
						mvScaleX, mvScaleY,
						resetAccumulation ? 1 : 0
				);
			} else {
				return false;
			}

			if (res != 0 && res != 1) {
				if (dlssgEvalFailureCount++ < 5) {
					Vitrail.logger().warn("DLSS Frame Generation evaluate failed with code 0x{}", Integer.toHexString(res));
				}
				return false;
			}
			dlssgEvalFailureCount = 0;
			return true;
		} catch (Throwable t) {
			Vitrail.logger().error("Exception during DLSS Frame Generation evaluate", t);
			return false;
		}
	}

	public static boolean evaluateFrameGeneration(
			long cmdBuffer,
			long inColorView, long inColorImage, int inColorFormat,
			long outInterpolatedView, long outInterpolatedImage, int outInterpolatedFormat,
			long inDepthView, long inDepthImage, int inDepthFormat,
			long inMvView, long inMvImage, int inMvFormat,
			int targetWidth, int targetHeight,
			int mvecWidth, int mvecHeight,
			float mvScaleX, float mvScaleY,
			boolean resetAccumulation
	) {
		return evaluateFrameGeneration(
				cmdBuffer,
				inColorView, inColorImage, inColorFormat,
				0L, 0L, 0,
				outInterpolatedView, outInterpolatedImage, outInterpolatedFormat,
				inDepthView, inDepthImage, inDepthFormat,
				inMvView, inMvImage, inMvFormat,
				targetWidth, targetHeight,
				mvecWidth, mvecHeight,
				mvScaleX, mvScaleY,
				resetAccumulation
		);
	}

	public static void releaseFrameGenerationFeature() {
		if (frameGenerationCreated && fnDlssgReleaseFeature != null) {
			try {
				fnDlssgReleaseFeature.invokeExact();
			} catch (Throwable ignored) {
			}
			frameGenerationCreated = false;
			currentDlssgWidth = 0;
			currentDlssgHeight = 0;
		}
	}

	public static void shutdown() {
		releaseFeature();
		releaseFrameGenerationFeature();
		if (initialized && fnShutdown != null) {
			try {
				fnShutdown.invokeExact();
			} catch (Throwable ignored) {
			}
			initialized = false;
		}
		initFailed = false;
	}
}
