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

	private static MethodHandle fnIsSupported;
	private static MethodHandle fnInit;
	private static MethodHandle fnGetOptimalSettings;
	private static MethodHandle fnCreateFeature;
	private static MethodHandle fnEvaluate;
	private static MethodHandle fnReleaseFeature;
	private static MethodHandle fnShutdown;

	static {
		loadBridge();
	}

	private DlssBridge() {
	}

	private static void loadBridge() {
		try {
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
			fnShutdown = downcall(linker, lookup, "vitrail_dlss_shutdown",
					FunctionDescriptor.ofVoid());

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

	private static SymbolLookup findSymbolLookup() {
		// 1. Try loading directly from working directory or relative build path
		Path[] searchPaths = new Path[] {
				Path.of(DLL_NAME),
				Path.of("mods", DLL_NAME),
				Path.of("native/dlss/build/Release", DLL_NAME),
				Vitrail.platform().gameDirectory().resolve(DLL_NAME),
				Vitrail.platform().gameDirectory().resolve("mods").resolve(DLL_NAME)
		};

		for (Path p : searchPaths) {
			if (Files.exists(p)) {
				try {
					return SymbolLookup.libraryLookup(p.toAbsolutePath(), Arena.global());
				} catch (Exception ignored) {
				}
			}
		}

		// 2. Try extracting from jar resources (natives/windows/x86_64/vitrail_dlss.dll)
		try {
			String resPath = "/natives/windows/x86_64/" + DLL_NAME;
			try (var in = DlssBridge.class.getResourceAsStream(resPath)) {
				if (in != null) {
					Path extractDir = Vitrail.platform().gameDirectory().resolve(".vitrail-natives");
					Files.createDirectories(extractDir);
					Path extracted = extractDir.resolve(DLL_NAME);
					Files.copy(in, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					return SymbolLookup.libraryLookup(extracted.toAbsolutePath(), Arena.global());
				}
			}
		} catch (Exception ignored) {
		}

		// 3. Try loading from system loader
		try {
			return SymbolLookup.loaderLookup();
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

	public static boolean init(VulkanDevice device, Path logDir) {
		if (!available || fnInit == null || initFailed) return false;
		if (initialized) return true;

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

			int res = (int) fnInit.invokeExact(0x2602L, pathSeg, instSeg, pdSeg, devSeg);
			if (res == 0 || res == 1) {
				initialized = true;
				initFailed = false;
				Vitrail.logger().info("NVIDIA NGX Vulkan initialized successfully for Vitrail");
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

	public static void shutdown() {
		releaseFeature();
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
