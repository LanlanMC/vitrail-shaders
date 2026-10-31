cmake_minimum_required(VERSION 3.20)
project(vitrail_dlss LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include_directories(include)

add_library(vitrail_dlss SHARED
    src/vitrail_dlss.cpp
)

target_include_directories(vitrail_dlss PUBLIC
    $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
)

if (MSVC)
    target_compile_options(vitrail_dlss PRIVATE /W4 /O2 /MD)
endif()
