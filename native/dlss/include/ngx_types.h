#pragma once

#include "vulkan_types.h"
#include <cstdint>

#ifndef NVSDK_CONV
#if defined(_WIN32)
#define NVSDK_CONV __cdecl
#else
#define NVSDK_CONV
#endif
#endif

typedef enum NVSDK_NGX_Result {
    NVSDK_NGX_Result_Success = 0x00000001,
    NVSDK_NGX_Result_Fail = 0xBAD00000,
    NVSDK_NGX_Result_Fail_FeatureNotSupported = 0xBAD00001,
    NVSDK_NGX_Result_Fail_PlatformError = 0xBAD00002,
    NVSDK_NGX_Result_Fail_FeatureAlreadyExists = 0xBAD00003,
    NVSDK_NGX_Result_Fail_FeatureNotFound = 0xBAD00004,
    NVSDK_NGX_Result_Fail_InvalidParameter = 0xBAD00005,
    NVSDK_NGX_Result_Fail_ScratchBufferTooSmall = 0xBAD00006,
    NVSDK_NGX_Result_Fail_NotInitialized = 0xBAD00007,
    NVSDK_NGX_Result_Fail_UnsupportedInputFormat = 0xBAD00008,
    NVSDK_NGX_Result_Fail_RWFlagMissing = 0xBAD00009,
    NVSDK_NGX_Result_Fail_MissingInput = 0xBAD0000A,
    NVSDK_NGX_Result_Fail_UnableToInitializeFeature = 0xBAD0000B,
    NVSDK_NGX_Result_Fail_OutOfDate = 0xBAD0000C,
    NVSDK_NGX_Result_Fail_NVNImageFormatMismatch = 0xBAD0000D
} NVSDK_NGX_Result;

#define NVSDK_NGX_SUCCEED(res) ((((uint32_t)(res)) & 0xFFF00000) != 0xBAD00000)
#define NVSDK_NGX_FAILED(res) ((((uint32_t)(res)) & 0xFFF00000) == 0xBAD00000)

typedef enum NVSDK_NGX_Feature {
    NVSDK_NGX_Feature_Reserved = 0,
    NVSDK_NGX_Feature_SuperSampling = 1,
    NVSDK_NGX_Feature_InPainting = 2,
    NVSDK_NGX_Feature_ImageSuperResolution = 3,
    NVSDK_NGX_Feature_SlowMotion = 4,
    NVSDK_NGX_Feature_VideoSuperResolution = 5,
    NVSDK_NGX_Feature_ImageSignalProcessing = 6,
    NVSDK_NGX_Feature_DeepResolve = 7,
    NVSDK_NGX_Feature_RayReconstruction = 8,
    NVSDK_NGX_Feature_FrameGeneration = 11,
    NVSDK_NGX_Feature_NumFeatures
} NVSDK_NGX_Feature;

typedef enum NVSDK_NGX_PerfQuality_Value {
    NVSDK_NGX_PerfQuality_Value_MaxPerf = 0,
    NVSDK_NGX_PerfQuality_Value_Balanced = 1,
    NVSDK_NGX_PerfQuality_Value_MaxQuality = 2,
    NVSDK_NGX_PerfQuality_Value_UltraPerformance = 3,
    NVSDK_NGX_PerfQuality_Value_UltraQuality = 4,
    NVSDK_NGX_PerfQuality_Value_DLAA = 5
} NVSDK_NGX_PerfQuality_Value;

typedef enum NVSDK_NGX_DLSS_Feature_Flags {
    NVSDK_NGX_DLSS_Feature_Flags_None = 0,
    NVSDK_NGX_DLSS_Feature_Flags_IsHDR = 1 << 0,
    NVSDK_NGX_DLSS_Feature_Flags_MVLowRes = 1 << 1,
    NVSDK_NGX_DLSS_Feature_Flags_MVJittered = 1 << 2,
    NVSDK_NGX_DLSS_Feature_Flags_DepthInverted = 1 << 3,
    NVSDK_NGX_DLSS_Feature_Flags_Reserved_0 = 1 << 4,
    NVSDK_NGX_DLSS_Feature_Flags_DoSharpening = 1 << 5,
    NVSDK_NGX_DLSS_Feature_Flags_AutoExposure = 1 << 6
} NVSDK_NGX_DLSS_Feature_Flags;

typedef enum NVSDK_NGX_Version {
    NVSDK_NGX_Version_API = 0x00000013
} NVSDK_NGX_Version;

typedef enum NVSDK_NGX_Resource_VK_Type {
    NVSDK_NGX_RESOURCE_VK_TYPE_VK_IMAGEVIEW = 0,
    NVSDK_NGX_RESOURCE_VK_TYPE_VK_BUFFER = 1
} NVSDK_NGX_Resource_VK_Type;

