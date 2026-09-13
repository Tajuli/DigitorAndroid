#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <ggml-backend.h>
#include <whisper.h>

namespace {
constexpr const char * kTag = "DigitorWhisperV80";
constexpr const char * kGpuUnavailablePrefix = "GPU_BACKENDS_UNAVAILABLE: ";

enum class RuntimeBackend {
    None,
    Vulkan,
    OpenCL,
    Cpu,
};

struct GpuCandidate {
    RuntimeBackend kind = RuntimeBackend::None;
    int gpuOrdinal = -1;
    std::string backendName;
    std::string deviceName;
    std::string description;
};

std::mutex gWhisperMutex;
whisper_context * gContext = nullptr;
std::string gModelPath;
RuntimeBackend gBackend = RuntimeBackend::None;
std::string gBackendDevice;
std::string gLastGpuFailure;

std::string JStringToUtf8(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void ThrowJava(JNIEnv * env, const char * type, const std::string & message) {
    jclass klass = env->FindClass(type);
    if (klass != nullptr) env->ThrowNew(klass, message.c_str());
}

std::string Trim(const char * raw) {
    std::string text = raw == nullptr ? std::string() : std::string(raw);
    auto notSpace = [](unsigned char c) { return !std::isspace(c); };
    text.erase(text.begin(), std::find_if(text.begin(), text.end(), notSpace));
    text.erase(std::find_if(text.rbegin(), text.rend(), notSpace).base(), text.end());
    return text;
}

std::string Lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

const char * BackendKindName(RuntimeBackend backend) {
    switch (backend) {
        case RuntimeBackend::Vulkan: return "Vulkan GPU";
        case RuntimeBackend::OpenCL: return "OpenCL GPU";
        case RuntimeBackend::Cpu: return "CPU (slow)";
        default: return "none";
    }
}

std::string BackendLabel() {
    std::string label = BackendKindName(gBackend);
    if ((gBackend == RuntimeBackend::Vulkan || gBackend == RuntimeBackend::OpenCL) && !gBackendDevice.empty()) {
        label += " · ";
        label += gBackendDevice;
    }
    return label;
}

void FreeContext() {
    if (gContext != nullptr) {
        whisper_free(gContext);
        gContext = nullptr;
    }
    gModelPath.clear();
    gBackend = RuntimeBackend::None;
    gBackendDevice.clear();
}

std::vector<GpuCandidate> EnumerateGpuCandidates() {
    std::vector<GpuCandidate> result;
    int gpuOrdinal = 0;
    const size_t deviceCount = ggml_backend_dev_count();
    __android_log_print(ANDROID_LOG_INFO, kTag, "ggml registered devices: %zu", deviceCount);

    for (size_t index = 0; index < deviceCount; ++index) {
        ggml_backend_dev_t device = ggml_backend_dev_get(index);
        if (device == nullptr) continue;
        const auto type = ggml_backend_dev_type(device);
        const char * rawDeviceName = ggml_backend_dev_name(device);
        const char * rawDescription = ggml_backend_dev_description(device);
        ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(device);
        const char * rawBackendName = reg == nullptr ? nullptr : ggml_backend_reg_name(reg);
        const std::string deviceName = rawDeviceName == nullptr ? "unknown" : rawDeviceName;
        const std::string description = rawDescription == nullptr ? "" : rawDescription;
        const std::string backendName = rawBackendName == nullptr ? "unknown" : rawBackendName;

        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "device[%zu]: backend=%s name=%s description=%s type=%d",
            index,
            backendName.c_str(),
            deviceName.c_str(),
            description.c_str(),
            static_cast<int>(type)
        );

        if (type != GGML_BACKEND_DEVICE_TYPE_GPU && type != GGML_BACKEND_DEVICE_TYPE_IGPU) continue;

        const std::string haystack = Lower(backendName + " " + deviceName + " " + description);
        RuntimeBackend kind = RuntimeBackend::None;
        if (haystack.find("vulkan") != std::string::npos) {
            kind = RuntimeBackend::Vulkan;
        } else if (haystack.find("opencl") != std::string::npos) {
            kind = RuntimeBackend::OpenCL;
        }

        if (kind != RuntimeBackend::None) {
            result.push_back(GpuCandidate{
                kind,
                gpuOrdinal,
                backendName,
                deviceName,
                description,
            });
        }
        ++gpuOrdinal;
    }
    return result;
}

const GpuCandidate * FindCandidate(const std::vector<GpuCandidate> & candidates, RuntimeBackend kind) {
    auto it = std::find_if(candidates.begin(), candidates.end(), [kind](const GpuCandidate & candidate) {
        return candidate.kind == kind;
    });
    return it == candidates.end() ? nullptr : &*it;
}

whisper_context * LoadGpuContext(const std::string & modelPath, const GpuCandidate & candidate) {
    FreeContext();

    whisper_context_params contextParams = whisper_context_default_params();
    contextParams.use_gpu = true;
    contextParams.flash_attn = false;
    contextParams.gpu_device = candidate.gpuOrdinal;

    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "trying %s: ordinal=%d backend=%s device=%s",
        BackendKindName(candidate.kind),
        candidate.gpuOrdinal,
        candidate.backendName.c_str(),
        candidate.deviceName.c_str()
    );

