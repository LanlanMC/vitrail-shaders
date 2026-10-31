#include "vitrail_dlss.h"
#include "ngx_types.h"

#include <windows.h>
#include <cstdio>
#include <cstring>

// Function pointer typedefs matching nvngx.dll exports
typedef NVSDK_NGX_Result (NVSDK_CONV *PFN_NVSDK_NGX_VULKAN_Init_Ext)(
    unsigned long long InApplicationId,
    const wchar_t *InApplicationDataPath,
    VkInstance InInstance,
    VkPhysicalDevice InPD,
    VkDevice InDevice,
    NVSDK_NGX_Version InSDKVersion,
    const NVSDK_NGX_Parameter *InParameters
);

typedef NVSDK_NGX_Result (NVSDK_CONV *PFN_NVSDK_NGX_VULKAN_GetCapabilityParameters)(
    NVSDK_NGX_Parameter **OutParameters
);

typedef NVSDK_NGX_Result (NVSDK_CONV *PFN_NVSDK_NGX_VULKAN_AllocateParameters)(
    NVSDK_NGX_Parameter **OutParameters
);

typedef NVSDK_NGX_Result (NVSDK_CONV *PFN_NVSDK_NGX_VULKAN_DestroyParameters)(
    NVSDK_NGX_Parameter *InParameters
);

typedef NVSDK_NGX_Result (NVSDK_CONV *PFN_NVSDK_NGX_VULKAN_CreateFeature)(
    VkCommandBuffer InCmdBuffer,
    NVSDK_NGX_Feature InFeatureID,
    NVSDK_NGX_Parameter *InParameters,
    NVSDK_NGX_Handle **OutHandle
);

typedef NVSDK_NGX_Result (NVSDK_CONV *PFN_NVSDK_NGX_VULKAN_EvaluateFeature)(
    VkCommandBuffer InCmdList,
    const NVSDK_NGX_Handle *InFeatureHandle,
    const NVSDK_NGX_Parameter *InParameters,
    PFN_NVSDK_NGX_ProgressCallback InCallback
);

typedef NVSDK_NGX_Result (NVSDK_CONV *PFN_NVSDK_NGX_VULKAN_ReleaseFeature)(
    NVSDK_NGX_Handle *InHandle
);

typedef NVSDK_NGX_Result (NVSDK_CONV *PFN_NVSDK_NGX_VULKAN_Shutdown1)(
    VkDevice InDevice
);

typedef NVSDK_NGX_Result (NVSDK_CONV *PFN_NVSDK_NGX_VULKAN_RequiredExtensions)(
    unsigned int *OutInstanceExtCount,
    const char ***OutInstanceExts,
    unsigned int *OutDeviceExtCount,
    const char ***OutDeviceExts
);

// Global state
static HMODULE g_hNvngx = nullptr;
static PFN_NVSDK_NGX_VULKAN_Init_Ext fn_Init_Ext = nullptr;
static PFN_NVSDK_NGX_VULKAN_GetCapabilityParameters fn_GetCapabilityParameters = nullptr;
static PFN_NVSDK_NGX_VULKAN_AllocateParameters fn_AllocateParameters = nullptr;
static PFN_NVSDK_NGX_VULKAN_DestroyParameters fn_DestroyParameters = nullptr;
static PFN_NVSDK_NGX_VULKAN_CreateFeature fn_CreateFeature = nullptr;
static PFN_NVSDK_NGX_VULKAN_EvaluateFeature fn_EvaluateFeature = nullptr;
static PFN_NVSDK_NGX_VULKAN_ReleaseFeature fn_ReleaseFeature = nullptr;
static PFN_NVSDK_NGX_VULKAN_Shutdown1 fn_Shutdown1 = nullptr;
static PFN_NVSDK_NGX_VULKAN_RequiredExtensions fn_RequiredExtensions = nullptr;

static VkDevice g_vkDevice = nullptr;
static NVSDK_NGX_Parameter* g_pCapabilityParameters = nullptr;
static NVSDK_NGX_Parameter* g_pParameters = nullptr;
static NVSDK_NGX_Handle* g_pFeatureHandle = nullptr;

static uint32_t g_currentRenderWidth = 0;
static uint32_t g_currentRenderHeight = 0;
static uint32_t g_currentTargetWidth = 0;
static uint32_t g_currentTargetHeight = 0;
static float g_currentSharpness = 0.0f;
static int s_evalErrorCount = 0;

