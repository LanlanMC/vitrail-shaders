#pragma once

#include <cstdint>

#if defined(_WIN32)
#define VITRAIL_API extern "C" __declspec(dllexport)
#else
#define VITRAIL_API extern "C" __attribute__((visibility("default")))
#endif

/**
 * Checks if the DLSS native driver runtime (nvngx.dll) is discoverable on this machine.
 * Returns 1 if present, 0 otherwise.
 */
VITRAIL_API int vitrail_dlss_is_supported();

/**
 * Initializes NVIDIA NGX for Vulkan.
 * @param appId Unique Application ID (can be 0 or an assigned project ID)
 * @param appDataPath Wide-character log/scratch directory path
 * @param vkInstance VkInstance handle
 * @param vkPhysicalDevice VkPhysicalDevice handle
 * @param vkDevice VkDevice handle
 * @return 0 on success, or non-zero error code.
 */
VITRAIL_API int vitrail_dlss_init(
    uint64_t appId,
    const wchar_t* appDataPath,
    void* vkInstance,
    void* vkPhysicalDevice,
    void* vkDevice
);

/**
 * Queries NGX for optimal rendering resolution and constraints for a given target resolution and quality mode.
 * @param targetWidth Target window width
 * @param targetHeight Target window height
 * @param perfQualityValue 0: MaxPerf, 1: Balanced, 2: MaxQuality, 3: UltraPerf, 4: UltraQuality, 5: DLAA
 * @param outRenderWidth Output: recommended render width
 * @param outRenderHeight Output: recommended render height
 * @param outSharpness Output: recommended default sharpness
 * @param outMinRenderWidth Output: min dynamic render width
 * @param outMinRenderHeight Output: min dynamic render height
 * @return 0 on success, or non-zero error code.
 */
VITRAIL_API int vitrail_dlss_get_optimal_settings(
    uint32_t targetWidth,
    uint32_t targetHeight,
    int perfQualityValue,
    uint32_t* outRenderWidth,
    uint32_t* outRenderHeight,
    float* outSharpness,
    uint32_t* outMinRenderWidth,
    uint32_t* outMinRenderHeight
);

/**
 * Creates the DLSS SuperResolution feature.
 * @param vkCommandBuffer VkCommandBuffer for initialization upload work
 * @param renderWidth Low-res render width
 * @param renderHeight Low-res render height
 * @param targetWidth Target output width
 * @param targetHeight Target output height
 * @param perfQualityValue DLSS quality mode value (0: MaxPerf, 1: Balanced, 2: MaxQuality, 3: UltraPerf, 5: DLAA)
 * @param isHdr Non-zero if color input is HDR
 * @param depthInverted Non-zero if depth is inverted (Reversed-Z: 1 near, 0 far)
 * @param autoExposure Non-zero if DLSS should compute exposure automatically
 * @param sharpness Sharpness bias (-1.0 to 1.0)
 * @param preset Model preset hint (0: Default, 1-7: A-G, 10: J, 11: K, 12: L, 13: M)
 * @return 0 on success, or non-zero error code.
 */
VITRAIL_API int vitrail_dlss_create_feature(
    void* vkCommandBuffer,
    uint32_t renderWidth,
    uint32_t renderHeight,
    uint32_t targetWidth,
    uint32_t targetHeight,
    int perfQualityValue,
    int isHdr,
    int depthInverted,
    int autoExposure,
    float sharpness,
    int preset
);

/**
 * Evaluates DLSS on the recorded Vulkan command buffer.
 * All resources must be in appropriate layouts (e.g. SHADER_READ_ONLY_OPTIMAL for inputs, COLOR_ATTACHMENT/GENERAL for output).
 * @return 0 on success, or non-zero error code.
 */
VITRAIL_API int vitrail_dlss_evaluate(
    void* vkCommandBuffer,
    void* inColorView, uint64_t inColorImage, uint32_t inColorFormat,
    void* outColorView, uint64_t outColorImage, uint32_t outColorFormat,
    void* inDepthView, uint64_t inDepthImage, uint32_t inDepthFormat,
    void* inMotionVectorsView, uint64_t inMotionVectorsImage, uint32_t inMvFormat,
    void* inReactiveMaskView, uint64_t inReactiveMaskImage, uint32_t inReactiveFormat,
    float jitterX, float jitterY,
    float mvScaleX, float mvScaleY,
    float sharpness,
    int resetAccumulation
);

