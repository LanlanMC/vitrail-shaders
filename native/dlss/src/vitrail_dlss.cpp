#include "vitrail_dlss.h"
#include "ngx_types.h"

#include <windows.h>
#include <cstdio>
#include <cstring>
#include <cstdarg>

static void LogNative(const char* fmt, ...) {
    static FILE* s_log = fopen("vitrail_dlss_native.log", "a");
    if (s_log) {
        va_list args;
        va_start(args, fmt);
        vfprintf(s_log, fmt, args);
        va_end(args);
        fflush(s_log);
    }
}

// Function pointer typedefs matching nvngx.dll exports
typedef NVSDK_NGX_Result (NVSDK_CONV *PFN_NVSDK_NGX_VULKAN_Init_Ext)(
    unsigned long long InApplicationId,
    const wchar_t *InApplicationDataPath,
    VkInstance InInstance,
    VkPhysicalDevice InPD,
    VkDevice InDevice,
    NVSDK_NGX_Version InSDKVersion,
    const NVSDK_NGX_FeatureCommonInfo *InFeatureInfo
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

typedef NVSDK_NGX_Result (NVSDK_CONV *PFN_NVSDK_NGX_VULKAN_CreateFeature1)(
    VkDevice InDevice,
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
static PFN_NVSDK_NGX_VULKAN_CreateFeature1 fn_CreateFeature1 = nullptr;
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

// DLSS-G Frame Generation state
static HMODULE g_hDlssg = nullptr;
static PFN_NVSDK_NGX_VULKAN_Init_Ext fn_Dlssg_Init_Ext = nullptr;
static PFN_NVSDK_NGX_VULKAN_CreateFeature1 fn_Dlssg_CreateFeature1 = nullptr;
static PFN_NVSDK_NGX_VULKAN_CreateFeature fn_Dlssg_CreateFeature = nullptr;
static PFN_NVSDK_NGX_VULKAN_EvaluateFeature fn_Dlssg_EvaluateFeature = nullptr;
static PFN_NVSDK_NGX_VULKAN_ReleaseFeature fn_Dlssg_ReleaseFeature = nullptr;
static PFN_NVSDK_NGX_VULKAN_Shutdown1 fn_Dlssg_Shutdown1 = nullptr;
static NVSDK_NGX_Handle* g_pDlssgFeatureHandle = nullptr;
static NVSDK_NGX_Parameter* g_pDlssgParameters = nullptr;
static uint32_t g_currentDlssgWidth = 0;
static uint32_t g_currentDlssgHeight = 0;
static uint64_t g_dlssgFrameId = 1;
static int s_dlssgEvalErrorCount = 0;

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
    fn_CreateFeature1 = (PFN_NVSDK_NGX_VULKAN_CreateFeature1)GetProcAddress(g_hNvngx, "NVSDK_NGX_VULKAN_CreateFeature1");
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

int vitrail_dlss_init_v2(
    uint64_t appId,
    const wchar_t* appDataPath,
    void* vkInstance,
    void* vkPhysicalDevice,
    void* vkDevice,
    const wchar_t* customDlssGPath
) {
    if (!ResolveProcAddresses()) {
        LogNative("[Init] ResolveProcAddresses failed\n");
        return -1;
    }
    g_vkDevice = (VkDevice)vkDevice;

    LogNative("[Init] Calling fn_Init_Ext with appId=0x%llx, path=%ls, inst=%p, pd=%p, dev=%p, dlssGPath=%ls\n",
              appId, appDataPath ? appDataPath : L"<null>", vkInstance, vkPhysicalDevice, vkDevice,
              customDlssGPath ? customDlssGPath : L"<null>");

    // Build search paths for NGX so it can locate nvngx_dlss.dll and nvngx_dlssg.dll in Java/Minecraft environment
    static wchar_t s_currentDir[MAX_PATH] = { 0 };
    static wchar_t s_nativesDir[MAX_PATH] = { 0 };
    static wchar_t s_appDataParent[MAX_PATH] = { 0 };
    static wchar_t s_customDlssGDir[MAX_PATH] = { 0 };
    static wchar_t s_swapperDlssDir[MAX_PATH] = { 0 };
    const wchar_t* searchPaths[16];
    unsigned int searchPathCount = 0;

    if (GetCurrentDirectoryW(MAX_PATH, s_currentDir) > 0) {
        searchPaths[searchPathCount++] = s_currentDir;
        LogNative("[Init] SearchPath[%u] (CurrentDir): %ls\n", searchPathCount - 1, s_currentDir);

        swprintf_s(s_nativesDir, L"%s\\.vitrail-natives", s_currentDir);
        searchPaths[searchPathCount++] = s_nativesDir;
        LogNative("[Init] SearchPath[%u] (NativesDir): %ls\n", searchPathCount - 1, s_nativesDir);
    }

    if (appDataPath && appDataPath[0] != L'\0') {
        wcsncpy_s(s_appDataParent, appDataPath, MAX_PATH - 1);
        wchar_t* lastSlash = wcsrchr(s_appDataParent, L'\\');
        if (!lastSlash) lastSlash = wcsrchr(s_appDataParent, L'/');
        if (lastSlash) {
            *lastSlash = L'\0';
            searchPaths[searchPathCount++] = s_appDataParent;
            LogNative("[Init] SearchPath[%u] (AppDataParent): %ls\n", searchPathCount - 1, s_appDataParent);
        }
    }

    if (customDlssGPath && customDlssGPath[0] != L'\0') {
        wcsncpy_s(s_customDlssGDir, customDlssGPath, MAX_PATH - 1);
        wchar_t* lastSlash = wcsrchr(s_customDlssGDir, L'\\');
        if (!lastSlash) lastSlash = wcsrchr(s_customDlssGDir, L'/');
        if (lastSlash) {
            *lastSlash = L'\0';
            searchPaths[searchPathCount++] = s_customDlssGDir;
            LogNative("[Init] SearchPath[%u] (DLSS-G Swapper): %ls\n", searchPathCount - 1, s_customDlssGDir);

            // Traverse up to find DLSS Swapper's dlss folder
            wchar_t swapperDllsBase[MAX_PATH] = { 0 };
            wcsncpy_s(swapperDllsBase, s_customDlssGDir, MAX_PATH - 1);
            wchar_t* s1 = wcsrchr(swapperDllsBase, L'\\');
            if (s1) {
                *s1 = L'\0';
                wchar_t* s2 = wcsrchr(swapperDllsBase, L'\\');
                if (s2) {
                    *s2 = L'\0';
                    wchar_t dlssSearchPattern[MAX_PATH] = { 0 };
                    swprintf_s(dlssSearchPattern, L"%s\\dlss\\*", swapperDllsBase);
                    WIN32_FIND_DATAW findData;
                    HANDLE hFind = FindFirstFileW(dlssSearchPattern, &findData);
                    if (hFind != INVALID_HANDLE_VALUE) {
                        do {
                            if ((findData.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) &&
                                wcscmp(findData.cFileName, L".") != 0 &&
                                wcscmp(findData.cFileName, L"..") != 0) {
                                swprintf_s(s_swapperDlssDir, L"%s\\dlss\\%s", swapperDllsBase, findData.cFileName);
                                wchar_t testDll[MAX_PATH];
                                swprintf_s(testDll, L"%s\\nvngx_dlss.dll", s_swapperDlssDir);
                                if (GetFileAttributesW(testDll) != INVALID_FILE_ATTRIBUTES) {
                                    searchPaths[searchPathCount++] = s_swapperDlssDir;
                                    LogNative("[Init] SearchPath[%u] (DLSS Swapper dlss): %ls\n", searchPathCount - 1, s_swapperDlssDir);
                                    break;
                                }
                            }
                        } while (FindNextFileW(hFind, &findData));
                        FindClose(hFind);
                    }
                }
            }
        }
    }

    NVSDK_NGX_PathListInfo pathListInfo = {};
    pathListInfo.Path = searchPaths;
    pathListInfo.Length = searchPathCount;

    NVSDK_NGX_FeatureCommonInfo featureInfo = {};
    featureInfo.PathListInfo = pathListInfo;
    featureInfo.InternalData = nullptr;
    featureInfo.LoggingInfo.LoggingCallback = nullptr;
    featureInfo.LoggingInfo.MinimumLoggingLevel = 0;
    featureInfo.LoggingInfo.DisableOtherLoggingSinks = false;

    NVSDK_NGX_Result res = fn_Init_Ext(
        appId,
        appDataPath,
        (VkInstance)vkInstance,
        (VkPhysicalDevice)vkPhysicalDevice,
        (VkDevice)vkDevice,
        NVSDK_NGX_Version_API,
        &featureInfo
    );

    LogNative("[Init] fn_Init_Ext returned 0x%08X\n", (unsigned int)res);

    if (NVSDK_NGX_FAILED(res)) {
        if (fn_RequiredExtensions) {
            unsigned int instCount = 0;
            const char** instExts = nullptr;
            unsigned int devCount = 0;
            const char** devExts = nullptr;
            if (NVSDK_NGX_SUCCEED(fn_RequiredExtensions(&instCount, &instExts, &devCount, &devExts))) {
                for (unsigned int i = 0; i < devCount; i++) {
                    LogNative("[Init] Required DevExt: %s\n", devExts[i]);
                }
            }
        }
        return (int)res;
    }

    // Load DLSS-G fallback handle and optionally init it directly
    if (!g_hDlssg) {
        if (customDlssGPath && customDlssGPath[0] != L'\0') {
            g_hDlssg = LoadLibraryW(customDlssGPath);
            if (g_hDlssg) {
                LogNative("[Init] Loaded custom nvngx_dlssg.dll: %ls\n", customDlssGPath);
            }
        }
        if (!g_hDlssg) {
            g_hDlssg = LoadLibraryW(L"nvngx_dlssg.dll");
        }
        if (g_hDlssg) {
            fn_Dlssg_Init_Ext = (PFN_NVSDK_NGX_VULKAN_Init_Ext)GetProcAddress(g_hDlssg, "NVSDK_NGX_VULKAN_Init_Ext");
            fn_Dlssg_CreateFeature1 = (PFN_NVSDK_NGX_VULKAN_CreateFeature1)GetProcAddress(g_hDlssg, "NVSDK_NGX_VULKAN_CreateFeature1");
            fn_Dlssg_CreateFeature = (PFN_NVSDK_NGX_VULKAN_CreateFeature)GetProcAddress(g_hDlssg, "NVSDK_NGX_VULKAN_CreateFeature");
            fn_Dlssg_EvaluateFeature = (PFN_NVSDK_NGX_VULKAN_EvaluateFeature)GetProcAddress(g_hDlssg, "NVSDK_NGX_VULKAN_EvaluateFeature");
            fn_Dlssg_ReleaseFeature = (PFN_NVSDK_NGX_VULKAN_ReleaseFeature)GetProcAddress(g_hDlssg, "NVSDK_NGX_VULKAN_ReleaseFeature");
            fn_Dlssg_Shutdown1 = (PFN_NVSDK_NGX_VULKAN_Shutdown1)GetProcAddress(g_hDlssg, "NVSDK_NGX_VULKAN_Shutdown1");

            LogNative("[Init] Loaded DLSS-G: g_hDlssg=%p, dlssg_init=%p, dlssg_f1=%p, dlssg_f=%p\n",
                      g_hDlssg, fn_Dlssg_Init_Ext, fn_Dlssg_CreateFeature1, fn_Dlssg_CreateFeature);

            if (fn_Dlssg_Init_Ext) {
                NVSDK_NGX_Result dlssgInitRes = fn_Dlssg_Init_Ext(
                    appId,
                    appDataPath,
                    (VkInstance)vkInstance,
                    (VkPhysicalDevice)vkPhysicalDevice,
                    (VkDevice)vkDevice,
                    NVSDK_NGX_Version_API,
                    &featureInfo
                );
                LogNative("[Init] fn_Dlssg_Init_Ext returned 0x%08X\n", (unsigned int)dlssgInitRes);
            }
        }
    }

    res = fn_GetCapabilityParameters(&g_pCapabilityParameters);
    LogNative("[Init] fn_GetCapabilityParameters returned 0x%08X, pCap=%p\n", (unsigned int)res, g_pCapabilityParameters);
    if (NVSDK_NGX_FAILED(res)) return (int)res;

    int isDlssSupported = 0;
    NVSDK_NGX_Result getRes = g_pCapabilityParameters->Get(NVSDK_NGX_Parameter_SuperSampling_Available, &isDlssSupported);
    int initResult = 0;
    g_pCapabilityParameters->Get(NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult, &initResult);
    LogNative("[Init] SuperSampling_Available=%d (getRes=0x%08X), initResult=0x%08X\n",
              isDlssSupported, (unsigned int)getRes, (unsigned int)initResult);

    if (NVSDK_NGX_FAILED(getRes) || !isDlssSupported) {
        LogNative("[Init] Warning: SuperSampling not available (initResult=0x%08X)\n", (unsigned int)initResult);
        if (!g_hDlssg) {
            return initResult != 0 ? initResult : -2;
        }
    }

    res = fn_AllocateParameters(&g_pParameters);
    LogNative("[Init] fn_AllocateParameters returned 0x%08X, pParams=%p\n", (unsigned int)res, g_pParameters);
    if (NVSDK_NGX_FAILED(res)) return (int)res;

    return 0;
}

int vitrail_dlss_init(
    uint64_t appId,
    const wchar_t* appDataPath,
    void* vkInstance,
    void* vkPhysicalDevice,
    void* vkDevice
) {
    return vitrail_dlss_init_v2(appId, appDataPath, vkInstance, vkPhysicalDevice, vkDevice, nullptr);
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
        resReactive = CreateVkResource(inReactiveMaskView, inReactiveMaskImage, inReactiveFormat, g_currentRenderWidth, g_currentRenderHeight, false, false);
        g_pParameters->Set(NVSDK_NGX_Parameter_DLSS_Input_Bias_Current_Color_Mask, &resReactive);
        g_pParameters->Set(NVSDK_NGX_Parameter_TransparencyMask, &resReactive);
    } else {
        g_pParameters->Set(NVSDK_NGX_Parameter_DLSS_Input_Bias_Current_Color_Mask, (void*)nullptr);
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

void vitrail_dlssg_release_feature() {
    __try {
        if (g_pDlssgFeatureHandle) {
            if (fn_Dlssg_ReleaseFeature) {
                fn_Dlssg_ReleaseFeature(g_pDlssgFeatureHandle);
            } else if (fn_ReleaseFeature) {
                fn_ReleaseFeature(g_pDlssgFeatureHandle);
            }
            g_pDlssgFeatureHandle = nullptr;
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        g_pDlssgFeatureHandle = nullptr;
    }
    g_currentDlssgWidth = 0;
    g_currentDlssgHeight = 0;
}

int vitrail_dlssg_is_supported() {
    if (!g_pCapabilityParameters) return 0;
    int isSupported = 0;
    NVSDK_NGX_Result res = g_pCapabilityParameters->Get(NVSDK_NGX_Parameter_FrameGeneration_Available, &isSupported);
    if (NVSDK_NGX_SUCCEED(res) && isSupported) {
        return 1;
    }
    res = g_pCapabilityParameters->Get("DLSSG.Available", &isSupported);
    if (NVSDK_NGX_SUCCEED(res) && isSupported) {
        return 1;
    }
    if (g_hDlssg) {
        return 1;
    }
    return 0;
}

int vitrail_dlssg_create_feature(
    void* vkCommandBuffer,
    uint32_t width,
    uint32_t height,
    int isHdr,
    int depthInverted,
    uint32_t backbufferFormat
) {
    if (!fn_CreateFeature && !fn_Dlssg_CreateFeature && !fn_Dlssg_CreateFeature1 && !fn_CreateFeature1) return -1;

    vitrail_dlssg_release_feature();

    if (!g_pDlssgParameters) {
        NVSDK_NGX_Result aRes = fn_AllocateParameters(&g_pDlssgParameters);
        if (NVSDK_NGX_FAILED(aRes)) return (int)aRes;
    }

    uint32_t vkFmt = backbufferFormat != 0 ? backbufferFormat : 37; // VK_FORMAT_R8G8B8A8_UNORM = 37

    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_CreationNodeMask, 1u);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_VisibilityNodeMask, 1u);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_Width, width);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_Height, height);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_OutWidth, width);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_OutHeight, height);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_Width, width);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_Height, height);
    g_pDlssgParameters->Set("DLSSG.Width", width);
    g_pDlssgParameters->Set("DLSSG.Height", height);
    g_pDlssgParameters->Set("DLSSG.InternalWidth", width);
    g_pDlssgParameters->Set("DLSSG.InternalHeight", height);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_BackbufferFormat, vkFmt);
    g_pDlssgParameters->Set("DLSSG.BackbufferFormat", vkFmt);
    g_pDlssgParameters->Set("DLSSG.DynamicResolution", 0u);
    g_pDlssgParameters->Set("DLSSG.UseReflexMatrices", 0u);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_MultiFrameCountMax, 1u);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_MultiFrameCount, 1u);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_DepthInverted, depthInverted ? 1 : 0);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_Dynamic_ReflexDriven, 0);
    g_pDlssgParameters->Set("DLSSG.Dynamic.ReflexDriven", 0u);
    g_pDlssgParameters->Set("DLSSG.UserInterfaceRecompositionEnabled", 1u);
    g_pDlssgParameters->Set(NVSDK_NGX_DLSSG_Parameter_UserInterfaceRecompositionEnabled, 1u);
    if (g_pCapabilityParameters) {
        g_pCapabilityParameters->Set("DLSSG.UserInterfaceRecompositionEnabled", 1u);
        g_pCapabilityParameters->Set(NVSDK_NGX_DLSSG_Parameter_UserInterfaceRecompositionEnabled, 1u);
    }

    LogNative("[CreateFeature] width=%u, height=%u, format=%u, dev=%p, cmd=%p\n",
              width, height, vkFmt, g_vkDevice, vkCommandBuffer);

    NVSDK_NGX_Result res = NVSDK_NGX_Result_Fail;
    if (fn_CreateFeature1 && g_vkDevice) {
        res = fn_CreateFeature1(
            g_vkDevice,
            (VkCommandBuffer)vkCommandBuffer,
            NVSDK_NGX_Feature_FrameGeneration,
            g_pDlssgParameters,
            &g_pDlssgFeatureHandle
        );
        LogNative("[CreateFeature] fn_CreateFeature1 -> res=0x%08X, handle=%p\n",
                  (unsigned int)res, g_pDlssgFeatureHandle);
    }
    if (NVSDK_NGX_FAILED(res) && fn_CreateFeature) {
        res = fn_CreateFeature(
            (VkCommandBuffer)vkCommandBuffer,
            NVSDK_NGX_Feature_FrameGeneration,
            g_pDlssgParameters,
            &g_pDlssgFeatureHandle
        );
        LogNative("[CreateFeature] fn_CreateFeature -> res=0x%08X, handle=%p\n",
                  (unsigned int)res, g_pDlssgFeatureHandle);
    }
    if (NVSDK_NGX_FAILED(res) && fn_Dlssg_CreateFeature1 && g_vkDevice) {
        res = fn_Dlssg_CreateFeature1(
            g_vkDevice,
            (VkCommandBuffer)vkCommandBuffer,
            NVSDK_NGX_Feature_FrameGeneration,
            g_pDlssgParameters,
            &g_pDlssgFeatureHandle
        );
        LogNative("[CreateFeature] fn_Dlssg_CreateFeature1 -> res=0x%08X, handle=%p\n",
                  (unsigned int)res, g_pDlssgFeatureHandle);
    }
    if (NVSDK_NGX_FAILED(res) && fn_Dlssg_CreateFeature) {
        res = fn_Dlssg_CreateFeature(
            (VkCommandBuffer)vkCommandBuffer,
            NVSDK_NGX_Feature_FrameGeneration,
            g_pDlssgParameters,
            &g_pDlssgFeatureHandle
        );
        LogNative("[CreateFeature] fn_Dlssg_CreateFeature -> res=0x%08X, handle=%p\n",
                  (unsigned int)res, g_pDlssgFeatureHandle);
    }

    if (NVSDK_NGX_SUCCEED(res)) {
        g_currentDlssgWidth = width;
        g_currentDlssgHeight = height;
        s_dlssgEvalErrorCount = 0;
        return 0;
    }

    LogNative("[CreateFeature] FAILED: 0x%08X\n", (unsigned int)res);
    fprintf(stderr, "[Vitrail DLSS-G] fn_CreateFeature failed: 0x%08X (size=%ux%u, format=%u, dev=%p)\n",
            (unsigned int)res, width, height, vkFmt, g_vkDevice);
    return (int)res;
}

