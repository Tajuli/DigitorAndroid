#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

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
        gGpuInitResult = ncnn::create_gpu_instance();
        if (gGpuInitResult == 0 && ncnn::get_gpu_count() <= 0) {
            gGpuInitResult = -2;
        }
    });

    if (gGpuInitResult != 0 || ncnn::get_gpu_count() <= 0) return false;
    const int gpuIndex = ncnn::get_default_gpu_index();
    if (gpuIndex < 0) return false;
    ncnn::VulkanDevice* device = ncnn::get_gpu_device(gpuIndex);
    return device != nullptr && device->is_valid();
}

std::string JStringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void ThrowJava(JNIEnv* env, const char* type, const std::string& message) {
    jclass klass = env->FindClass(type);
    if (klass) env->ThrowNew(klass, message.c_str());
}

struct Engine {
    ncnn::Net net;
    ncnn::VulkanDevice* vkdev = nullptr;
    ncnn::VkAllocator* blobAllocator = nullptr;
    ncnn::VkAllocator* workspaceAllocator = nullptr;
    ncnn::VkAllocator* stagingAllocator = nullptr;
    int inputIndex = -1;
    int outputIndex = -1;
    int gpuIndex = -1;
    std::string gpuName;
    double lastInferenceMs = -1.0;

    ~Engine() {
        if (vkdev != nullptr) {
            if (blobAllocator != nullptr) vkdev->reclaim_blob_allocator(blobAllocator);
            if (workspaceAllocator != nullptr) vkdev->reclaim_blob_allocator(workspaceAllocator);
            if (stagingAllocator != nullptr) vkdev->reclaim_staging_allocator(stagingAllocator);
        }
    }
};

void ConfigureExtractor(Engine* engine, ncnn::Extractor& extractor) {
    extractor.set_light_mode(true);
    if (engine->blobAllocator != nullptr) extractor.set_blob_vkallocator(engine->blobAllocator);
    if (engine->workspaceAllocator != nullptr) extractor.set_workspace_vkallocator(engine->workspaceAllocator);
    if (engine->stagingAllocator != nullptr) extractor.set_staging_vkallocator(engine->stagingAllocator);
}

int RunInference(Engine* engine, const ncnn::Mat& input, ncnn::Mat& output, double* elapsedMs) {
    ncnn::Extractor extractor = engine->net.create_extractor();
    ConfigureExtractor(engine, extractor);

    int status = extractor.input(engine->inputIndex, input);
    if (status != 0) return status;

    const auto started = std::chrono::steady_clock::now();
    status = extractor.extract(engine->outputIndex, output);
    const auto ended = std::chrono::steady_clock::now();
    if (elapsedMs != nullptr) {
        *elapsedMs = std::chrono::duration<double, std::milli>(ended - started).count();
    }
    return status;
}

void WarmUp(Engine* engine) {
    std::vector<float> zeros(kInputCount, 0.0f);
    ncnn::Mat input(kModelSize, kModelSize, 3, zeros.data(), sizeof(float));
    ncnn::Mat output;
    double warmupMs = -1.0;
    const int status = RunInference(engine, input, output, &warmupMs);
    if (status == 0 && !output.empty()) {
        __android_log_print(
                ANDROID_LOG_INFO,
                kTag,
                "Vulkan warm-up complete on %s in %.1f ms",
                engine->gpuName.c_str(),
                warmupMs);
    } else {
        __android_log_print(ANDROID_LOG_WARN, kTag, "Vulkan warm-up failed with status=%d", status);
    }
    engine->lastInferenceMs = -1.0;
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanNativeV52_isVulkanAvailable(
        JNIEnv*, jobject) {
    return EnsureVulkanRuntime() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanNativeV52_createEngine(
        JNIEnv* env, jobject, jstring paramPath, jstring binPath, jint threads) {
    if (!EnsureVulkanRuntime()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "No usable Vulkan compute device");
        return 0;
    }

    const std::string param = JStringToString(env, paramPath);
    if (env->ExceptionCheck()) return 0;
    const std::string bin = JStringToString(env, binPath);
    if (env->ExceptionCheck()) return 0;
    if (param.empty() || bin.empty()) return 0;

    auto engine = std::make_unique<Engine>();
    engine->gpuIndex = ncnn::get_default_gpu_index();
    if (engine->gpuIndex < 0) return 0;

    ncnn::VulkanDevice* vkdev = ncnn::get_gpu_device(engine->gpuIndex);
    if (vkdev == nullptr || !vkdev->is_valid()) return 0;
    engine->vkdev = vkdev;

    const ncnn::GpuInfo& gpuInfo = ncnn::get_gpu_info(engine->gpuIndex);
    const char* deviceName = gpuInfo.device_name();
    engine->gpuName = (deviceName != nullptr && deviceName[0] != '\0') ? deviceName : "Vulkan GPU";

    engine->net.opt.use_vulkan_compute = true;
    engine->net.opt.num_threads = std::max(1, static_cast<int>(threads));
    engine->net.opt.use_fp16_packed = gpuInfo.support_fp16_packed();
    engine->net.opt.use_fp16_storage = gpuInfo.support_fp16_storage();
    engine->net.opt.use_fp16_arithmetic = gpuInfo.support_fp16_arithmetic();
    engine->net.set_vulkan_device(vkdev);

    engine->blobAllocator = vkdev->acquire_blob_allocator();
    engine->workspaceAllocator = vkdev->acquire_blob_allocator();
    engine->stagingAllocator = vkdev->acquire_staging_allocator();

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
            "PP-MattingV2 Vulkan ready on %s (input=%d output=%d fp16-storage=%d fp16-arithmetic=%d)",
            engine->gpuName.c_str(),
            engine->inputIndex,
            engine->outputIndex,
            gpuInfo.support_fp16_storage() ? 1 : 0,
            gpuInfo.support_fp16_arithmetic() ? 1 : 0);

    WarmUp(engine.get());
    return reinterpret_cast<jlong>(engine.release());
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

    ncnn::Mat input(kModelSize, kModelSize, 3, static_cast<void*>(inputData), sizeof(float));
    ncnn::Mat output;
    const int status = RunInference(engine, input, output, &engine->lastInferenceMs);

    env->ReleaseFloatArrayElements(inputArray, inputData, JNI_ABORT);

    if (status != 0 || output.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "extract failed with status=%d", status);
        ThrowJava(env, "java/lang/RuntimeException", "ncnn Vulkan PP-MattingV2 inference failed");
        return nullptr;
    }
    if (output.elembits() != 32 || output.total() < static_cast<size_t>(kPlane)) {
        __android_log_print(
                ANDROID_LOG_ERROR,
                kTag,
                "Unexpected output: bits=%d total=%zu dims=%d w=%d h=%d c=%d",
                output.elembits(),
                output.total(),
                output.dims,
                output.w,
                output.h,
                output.c);
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
}
