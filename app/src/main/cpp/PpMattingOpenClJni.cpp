#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#ifdef DIGITOR_PADDLE_OPENCL
#include "paddle_api.h"
#endif

namespace {
constexpr const char* kTag = "PpMattingOpenCL";
constexpr int kModelSize = 512;
constexpr int kPlane = kModelSize * kModelSize;
constexpr int kInputCount = kPlane * 3;

#ifdef DIGITOR_PADDLE_OPENCL
struct Engine {
    std::shared_ptr<paddle::lite_api::PaddlePredictor> predictor;
    double lastInferenceMs = 0.0;
};
#endif

std::string JStringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

void ThrowJava(JNIEnv* env, const char* type, const std::string& message) {
    jclass klass = env->FindClass(type);
    if (klass) env->ThrowNew(klass, message.c_str());
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tajuli_digitorandroid_editor_processing_PaddleLiteOpenClNativeV51_isOpenClAvailable(
        JNIEnv*, jobject) {
#ifdef DIGITOR_PADDLE_OPENCL
    try {
        return ::IsOpenCLBackendValid(false) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_tajuli_digitorandroid_editor_processing_PaddleLiteOpenClNativeV51_create(
        JNIEnv* env, jobject, jstring modelPath, jint threads) {
#ifdef DIGITOR_PADDLE_OPENCL
    try {
        if (!::IsOpenCLBackendValid(false)) return 0;

        const std::string path = JStringToString(env, modelPath);
        paddle::lite_api::MobileConfig config;
        config.set_model_from_file(path);
        config.set_threads(std::max(1, static_cast<int>(threads)));
        config.set_power_mode(paddle::lite_api::PowerMode::LITE_POWER_NO_BIND);

        auto engine = std::make_unique<Engine>();
        engine->predictor = paddle::lite_api::CreatePaddlePredictor(config);
        if (!engine->predictor) return 0;
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "create failed: %s", error.what());
        return 0;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "create failed with native exception");
        return 0;
    }
#else
    (void)env;
    (void)modelPath;
    (void)threads;
    return 0;
#endif
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tajuli_digitorandroid_editor_processing_PaddleLiteOpenClNativeV51_run(
        JNIEnv* env, jobject, jlong handle, jfloatArray inputArray) {
#ifdef DIGITOR_PADDLE_OPENCL
    if (handle == 0 || inputArray == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException", "Paddle Lite OpenCL engine is not initialized");
        return nullptr;
    }
    if (env->GetArrayLength(inputArray) != kInputCount) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "PP-MattingV2 input must contain 3x512x512 floats");
        return nullptr;
    }

    auto* engine = reinterpret_cast<Engine*>(handle);
    try {
        auto input = engine->predictor->GetInput(0);
        input->Resize({1, 3, kModelSize, kModelSize});
        float* inputData = input->mutable_data<float>();
        env->GetFloatArrayRegion(inputArray, 0, kInputCount, inputData);
        if (env->ExceptionCheck()) return nullptr;

        const auto start = std::chrono::steady_clock::now();
        engine->predictor->Run();
        const auto end = std::chrono::steady_clock::now();
        engine->lastInferenceMs = std::chrono::duration<double, std::milli>(end - start).count();

        auto output = engine->predictor->GetOutput(0);
        const auto shape = output->shape();
        int64_t count = 1;
        for (const auto dim : shape) count *= dim;
        if (count < kPlane) {
            ThrowJava(env, "java/lang/IllegalStateException", "PP-MattingV2 output is smaller than 512x512 alpha matte");
            return nullptr;
        }

        const float* outputData = output->data<float>();
        jfloatArray result = env->NewFloatArray(kPlane);
        if (!result) return nullptr;
        env->SetFloatArrayRegion(result, 0, kPlane, outputData);
        return result;
    } catch (const std::exception& error) {
        ThrowJava(env, "java/lang/RuntimeException", std::string("Paddle Lite OpenCL inference failed: ") + error.what());
        return nullptr;
    } catch (...) {
        ThrowJava(env, "java/lang/RuntimeException", "Paddle Lite OpenCL inference failed with native exception");
        return nullptr;
    }
#else
    (void)handle;
    (void)inputArray;
    ThrowJava(env, "java/lang/IllegalStateException", "Paddle Lite OpenCL is available only in arm64 phone builds");
    return nullptr;
#endif
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_tajuli_digitorandroid_editor_processing_PaddleLiteOpenClNativeV51_lastInferenceMs(
        JNIEnv*, jobject, jlong handle) {
#ifdef DIGITOR_PADDLE_OPENCL
    if (handle == 0) return -1.0;
    return reinterpret_cast<Engine*>(handle)->lastInferenceMs;
#else
    (void)handle;
    return -1.0;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_tajuli_digitorandroid_editor_processing_PaddleLiteOpenClNativeV51_destroy(
        JNIEnv*, jobject, jlong handle) {
#ifdef DIGITOR_PADDLE_OPENCL
    if (handle != 0) delete reinterpret_cast<Engine*>(handle);
#else
    (void)handle;
#endif
}
