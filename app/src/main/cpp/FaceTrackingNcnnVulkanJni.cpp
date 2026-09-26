#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <gpu.h>
#include <net.h>

namespace {
constexpr const char* kTag = "FaceTrackNcnnVk";
constexpr int kDetectorSize = 128;
constexpr int kMeshSize = 192;
constexpr int kAnchorCount = 896;
constexpr int kRegValues = 16;
constexpr int kLandmarkCount = 468;
constexpr float kPi = 3.14159265358979323846f;

struct Roi {
    float cx = 0.f;
    float cy = 0.f;
    float side = 0.f;
    float angle = 0.f; // radians, source eye-line angle
    bool valid = false;
};

struct Point {
    float x = 0.f;
    float y = 0.f;
};

struct FaceEngine {
    ncnn::Net detector;
    ncnn::Net mesh;
    ncnn::VulkanDevice* vkdev = nullptr;
    ncnn::VkAllocator* blobAllocator = nullptr;
    ncnn::VkAllocator* workspaceAllocator = nullptr;
    ncnn::VkAllocator* stagingAllocator = nullptr;
    int detectorInput = -1;
    int meshInput = -1;
    std::vector<int> detectorOutputs;
    std::vector<int> meshOutputs;
    bool gpu = false;
    std::string gpuName = "CPU";
    Roi roi;
    int frameCounter = 0;
    double lastInferenceMs = -1.0;