static HMODULE LoadNvngxLibrary() {
    if (g_hNvngx) return g_hNvngx;

    // 1. Try standard DLL search path (System32 / application directory)
    HMODULE hMod = LoadLibraryW(L"nvngx.dll");
    if (hMod) return hMod;

    // 2. Query DriverStore / Registry if standard search fails
    HKEY hKey;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\NVIDIA Corporation\\Global\\NGXCore", 0, KEY_READ, &hKey) == ERROR_SUCCESS) {
        wchar_t path[MAX_PATH];
        DWORD size = sizeof(path);
        // NVIDIA driver stores "FullPath" (directory where nvngx.dll resides) or "Path"
        if (RegQueryValueExW(hKey, L"FullPath", nullptr, nullptr, (LPBYTE)path, &size) == ERROR_SUCCESS ||
            RegQueryValueExW(hKey, L"Path", nullptr, nullptr, (LPBYTE)path, &size) == ERROR_SUCCESS) {
            wchar_t fullPath[MAX_PATH];
            swprintf_s(fullPath, L"%s\\nvngx.dll", path);
            hMod = LoadLibraryExW(fullPath, nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
            if (!hMod) {
                swprintf_s(fullPath, L"%s\\_nvngx.dll", path);
                hMod = LoadLibraryExW(fullPath, nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
            }
        }
        RegCloseKey(hKey);
    }
    return hMod;
}

static bool ResolveProcAddresses() {
    if (!g_hNvngx) {
        g_hNvngx = LoadNvngxLibrary();
        if (!g_hNvngx) return false;
    }

    fn_Init_Ext = (PFN_NVSDK_NGX_VULKAN_Init_Ext)GetProcAddress(g_hNvngx, "NVSDK_NGX_VULKAN_Init_Ext");
    fn_GetCapabilityParameters = (PFN_NVSDK_NGX_VULKAN_GetCapabilityParameters)GetProcAddress(g_hNvngx, "NVSDK_NGX_VULKAN_GetCapabilityParameters");
    fn_AllocateParameters = (PFN_NVSDK_NGX_VULKAN_AllocateParameters)GetProcAddress(g_hNvngx, "NVSDK_NGX_VULKAN_AllocateParameters");
    fn_DestroyParameters = (PFN_NVSDK_NGX_VULKAN_DestroyParameters)GetProcAddress(g_hNvngx, "NVSDK_NGX_VULKAN_DestroyParameters");
    fn_CreateFeature = (PFN_NVSDK_NGX_VULKAN_CreateFeature)GetProcAddress(g_hNvngx, "NVSDK_NGX_VULKAN_CreateFeature");
    fn_EvaluateFeature = (PFN_NVSDK_NGX_VULKAN_EvaluateFeature)GetProcAddress(g_hNvngx, "NVSDK_NGX_VULKAN_EvaluateFeature");
    fn_ReleaseFeature = (PFN_NVSDK_NGX_VULKAN_ReleaseFeature)GetProcAddress(g_hNvngx, "NVSDK_NGX_VULKAN_ReleaseFeature");
    fn_Shutdown1 = (PFN_NVSDK_NGX_VULKAN_Shutdown1)GetProcAddress(g_hNvngx, "NVSDK_NGX_VULKAN_Shutdown1");
    fn_RequiredExtensions = (PFN_NVSDK_NGX_VULKAN_RequiredExtensions)GetProcAddress(g_hNvngx, "NVSDK_NGX_VULKAN_RequiredExtensions");

    return fn_Init_Ext && fn_GetCapabilityParameters && fn_AllocateParameters &&
           fn_DestroyParameters && fn_CreateFeature && fn_EvaluateFeature &&
           fn_ReleaseFeature && fn_Shutdown1;
}

static NVSDK_NGX_Resource_VK CreateVkResource(
    void* view, uint64_t image, uint32_t format, uint32_t width, uint32_t height, bool readWrite, bool isDepth
) {
    NVSDK_NGX_Resource_VK res = {};
    res.Type = NVSDK_NGX_RESOURCE_VK_TYPE_VK_IMAGEVIEW;
    res.ReadWrite = readWrite;
    res.Resource.ImageViewInfo.ImageView = (VkImageView)view;
    res.Resource.ImageViewInfo.Image = (VkImage)image;
    res.Resource.ImageViewInfo.Format = (VkFormat)format;
    res.Resource.ImageViewInfo.Width = width;
    res.Resource.ImageViewInfo.Height = height;
    res.Resource.ImageViewInfo.SubresourceRange.aspectMask = isDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
    res.Resource.ImageViewInfo.SubresourceRange.baseMipLevel = 0;
    res.Resource.ImageViewInfo.SubresourceRange.levelCount = 1;
    res.Resource.ImageViewInfo.SubresourceRange.baseArrayLayer = 0;
    res.Resource.ImageViewInfo.SubresourceRange.layerCount = 1;
    return res;
}

int vitrail_dlss_is_supported() {
    return ResolveProcAddresses() ? 1 : 0;
}

int vitrail_dlss_init(
    uint64_t appId,
    const wchar_t* appDataPath,
    void* vkInstance,
    void* vkPhysicalDevice,
    void* vkDevice
) {
    if (!ResolveProcAddresses()) return -1;
    g_vkDevice = (VkDevice)vkDevice;

    NVSDK_NGX_Result res = fn_Init_Ext(
        appId,
        appDataPath,
        (VkInstance)vkInstance,
        (VkPhysicalDevice)vkPhysicalDevice,
        (VkDevice)vkDevice,
        NVSDK_NGX_Version_API,
        nullptr
    );

    if (NVSDK_NGX_FAILED(res)) {
        if (fn_RequiredExtensions) {
            unsigned int instCount = 0;
            const char** instExts = nullptr;
            unsigned int devCount = 0;
            const char** devExts = nullptr;
            if (NVSDK_NGX_SUCCEED(fn_RequiredExtensions(&instCount, &instExts, &devCount, &devExts))) {
                fprintf(stderr, "[Vitrail DLSS] Required Vulkan Device Extensions (%u):\n", devCount);
                for (unsigned int i = 0; i < devCount; i++) {
                    fprintf(stderr, "  - %s\n", devExts[i]);
                }
            }
        }
        return (int)res;
    }

    res = fn_GetCapabilityParameters(&g_pCapabilityParameters);
    if (NVSDK_NGX_FAILED(res)) return (int)res;

    int isDlssSupported = 0;
    NVSDK_NGX_Result getRes = g_pCapabilityParameters->Get(NVSDK_NGX_Parameter_SuperSampling_Available, &isDlssSupported);
    if (NVSDK_NGX_FAILED(getRes) || !isDlssSupported) {
        int initResult = 0;
        g_pCapabilityParameters->Get(NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, &initResult);
        return initResult != 0 ? initResult : -2;
    }

    res = fn_AllocateParameters(&g_pParameters);
    if (NVSDK_NGX_FAILED(res)) return (int)res;

    return 0;
}

int vitrail_dlss_get_optimal_settings(
    uint32_t targetWidth,
    uint32_t targetHeight,
    int perfQualityValue,
    uint32_t* outRenderWidth,
    uint32_t* outRenderHeight,
    float* outSharpness,
    uint32_t* outMinRenderWidth,
    uint32_t* outMinRenderHeight
) {
    if (!g_pCapabilityParameters) return -1;

    g_pCapabilityParameters->Set(NVSDK_NGX_Parameter_Width, targetWidth);
    g_pCapabilityParameters->Set(NVSDK_NGX_Parameter_Height, targetHeight);
    g_pCapabilityParameters->Set(NVSDK_NGX_Parameter_PerfQualityValue, perfQualityValue);

    unsigned int renderW = 0, renderH = 0, minW = 0, minH = 0;
    float sharpness = 0.0f;

    g_pCapabilityParameters->Get(NVSDK_NGX_Parameter_DLSS_Optimal_Render_Width, &renderW);
    g_pCapabilityParameters->Get(NVSDK_NGX_Parameter_DLSS_Optimal_Render_Height, &renderH);
    g_pCapabilityParameters->Get(NVSDK_NGX_Parameter_Sharpness, &sharpness);
    g_pCapabilityParameters->Get(NVSDK_NGX_Parameter_DLSS_Render_Width_Min, &minW);
    g_pCapabilityParameters->Get(NVSDK_NGX_Parameter_DLSS_Render_Height_Min, &minH);

    if (outRenderWidth) *outRenderWidth = renderW;
    if (outRenderHeight) *outRenderHeight = renderH;
    if (outSharpness) *outSharpness = sharpness;
    if (outMinRenderWidth) *outMinRenderWidth = minW;
    if (outMinRenderHeight) *outMinRenderHeight = minH;

    return (renderW > 0 && renderH > 0) ? 0 : -2;
}

int vitrail_dlss_create_feature(
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
) {
    if (!g_pParameters || !fn_CreateFeature) return -1;

    if (g_pFeatureHandle) {
        fn_ReleaseFeature(g_pFeatureHandle);
        g_pFeatureHandle = nullptr;
    }

    uint32_t flags = NVSDK_NGX_DLSS_Feature_Flags_MVLowRes;
    if (isHdr) flags |= NVSDK_NGX_DLSS_Feature_Flags_IsHDR;
    if (depthInverted) flags |= NVSDK_NGX_DLSS_Feature_Flags_DepthInverted;
    if (autoExposure) flags |= NVSDK_NGX_DLSS_Feature_Flags_AutoExposure;
    // Note: DoSharpening flag is intentionally omitted. Built-in DLSS sharpening is deprecated
    // and causes sub-pixel jitter amplification (screen vibrating/shaking). Sharpening is
    // performed via post-upscale RCAS on the reconstructed frame.

    g_currentSharpness = sharpness;

    g_pParameters->Set(NVSDK_NGX_Parameter_CreationNodeMask, 1u);
    g_pParameters->Set(NVSDK_NGX_Parameter_VisibilityNodeMask, 1u);
    g_pParameters->Set(NVSDK_NGX_Parameter_Width, renderWidth);
    g_pParameters->Set(NVSDK_NGX_Parameter_Height, renderHeight);
    g_pParameters->Set(NVSDK_NGX_Parameter_OutWidth, targetWidth);
    g_pParameters->Set(NVSDK_NGX_Parameter_OutHeight, targetHeight);
    g_pParameters->Set(NVSDK_NGX_Parameter_PerfQualityValue, perfQualityValue);
    g_pParameters->Set(NVSDK_NGX_Parameter_DLSS_Feature_Create_Flags, flags);

    unsigned int presetVal = (unsigned int)(preset > 0 ? preset : 0);
    g_pParameters->Set(NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, presetVal);
    g_pParameters->Set(NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality, presetVal);
    g_pParameters->Set(NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced, presetVal);
    g_pParameters->Set(NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance, presetVal);
    g_pParameters->Set(NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance, presetVal);
    g_pParameters->Set(NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraQuality, presetVal);

    if (g_pCapabilityParameters) {
        g_pCapabilityParameters->Set(NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA, presetVal);
        g_pCapabilityParameters->Set(NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality, presetVal);
        g_pCapabilityParameters->Set(NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced, presetVal);
        g_pCapabilityParameters->Set(NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance, presetVal);
        g_pCapabilityParameters->Set(NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance, presetVal);
        g_pCapabilityParameters->Set(NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraQuality, presetVal);
    }

    NVSDK_NGX_Result res = fn_CreateFeature(
        (VkCommandBuffer)vkCommandBuffer,
        NVSDK_NGX_Feature_SuperSampling,
        g_pParameters,
        &g_pFeatureHandle
    );

    if (NVSDK_NGX_SUCCEED(res)) {
        g_currentRenderWidth = renderWidth;
        g_currentRenderHeight = renderHeight;
        g_currentTargetWidth = targetWidth;
        g_currentTargetHeight = targetHeight;
        s_evalErrorCount = 0;
        return 0;
    }
    fprintf(stderr, "[Vitrail DLSS] fn_CreateFeature failed: 0x%08X (render=%ux%u, target=%ux%u, preset=%u)\n",
            (unsigned int)res, renderWidth, renderHeight, targetWidth, targetHeight, presetVal);
    return (int)res;
}

int vitrail_dlss_evaluate(
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
) {
    if (!g_pFeatureHandle || !g_pParameters || !fn_EvaluateFeature) return -1;

    NVSDK_NGX_Resource_VK resColorIn = CreateVkResource(inColorView, inColorImage, inColorFormat, g_currentRenderWidth, g_currentRenderHeight, false, false);
    NVSDK_NGX_Resource_VK resColorOut = CreateVkResource(outColorView, outColorImage, outColorFormat, g_currentTargetWidth, g_currentTargetHeight, true, false);
    NVSDK_NGX_Resource_VK resDepth = CreateVkResource(inDepthView, inDepthImage, inDepthFormat, g_currentRenderWidth, g_currentRenderHeight, false, true);

    g_pParameters->Set(NVSDK_NGX_Parameter_Color, &resColorIn);
    g_pParameters->Set(NVSDK_NGX_Parameter_Output, &resColorOut);
    g_pParameters->Set(NVSDK_NGX_Parameter_Depth, &resDepth);

    NVSDK_NGX_Resource_VK resMv = {};
    if (inMotionVectorsView && inMotionVectorsImage) {
        resMv = CreateVkResource(inMotionVectorsView, inMotionVectorsImage, inMvFormat, g_currentRenderWidth, g_currentRenderHeight, false, false);
        g_pParameters->Set(NVSDK_NGX_Parameter_MotionVectors, &resMv);
    } else {
        g_pParameters->Set(NVSDK_NGX_Parameter_MotionVectors, (void*)nullptr);
    }
    g_pParameters->Set(NVSDK_NGX_Parameter_Jitter_Offset_X, jitterX);
    g_pParameters->Set(NVSDK_NGX_Parameter_Jitter_Offset_Y, jitterY);
    g_pParameters->Set(NVSDK_NGX_Parameter_MV_Scale_X, mvScaleX);
    g_pParameters->Set(NVSDK_NGX_Parameter_MV_Scale_Y, mvScaleY);
    g_pParameters->Set(NVSDK_NGX_Parameter_Reset, resetAccumulation ? 1 : 0);

    g_pParameters->Set(NVSDK_NGX_Parameter_Width, g_currentRenderWidth);
    g_pParameters->Set(NVSDK_NGX_Parameter_Height, g_currentRenderHeight);
    g_pParameters->Set(NVSDK_NGX_Parameter_DLSS_Render_Subrect_Dimensions_Width, g_currentRenderWidth);
    g_pParameters->Set(NVSDK_NGX_Parameter_DLSS_Render_Subrect_Dimensions_Height, g_currentRenderHeight);
    g_pParameters->Set(NVSDK_NGX_Parameter_Sharpness, 0.0f);

    NVSDK_NGX_Resource_VK resReactive = {};
    if (inReactiveMaskView && inReactiveMaskImage) {
        resReactive = CreateVkResource(inReactiveMaskView, inReactiveMaskImage, inReactiveFormat, g_currentRenderWidth, g_currentRenderHeight, 0, false);
        g_pParameters->Set(NVSDK_NGX_Parameter_TransparencyMask, &resReactive);
    } else {
        g_pParameters->Set(NVSDK_NGX_Parameter_TransparencyMask, (void*)nullptr);
    }

    NVSDK_NGX_Result res = fn_EvaluateFeature(
        (VkCommandBuffer)vkCommandBuffer,
        g_pFeatureHandle,
        g_pParameters,
        nullptr
    );

    if (NVSDK_NGX_FAILED(res)) {
        if (s_evalErrorCount++ < 5) {
            fprintf(stderr, "[Vitrail DLSS] EvaluateFeature failed: 0x%08X (render=%ux%u, target=%ux%u, cmd=%p)\n",
                    (unsigned int)res, g_currentRenderWidth, g_currentRenderHeight,
                    g_currentTargetWidth, g_currentTargetHeight, vkCommandBuffer);
        }
        return (int)res;
    }
    s_evalErrorCount = 0;

    return 0;
}

void vitrail_dlss_release_feature() {
    __try {
        if (g_pFeatureHandle && fn_ReleaseFeature) {
            fn_ReleaseFeature(g_pFeatureHandle);
            g_pFeatureHandle = nullptr;
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        g_pFeatureHandle = nullptr;
    }
    g_currentRenderWidth = 0;
    g_currentRenderHeight = 0;
    g_currentTargetWidth = 0;
    g_currentTargetHeight = 0;
}

void vitrail_dlss_shutdown() {
    vitrail_dlss_release_feature();

    __try {
        if (g_pParameters && fn_DestroyParameters) {
            fn_DestroyParameters(g_pParameters);
            g_pParameters = nullptr;
        }
        if (g_pCapabilityParameters && fn_DestroyParameters) {
            fn_DestroyParameters(g_pCapabilityParameters);
            g_pCapabilityParameters = nullptr;
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        fprintf(stderr, "[Vitrail DLSS] Exception caught while destroying parameters\n");
        g_pParameters = nullptr;
        g_pCapabilityParameters = nullptr;
    }

    // Note: fn_Shutdown1(VkDevice) is intentionally omitted during client shutdown
    // because the Vulkan driver / device is already in the teardown phase when NeoForge
    // fires ClientStoppingEvent, causing an access violation (0xC0000005) inside nvoglv64.dll.
    // Releasing features and parameter blocks is sufficient for clean teardown.
    g_vkDevice = nullptr;
    g_pCapabilityParameters = nullptr;
}