int vitrail_dlssg_evaluate_v2(
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
) {
    if (!g_pDlssgFeatureHandle || !g_pDlssgParameters) return -1;

    NVSDK_NGX_Resource_VK resColorIn = CreateVkResource(inColorView, inColorImage, inColorFormat, targetWidth, targetHeight, false, false);
    NVSDK_NGX_Resource_VK resColorOut = CreateVkResource(outInterpolatedView, outInterpolatedImage, outInterpolatedFormat, targetWidth, targetHeight, true, false);
    NVSDK_NGX_Resource_VK resDepth = CreateVkResource(inDepthView, inDepthImage, inDepthFormat, mvecWidth, mvecHeight, false, true);

    NVSDK_NGX_Resource_VK resMv = {};
    if (inMotionVectorsView && inMotionVectorsImage) {
        resMv = CreateVkResource(inMotionVectorsView, inMotionVectorsImage, inMvFormat, mvecWidth, mvecHeight, false, false);
    }

    NVSDK_NGX_Resource_VK resHudless = {};
    if (inHudlessView && inHudlessImage) {
        resHudless = CreateVkResource(inHudlessView, inHudlessImage, inHudlessFormat, targetWidth, targetHeight, false, false);
    }

    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_Backbuffer, &resColorIn);
    if (inHudlessView && inHudlessImage) {
        g_pDlssgParameters->Set("DLSSG.HUDLess", &resHudless);
        g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_HUDLess, &resHudless);
    } else {
        g_pDlssgParameters->Set("DLSSG.HUDLess", (void*)nullptr);
        g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_HUDLess, (void*)nullptr);
    }
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_OutputInterpolated, &resColorOut);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_OutputReal, (void*)nullptr);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_Depth, &resDepth);

    if (inMotionVectorsView && inMotionVectorsImage) {
        g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_MVecs, &resMv);
        g_pDlssgParameters->Set(NVSDK_NGX_Parameter_MotionVectors, &resMv);
    } else {
        g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_MVecs, (void*)nullptr);
    }

    // Standard NGX parameter aliases
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_Color, &resColorIn);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_Output, &resColorOut);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_Depth, &resDepth);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_Width, targetWidth);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_Height, targetHeight);

    // Subrect dimensions
    g_pDlssgParameters->Set("DLSSG.BackbufferSubrectBaseX", 0u);
    g_pDlssgParameters->Set("DLSSG.BackbufferSubrectBaseY", 0u);
    g_pDlssgParameters->Set("DLSSG.BackbufferSubrectWidth", targetWidth);
    g_pDlssgParameters->Set("DLSSG.BackbufferSubrectHeight", targetHeight);

    g_pDlssgParameters->Set("DLSSG.InputBackbufferSubrectBaseX", 0u);
    g_pDlssgParameters->Set("DLSSG.InputBackbufferSubrectBaseY", 0u);
    g_pDlssgParameters->Set("DLSSG.InputBackbufferSubrectWidth", targetWidth);
    g_pDlssgParameters->Set("DLSSG.InputBackbufferSubrectHeight", targetHeight);

    g_pDlssgParameters->Set("DLSSG.OutputInterpolatedSubrectBaseX", 0u);
    g_pDlssgParameters->Set("DLSSG.OutputInterpolatedSubrectBaseY", 0u);
    g_pDlssgParameters->Set("DLSSG.OutputInterpolatedSubrectWidth", targetWidth);
    g_pDlssgParameters->Set("DLSSG.OutputInterpolatedSubrectHeight", targetHeight);

    g_pDlssgParameters->Set("DLSSG.HUDLessSubrectBaseX", 0u);
    g_pDlssgParameters->Set("DLSSG.HUDLessSubrectBaseY", 0u);
    g_pDlssgParameters->Set("DLSSG.HUDLessSubrectWidth", targetWidth);
    g_pDlssgParameters->Set("DLSSG.HUDLessSubrectHeight", targetHeight);

    g_pDlssgParameters->Set("DLSSG.DepthSubrectBaseX", 0u);
    g_pDlssgParameters->Set("DLSSG.DepthSubrectBaseY", 0u);
    g_pDlssgParameters->Set("DLSSG.DepthSubrectWidth", mvecWidth);
    g_pDlssgParameters->Set("DLSSG.DepthSubrectHeight", mvecHeight);

    g_pDlssgParameters->Set("DLSSG.MVecsSubrectBaseX", 0u);
    g_pDlssgParameters->Set("DLSSG.MVecsSubrectBaseY", 0u);
    g_pDlssgParameters->Set("DLSSG.MVecsSubrectWidth", mvecWidth);
    g_pDlssgParameters->Set("DLSSG.MVecsSubrectHeight", mvecHeight);

    static uint64_t s_dlssgFrameId = 0;
    s_dlssgFrameId++;
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_BackbufferFrameID, (unsigned long long)s_dlssgFrameId);
    g_pDlssgParameters->Set("DLSSG.BackbufferFrameID", (unsigned long long)s_dlssgFrameId);

    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_MultiFrameCount, 1u);
    g_pDlssgParameters->Set("DLSSG.MultiFrameCount", 1u);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_MultiFrameIndex, 1u);
    g_pDlssgParameters->Set("DLSSG.MultiFrameIndex", 1u);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_Reset, resetAccumulation ? 1 : 0);
    g_pDlssgParameters->Set("DLSSG.Reset", resetAccumulation ? 1 : 0);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_Reset, resetAccumulation ? 1 : 0);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_MvecScaleX, mvScaleX);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_MvecScaleY, mvScaleY);
    g_pDlssgParameters->Set("DLSSG.MvecScaleX", mvScaleX);
    g_pDlssgParameters->Set("DLSSG.MvecScaleY", mvScaleY);
    g_pDlssgParameters->Set(NVSDK_NGX_Parameter_DLSSG_Dynamic_ReflexDriven, 0);
    g_pDlssgParameters->Set("DLSSG.Dynamic.ReflexDriven", 0u);

    // Camera matrices required by EndpointCoreInputs::ParseNGXParameters
    static const float s_identityMatrix[16] = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };
    g_pDlssgParameters->Set("DLSSG.ClipToPrevClip", (void*)s_identityMatrix);
    g_pDlssgParameters->Set("DLSSG.PrevClipToClip", (void*)s_identityMatrix);
    g_pDlssgParameters->Set("ClipToPrevClip", (void*)s_identityMatrix);
    g_pDlssgParameters->Set("PrevClipToClip", (void*)s_identityMatrix);

    NVSDK_NGX_Result res = NVSDK_NGX_Result_Fail;
    if (fn_EvaluateFeature) {
        res = fn_EvaluateFeature(
            (VkCommandBuffer)vkCommandBuffer,
            g_pDlssgFeatureHandle,
            g_pDlssgParameters,
            nullptr
        );
    }

    if (NVSDK_NGX_FAILED(res)) {
        if (s_dlssgEvalErrorCount++ < 5) {
            LogNative("[Evaluate] FAILED: res=0x%08X (target=%ux%u, mvec=%ux%u, colorFmt=%u, interpFmt=%u, depthFmt=%u, mvFmt=%u, cmd=%p, handle=%p)\n",
                      (unsigned int)res, targetWidth, targetHeight, mvecWidth, mvecHeight,
                      inColorFormat, outInterpolatedFormat, inDepthFormat, inMvFormat,
                      vkCommandBuffer, g_pDlssgFeatureHandle);
            fprintf(stderr, "[Vitrail DLSS-G] EvaluateFeature failed: 0x%08X (target=%ux%u, mvec=%ux%u, cmd=%p)\n",
                    (unsigned int)res, targetWidth, targetHeight, mvecWidth, mvecHeight, vkCommandBuffer);
        }
        return (int)res;
    }
    if (s_dlssgEvalErrorCount > 0) {
        LogNative("[Evaluate] SUCCEEDED! FrameGeneration Active.\n");
    }
    s_dlssgEvalErrorCount = 0;
    return 0;
}

