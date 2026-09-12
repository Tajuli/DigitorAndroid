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

# V80/V84 Auto Caption: pin whisper.cpp and build ggml's cross-vendor Vulkan backend. Android
# phones with a usable Vulkan compute device offload Whisper tensors to the GPU; JNI keeps a CPU
# fallback for devices/drivers where Vulkan cannot initialize.
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
# host SPIR-V headers and glslc when they are installed by CI / Linux developer environments.
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

    # The NDK exposes Vulkan C headers but not Vulkan-Hpp (`vulkan/vulkan.hpp`) required by ggml.
    # Copy only the host's self-contained Vulkan header directory into the CMake build tree instead
    # of adding /usr/include wholesale to an Android cross-compile.
    foreach(_vulkan_hpp_root "/usr/include" "/usr/local/include")
        if(EXISTS "${_vulkan_hpp_root}/vulkan/vulkan.hpp")
            set(DIGITOR_VULKAN_HPP_INCLUDE "${CMAKE_BINARY_DIR}/digitor-vulkan-hpp")
            file(MAKE_DIRECTORY "${DIGITOR_VULKAN_HPP_INCLUDE}")
            file(COPY "${_vulkan_hpp_root}/vulkan" DESTINATION "${DIGITOR_VULKAN_HPP_INCLUDE}")
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
)
FetchContent_MakeAvailable(whisper_cpp)

if(TARGET ggml-vulkan AND DIGITOR_VULKAN_HPP_INCLUDE)
    target_include_directories(ggml-vulkan PRIVATE "${DIGITOR_VULKAN_HPP_INCLUDE}")
endif()

# The Android Gradle native build historically supplies -ffast-math for the PP-Matting path.
# ggml deliberately relies on NaN/Infinity semantics and refuses to compile with finite-math-only.
# Reset fast-math only for whisper/ggml targets while preserving the existing PP-Matting tuning.
foreach(whisper_math_target whisper ggml ggml-base ggml-cpu ggml-vulkan)
    if(TARGET ${whisper_math_target})
        target_compile_options(${whisper_math_target} PRIVATE -fno-fast-math -fno-finite-math-only)
    endif()
endforeach()

add_library(digitor_whisper_jni SHARED WhisperAutoCaptionJni.cpp)
target_compile_features(digitor_whisper_jni PRIVATE cxx_std_17)
target_compile_options(digitor_whisper_jni PRIVATE -O3 -fno-fast-math -fno-finite-math-only -fvisibility=hidden)
target_include_directories(digitor_whisper_jni PRIVATE ${whisper_cpp_SOURCE_DIR}/include)
target_link_libraries(digitor_whisper_jni whisper ${log-lib} ${android-lib})