    gContext = whisper_init_from_file_with_params(modelPath.c_str(), contextParams);
    if (gContext == nullptr) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kTag,
            "%s model initialization failed on %s",
            BackendKindName(candidate.kind),
            candidate.deviceName.c_str()
        );
        return nullptr;
    }

    gModelPath = modelPath;
    gBackend = candidate.kind;
    gBackendDevice = candidate.deviceName;
    __android_log_print(ANDROID_LOG_INFO, kTag, "Whisper ready: %s", BackendLabel().c_str());
    return gContext;
}

whisper_context * LoadCpuContext(const std::string & modelPath) {
    FreeContext();
    whisper_context_params contextParams = whisper_context_default_params();
    contextParams.use_gpu = false;
    contextParams.flash_attn = false;
    gContext = whisper_init_from_file_with_params(modelPath.c_str(), contextParams);
    if (gContext == nullptr) return nullptr;
    gModelPath = modelPath;
    gBackend = RuntimeBackend::Cpu;
    gBackendDevice.clear();
    __android_log_print(ANDROID_LOG_WARN, kTag, "Whisper ready in explicit CPU mode");
    return gContext;
}

whisper_context * PreparePreferredBackend(const std::string & modelPath, bool allowCpu) {
    if (gContext != nullptr && gModelPath == modelPath) {
        if (gBackend != RuntimeBackend::Cpu || allowCpu) return gContext;
        // A previous explicit slow-CPU run must never make the next normal Generate silently CPU.
        FreeContext();
    }

    const std::vector<GpuCandidate> candidates = EnumerateGpuCandidates();
    const GpuCandidate * vulkan = FindCandidate(candidates, RuntimeBackend::Vulkan);
    const GpuCandidate * opencl = FindCandidate(candidates, RuntimeBackend::OpenCL);
    bool vulkanInitFailed = false;
    bool openclInitFailed = false;

    if (vulkan != nullptr) {
        if (whisper_context * context = LoadGpuContext(modelPath, *vulkan)) return context;
        vulkanInitFailed = true;
    }
    if (opencl != nullptr) {
        if (whisper_context * context = LoadGpuContext(modelPath, *opencl)) return context;
        openclInitFailed = true;
    }

    gLastGpuFailure = "Vulkan ";
    gLastGpuFailure += vulkan == nullptr ? "not exposed" : (vulkanInitFailed ? "initialization failed" : "unavailable");
    gLastGpuFailure += "; OpenCL ";
    gLastGpuFailure += opencl == nullptr ? "not exposed" : (openclInitFailed ? "initialization failed" : "unavailable");

    if (allowCpu) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kTag,
            "GPU backends unavailable (%s); user allowed slow CPU mode",
            gLastGpuFailure.c_str()
        );
        return LoadCpuContext(modelPath);
    }

    __android_log_print(ANDROID_LOG_ERROR, kTag, "GPU backends unavailable: %s", gLastGpuFailure.c_str());
    return nullptr;
}

