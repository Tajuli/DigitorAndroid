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

# V80 Auto Caption: pin whisper.cpp so speech recognition stays fully on-device after the model's
# first download. FetchContent happens only at build time; the app never sends user audio anywhere.
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build whisper.cpp statically inside Digitor JNI" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    whisper_cpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.9.4
    GIT_SHALLOW TRUE
    GIT_PROGRESS FALSE
)
FetchContent_MakeAvailable(whisper_cpp)

# The Android Gradle native build historically supplies -ffast-math for the PP-Matting path.
# ggml deliberately relies on NaN/Infinity semantics and refuses to compile with finite-math-only.
# Reset fast-math only for whisper/ggml targets while preserving the existing PP-Matting tuning.
foreach(whisper_math_target whisper ggml ggml-base ggml-cpu)
    if(TARGET ${whisper_math_target})
        target_compile_options(${whisper_math_target} PRIVATE -fno-fast-math -fno-finite-math-only)
    endif()
endforeach()

add_library(digitor_whisper_jni SHARED WhisperAutoCaptionJni.cpp)
target_compile_features(digitor_whisper_jni PRIVATE cxx_std_17)
target_compile_options(digitor_whisper_jni PRIVATE -O3 -fno-fast-math -fno-finite-math-only -fvisibility=hidden)
target_include_directories(digitor_whisper_jni PRIVATE ${whisper_cpp_SOURCE_DIR}/include)
target_link_libraries(digitor_whisper_jni whisper ${log-lib} ${android-lib})
