cmake_minimum_required(VERSION 3.22.1)
project(digitor_native LANGUAGES C CXX)

# V69 chooses 256/320/384/512 at runtime. The selected ncnn graph is fixed-shape, but the JNI bridge
# itself must not bake one compile-time tensor size into the shared library.
add_library(digitor_ppmatting_ncnn SHARED PpMattingNcnnVulkanJni.cpp)
target_compile_features(digitor_ppmatting_ncnn PRIVATE cxx_std_17)
target_compile_options(digitor_ppmatting_ncnn PRIVATE -O3 -ffast-math -fvisibility=hidden)

if(NOT DEFINED NCNN_ANDROID_ROOT)
    message(FATAL_ERROR "NCNN_ANDROID_ROOT is required for the PP-MattingV2 Vulkan backend")
endif()

set(ncnn_DIR "${NCNN_ANDROID_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn" CACHE PATH "ncnn package directory" FORCE)
if(NOT EXISTS "${ncnn_DIR}/ncnnConfig.cmake")
    message(FATAL_ERROR "ncnn Vulkan package missing for ${ANDROID_ABI}: ${ncnn_DIR}/ncnnConfig.cmake")
endif()

find_package(ncnn REQUIRED)
find_library(log-lib log)
find_library(android-lib android)
find_library(dl-lib dl)

target_link_libraries(digitor_ppmatting_ncnn ncnn ${log-lib})

# Auto Caption prefers Vulkan, then OpenCL on the production 64-bit phone build. Compatibility/test
# ABIs stay CPU-only so x86 emulators and 32-bit Android builds do not inherit mobile GPU toolchain
# requirements. V86 also enables Arm KleidiAI on arm64 so the CPU compatibility path uses runtime-
# selected dot-product/i8mm kernels when the SoC supports them instead of relying only on generic
# ggml matrix kernels.
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build whisper.cpp statically inside Digitor JNI" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

set(DIGITOR_WHISPER_VULKAN OFF)
set(DIGITOR_WHISPER_OPENCL OFF)
set(DIGITOR_WHISPER_KLEIDIAI OFF)
if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(DIGITOR_WHISPER_VULKAN ON)
    set(DIGITOR_WHISPER_OPENCL ON)
    set(DIGITOR_WHISPER_KLEIDIAI ON)
endif()

set(GGML_CPU_KLEIDIAI ${DIGITOR_WHISPER_KLEIDIAI} CACHE BOOL "Enable Arm KleidiAI CPU kernels for Auto Caption fallback" FORCE)
set(GGML_VULKAN ${DIGITOR_WHISPER_VULKAN} CACHE BOOL "Enable Vulkan GPU inference for Auto Caption" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_DEBUG OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_VALIDATE OFF CACHE BOOL "" FORCE)

# Generic OpenCL kernels are intentionally used instead of the Adreno-only optimized path so Mali,
# Adreno and other Android OpenCL implementations can be tried as the second GPU backend.
set(GGML_OPENCL ${DIGITOR_WHISPER_OPENCL} CACHE BOOL "Enable OpenCL GPU fallback for Auto Caption" FORCE)
set(GGML_OPENCL_PROFILING OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "Embed OpenCL kernels in the JNI library" FORCE)
set(GGML_OPENCL_USE_ADRENO_KERNELS OFF CACHE BOOL "Use portable OpenCL kernels across Android GPUs" FORCE)
set(GGML_OPENCL_USE_ADRENO_BIN_KERNELS OFF CACHE BOOL "" FORCE)
message(STATUS "Digitor Whisper backends for ${ANDROID_ABI}: Vulkan=${DIGITOR_WHISPER_VULKAN}, OpenCL=${DIGITOR_WHISPER_OPENCL}, KleidiAI=${DIGITOR_WHISPER_KLEIDIAI}")

