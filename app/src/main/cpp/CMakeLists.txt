cmake_minimum_required(VERSION 3.22.1)
project(digitor_native LANGUAGES C CXX)

# Digitor ships the ncnn 20260526 Android Vulkan runtime for PP-MattingV2 and Auto Caption reuses
# that exact runtime. Whisper's mobile compatibility policy now lives in the JNI source itself rather
# than being synthesized through CMake string rewriting; this keeps the compiled phone code identical
# to the reviewed source and avoids CMake semicolon/list transformations corrupting C++ declarations.
if(NOT DEFINED NCNN_ANDROID_ROOT)
    message(FATAL_ERROR "NCNN_ANDROID_ROOT is required for Digitor Vulkan inference")
endif()

set(ncnn_DIR "${NCNN_ANDROID_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn" CACHE PATH "ncnn package directory" FORCE)
if(NOT EXISTS "${ncnn_DIR}/ncnnConfig.cmake")
    message(FATAL_ERROR "ncnn Vulkan package missing for ${ANDROID_ABI}: ${ncnn_DIR}/ncnnConfig.cmake")
endif()

find_package(ncnn REQUIRED)
find_library(log-lib log)
find_library(android-lib android)

# PP-MattingV2 Vulkan backend.
add_library(digitor_ppmatting_ncnn SHARED PpMattingNcnnVulkanJni.cpp)
target_compile_features(digitor_ppmatting_ncnn PRIVATE cxx_std_17)
target_compile_options(digitor_ppmatting_ncnn PRIVATE -O3 -ffast-math -fvisibility=hidden)
target_link_libraries(digitor_ppmatting_ncnn PRIVATE ncnn ${log-lib})

# Auto Caption: the expensive Whisper encoder/decoder/projection remain ncnn Vulkan GPU. The tiny
# multilingual profile, CPU-side fbank preprocessing, greedy decoding, and the GPU-only no-KV-cache
# compatibility path are implemented directly in WhisperAutoCaptionJni.cpp.
add_library(digitor_whisper_jni SHARED WhisperAutoCaptionJni.cpp)
target_compile_features(digitor_whisper_jni PRIVATE cxx_std_17)

# PP-Matting's Gradle configuration supplies -ffast-math globally. Whisper's causal attention mask
# relies on IEEE infinity semantics, so explicitly undo fast-math only for the caption target.
target_compile_options(
    digitor_whisper_jni
    PRIVATE
        -O3
        -fno-fast-math
        -fno-finite-math-only
        -fvisibility=hidden
        -include
        cstring
)
target_link_libraries(digitor_whisper_jni PRIVATE ncnn ${log-lib} ${android-lib})
