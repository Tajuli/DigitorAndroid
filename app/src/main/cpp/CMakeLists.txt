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

# Auto Caption is compiled from a generated Android translation unit so we can keep all mobile-only
# compatibility/stability changes guarded in one place without changing the public JNI ABI.
set(WHISPER_JNI_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/WhisperAutoCaptionJni.cpp")
file(READ "${WHISPER_JNI_SOURCE}" _digitor_whisper_source)

# Android arm64 typedefs int64_t as long, while the C++ LL suffix is long long.
set(_digitor_android_i64_old "std::min(durationUs, localStart + 180000LL)")
set(_digitor_android_i64_new "std::min<int64_t>(durationUs, localStart + static_cast<int64_t>(180000))")
string(FIND "${_digitor_whisper_source}" "${_digitor_android_i64_old}" _digitor_android_i64_pos)
if(_digitor_android_i64_pos LESS 0)
    message(FATAL_ERROR "Whisper Android int64 compatibility expression changed; update the guarded normalization")
endif()
string(REPLACE "${_digitor_android_i64_old}" "${_digitor_android_i64_new}" _digitor_whisper_source "${_digitor_whisper_source}")

# V89 mobile profile: the real Mali-G57 phone could take ~20 minutes on the base/beam-5 profile and
# then fail in fbank/decoder with ncnn status=-1. Use the official multilingual tiny graphs, run the
# lightweight fbank preprocessing on CPU for broad driver compatibility, and keep the expensive
# encoder/decoder/projection stages on Vulkan. Greedy top-1 decoding reduces decoder invocations by
# roughly 5x versus the reference beam-5 search and bounds runaway mobile latency.
foreach(_pair
    "constexpr int kMaxDecodedTokens = 448;|constexpr int kMaxDecodedTokens = 192;"
    "constexpr int kBeamSize = 5;|constexpr int kBeamSize = 1;"
    "constexpr int kMaxFinishedBeams = 5;|constexpr int kMaxFinishedBeams = 1;"
    "constexpr int kTopK = 5;|constexpr int kTopK = 1;"
)
    string(REPLACE "|" ";" _parts "${_pair}")
    list(GET _parts 0 _old)
    list(GET _parts 1 _new)
    string(FIND "${_digitor_whisper_source}" "${_old}" _pos)
    if(_pos LESS 0)
        message(FATAL_ERROR "Whisper mobile decoder profile source changed: ${_old}")
    endif()
    string(REPLACE "${_old}" "${_new}" _digitor_whisper_source "${_digitor_whisper_source}")
endforeach()

set(_digitor_fbank_gpu_old "        configureGpuNet(fbank);")
set(_digitor_fbank_cpu_new "        fbank.opt.use_vulkan_compute = false;\n        fbank.opt.num_threads = 2;\n        fbank.opt.use_fp16_packed = false;\n        fbank.opt.use_fp16_storage = false;\n        fbank.opt.use_fp16_arithmetic = false;")
string(FIND "${_digitor_whisper_source}" "${_digitor_fbank_gpu_old}" _digitor_fbank_gpu_pos)
if(_digitor_fbank_gpu_pos LESS 0)
    message(FATAL_ERROR "Whisper fbank configuration changed; update the mobile CPU-preprocess profile")
endif()
string(REPLACE "${_digitor_fbank_gpu_old}" "${_digitor_fbank_cpu_new}" _digitor_whisper_source "${_digitor_whisper_source}")

string(FIND "${_digitor_whisper_source}" "whisper_base_" _digitor_base_model_pos)
if(_digitor_base_model_pos LESS 0)
    message(FATAL_ERROR "Whisper base model filenames changed; update the tiny mobile profile")
endif()
string(REPLACE "whisper_base_" "whisper_tiny_" _digitor_whisper_source "${_digitor_whisper_source}")
string(REPLACE "ncnn Whisper base ready" "ncnn Whisper tiny mobile ready" _digitor_whisper_source "${_digitor_whisper_source}")

set(WHISPER_JNI_ANDROID_SOURCE "${CMAKE_CURRENT_BINARY_DIR}/WhisperAutoCaptionJniAndroid.cpp")
file(WRITE "${WHISPER_JNI_ANDROID_SOURCE}" "${_digitor_whisper_source}")

# Auto Caption Vulkan backend. The JNI library name intentionally stays digitor_whisper_jni so the
# Kotlin surface remains ABI-compatible. Heavy Whisper inference remains ncnn Vulkan GPU; only fbank
# preprocessing is CPU-side in the mobile profile to avoid Mali driver failures. No CPU/OpenCL
# transcription fallback is linked into this path.
add_library(digitor_whisper_jni SHARED "${WHISPER_JNI_ANDROID_SOURCE}")
target_compile_features(digitor_whisper_jni PRIVATE cxx_std_17)
# PP-Matting's Gradle configuration supplies -ffast-math globally, but Whisper's causal attention
# mask relies on negative infinity exactly like Tencent's reference implementation. Override the
# inherited fast-math flags only for the Whisper JNI target so the decoder sees the correct mask.
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