    ~FaceEngine() {
        detector.clear();
        mesh.clear();
        if (vkdev != nullptr) {
            if (blobAllocator != nullptr) vkdev->reclaim_blob_allocator(blobAllocator);
            if (workspaceAllocator != nullptr) vkdev->reclaim_blob_allocator(workspaceAllocator);
            if (stagingAllocator != nullptr) vkdev->reclaim_staging_allocator(stagingAllocator);
        }
    }
};

std::mutex gFaceEngineMutex;

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

inline float Sigmoid(float x) {
    x = std::max(-60.f, std::min(60.f, x));
    return 1.f / (1.f + std::exp(-x));
}

inline float Clamp(float x, float lo, float hi) {
    return std::max(lo, std::min(hi, x));
}

inline int A(jint pixel) { return (pixel >> 24) & 0xff; }
inline int R(jint pixel) { return (pixel >> 16) & 0xff; }
inline int G(jint pixel) { return (pixel >> 8) & 0xff; }
inline int B(jint pixel) { return pixel & 0xff; }

inline float SampleChannel(
        const jint* pixels,
        int width,
        int height,
        float x,
        float y,
        int channel) {
    if (x < 0.f || y < 0.f || x > width - 1.f || y > height - 1.f) return 0.f;
    const int x0 = std::max(0, std::min(width - 1, static_cast<int>(std::floor(x))));
    const int y0 = std::max(0, std::min(height - 1, static_cast<int>(std::floor(y))));
    const int x1 = std::min(width - 1, x0 + 1);
    const int y1 = std::min(height - 1, y0 + 1);
    const float fx = x - x0;
    const float fy = y - y0;

    auto value = [&](int px, int py) -> float {
        const jint p = pixels[py * width + px];
        switch (channel) {
            case 0: return static_cast<float>(R(p));
            case 1: return static_cast<float>(G(p));
            default: return static_cast<float>(B(p));
        }
    };

    const float a = value(x0, y0) * (1.f - fx) + value(x1, y0) * fx;
    const float b = value(x0, y1) * (1.f - fx) + value(x1, y1) * fx;
    return a * (1.f - fy) + b * fy;
}

void ConfigureNet(ncnn::Net& net, FaceEngine* engine, int threads) {
    net.opt.num_threads = std::max(1, threads);
    net.opt.use_vulkan_compute = engine->gpu;
    net.opt.use_packing_layout = true;
    if (engine->gpu && engine->vkdev != nullptr) {
        const int gpuIndex = ncnn::get_default_gpu_index();
        const ncnn::GpuInfo& info = ncnn::get_gpu_info(gpuIndex);
        net.opt.use_subgroup_ops = true;
        net.opt.use_shader_local_memory = true;
        net.opt.use_fp16_packed = info.support_fp16_packed();
        net.opt.use_fp16_storage = info.support_fp16_storage();
        net.opt.use_fp16_arithmetic = info.support_fp16_arithmetic();
        net.opt.use_fp16_uniform = info.support_fp16_uniform();
        net.set_vulkan_device(engine->vkdev);
    }
}

void ConfigureExtractor(FaceEngine* engine, ncnn::Extractor& ex) {
    ex.set_light_mode(true);
    if (engine->gpu && engine->vkdev != nullptr) {
        if (engine->blobAllocator) ex.set_blob_vkallocator(engine->blobAllocator);
        if (engine->workspaceAllocator) ex.set_workspace_vkallocator(engine->workspaceAllocator);
        if (engine->stagingAllocator) ex.set_staging_vkallocator(engine->stagingAllocator);
    }
}

std::vector<Point> GenerateAnchors() {
    std::vector<Point> anchors;
    anchors.reserve(kAnchorCount);
    const int strides[] = {8, 16, 16, 16};
    int idx = 0;
    while (idx < 4) {
        int last = idx;
        while (last < 4 && strides[last] == strides[idx]) ++last;
        const int repeats = 2 * (last - idx);
        const int cells = kDetectorSize / strides[idx];
        for (int y = 0; y < cells; ++y) {
            for (int x = 0; x < cells; ++x) {
                const Point p{
                    (static_cast<float>(x) + 0.5f) / cells,
                    (static_cast<float>(y) + 0.5f) / cells,
                };
                for (int r = 0; r < repeats; ++r) anchors.push_back(p);
            }
        }
        idx = last;
    }
    return anchors;
}

const std::vector<Point>& Anchors() {
    static const std::vector<Point> anchors = GenerateAnchors();
    return anchors;
}

float ScoreAt(const ncnn::Mat& score, int anchor) {
    if (score.total() < kAnchorCount) return -100.f;
    if (score.dims == 2 && score.w == kAnchorCount) {
        return score.row(0)[anchor];
    }
    if (score.dims == 2 && score.h == kAnchorCount) {
        return score.row(anchor)[0];
    }
    return static_cast<const float*>(score)[anchor];
}

float RegAt(const ncnn::Mat& reg, int anchor, int k) {
    if (reg.total() < static_cast<size_t>(kAnchorCount * kRegValues)) return 0.f;
    if (reg.dims == 2 && reg.w == kRegValues && reg.h >= kAnchorCount) {
        return reg.row(anchor)[k];
    }
    if (reg.dims == 2 && reg.w == kAnchorCount && reg.h >= kRegValues) {
        return reg.row(k)[anchor];
    }
    return static_cast<const float*>(reg)[anchor * kRegValues + k];
}

ncnn::Mat BuildDetectorInput(
        const jint* pixels,
        int width,
        int height,
        float* scaleOut,
        float* padXOut,
        float* padYOut) {
    const float scale = static_cast<float>(kDetectorSize) /
        static_cast<float>(std::max(width, height));
    const float scaledW = width * scale;
    const float scaledH = height * scale;
    const float padX = (kDetectorSize - scaledW) * 0.5f;
    const float padY = (kDetectorSize - scaledH) * 0.5f;

    ncnn::Mat input(kDetectorSize, kDetectorSize, 3);
    for (int c = 0; c < 3; ++c) {
        float* dst = input.channel(c);
        for (int y = 0; y < kDetectorSize; ++y) {
            for (int x = 0; x < kDetectorSize; ++x) {
                const float sx = (x - padX + 0.5f) / scale - 0.5f;
                const float sy = (y - padY + 0.5f) / scale - 0.5f;
                const float v = SampleChannel(pixels, width, height, sx, sy, c);
                dst[y * kDetectorSize + x] = v / 127.5f - 1.f;
            }
        }
    }

    *scaleOut = scale;
    *padXOut = padX;
    *padYOut = padY;
    return input;
}

bool RunDetector(
        FaceEngine* engine,
        const jint* pixels,
        int width,
        int height,
        Roi* roiOut) {
    float scale = 1.f, padX = 0.f, padY = 0.f;
    ncnn::Mat input = BuildDetectorInput(pixels, width, height, &scale, &padX, &padY);

    ncnn::Extractor ex = engine->detector.create_extractor();
    ConfigureExtractor(engine, ex);
    if (ex.input(engine->detectorInput, input) != 0) return false;

    ncnn::Mat reg;
    ncnn::Mat score;
    for (int outputIndex : engine->detectorOutputs) {
        ncnn::Mat out;
        if (ex.extract(outputIndex, out) != 0 || out.empty()) continue;
        if (out.total() == kAnchorCount) score = out;
        else if (out.total() >= static_cast<size_t>(kAnchorCount * kRegValues)) reg = out;
    }
    if (reg.empty() || score.empty()) return false;

    float bestScore = 0.5f;
    int best = -1;
    for (int i = 0; i < kAnchorCount; ++i) {
        const float probability = Sigmoid(ScoreAt(score, i));
        if (probability > bestScore) {
            bestScore = probability;
            best = i;
        }
    }
    if (best < 0) return false;

    const Point anchor = Anchors()[best];
    const float cxN = RegAt(reg, best, 0) / kDetectorSize + anchor.x;
    const float cyN = RegAt(reg, best, 1) / kDetectorSize + anchor.y;
    const float wN = std::fabs(RegAt(reg, best, 2)) / kDetectorSize;
    const float hN = std::fabs(RegAt(reg, best, 3)) / kDetectorSize;

    const float cx = (cxN * kDetectorSize - padX) / scale;
    const float cy = (cyN * kDetectorSize - padY) / scale;
    const float bw = wN * kDetectorSize / scale;
    const float bh = hN * kDetectorSize / scale;

    auto keypoint = [&](int k) -> Point {
        const float xN = RegAt(reg, best, 4 + 2 * k) / kDetectorSize + anchor.x;
        const float yN = RegAt(reg, best, 5 + 2 * k) / kDetectorSize + anchor.y;
        return Point{
            (xN * kDetectorSize - padX) / scale,
            (yN * kDetectorSize - padY) / scale,
        };
    };
    const Point eye0 = keypoint(0);
    const Point eye1 = keypoint(1);

    roiOut->cx = cx;
    roiOut->cy = cy;
    roiOut->side = std::max(48.f, 1.5f * std::max(bw, bh));
    roiOut->angle = std::atan2(eye1.y - eye0.y, eye1.x - eye0.x);
    roiOut->valid = true;
    return true;
}

ncnn::Mat BuildMeshInput(
        const jint* pixels,
        int width,
        int height,
        const Roi& roi) {
    ncnn::Mat input(kMeshSize, kMeshSize, 3);
    const float cosA = std::cos(roi.angle);
    const float sinA = std::sin(roi.angle);
    const float unit = roi.side / kMeshSize;

    for (int c = 0; c < 3; ++c) {
        float* dst = input.channel(c);
        for (int y = 0; y < kMeshSize; ++y) {
            const float dy = (y + 0.5f - kMeshSize * 0.5f) * unit;
            for (int x = 0; x < kMeshSize; ++x) {
                const float dx = (x + 0.5f - kMeshSize * 0.5f) * unit;
                const float sx = roi.cx + cosA * dx - sinA * dy;
                const float sy = roi.cy + sinA * dx + cosA * dy;
                dst[y * kMeshSize + x] =
                    SampleChannel(pixels, width, height, sx, sy, c) / 255.f;
            }
        }
    }
    return input;
}

Point MapMeshPoint(const float* lm, int index, const Roi& roi) {
    const float u = lm[index * 3 + 0];
    const float v = lm[index * 3 + 1];
    const float dx = (u - kMeshSize * 0.5f) * roi.side / kMeshSize;
    const float dy = (v - kMeshSize * 0.5f) * roi.side / kMeshSize;
    const float cosA = std::cos(roi.angle);
    const float sinA = std::sin(roi.angle);
    return Point{
        roi.cx + cosA * dx - sinA * dy,
        roi.cy + sinA * dx + cosA * dy,
    };
}

float Distance(const Point& a, const Point& b) {
    const float dx = b.x - a.x;
    const float dy = b.y - a.y;
    return std::sqrt(dx * dx + dy * dy);
}

bool RunMesh(
        FaceEngine* engine,
        const jint* pixels,
        int width,
        int height,
        const Roi& roi,
        float* output) {
    ncnn::Mat input = BuildMeshInput(pixels, width, height, roi);
    ncnn::Extractor ex = engine->mesh.create_extractor();
    ConfigureExtractor(engine, ex);
    if (ex.input(engine->meshInput, input) != 0) return false;

    ncnn::Mat landmarks;
    ncnn::Mat score;
    for (int outputIndex : engine->meshOutputs) {
        ncnn::Mat out;
        if (ex.extract(outputIndex, out) != 0 || out.empty()) continue;
        if (out.total() >= static_cast<size_t>(kLandmarkCount * 3)) landmarks = out;
        else if (out.total() == 1) score = out;
    }
    if (landmarks.empty()) return false;
    if (!score.empty() && Sigmoid(static_cast<const float*>(score)[0]) < 0.5f) return false;

    const float* lm = landmarks;
    const Point lOuter = MapMeshPoint(lm, 33, roi);
    const Point lInner = MapMeshPoint(lm, 133, roi);
    const Point lTop = MapMeshPoint(lm, 159, roi);
    const Point lBottom = MapMeshPoint(lm, 145, roi);
    const Point rOuter = MapMeshPoint(lm, 362, roi);
    const Point rInner = MapMeshPoint(lm, 263, roi);
    const Point rTop = MapMeshPoint(lm, 386, roi);
    const Point rBottom = MapMeshPoint(lm, 374, roi);

    auto fillEye = [&](int offset, const Point& outer, const Point& inner,
                       const Point& top, const Point& bottom) {
        const float eyeWidth = std::max(3.f, Distance(outer, inner));
        const float vertical = Distance(top, bottom);
        output[offset + 0] = ((outer.x + inner.x) * 0.5f) / width;
        output[offset + 1] = ((outer.y + inner.y) * 0.5f) / height;
        output[offset + 2] = (eyeWidth * 0.5f) / width;
        output[offset + 3] = std::atan2(inner.y - outer.y, inner.x - outer.x);
        output[offset + 4] = Clamp((vertical / eyeWidth - 0.035f) / 0.18f, 0.f, 1.f);
    };
    fillEye(0, lOuter, lInner, lTop, lBottom);
    fillEye(5, rOuter, rInner, rTop, rBottom);

    float minX = static_cast<float>(width);
    float minY = static_cast<float>(height);
    float maxX = 0.f;
    float maxY = 0.f;
    for (int i = 0; i < kLandmarkCount; ++i) {
        const Point p = MapMeshPoint(lm, i, roi);
        minX = std::min(minX, p.x);
        minY = std::min(minY, p.y);
        maxX = std::max(maxX, p.x);
        maxY = std::max(maxY, p.y);
    }
    minX = Clamp(minX, 0.f, static_cast<float>(width));
    minY = Clamp(minY, 0.f, static_cast<float>(height));
    maxX = Clamp(maxX, 0.f, static_cast<float>(width));
    maxY = Clamp(maxY, 0.f, static_cast<float>(height));

    output[10] = minX / width;
    output[11] = minY / height;
    output[12] = maxX / width;
    output[13] = maxY / height;

    const Point mouthL = MapMeshPoint(lm, 61, roi);
    const Point mouthR = MapMeshPoint(lm, 291, roi);
    const Point mouthT = MapMeshPoint(lm, 13, roi);
    const Point mouthB = MapMeshPoint(lm, 14, roi);
    output[14] = std::min({mouthL.x, mouthR.x, mouthT.x, mouthB.x}) / width;
    output[15] = std::min({mouthL.y, mouthR.y, mouthT.y, mouthB.y}) / height;
    output[16] = std::max({mouthL.x, mouthR.x, mouthT.x, mouthB.x}) / width;
    output[17] = std::max({mouthL.y, mouthR.y, mouthT.y, mouthB.y}) / height;

    // Landmarks-to-ROI video tracking: keep a generously padded, roll-normalized ROI between
    // detector reacquisitions. This is the motion-safe crop, analogous to PP-Matting's ROI path.
    const float faceW = std::max(1.f, maxX - minX);
    const float faceH = std::max(1.f, maxY - minY);
    const float centerX = (minX + maxX) * 0.5f;
    const float centerY = (minY + maxY) * 0.5f;
    const float lcx = (lOuter.x + lInner.x) * 0.5f;
    const float lcy = (lOuter.y + lInner.y) * 0.5f;
    const float rcx = (rOuter.x + rInner.x) * 0.5f;
    const float rcy = (rOuter.y + rInner.y) * 0.5f;
    engine->roi.cx = centerX;
    engine->roi.cy = centerY;
    engine->roi.side = std::max(64.f, std::max(faceW, faceH) * 2.15f);
    engine->roi.angle = std::atan2(rcy - lcy, rcx - lcx);
    engine->roi.valid = true;
    return true;
}

bool LoadNet(
        ncnn::Net& net,
        FaceEngine* engine,
        const std::string& param,
        const std::string& bin,
        int threads) {
    ConfigureNet(net, engine, threads);
    if (net.load_param(param.c_str()) != 0) return false;
    if (net.load_model(bin.c_str()) != 0) return false;
    return true;
}

void WarmUp(FaceEngine* engine) {
    std::vector<jint> pixels(256 * 256, static_cast<jint>(0xff7f7f7f));
    Roi roi;
    roi.cx = 128.f;
    roi.cy = 128.f;
    roi.side = 160.f;
    roi.valid = true;
    float out[18] = {};
    RunMesh(engine, pixels.data(), 256, 256, roi, out);
    engine->lastInferenceMs = -1.0;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanFaceTrackingNativeV103_createEngine(
        JNIEnv* env,
        jobject,
        jstring detectorParamPath,
        jstring detectorBinPath,
        jstring meshParamPath,
        jstring meshBinPath,
        jint threads,
        jboolean useGpu) {
    std::lock_guard<std::mutex> guard(gFaceEngineMutex);
    const std::string detectorParam = JStringToString(env, detectorParamPath);
    const std::string detectorBin = JStringToString(env, detectorBinPath);
    const std::string meshParam = JStringToString(env, meshParamPath);
    const std::string meshBin = JStringToString(env, meshBinPath);
    if (detectorParam.empty() || detectorBin.empty() || meshParam.empty() || meshBin.empty()) return 0;

    auto engine = std::make_unique<FaceEngine>();
    engine->gpu = useGpu == JNI_TRUE && ncnn::get_gpu_count() > 0;
    if (engine->gpu) {
        const int gpuIndex = ncnn::get_default_gpu_index();
        if (gpuIndex >= 0) {
            engine->vkdev = ncnn::get_gpu_device(gpuIndex);
        }
        if (engine->vkdev == nullptr || !engine->vkdev->is_valid()) {
            engine->gpu = false;
            engine->vkdev = nullptr;
        }
    }

    if (engine->gpu) {
        const int gpuIndex = ncnn::get_default_gpu_index();
        const ncnn::GpuInfo& info = ncnn::get_gpu_info(gpuIndex);
        const char* name = info.device_name();
        engine->gpuName = name && name[0] ? name : "Vulkan GPU";
        engine->blobAllocator = engine->vkdev->acquire_blob_allocator();
        engine->workspaceAllocator = engine->vkdev->acquire_blob_allocator();
        engine->stagingAllocator = engine->vkdev->acquire_staging_allocator();
    }

    if (!LoadNet(engine->detector, engine.get(), detectorParam, detectorBin, threads) ||
        !LoadNet(engine->mesh, engine.get(), meshParam, meshBin, threads)) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Could not load face tracking ncnn models");
        return 0;
    }

    const auto& detectorInputs = engine->detector.input_indexes();
    const auto& meshInputs = engine->mesh.input_indexes();
    if (detectorInputs.empty() || meshInputs.empty()) return 0;
    engine->detectorInput = detectorInputs.front();
    engine->meshInput = meshInputs.front();
    engine->detectorOutputs = engine->detector.output_indexes();
    engine->meshOutputs = engine->mesh.output_indexes();
    if (engine->detectorOutputs.size() < 2 || engine->meshOutputs.size() < 2) return 0;

    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "Face tracking engine ready: backend=%s gpu=%s detectorOut=%zu meshOut=%zu",
        engine->gpu ? "ncnn Vulkan" : "ncnn CPU",
        engine->gpuName.c_str(),
        engine->detectorOutputs.size(),
        engine->meshOutputs.size());

