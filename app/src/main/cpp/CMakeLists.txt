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

target_link_libraries(digitor_ppmatting_ncnn ncnn ${log-lib})

# Auto Caption uses ggml's cross-vendor Vulkan backend first. The JNI layer keeps a CPU fallback
# for phones/drivers where Vulkan compute cannot initialize. PP-Matting's ncnn Vulkan path above is
# independent and remains unchanged.
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build whisper.cpp statically inside Digitor JNI" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "Enable Vulkan GPU inference for Auto Caption" FORCE)
set(GGML_VULKAN_CHECK_RESULTS OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_DEBUG OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN_VALIDATE OFF CACHE BOOL "" FORCE)

# ggml-vulkan cross-compiles the Android runtime but compiles its shader generator on the host.
# Android's toolchain normally searches packages inside the NDK sysroot, so explicitly expose the
# host SPIR-V headers and glslc installed by CI / Linux development environments.
if(ANDROID)
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

    # CI/Android Studio hosts do not always ship Vulkan-Hpp. Fetch a pinned Khronos header snapshot
    # as a portable fallback. Only headers are consumed; Android still uses the platform Vulkan loader.
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
    # Stage only the `spirv/` directory to keep host libc/C++ headers out of the target build.
    foreach(_spirv_include_root "/usr/include" "/usr/local/include")
        if(EXISTS "${_spirv_include_root}/spirv/unified1/spirv.hpp")
            set(DIGITOR_SPIRV_INCLUDE "${CMAKE_BINARY_DIR}/digitor-spirv-headers")
            file(MAKE_DIRECTORY "${DIGITOR_SPIRV_INCLUDE}")
            file(COPY "${_spirv_include_root}/spirv" DESTINATION "${DIGITOR_SPIRV_INCLUDE}")
            break()
        endif()
    endforeach()
endif()

FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.4
    GIT_SHALLOW TRUE
    GIT_PROGRESS FALSE
    # whisper.cpp v1.9.4 directly links vkGetPhysicalDeviceFeatures2. Android API 24's NDK
    # Vulkan stub does not export that Vulkan 1.1 symbol, so route the pinned calls through
    # Vulkan-Hpp's already-initialized dynamic dispatcher instead of raising Digitor's minSdk.
    PATCH_COMMAND ${CMAKE_COMMAND}
        -DGGML_VULKAN_SOURCE=<SOURCE_DIR>/ggml/src/ggml-vulkan/ggml-vulkan.cpp
        -P ${CMAKE_CURRENT_LIST_DIR}/PatchWhisperVulkanAndroid.cmake
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
foreach(whisper_math_target whisper ggml ggml-base ggml-cpu ggml-vulkan)
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
target_link_libraries(digitor_whisper_jni whisper ${log-lib} ${android-lib})
