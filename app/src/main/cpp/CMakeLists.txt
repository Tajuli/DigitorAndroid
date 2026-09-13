cmake_minimum_required(VERSION 3.22.1)
project(digitor_native LANGUAGES C CXX)

# Digitor already ships the ncnn 20260526 Android Vulkan runtime for PP-MattingV2. Auto Caption now
# reuses that exact runtime instead of embedding ggml-vulkan/OpenCL. This matters on phones such as
# the target UNISOC T606 device: ncnn Vulkan is already proven usable there, while ggml-vulkan v1.9.4
# rejects the device because it hard-requires Vulkan 1.2.
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

# Android arm64 typedefs int64_t as long, while the C++ LL suffix is long long. Normalize the single
# caption-boundary expression into an Android-safe generated translation unit. Keep the replacement
# guarded so a future source edit cannot silently patch the wrong code.
set(WHISPER_JNI_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/WhisperAutoCaptionJni.cpp")
file(READ "${WHISPER_JNI_SOURCE}" _digitor_whisper_source)
set(_digitor_android_i64_old "std::min(durationUs, localStart + 180000LL)")
set(_digitor_android_i64_new "std::min<int64_t>(durationUs, localStart + static_cast<int64_t>(180000))")
string(FIND "${_digitor_whisper_source}" "${_digitor_android_i64_old}" _digitor_android_i64_pos)
if(_digitor_android_i64_pos LESS 0)
    message(FATAL_ERROR "Whisper Android int64 compatibility expression changed; update the guarded normalization")
endif()
string(REPLACE "${_digitor_android_i64_old}" "${_digitor_android_i64_new}" _digitor_whisper_android_source "${_digitor_whisper_source}")
set(WHISPER_JNI_ANDROID_SOURCE "${CMAKE_CURRENT_BINARY_DIR}/WhisperAutoCaptionJniAndroid.cpp")
file(WRITE "${WHISPER_JNI_ANDROID_SOURCE}" "${_digitor_whisper_android_source}")

# Auto Caption Vulkan backend. The JNI library name intentionally stays digitor_whisper_jni so the
# Kotlin surface remains ABI-compatible, but the implementation is Tencent ncnn Whisper running on
# the same VulkanDevice family as PP-MattingV2. No CPU/OpenCL fallback is linked into this path.
add_library(digitor_whisper_jni SHARED "${WHISPER_JNI_ANDROID_SOURCE}")
target_compile_features(digitor_whisper_jni PRIVATE cxx_std_17)
# PP-Matting's Gradle configuration supplies -ffast-math globally, but Whisper's causal attention
# mask relies on negative infinity exactly like Tencent's reference implementation. Override the
# inherited fast-math flags only for the Whisper JNI target so the Vulkan model sees the correct mask.
# Preinclude cstring because WhisperAutoCaptionJni.cpp uses std::memcpy for ncnn tensor transfers.
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