int vitrail_dlssg_evaluate(
    void* vkCommandBuffer,
    void* inColorView, uint64_t inColorImage, uint32_t inColorFormat,
    void* outInterpolatedView, uint64_t outInterpolatedImage, uint32_t outInterpolatedFormat,
    void* inDepthView, uint64_t inDepthImage, uint32_t inDepthFormat,
    void* inMotionVectorsView, uint64_t inMotionVectorsImage, uint32_t inMvFormat,
    uint32_t targetWidth, uint32_t targetHeight,
    uint32_t mvecWidth, uint32_t mvecHeight,
    float mvScaleX, float mvScaleY,
    int resetAccumulation
) {
    return vitrail_dlssg_evaluate_v2(
        vkCommandBuffer,
        inColorView, inColorImage, inColorFormat,
        nullptr, 0, 0,
        outInterpolatedView, outInterpolatedImage, outInterpolatedFormat,
        inDepthView, inDepthImage, inDepthFormat,
        inMotionVectorsView, inMotionVectorsImage, inMvFormat,
        targetWidth, targetHeight,
        mvecWidth, mvecHeight,
        mvScaleX, mvScaleY,
        resetAccumulation
    );
}

void vitrail_dlss_shutdown() {
    __try {
        vitrail_dlss_release_feature();
    } __except (EXCEPTION_EXECUTE_HANDLER) {
    }

    __try {
        vitrail_dlssg_release_feature();
    } __except (EXCEPTION_EXECUTE_HANDLER) {
    }

    __try {
        if (g_pDlssgParameters && fn_DestroyParameters) {
            fn_DestroyParameters(g_pDlssgParameters);
            g_pDlssgParameters = nullptr;
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        g_pDlssgParameters = nullptr;
    }

    __try {
        if (g_pParameters && fn_DestroyParameters) {
            fn_DestroyParameters(g_pParameters);
            g_pParameters = nullptr;
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        g_pParameters = nullptr;
    }

    __try {
        if (g_pCapabilityParameters && fn_DestroyParameters) {
            fn_DestroyParameters(g_pCapabilityParameters);
            g_pCapabilityParameters = nullptr;
        }
    } __except (EXCEPTION_EXECUTE_HANDLER) {
        g_pCapabilityParameters = nullptr;
    }

    // Note: Do NOT call FreeLibrary(g_hDlssg) or FreeLibrary(g_hNvngx), and do NOT
    // call fn_Shutdown1(VkDevice). Doing so causes an access violation (0xC0000005) inside
    // nvoglv64.dll during exit teardown because the graphics driver maintains internal
    // hooks and will be cleaned up by the operating system upon process termination.
    g_vkDevice = nullptr;
    g_pDlssgParameters = nullptr;
    g_pParameters = nullptr;
    g_pCapabilityParameters = nullptr;
    g_pFeatureHandle = nullptr;
    g_pDlssgFeatureHandle = nullptr;
}
