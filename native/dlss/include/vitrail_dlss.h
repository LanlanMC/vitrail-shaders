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
 * Shuts down NGX Vulkan and cleans up parameters.
 */
VITRAIL_API void vitrail_dlss_shutdown();