# ggml-vulkan cross-compiles the Android runtime but compiles its shader generator on the host.
if(DIGITOR_WHISPER_VULKAN)
    if(NOT DEFINED SPIRV-Headers_DIR)
        foreach(_spirv_dir
            "/usr/share/cmake/SPIRV-Headers"
            "/usr/local/share/cmake/SPIRV-Headers"
        )
            if(EXISTS "${_spirv_dir}/SPIRV-HeadersConfig.cmake")
                set(SPIRV-Headers_DIR "${_spirv_dir}" CACHE PATH "Host SPIR-V headers package" FORCE)
                break()
            endif()
        endforeach()
    endif()

    if(NOT Vulkan_GLSLC_EXECUTABLE)
        find_program(DIGITOR_HOST_GLSLC NAMES glslc NO_CMAKE_FIND_ROOT_PATH)
        if(DIGITOR_HOST_GLSLC)
            set(Vulkan_GLSLC_EXECUTABLE "${DIGITOR_HOST_GLSLC}" CACHE FILEPATH "Host glslc for ggml Vulkan shaders" FORCE)
        endif()
    endif()

    if(NOT Vulkan_GLSLC_EXECUTABLE)
        message(FATAL_ERROR "glslc is required to build the Whisper Vulkan shaders")
    endif()
    if(NOT DEFINED SPIRV-Headers_DIR)
        message(FATAL_ERROR "SPIRV-Headers CMake package is required to build the Whisper Vulkan backend")
    endif()

    # The NDK Vulkan headers do not include Vulkan-Hpp (`vulkan/vulkan.hpp`), which ggml-vulkan uses.
    # Prefer an installed host copy, but stage only the self-contained Vulkan folder so host libc
    # headers never leak into the Android cross-compile.
    foreach(_vulkan_hpp_root "/usr/include" "/usr/local/include")
        if(EXISTS "${_vulkan_hpp_root}/vulkan/vulkan.hpp")
            set(DIGITOR_VULKAN_HPP_INCLUDE "${CMAKE_BINARY_DIR}/digitor-vulkan-hpp")
            file(MAKE_DIRECTORY "${DIGITOR_VULKAN_HPP_INCLUDE}")
            file(COPY "${_vulkan_hpp_root}/vulkan" DESTINATION "${DIGITOR_VULKAN_HPP_INCLUDE}")
            break()
        endif()
    endforeach()

    if(NOT DIGITOR_VULKAN_HPP_INCLUDE)
        FetchContent_Declare(
            digitor_vulkan_headers
            GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
            GIT_TAG v1.4.321
            GIT_SHALLOW TRUE
            GIT_PROGRESS FALSE
        )
        FetchContent_GetProperties(digitor_vulkan_headers)
        if(NOT digitor_vulkan_headers_POPULATED)
            FetchContent_Populate(digitor_vulkan_headers)
        endif()
        set(DIGITOR_VULKAN_HPP_INCLUDE "${digitor_vulkan_headers_SOURCE_DIR}/include")
    endif()

    # ggml-vulkan.cpp also includes SPIR-V's generated C++ enums (`spirv/unified1/spirv.hpp`).
    foreach(_spirv_include_root "/usr/include" "/usr/local/include")
        if(EXISTS "${_spirv_include_root}/spirv/unified1/spirv.hpp")
            set(DIGITOR_SPIRV_INCLUDE "${CMAKE_BINARY_DIR}/digitor-spirv-headers")
            file(MAKE_DIRECTORY "${DIGITOR_SPIRV_INCLUDE}")
            file(COPY "${_spirv_include_root}/spirv" DESTINATION "${DIGITOR_SPIRV_INCLUDE}")
            break()
        endif()
    endforeach()
endif()

# Android does not ship OpenCL headers or a public NDK import library. Build the Khronos ICD loader
# statically into Digitor. The JNI bridge first honors OCL_ICD_FILENAMES, then probes common Android
# vendor library names before the ggml registry is initialized; the Khronos vendor-directory scan is
# retained as a final fallback. This lets phones that expose an app-visible OEM ICD use OpenCL even
# when they do not install a /system/vendor/Khronos/OpenCL/vendors/*.icd file.
if(DIGITOR_WHISPER_OPENCL)
    FetchContent_Declare(
        digitor_opencl_headers
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git
        GIT_TAG v2025.07.22
        GIT_SHALLOW TRUE
        GIT_PROGRESS FALSE
    )
    FetchContent_GetProperties(digitor_opencl_headers)
    if(NOT digitor_opencl_headers_POPULATED)
        FetchContent_Populate(digitor_opencl_headers)
    endif()

    set(OPENCL_ICD_LOADER_HEADERS_DIR "${digitor_opencl_headers_SOURCE_DIR}" CACHE PATH "Pinned Khronos OpenCL headers" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_SHARED_LIBS OFF CACHE BOOL "Embed Khronos OpenCL ICD loader" FORCE)
    set(OPENCL_ICD_LOADER_PIC ON CACHE BOOL "OpenCL loader must link into Digitor JNI" FORCE)
    set(ENABLE_OPENCL_LAYERS OFF CACHE BOOL "" FORCE)
    set(ENABLE_OPENCL_LAYERINFO OFF CACHE BOOL "" FORCE)

    FetchContent_Declare(
        digitor_opencl_loader
        GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-ICD-Loader.git
        GIT_TAG v2025.07.22
        GIT_SHALLOW TRUE
        GIT_PROGRESS FALSE
    )
    FetchContent_MakeAvailable(digitor_opencl_loader)

    # whisper.cpp's ggml-opencl CMake invokes find_package(OpenCL). Preseed the module variables with
    # our in-tree Khronos target so it links the static loader instead of searching the Android NDK.
    set(OpenCL_INCLUDE_DIR "${digitor_opencl_headers_SOURCE_DIR}" CACHE PATH "Digitor OpenCL headers" FORCE)
    set(OpenCL_INCLUDE_DIRS "${digitor_opencl_headers_SOURCE_DIR}")
    set(OpenCL_LIBRARY OpenCL CACHE STRING "Digitor embedded Khronos OpenCL loader" FORCE)
    set(OpenCL_LIBRARIES OpenCL)