typedef struct NVSDK_NGX_ImageViewInfo_VK {
    VkImageView ImageView;
    VkImage Image;
    VkImageSubresourceRange SubresourceRange;
    VkFormat Format;
    uint32_t Width;
    uint32_t Height;
} NVSDK_NGX_ImageViewInfo_VK;

typedef struct NVSDK_NGX_BufferInfo_VK {
    VkBuffer Buffer;
    uint32_t SizeInBytes;
} NVSDK_NGX_BufferInfo_VK;

typedef struct NVSDK_NGX_Resource_VK {
    union {
        NVSDK_NGX_ImageViewInfo_VK ImageViewInfo;
        NVSDK_NGX_BufferInfo_VK BufferInfo;
    } Resource;
    NVSDK_NGX_Resource_VK_Type Type;
    bool ReadWrite;
} NVSDK_NGX_Resource_VK;

typedef struct NVSDK_NGX_Handle {
    uint32_t Id;
} NVSDK_NGX_Handle;

typedef void (NVSDK_CONV *PFN_NVSDK_NGX_ProgressCallback)(float InProgress, int *OutShouldCancel);

// NGX Parameter keys
#define NVSDK_NGX_Parameter_SuperSampling_Available "SuperSampling.Available"
#define NVSDK_NGX_Parameter_SuperSampling_NeedsUpdatedDriver "SuperSampling.NeedsUpdatedDriver"
#define NVSDK_NGX_Parameter_CreationNodeMask "CreationNodeMask"
#define NVSDK_NGX_Parameter_VisibilityNodeMask "VisibilityNodeMask"
#define NVSDK_NGX_Parameter_Width "Width"
#define NVSDK_NGX_Parameter_Height "Height"
#define NVSDK_NGX_Parameter_OutWidth "OutWidth"
#define NVSDK_NGX_Parameter_OutHeight "OutHeight"
#define NVSDK_NGX_Parameter_PerfQualityValue "PerfQualityValue"
#define NVSDK_NGX_Parameter_DLSS_Feature_Create_Flags "DLSS.Feature.Create.Flags"
#define NVSDK_NGX_Parameter_DLSS_Optimal_Render_Width "DLSS.Optimal.Render.Width"
#define NVSDK_NGX_Parameter_DLSS_Optimal_Render_Height "DLSS.Optimal.Render.Height"
#define NVSDK_NGX_Parameter_DLSS_Render_Width_Min "DLSS.Render.Width.Min"
#define NVSDK_NGX_Parameter_DLSS_Render_Height_Min "DLSS.Render.Height.Min"
#define NVSDK_NGX_Parameter_DLSS_Render_Width_Max "DLSS.Render.Width.Max"
#define NVSDK_NGX_Parameter_DLSS_Render_Height_Max "DLSS.Render.Height.Max"
#define NVSDK_NGX_Parameter_DLSS_Render_Subrect_Dimensions_Width "DLSS.Render.Subrect.Dimensions.Width"
#define NVSDK_NGX_Parameter_DLSS_Render_Subrect_Dimensions_Height "DLSS.Render.Subrect.Dimensions.Height"
#define NVSDK_NGX_Parameter_Sharpness "Sharpness"
#define NVSDK_NGX_Parameter_Color "Color"
#define NVSDK_NGX_Parameter_Output "Output"
#define NVSDK_NGX_Parameter_Depth "Depth"
#define NVSDK_NGX_Parameter_MotionVectors "MotionVectors"
#define NVSDK_NGX_Parameter_Jitter_Offset_X "Jitter.Offset.X"
#define NVSDK_NGX_Parameter_Jitter_Offset_Y "Jitter.Offset.Y"
#define NVSDK_NGX_Parameter_Reset "Reset"
#define NVSDK_NGX_Parameter_MV_Scale_X "MV.Scale.X"
#define NVSDK_NGX_Parameter_MV_Scale_Y "MV.Scale.Y"
#define NVSDK_NGX_Parameter_TransparencyMask "TransparencyMask"
#define NVSDK_NGX_Parameter_DLSS_Input_Bias_Current_Color_Mask "DLSS.Input.Bias.Current.Color.Mask"
#define NVSDK_NGX_Parameter_ExposureTexture "ExposureTexture"
#define NVSDK_NGX_Parameter_SuperSampling_FeatureInitResult "SuperSampling.FeatureInitResult"
#define NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_DLAA "DLSS.Hint.Render.Preset.DLAA"
#define NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Quality "DLSS.Hint.Render.Preset.Quality"
#define NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Balanced "DLSS.Hint.Render.Preset.Balanced"
#define NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_Performance "DLSS.Hint.Render.Preset.Performance"
#define NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraPerformance "DLSS.Hint.Render.Preset.UltraPerformance"
#define NVSDK_NGX_Parameter_DLSS_Hint_Render_Preset_UltraQuality "DLSS.Hint.Render.Preset.UltraQuality"

