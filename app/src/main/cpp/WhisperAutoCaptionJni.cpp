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

whisper_context * GetOrLoadContext(const std::string & modelPath) {
    if (gContext != nullptr && gModelPath == modelPath) return gContext;
    if (gContext != nullptr) {
        whisper_free(gContext);
        gContext = nullptr;
        gModelPath.clear();
    }

    whisper_context_params contextParams = whisper_context_default_params();
    // CPU inference is the most portable Android path. The app's Vulkan backend remains dedicated
    // to video processing; using it here would compete with preview/cutout for device GPU memory.
    contextParams.use_gpu = false;
    contextParams.flash_attn = false;
    gContext = whisper_init_from_file_with_params(modelPath.c_str(), contextParams);
    if (gContext != nullptr) gModelPath = modelPath;
    return gContext;
}

} // namespace

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
    const std::string language = JStringToUtf8(env, languageValue);
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

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const unsigned int cores = std::max(1u, std::thread::hardware_concurrency());
    params.n_threads = static_cast<int>(std::min(4u, cores));
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = false;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.token_timestamps = false;
    params.max_len = 56;
    params.split_on_word = true;
    params.suppress_blank = true;

    const bool autoLanguage = language.empty() || language == "auto";
    // In whisper.cpp, detect_language=true is a language-detection-only mode and returns before
    // normal transcription. For Auto captions we instead pass language="auto" while keeping
    // detect_language=false so whisper_full auto-detects the language and still emits segments.
    params.language = autoLanguage ? "auto" : language.c_str();
    params.detect_language = false;

    const int status = whisper_full(context, params, samples.data(), static_cast<int>(samples.size()));
    if (status != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "whisper_full failed: %d", status);
        ThrowJava(env, "java/lang/IllegalStateException", "Whisper transcription failed");
        return nullptr;
    }

    const int languageId = whisper_full_lang_id(context);
    if (languageId >= 0) {
        const char * detected = whisper_lang_str(languageId);
        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "transcription complete: requested=%s detected=%s samples=%d",
            autoLanguage ? "auto" : language.c_str(),
            detected == nullptr ? "unknown" : detected,
            static_cast<int>(sampleCount)
        );
    }

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