std::string ResolveLanguage(
    whisper_context * context,
    const std::string & requested,
    const std::vector<float> & samples,
    int threads
) {
    if (!requested.empty() && requested != "auto") return requested;

    if (whisper_pcm_to_mel(context, samples.data(), static_cast<int>(samples.size()), threads) != 0) {
        return "auto";
    }
    const int languageId = whisper_lang_auto_detect(context, 0, threads, nullptr);
    if (languageId < 0) return "auto";
    const char * code = whisper_lang_str(languageId);
    return code == nullptr ? std::string("auto") : std::string(code);
}

whisper_full_params BuildParams(const std::string & language, int threads) {
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = false;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.token_timestamps = true;
    params.max_len = 42;
    params.split_on_word = true;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.language = language.c_str();
    params.detect_language = false;
    return params;
}

int RunWhisper(
    whisper_context * context,
    const std::string & requestedLanguage,
    const std::vector<float> & samples,
    int threads,
    std::string & resolvedLanguage
) {
    resolvedLanguage = ResolveLanguage(context, requestedLanguage, samples, threads);
    whisper_full_params params = BuildParams(resolvedLanguage, threads);
    return whisper_full(context, params, samples.data(), static_cast<int>(samples.size()));
}

int RetryAfterGpuFailure(
    const std::string & modelPath,
    const std::string & requestedLanguage,
    const std::vector<float> & samples,
    int threads,
    bool allowCpu,
    std::string & resolvedLanguage,
    int previousStatus
) {
    int status = previousStatus;
    const RuntimeBackend failedBackend = gBackend;
    __android_log_print(
        ANDROID_LOG_WARN,
        kTag,
        "%s inference failed: %d",
        BackendKindName(failedBackend),
        status
    );

    // If Vulkan initialized but its driver fails during the actual graph, retry the same request on
    // OpenCL before considering CPU. This is the important fallback for phones with incomplete
    // Vulkan compute drivers but a working OEM OpenCL ICD.
    if (failedBackend == RuntimeBackend::Vulkan) {
        const std::vector<GpuCandidate> candidates = EnumerateGpuCandidates();
        const GpuCandidate * opencl = FindCandidate(candidates, RuntimeBackend::OpenCL);
        if (opencl != nullptr) {
            whisper_context * openclContext = LoadGpuContext(modelPath, *opencl);
            if (openclContext != nullptr) {
                status = RunWhisper(openclContext, requestedLanguage, samples, threads, resolvedLanguage);
                if (status == 0) return 0;
                __android_log_print(ANDROID_LOG_WARN, kTag, "OpenCL retry failed: %d", status);
            }
        }
    }

    if (allowCpu && gBackend != RuntimeBackend::Cpu) {
        whisper_context * cpuContext = LoadCpuContext(modelPath);
        if (cpuContext != nullptr) {
            status = RunWhisper(cpuContext, requestedLanguage, samples, threads, resolvedLanguage);
        }
    }
    return status;
}