// DLSS-G Frame Generation Parameter keys
#define NVSDK_NGX_Parameter_FrameGeneration_Available "FrameGeneration.Available"
#define NVSDK_NGX_Parameter_FrameGeneration_FeatureInitResult "FrameGeneration.FeatureInitResult"
#define NVSDK_NGX_Parameter_DLSSG_MultiFrameCount "DLSSG.MultiFrameCount"
#define NVSDK_NGX_Parameter_DLSSG_MultiFrameCountMax "DLSSG.MultiFrameCountMax"
#define NVSDK_NGX_Parameter_DLSSG_MultiFrameIndex "DLSSG.MultiFrameIndex"
#define NVSDK_NGX_Parameter_DLSSG_Backbuffer "DLSSG.Backbuffer"
#define NVSDK_NGX_Parameter_DLSSG_OutputInterpolated "DLSSG.OutputInterpolated"
#define NVSDK_NGX_Parameter_DLSSG_OutputReal "DLSSG.OutputReal"
#define NVSDK_NGX_Parameter_DLSSG_Depth "DLSSG.Depth"
#define NVSDK_NGX_Parameter_DLSSG_MVecs "DLSSG.MVecs"
#define NVSDK_NGX_Parameter_DLSSG_Width "DLSSG.Width"
#define NVSDK_NGX_Parameter_DLSSG_Height "DLSSG.Height"
#define NVSDK_NGX_Parameter_DLSSG_DepthInverted "DLSSG.DepthInverted"
#define NVSDK_NGX_Parameter_DLSSG_ColorBuffersHDR "DLSSG.ColorBuffersHDR"
#define NVSDK_NGX_Parameter_DLSSG_Dynamic_ReflexDriven "DLSSG.Dynamic.ReflexDriven"
#define NVSDK_NGX_Parameter_DLSSG_Reset "DLSSG.Reset"
#define NVSDK_NGX_Parameter_DLSSG_MvecScaleX "DLSSG.MvecScaleX"
#define NVSDK_NGX_Parameter_DLSSG_MvecScaleY "DLSSG.MvecScaleY"
#define NVSDK_NGX_Parameter_DLSSG_BackbufferFrameID "DLSSG.BackbufferFrameID"
#define NVSDK_NGX_Parameter_DLSSG_BackbufferFormat "DLSSG.BackbufferFormat"
#define NVSDK_NGX_DLSSG_Parameter_UserInterfaceRecompositionEnabled "DLSSG.UserInterfaceRecompositionEnabled"
#define NVSDK_NGX_Parameter_DLSSG_HUDLess "DLSSG.HUDLess"
#define NVSDK_NGX_Parameter_DLSSG_UI "DLSSG.UI"

typedef struct NVSDK_NGX_PathListInfo {
    wchar_t const* const* Path;
    unsigned int Length;
} NVSDK_NGX_PathListInfo;

typedef struct NVSDK_NGX_LoggingInfo {
    void* LoggingCallback;
    int MinimumLoggingLevel;
    bool DisableOtherLoggingSinks;
} NVSDK_NGX_LoggingInfo;

typedef struct NVSDK_NGX_FeatureCommonInfo {
    NVSDK_NGX_PathListInfo PathListInfo;
    void* InternalData;
    NVSDK_NGX_LoggingInfo LoggingInfo;
} NVSDK_NGX_FeatureCommonInfo;

struct ID3D11Resource;
struct ID3D12Resource;

// NGX Parameter interface vtable matching official nvsdk_ngx_params.h
struct NVSDK_NGX_Parameter;
typedef struct NVSDK_NGX_Parameter {
    virtual void Set(const char* InName, unsigned long long InValue) = 0;
    virtual void Set(const char* InName, float InValue) = 0;
    virtual void Set(const char* InName, double InValue) = 0;
    virtual void Set(const char* InName, unsigned int InValue) = 0;
    virtual void Set(const char* InName, int InValue) = 0;
    virtual void Set(const char* InName, ID3D11Resource* InValue) = 0;
    virtual void Set(const char* InName, ID3D12Resource* InValue) = 0;
    virtual void Set(const char* InName, void* InValue) = 0;

    virtual NVSDK_NGX_Result Get(const char* InName, unsigned long long* OutValue) const = 0;
    virtual NVSDK_NGX_Result Get(const char* InName, float* OutValue) const = 0;
    virtual NVSDK_NGX_Result Get(const char* InName, double* OutValue) const = 0;
    virtual NVSDK_NGX_Result Get(const char* InName, unsigned int* OutValue) const = 0;
    virtual NVSDK_NGX_Result Get(const char* InName, int* OutValue) const = 0;
    virtual NVSDK_NGX_Result Get(const char* InName, ID3D11Resource** OutValue) const = 0;
    virtual NVSDK_NGX_Result Get(const char* InName, ID3D12Resource** OutValue) const = 0;
    virtual NVSDK_NGX_Result Get(const char* InName, void** OutValue) const = 0;

    virtual void Reset() = 0;
} NVSDK_NGX_Parameter;