    WarmUp(engine.get());
    return reinterpret_cast<jlong>(engine.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanFaceTrackingNativeV103_runInto(
        JNIEnv* env,
        jobject,
        jlong handle,
        jintArray pixelArray,
        jint width,
        jint height,
        jfloatArray outputArray) {
    if (handle == 0 || pixelArray == nullptr || outputArray == nullptr) return JNI_FALSE;
    if (width <= 1 || height <= 1 || env->GetArrayLength(pixelArray) < width * height ||
        env->GetArrayLength(outputArray) < 18) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Invalid face tracking frame buffers");
        return JNI_FALSE;
    }

    auto* engine = reinterpret_cast<FaceEngine*>(handle);
    jint* pixels = env->GetIntArrayElements(pixelArray, nullptr);
    if (pixels == nullptr) return JNI_FALSE;

    float output[18] = {};
    const auto started = std::chrono::steady_clock::now();

    bool ok = false;
    const bool periodicReacquire =
        !engine->roi.valid || (engine->frameCounter % 6 == 0);

    if (!periodicReacquire) {
        ok = RunMesh(engine, pixels, width, height, engine->roi, output);
    }

    if (!ok) {
        Roi acquired;
        if (RunDetector(engine, pixels, width, height, &acquired)) {
            engine->roi = acquired;
            ok = RunMesh(engine, pixels, width, height, engine->roi, output);
        } else {
            engine->roi.valid = false;
        }
    }

    const auto ended = std::chrono::steady_clock::now();
    engine->lastInferenceMs =
        std::chrono::duration<double, std::milli>(ended - started).count();
    engine->frameCounter += 1;
    env->ReleaseIntArrayElements(pixelArray, pixels, JNI_ABORT);

    if (!ok) return JNI_FALSE;
    env->SetFloatArrayRegion(outputArray, 0, 18, output);
    return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanFaceTrackingNativeV103_isGpu(
        JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    return reinterpret_cast<FaceEngine*>(handle)->gpu ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanFaceTrackingNativeV103_gpuName(
        JNIEnv* env, jobject, jlong handle) {
    if (handle == 0) return env->NewStringUTF("CPU");
    return env->NewStringUTF(reinterpret_cast<FaceEngine*>(handle)->gpuName.c_str());
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanFaceTrackingNativeV103_lastInferenceMs(
        JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return -1.0;
    return reinterpret_cast<FaceEngine*>(handle)->lastInferenceMs;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tajuli_digitorandroid_editor_processing_NcnnVulkanFaceTrackingNativeV103_destroy(
        JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return;
    std::lock_guard<std::mutex> guard(gFaceEngineMutex);
    delete reinterpret_cast<FaceEngine*>(handle);
}