std::string GpuFailureMessage() {
    std::string detail = gLastGpuFailure.empty() ? "Vulkan and OpenCL could not run this Whisper model" : gLastGpuFailure;
    return std::string(kGpuUnavailablePrefix) + detail + ". CPU mode is available but can be much slower.";
}

} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperLanguageNativeV82_supportedLanguages(
    JNIEnv * env,
    jobject
) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;

    const int maxLanguageId = whisper_lang_max_id();
    const int languageCount = std::max(0, maxLanguageId + 1);
    jobjectArray result = env->NewObjectArray(languageCount, stringClass, nullptr);
    if (result == nullptr) return nullptr;

    for (int id = 0; id < languageCount; ++id) {
        const char * code = whisper_lang_str(id);
        const char * fullName = whisper_lang_str_full(id);
        const std::string encoded = std::string(code == nullptr ? "" : code) + "\t" +
            std::string(fullName == nullptr ? "" : fullName);
        jstring item = env->NewStringUTF(encoded.c_str());
        if (item == nullptr) return nullptr;
        env->SetObjectArrayElement(result, id, item);
        env->DeleteLocalRef(item);
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperNativeV80_prepareBackend(
    JNIEnv * env,
    jobject,
    jstring modelPathValue,
    jboolean allowCpuFallback
) {
    if (modelPathValue == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Whisper model path is required");
        return nullptr;
    }
    const std::string modelPath = JStringToUtf8(env, modelPathValue);
    std::lock_guard<std::mutex> guard(gWhisperMutex);
    whisper_context * context = PreparePreferredBackend(modelPath, allowCpuFallback == JNI_TRUE);
    if (context == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException", GpuFailureMessage());
        return nullptr;
    }
    const std::string label = BackendLabel();
    return env->NewStringUTF(label.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperNativeV80_activeBackend(
    JNIEnv * env,
    jobject
) {
    std::lock_guard<std::mutex> guard(gWhisperMutex);
    const std::string label = BackendLabel();
    return env->NewStringUTF(label.c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperNativeV80_transcribe(
    JNIEnv * env,
    jobject,
    jstring modelPathValue,
    jfloatArray samplesValue,
    jstring languageValue,
    jboolean allowCpuFallback
) {
    if (modelPathValue == nullptr || samplesValue == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Whisper model path and PCM samples are required");
        return nullptr;
    }

    const std::string modelPath = JStringToUtf8(env, modelPathValue);
    const std::string requestedLanguage = JStringToUtf8(env, languageValue);
    const bool allowCpu = allowCpuFallback == JNI_TRUE;
    const jsize sampleCount = env->GetArrayLength(samplesValue);
    if (sampleCount <= 0) {
        jclass stringClass = env->FindClass("java/lang/String");
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    std::vector<float> samples(static_cast<size_t>(sampleCount));
    env->GetFloatArrayRegion(samplesValue, 0, sampleCount, samples.data());
    if (env->ExceptionCheck()) return nullptr;

    std::lock_guard<std::mutex> guard(gWhisperMutex);
    whisper_context * context = PreparePreferredBackend(modelPath, allowCpu);
    if (context == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException", GpuFailureMessage());
        return nullptr;
    }

    const unsigned int cores = std::max(1u, std::thread::hardware_concurrency());
    const int threads = static_cast<int>(std::min(4u, cores));
    std::string resolvedLanguage;
    int status = RunWhisper(context, requestedLanguage, samples, threads, resolvedLanguage);

    if (status != 0 && gBackend != RuntimeBackend::Cpu) {
        status = RetryAfterGpuFailure(
            modelPath,
            requestedLanguage,
            samples,
            threads,
            allowCpu,
            resolvedLanguage,
            status
        );
    }

    if (status != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "whisper_full failed after backend fallback: %d", status);
        if (!allowCpu && gBackend != RuntimeBackend::Cpu) {
            gLastGpuFailure = "Vulkan/OpenCL inference failed";
            ThrowJava(env, "java/lang/IllegalStateException", GpuFailureMessage());
        } else {
            ThrowJava(env, "java/lang/IllegalStateException", "Whisper transcription failed");
        }
        return nullptr;
    }

    const int languageId = whisper_full_lang_id(gContext);
    const char * detected = languageId >= 0 ? whisper_lang_str(languageId) : nullptr;
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "transcription complete: backend=%s requested=%s resolved=%s detected=%s samples=%d",
        BackendLabel().c_str(),
        requestedLanguage.empty() ? "auto" : requestedLanguage.c_str(),
        resolvedLanguage.c_str(),
        detected == nullptr ? "unknown" : detected,
        static_cast<int>(sampleCount)
    );

    const int segmentCount = whisper_full_n_segments(gContext);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(segmentCount, stringClass, nullptr);
    if (result == nullptr) return nullptr;

    for (int index = 0; index < segmentCount; ++index) {
        const int64_t startUs = whisper_full_get_segment_t0(gContext, index) * 10'000LL;
        const int64_t endUs = whisper_full_get_segment_t1(gContext, index) * 10'000LL;
        const std::string text = Trim(whisper_full_get_segment_text(gContext, index));
        const std::string encoded = std::to_string(startUs) + "\t" + std::to_string(endUs) + "\t" + text;
        jstring line = env->NewStringUTF(encoded.c_str());
        env->SetObjectArrayElement(result, index, line);
        env->DeleteLocalRef(line);
    }
    return result;
}
