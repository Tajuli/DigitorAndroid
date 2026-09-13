#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <whisper.h>

namespace {
constexpr const char * kTag = "DigitorWhisperV80";
std::mutex gWhisperMutex;
whisper_context * gContext = nullptr;
std::string gModelPath;

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

void FreeContext() {
    if (gContext != nullptr) {
        whisper_free(gContext);
        gContext = nullptr;
    }
    gModelPath.clear();
}

whisper_context * LoadContext(const std::string & modelPath) {
    if (gContext != nullptr && gModelPath == modelPath) return gContext;
    FreeContext();

    whisper_context_params contextParams = whisper_context_default_params();
    // Keep Auto Caption CPU-only. Digitor's video/PP-Matting pipeline already uses Vulkan, while
    // tiny-q5_1 Whisper is small enough for the conservative Android CPU backend and avoids a
    // second Vulkan runtime competing with rendering or depending on newer loader symbols.
    contextParams.use_gpu = false;
    contextParams.flash_attn = false;
    gContext = whisper_init_from_file_with_params(modelPath.c_str(), contextParams);
    if (gContext != nullptr) {
        gModelPath = modelPath;
        __android_log_print(ANDROID_LOG_INFO, kTag, "Whisper model loaded with CPU backend");
    }
    return gContext;
}

whisper_context * GetOrLoadContext(const std::string & modelPath) {
    return LoadContext(modelPath);
}

std::string ResolveLanguage(
    whisper_context * context,
    const std::string & requested,
    const std::vector<float> & samples,
    int threads
) {
    if (!requested.empty() && requested != "auto") return requested;

    // Detect first, then transcribe with an explicit language token. This makes Auto mode behave
    // like a manual language selection after detection and gives the decoder the correct native
    // writing-system prior instead of relying on a loosely resolved "auto" path.
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

    // max_len/split_on_word only produce subtitle-sized boundaries when token timestamps are on.
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
    jstring modelPathValue
) {
    if (modelPathValue == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Whisper model path is required");
        return nullptr;
    }
    const std::string modelPath = JStringToUtf8(env, modelPathValue);
    std::lock_guard<std::mutex> guard(gWhisperMutex);
    whisper_context * context = GetOrLoadContext(modelPath);
    if (context == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException", "Could not load the Whisper model");
        return nullptr;
    }
    return env->NewStringUTF("CPU");
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperNativeV80_transcribe(
    JNIEnv * env,
    jobject,
    jstring modelPathValue,
    jfloatArray samplesValue,
    jstring languageValue
) {
    if (modelPathValue == nullptr || samplesValue == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Whisper model path and PCM samples are required");
        return nullptr;
    }

    const std::string modelPath = JStringToUtf8(env, modelPathValue);
    const std::string requestedLanguage = JStringToUtf8(env, languageValue);
    const jsize sampleCount = env->GetArrayLength(samplesValue);
    if (sampleCount <= 0) {
        jclass stringClass = env->FindClass("java/lang/String");
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    std::vector<float> samples(static_cast<size_t>(sampleCount));
    env->GetFloatArrayRegion(samplesValue, 0, sampleCount, samples.data());
    if (env->ExceptionCheck()) return nullptr;

    std::lock_guard<std::mutex> guard(gWhisperMutex);
    whisper_context * context = GetOrLoadContext(modelPath);
    if (context == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException", "Could not load the Whisper model");
        return nullptr;
    }

    const unsigned int cores = std::max(1u, std::thread::hardware_concurrency());
    const int threads = static_cast<int>(std::min(4u, cores));
    std::string resolvedLanguage;
    const int status = RunWhisper(context, requestedLanguage, samples, threads, resolvedLanguage);

    if (status != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "whisper_full failed: %d", status);
        ThrowJava(env, "java/lang/IllegalStateException", "Whisper transcription failed");
        return nullptr;
    }

    const int languageId = whisper_full_lang_id(context);
    const char * detected = languageId >= 0 ? whisper_lang_str(languageId) : nullptr;
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "transcription complete: backend=cpu requested=%s resolved=%s detected=%s samples=%d",
        requestedLanguage.empty() ? "auto" : requestedLanguage.c_str(),
        resolvedLanguage.c_str(),
        detected == nullptr ? "unknown" : detected,
        static_cast<int>(sampleCount)
    );

    const int segmentCount = whisper_full_n_segments(context);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(segmentCount, stringClass, nullptr);
    if (result == nullptr) return nullptr;

    for (int index = 0; index < segmentCount; ++index) {
        const int64_t startUs = whisper_full_get_segment_t0(context, index) * 10'000LL;
        const int64_t endUs = whisper_full_get_segment_t1(context, index) * 10'000LL;
        const std::string text = Trim(whisper_full_get_segment_text(context, index));
        const std::string encoded = std::to_string(startUs) + "\t" + std::to_string(endUs) + "\t" + text;
        jstring line = env->NewStringUTF(encoded.c_str());
        env->SetObjectArrayElement(result, index, line);
        env->DeleteLocalRef(line);
    }
    return result;
}