endif()

# The API-24 Vulkan compatibility source patch is only relevant to the arm64 GPU build.
set(DIGITOR_WHISPER_PATCH_ARGS)
if(DIGITOR_WHISPER_VULKAN)
    list(APPEND DIGITOR_WHISPER_PATCH_ARGS
        PATCH_COMMAND ${CMAKE_COMMAND}
            -DGGML_VULKAN_SOURCE=<SOURCE_DIR>/ggml/src/ggml-vulkan/ggml-vulkan.cpp
            -P ${CMAKE_CURRENT_LIST_DIR}/PatchWhisperVulkanAndroid.cmake
    )
endif()

FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.4
    GIT_SHALLOW TRUE
    GIT_PROGRESS FALSE
    ${DIGITOR_WHISPER_PATCH_ARGS}
)
FetchContent_MakeAvailable(whisper_cpp)

if(TARGET ggml-vulkan AND DIGITOR_VULKAN_HPP_INCLUDE)
    target_include_directories(ggml-vulkan PRIVATE "${DIGITOR_VULKAN_HPP_INCLUDE}")
endif()
if(TARGET ggml-vulkan AND DIGITOR_SPIRV_INCLUDE)
    target_include_directories(ggml-vulkan PRIVATE "${DIGITOR_SPIRV_INCLUDE}")
endif()

# The Android Gradle native build historically supplies -ffast-math for the PP-Matting path.
# ggml deliberately relies on NaN/Infinity semantics and refuses to compile with finite-math-only.
# Reset fast-math only for Whisper/ggml targets while preserving PP-Matting's tuning.
foreach(whisper_math_target whisper ggml ggml-base ggml-cpu ggml-vulkan ggml-opencl kleidiai)
    if(TARGET ${whisper_math_target})
        target_compile_options(${whisper_math_target} PRIVATE -fno-fast-math -fno-finite-math-only)
    endif()
endforeach()

add_library(digitor_whisper_jni SHARED WhisperAutoCaptionJni.cpp)
target_compile_features(digitor_whisper_jni PRIVATE cxx_std_17)
target_compile_options(digitor_whisper_jni PRIVATE -O3 -fno-fast-math -fno-finite-math-only -fvisibility=hidden)
target_include_directories(
    digitor_whisper_jni
    PRIVATE
        ${whisper_cpp_SOURCE_DIR}/include
        ${whisper_cpp_SOURCE_DIR}/ggml/include
)
target_link_libraries(digitor_whisper_jni PRIVATE whisper ${log-lib} ${android-lib} ${dl-lib})

# Keep explicit references from the JNI shared object to both GPU backends. ggml already links them
# when enabled, but the direct edges make the production APK robust against static archive/dead-code
# elimination and give JNI a supported way to prime/register each backend before enumeration.
if(DIGITOR_WHISPER_VULKAN AND TARGET ggml-vulkan)
    target_compile_definitions(digitor_whisper_jni PRIVATE DIGITOR_WHISPER_HAS_VULKAN=1)
    target_link_libraries(digitor_whisper_jni PRIVATE ggml-vulkan)
endif()
if(DIGITOR_WHISPER_OPENCL AND TARGET ggml-opencl)
    target_compile_definitions(digitor_whisper_jni PRIVATE DIGITOR_WHISPER_HAS_OPENCL=1)
    target_link_libraries(digitor_whisper_jni PRIVATE ggml-opencl)
endif()
