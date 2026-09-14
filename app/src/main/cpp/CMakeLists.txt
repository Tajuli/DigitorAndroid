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

target_link_libraries(digitor_ppmatting_ncnn ncnn ${log-lib})

# -----------------------------------------------------------------------------
# Auto CC V78: whisper.cpp + ggml Vulkan, with the same library retaining CPU fallback.
# Everything fetched here is pinned to an immutable commit. No Qualcomm/QNN binary is required.
# -----------------------------------------------------------------------------
include(FetchContent)

# ggml's Vulkan backend asks for a SPIRV-Headers CMake package. Android's NDK ships Vulkan itself,
# but not that host-side CMake package, so materialize a tiny package config around pinned headers.
set(DIGITOR_SPIRV_HEADERS_COMMIT "04fd3caa1e8267e4d95c806cad901181728e1006")
FetchContent_Declare(
    digitor_spirv_headers
    URL "https://github.com/KhronosGroup/SPIRV-Headers/archive/${DIGITOR_SPIRV_HEADERS_COMMIT}.tar.gz"
)
FetchContent_GetProperties(digitor_spirv_headers)
if(NOT digitor_spirv_headers_POPULATED)
    FetchContent_Populate(digitor_spirv_headers)
endif()

set(DIGITOR_SPIRV_CONFIG_DIR "${CMAKE_BINARY_DIR}/digitor-spirv-headers-config")
file(MAKE_DIRECTORY "${DIGITOR_SPIRV_CONFIG_DIR}")
file(WRITE "${DIGITOR_SPIRV_CONFIG_DIR}/SPIRV-HeadersConfig.cmake"
"if(NOT TARGET SPIRV-Headers::SPIRV-Headers)\n"
"  add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)\n"
"  set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES \"${digitor_spirv_headers_SOURCE_DIR}/include\")\n"
"endif()\n")
set(SPIRV-Headers_DIR "${DIGITOR_SPIRV_CONFIG_DIR}" CACHE PATH "Pinned SPIRV-Headers package" FORCE)

# Android NDK 29 exposes the Vulkan C API, but its sysroot does not include Vulkan-Hpp
# (vulkan/vulkan.hpp), which ggml's Vulkan backend includes directly. Pin the Khronos headers and
# make CMake's Vulkan::Vulkan imported target use them while still linking Android's libvulkan.
set(DIGITOR_VULKAN_HEADERS_COMMIT "e3b1eec08173d6b825cd3ac88c885a63b621504a") # Vulkan-Headers v1.4.357
FetchContent_Declare(
    digitor_vulkan_headers
    URL "https://github.com/KhronosGroup/Vulkan-Headers/archive/${DIGITOR_VULKAN_HEADERS_COMMIT}.tar.gz"
)
FetchContent_GetProperties(digitor_vulkan_headers)
if(NOT digitor_vulkan_headers_POPULATED)
    FetchContent_Populate(digitor_vulkan_headers)
endif()
set(Vulkan_INCLUDE_DIR "${digitor_vulkan_headers_SOURCE_DIR}/include" CACHE PATH "Pinned Vulkan-Headers include directory" FORCE)

# Find the host glslc bundled with the Android NDK. ggml uses it only at build time to compile
# Vulkan shaders; the generated SPIR-V is embedded in the native library and no compiler ships in APK.
set(DIGITOR_NDK_ROOT "${ANDROID_NDK}")
if(NOT DIGITOR_NDK_ROOT)
    set(DIGITOR_NDK_ROOT "${CMAKE_ANDROID_NDK}")
endif()
file(GLOB DIGITOR_GLSLC_CANDIDATES
    "${DIGITOR_NDK_ROOT}/shader-tools/*/glslc"
    "${DIGITOR_NDK_ROOT}/shader-tools/*/glslc.exe"
)
if(DIGITOR_GLSLC_CANDIDATES)
    list(GET DIGITOR_GLSLC_CANDIDATES 0 DIGITOR_GLSLC)
    set(Vulkan_GLSLC_EXECUTABLE "${DIGITOR_GLSLC}" CACHE FILEPATH "Android NDK glslc" FORCE)
else()
    find_program(DIGITOR_GLSLC NAMES glslc)
    if(DIGITOR_GLSLC)
        set(Vulkan_GLSLC_EXECUTABLE "${DIGITOR_GLSLC}" CACHE FILEPATH "Host glslc" FORCE)
    endif()
endif()

set(DIGITOR_WHISPER_CPP_COMMIT "927cfce34f31707e17f2bff35c349632fb9e2c3a") # whisper.cpp v1.9.4
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)

# Vulkan is the primary path on actual supported phones. Emulator/x86 parity builds do not need it.
if(ANDROID_ABI STREQUAL "arm64-v8a" AND Vulkan_GLSLC_EXECUTABLE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    message(STATUS "Digitor Auto CC: whisper.cpp Vulkan enabled with ${Vulkan_GLSLC_EXECUTABLE}")
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    message(STATUS "Digitor Auto CC: building CPU backend for ${ANDROID_ABI}")
endif()

FetchContent_Declare(
    digitor_whispercpp
    URL "https://github.com/ggml-org/whisper.cpp/archive/${DIGITOR_WHISPER_CPP_COMMIT}.tar.gz"
)
FetchContent_MakeAvailable(digitor_whispercpp)

# The pre-existing PP-Matting Gradle block passes -ffast-math to the whole native build. That is
# valid for the matting target, but Whisper/ggml relies on IEEE NaN/Infinity semantics and its
# numerics should not inherit fast-math. Clang processes these target-local flags after the global
# flags, so the speech engine gets normal IEEE math while PP-Matting keeps its optimized setting.
foreach(DIGITOR_GGML_TARGET ggml-base ggml-cpu ggml ggml-vulkan whisper)
    if(TARGET ${DIGITOR_GGML_TARGET})
        target_compile_options(${DIGITOR_GGML_TARGET} PRIVATE -fno-fast-math -fno-finite-math-only)
    endif()
endforeach()

add_library(digitor_whispercpp SHARED WhisperCppJni.cpp)
target_compile_features(digitor_whispercpp PRIVATE cxx_std_17)
target_compile_options(digitor_whispercpp PRIVATE -O3 -fno-fast-math -fno-finite-math-only -fvisibility=hidden)
target_include_directories(digitor_whispercpp PRIVATE "${digitor_whispercpp_SOURCE_DIR}/include")
target_link_libraries(digitor_whispercpp PRIVATE whisper ${log-lib})
