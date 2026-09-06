#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>

#include <gpu.h>
#include <net.h>

namespace {
constexpr const char* kTag = "PpMattingNcnnVk";
constexpr int kModelSize = 512;
constexpr int kPlane = kModelSize * kModelSize;
constexpr int kInputCount = kPlane * 3;

std::once_flag gGpuInitOnce;
int gGpuInitResult = -1;

bool EnsureVulkanRuntime() {
    std::call_once(gGpuInitOnce, []() {
        try {
            gGpuInitResult = ncnn::create_gpu_instance();
            if (gGpuInitResult == 0 && ncnn::get_gpu_count() <= 0) {
                gGpuInitResult = -2;
            }
        } catch (...) {
            gGpuInitResult = -3;
        }
    });

    if (gGpuInitResult != 0 || ncnn::get_gpu_count() <= 0) return false;
    ncnn::VulkanDevice* device = ncnn::get_gpu_device(ncnn::get_default_gpu_index());
    return device != nullptr && device->is_valid();
}

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

struct Engine {
    ncnn::Net net;
    int inputIndex = -1;
    int outputIndex = -1;
    int gpuIndex = -1;
    std::string gpuName;
    double lastInferenceMs = -1.0;
};
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanNativeV52_isVulkanAvailable(
        JNIEnv*, jobject) {
    return EnsureVulkanRuntime() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanNativeV52_createEngine(
        JNIEnv* env, jobject, jstring paramPath, jstring binPath, jint threads) {
    try {
        if (!EnsureVulkanRuntime()) return 0;

        const std::string param = JStringToString(env, paramPath);
        const std::string bin = JStringToString(env, binPath);
        if (param.empty() || bin.empty()) return 0;

        auto engine = std::make_unique<Engine>();
        engine->gpuIndex = ncnn::get_default_gpu_index();
        ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(engine->gpuIndex);
        if (vkdev == nullptr || !vkdev->is_valid()) return 0;

        engine->gpuName = vkdev->info.device_name() ? vkdev->info.device_name() : "Vulkan GPU";

        // PP-MattingV2 inference is dispatched to Vulkan. ncnn automatically applies device
        // feature/driver workarounds and can execute an unsupported layer on ARM when necessary.
        engine->net.opt.use_vulkan_compute = true;
        engine->net.opt.num_threads = std::max(1, static_cast<int>(threads));
        engine->net.opt.use_fp16_packed = true;
        engine->net.opt.use_fp16_storage = true;
        engine->net.opt.use_fp16_arithmetic = true;
        engine->net.set_vulkan_device(vkdev);

        if (engine->net.load_param(param.c_str()) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "load_param failed: %s", param.c_str());
            return 0;
        }
        if (engine->net.load_model(bin.c_str()) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "load_model failed: %s", bin.c_str());
            return 0;
        }

        const auto& inputs = engine->net.input_indexes();
        const auto& outputs = engine->net.output_indexes();
        if (inputs.empty() || outputs.empty()) {
            __android_log_print(ANDROID_LOG_ERROR, kTag, "converted PP-MattingV2 has no input/output blobs");
            return 0;
        }
        engine->inputIndex = inputs.front();
        engine->outputIndex = outputs.front();

        __android_log_print(
                ANDROID_LOG_INFO,
                kTag,
                "PP-MattingV2 Vulkan ready on %s (input=%d output=%d)",
                engine->gpuName.c_str(),
                engine->inputIndex,
                engine->outputIndex);
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "create failed: %s", error.what());
        return 0;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "create failed with native exception");
        return 0;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanNativeV52_run(
        JNIEnv* env, jobject, jlong handle, jfloatArray inputArray) {
    if (handle == 0 || inputArray == nullptr) {
        ThrowJava(env, "java/lang/IllegalStateException", "ncnn Vulkan PP-MattingV2 engine is not initialized");
        return nullptr;
    }
    if (env->GetArrayLength(inputArray) != kInputCount) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "PP-MattingV2 input must contain 3x512x512 floats");
        return nullptr;
    }

    auto* engine = reinterpret_cast<Engine*>(handle);
    jfloat* inputData = env->GetFloatArrayElements(inputArray, nullptr);
    if (inputData == nullptr) return nullptr;

    try {
        // ncnn's 3-D Mat is planar CHW, exactly matching the FloatArray prepared by Kotlin.
        ncnn::Mat input(kModelSize, kModelSize, 3, static_cast<void*>(inputData), sizeof(float));
        ncnn::Extractor extractor = engine->net.create_extractor();
        extractor.set_light_mode(true);

        int status = extractor.input(engine->inputIndex, input);
        if (status != 0) {
            env->ReleaseFloatArrayElements(inputArray, inputData, JNI_ABORT);
            ThrowJava(env, "java/lang/RuntimeException", "ncnn Vulkan rejected PP-MattingV2 input");
            return nullptr;
        }

        const auto started = std::chrono::steady_clock::now();
        ncnn::Mat output;
        status = extractor.extract(engine->outputIndex, output);
        const auto ended = std::chrono::steady_clock::now();
        engine->lastInferenceMs =
                std::chrono::duration<double, std::milli>(ended - started).count();

        env->ReleaseFloatArrayElements(inputArray, inputData, JNI_ABORT);
        inputData = nullptr;

        if (status != 0 || output.empty()) {
            ThrowJava(env, "java/lang/RuntimeException", "ncnn Vulkan PP-MattingV2 inference failed");
            return nullptr;
        }
        if (output.elembits() != 32 || output.total() < static_cast<size_t>(kPlane)) {
            ThrowJava(
                    env,
                    "java/lang/IllegalStateException",
                    "ncnn PP-MattingV2 output is not a 512x512 fp32 alpha matte");
            return nullptr;
        }

        const float* alpha = output;
        jfloatArray result = env->NewFloatArray(kPlane);
        if (result == nullptr) return nullptr;
        env->SetFloatArrayRegion(result, 0, kPlane, alpha);
        return result;
    } catch (const std::exception& error) {
        if (inputData != nullptr) env->ReleaseFloatArrayElements(inputArray, inputData, JNI_ABORT);
        ThrowJava(env, "java/lang/RuntimeException", std::string("ncnn Vulkan inference failed: ") + error.what());
        return nullptr;
    } catch (...) {
        if (inputData != nullptr) env->ReleaseFloatArrayElements(inputArray, inputData, JNI_ABORT);
        ThrowJava(env, "java/lang/RuntimeException", "ncnn Vulkan inference failed with native exception");
        return nullptr;
    }
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanNativeV52_lastInferenceMs(
        JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return -1.0;
    return reinterpret_cast<Engine*>(handle)->lastInferenceMs;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanNativeV52_gpuName(
        JNIEnv* env, jobject, jlong handle) {
    if (handle == 0) return env->NewStringUTF("Vulkan GPU");
    const auto* engine = reinterpret_cast<Engine*>(handle);
    return env->NewStringUTF(engine->gpuName.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanNativeV52_destroy(
        JNIEnv*, jobject, jlong handle) {
    if (handle != 0) delete reinterpret_cast<Engine*>(handle);
    // Keep ncnn's process-wide Vulkan instance alive. Repeated create/destroy during editor use is
    // safer and faster than tearing the GPU driver down between Analyze operations.
}
