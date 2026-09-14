#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "whisper.h"

namespace {
constexpr const char * TAG = "DigitorWhisperCpp";

void throwIllegalState(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

std::string cleanSegmentText(const char * raw) {
    std::string value = raw == nullptr ? "" : std::string(raw);
    for (char & ch : value) {
        if (ch == '\n' || ch == '\r' || ch == '\t') ch = ' ';
    }
    const auto first = value.find_first_not_of(' ');
    if (first == std::string::npos) return {};
    const auto last = value.find_last_not_of(' ');
    value = value.substr(first, last - first + 1);

    std::string compact;
    compact.reserve(value.size());
    bool previousSpace = false;
    for (char ch : value) {
        const bool isSpace = ch == ' ';
        if (isSpace && previousSpace) continue;
        compact.push_back(ch);
        previousSpace = isSpace;
    }
    return compact;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperCppNativeV78_createContext(
        JNIEnv * env,
        jobject /* thiz */,
        jstring modelPath,
        jboolean useGpu) {
    if (modelPath == nullptr) {
        throwIllegalState(env, "Auto CC model path is null");
        return 0;
    }

    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = useGpu == JNI_TRUE;
    cparams.flash_attn = false;

    whisper_context * ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        throwIllegalState(env, useGpu == JNI_TRUE
            ? "Could not initialize whisper.cpp Vulkan context"
            : "Could not initialize whisper.cpp CPU context");
        return 0;
    }

    __android_log_print(ANDROID_LOG_INFO, TAG, "whisper.cpp context ready (gpu=%d)", useGpu == JNI_TRUE ? 1 : 0);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperCppNativeV78_freeContext(
        JNIEnv * /* env */,
        jobject /* thiz */,
        jlong contextPtr) {
    auto * ctx = reinterpret_cast<whisper_context *>(contextPtr);
    if (ctx != nullptr) whisper_free(ctx);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperCppNativeV78_transcribeSegments(
        JNIEnv * env,
        jobject /* thiz */,
        jlong contextPtr,
        jfloatArray audioData,
        jint threadCount,
        jint bestOf) {
    auto * ctx = reinterpret_cast<whisper_context *>(contextPtr);
    if (ctx == nullptr || audioData == nullptr) {
        throwIllegalState(env, "Auto CC native context/audio is unavailable");
        return nullptr;
    }

    const jsize sampleCount = env->GetArrayLength(audioData);
    if (sampleCount <= 0) {
        jclass stringClass = env->FindClass("java/lang/String");
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    jfloat * samples = env->GetFloatArrayElements(audioData, nullptr);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::clamp(static_cast<int>(threadCount), 1, 8);
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = false;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.token_timestamps = false;
    params.language = "auto";
    params.detect_language = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.temperature = 0.0f;
    params.greedy.best_of = std::clamp(static_cast<int>(bestOf), 1, 5);

    const int status = whisper_full(ctx, params, samples, static_cast<int>(sampleCount));
    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);

    if (status != 0) {
        throwIllegalState(env, "whisper.cpp transcription failed (code " + std::to_string(status) + ")");
        return nullptr;
    }

    const int segmentCount = whisper_full_n_segments(ctx);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(segmentCount, stringClass, nullptr);

    for (int i = 0; i < segmentCount; ++i) {
        const int64_t t0 = whisper_full_get_segment_t0(ctx, i); // centiseconds
        const int64_t t1 = whisper_full_get_segment_t1(ctx, i);
        const std::string text = cleanSegmentText(whisper_full_get_segment_text(ctx, i));
        const std::string encoded = std::to_string(t0) + "\t" + std::to_string(t1) + "\t" + text;
        jstring javaString = env->NewStringUTF(encoded.c_str());
        env->SetObjectArrayElement(result, i, javaString);
        env->DeleteLocalRef(javaString);
    }

    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperCppNativeV78_systemInfo(
        JNIEnv * env,
        jobject /* thiz */) {
    return env->NewStringUTF(whisper_print_system_info());
}