/**
 * Releases the currently active DLSS feature handle.
 */
VITRAIL_API void vitrail_dlss_release_feature();

/**
 * Checks if DLSS Frame Generation (DLSS-G) is supported on this machine and driver.
 * Returns 1 if supported, 0 otherwise.
 */
VITRAIL_API int vitrail_dlssg_is_supported();

/**
 * Initializes NVIDIA NGX for Vulkan with an optional custom DLSS-G dll path (e.g. from DLSS Swapper).
 * @param appId Unique Application ID (can be 0 or an assigned project ID)
 * @param appDataPath Wide-character log/scratch directory path
 * @param vkInstance VkInstance handle
 * @param vkPhysicalDevice VkPhysicalDevice handle
 * @param vkDevice VkDevice handle
 * @param customDlssGPath Optional wide-character path to nvngx_dlssg.dll
 * @return 0 on success, or non-zero error code.
 */
VITRAIL_API int vitrail_dlss_init_v2(
    uint64_t appId,
    const wchar_t* appDataPath,
    void* vkInstance,
    void* vkPhysicalDevice,
    void* vkDevice,
    const wchar_t* customDlssGPath
);

/**
 * Creates the DLSS Frame Generation feature.
 * @param vkCommandBuffer VkCommandBuffer for initialization work
 * @param width Target window width
 * @param height Target window height
 * @param backbufferFormat Vulkan format of the backbuffer (e.g. VK_FORMAT_R8G8B8A8_UNORM = 37)
 * @param isHdr Non-zero if color input is HDR
 * @param depthInverted Non-zero if depth is inverted (Reversed-Z)
 * @return 0 on success, or non-zero error code.
 */
VITRAIL_API int vitrail_dlssg_create_feature(
    void* vkCommandBuffer,
    uint32_t width,
    uint32_t height,
    int isHdr,
    int depthInverted,
    uint32_t backbufferFormat
);

/**
 * Evaluates DLSS Frame Generation on the command buffer.
 * Interpolates an additional intermediate frame into outInterpolatedView.
 * @return 0 on success, or non-zero error code.
 */
VITRAIL_API int vitrail_dlssg_evaluate(
    void* vkCommandBuffer,
    void* inColorView, uint64_t inColorImage, uint32_t inColorFormat,
    void* outInterpolatedView, uint64_t outInterpolatedImage, uint32_t outInterpolatedFormat,
    void* inDepthView, uint64_t inDepthImage, uint32_t inDepthFormat,
    void* inMotionVectorsView, uint64_t inMotionVectorsImage, uint32_t inMvFormat,
    uint32_t targetWidth, uint32_t targetHeight,
    uint32_t mvecWidth, uint32_t mvecHeight,
    float mvScaleX, float mvScaleY,
    int resetAccumulation
);

/**
 * Evaluates DLSS Frame Generation on the command buffer with explicit HUDLess (pre-UI) world color input.
 * When inHudlessView is provided, DLSS-G extracts the UI overlay and composites it onto outInterpolatedView,
 * preventing UI flicker/strobing on the generated frame.
 * @return 0 on success, or non-zero error code.
 */
VITRAIL_API int vitrail_dlssg_evaluate_v2(
    void* vkCommandBuffer,
    void* inColorView, uint64_t inColorImage, uint32_t inColorFormat,
    void* inHudlessView, uint64_t inHudlessImage, uint32_t inHudlessFormat,
    void* outInterpolatedView, uint64_t outInterpolatedImage, uint32_t outInterpolatedFormat,
    void* inDepthView, uint64_t inDepthImage, uint32_t inDepthFormat,
    void* inMotionVectorsView, uint64_t inMotionVectorsImage, uint32_t inMvFormat,
    uint32_t targetWidth, uint32_t targetHeight,
    uint32_t mvecWidth, uint32_t mvecHeight,
    float mvScaleX, float mvScaleY,
    int resetAccumulation
);

/**
 * Releases the currently active DLSS Frame Generation feature handle.
 */
VITRAIL_API void vitrail_dlssg_release_feature();

/**
 * Shuts down NGX Vulkan and cleans up parameters.
 */
VITRAIL_API void vitrail_dlss_shutdown();

