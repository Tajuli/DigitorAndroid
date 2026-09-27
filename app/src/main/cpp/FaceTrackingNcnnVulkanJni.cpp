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
#include "FaceTrackingGeometry.h"

namespace {
constexpr const char* kTag = "FaceTrackNcnnVk";
constexpr int kDetectorSize = 128;
constexpr int kMeshSize = 192;
constexpr int kAnchorCount = 896;
constexpr int kRegValues = 16;
constexpr int kLandmarkCount = 468;
constexpr float kPi = 3.14159265358979323846f;

using face_tracking::Point;
using face_tracking::Roi;

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

inline float AngleDelta(float a, float b) {
    float d = a - b;
    while (d > kPi) d -= 2.f * kPi;
    while (d < -kPi) d += 2.f * kPi;
    return d;
}

bool RoiNeedsDetectorCorrection(const Roi& tracked, const Roi& detected) {
    if (!tracked.valid || !detected.valid) return true;
    const float dx = tracked.cx - detected.cx;
    const float dy = tracked.cy - detected.cy;
    const float centerDistance = std::sqrt(dx * dx + dy * dy);
    const float scale = std::max(48.f, detected.side);
    const float ratio = tracked.side / scale;
    return centerDistance > scale * .12f ||
        ratio < .78f || ratio > 1.28f ||
        std::fabs(AngleDelta(tracked.angle, detected.angle)) > .28f;
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

    struct Detection {
        float score = 0.f;
        float cx = 0.f;
        float cy = 0.f;
        float w = 0.f;
        float h = 0.f;
        Point eye0;
        Point eye1;
    };

    auto decode = [&](int index, float probability) -> Detection {
        const Point anchor = Anchors()[index];
        Detection d;
        d.score = probability;
        d.cx = RegAt(reg, index, 0) / kDetectorSize + anchor.x;
        d.cy = RegAt(reg, index, 1) / kDetectorSize + anchor.y;
        d.w = std::fabs(RegAt(reg, index, 2)) / kDetectorSize;
        d.h = std::fabs(RegAt(reg, index, 3)) / kDetectorSize;
        d.eye0 = Point{
            RegAt(reg, index, 4) / kDetectorSize + anchor.x,
            RegAt(reg, index, 5) / kDetectorSize + anchor.y,
        };
        d.eye1 = Point{
            RegAt(reg, index, 6) / kDetectorSize + anchor.x,
            RegAt(reg, index, 7) / kDetectorSize + anchor.y,
        };
        return d;
    };

    std::vector<Detection> candidates;
    candidates.reserve(64);
    int bestIndex = -1;
    float bestScore = .5f;
    for (int i = 0; i < kAnchorCount; ++i) {
        const float probability = Sigmoid(ScoreAt(score, i));
        if (probability < .5f) continue;
        candidates.push_back(decode(i, probability));
        if (probability > bestScore) {
            bestScore = probability;
            bestIndex = static_cast<int>(candidates.size()) - 1;
        }
    }
    if (bestIndex < 0 || candidates.empty()) return false;

    const Detection best = candidates[bestIndex];
    auto iou = [](const Detection& a, const Detection& b) -> float {
        const float ax1 = a.cx - a.w * .5f, ay1 = a.cy - a.h * .5f;
        const float ax2 = a.cx + a.w * .5f, ay2 = a.cy + a.h * .5f;
        const float bx1 = b.cx - b.w * .5f, by1 = b.cy - b.h * .5f;
        const float bx2 = b.cx + b.w * .5f, by2 = b.cy + b.h * .5f;
        const float iw = std::max(0.f, std::min(ax2, bx2) - std::max(ax1, bx1));
        const float ih = std::max(0.f, std::min(ay2, by2) - std::max(ay1, by1));
        const float inter = iw * ih;
        const float uni = a.w * a.h + b.w * b.h - inter;
        return uni > 1e-9f ? inter / uni : 0.f;
    };

    // MediaPipe BlazeFace uses weighted NMS. Blend the highest-score face with its overlapping
    // anchors instead of letting the ROI jump when two neighboring SSD anchors exchange rank.
    float weightSum = 0.f;
    Detection blended;
    for (const Detection& candidate : candidates) {
        if (iou(best, candidate) <= .30f) continue;
        const float weight = candidate.score;
        weightSum += weight;
        blended.cx += candidate.cx * weight;
        blended.cy += candidate.cy * weight;
        blended.w += candidate.w * weight;
        blended.h += candidate.h * weight;
        blended.eye0.x += candidate.eye0.x * weight;
        blended.eye0.y += candidate.eye0.y * weight;
        blended.eye1.x += candidate.eye1.x * weight;
        blended.eye1.y += candidate.eye1.y * weight;
    }
    if (weightSum <= 0.f) return false;
    blended.cx /= weightSum;
    blended.cy /= weightSum;
    blended.w /= weightSum;
    blended.h /= weightSum;
    blended.eye0.x /= weightSum;
    blended.eye0.y /= weightSum;
    blended.eye1.x /= weightSum;
    blended.eye1.y /= weightSum;

    const float cx = (blended.cx * kDetectorSize - padX) / scale;
    const float cy = (blended.cy * kDetectorSize - padY) / scale;
    const float bw = blended.w * kDetectorSize / scale;
    const float bh = blended.h * kDetectorSize / scale;
    const Point eye0{
        (blended.eye0.x * kDetectorSize - padX) / scale,
        (blended.eye0.y * kDetectorSize - padY) / scale,
    };
    const Point eye1{
        (blended.eye1.x * kDetectorSize - padX) / scale,
        (blended.eye1.y * kDetectorSize - padY) / scale,
    };

    roiOut->cx = cx;
    roiOut->cy = cy;
    roiOut->side = std::max(48.f, 1.5f * std::max(bw, bh));
    roiOut->angle = std::atan2(eye1.y - eye0.y, eye1.x - eye0.x);
    roiOut->valid = std::isfinite(roiOut->cx) && std::isfinite(roiOut->cy) &&
        std::isfinite(roiOut->side) && std::isfinite(roiOut->angle);
    return roiOut->valid;
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
    Point points[kLandmarkCount];
    for (int i = 0; i < kLandmarkCount; ++i) {
        points[i] = MapMeshPoint(lm, i, roi);
        if (!std::isfinite(points[i].x) || !std::isfinite(points[i].y)) return false;
    }

    const Point lOuter = points[33];
    const Point lInner = points[133];
    const Point lTop = points[159];
    const Point lBottom = points[145];
    const Point rOuter = points[362];
    const Point rInner = points[263];
    const Point rTop = points[386];
    const Point rBottom = points[374];

    // Use one face-level roll for both eyes. Per-eye corner roll is much noisier and was producing
    // +60/-80 degree laser directions in the device recording even while the head was nearly level.
    // MediaPipe's tracking ROI itself uses landmark 33 -> 263 for this same eye-line direction.
    const float globalRoll = std::atan2(
        points[263].y - points[33].y,
        points[263].x - points[33].x);

    auto fillEye = [&](int offset, const Point& outer, const Point& inner,
                       const Point& top, const Point& bottom) {
        const float eyeWidth = std::max(3.f, Distance(outer, inner));
        const float vertical = Distance(top, bottom);
        output[offset + 0] = ((outer.x + inner.x) * 0.5f) / width;
        output[offset + 1] = ((outer.y + inner.y) * 0.5f) / height;
        output[offset + 2] = (eyeWidth * 0.5f) / width;
        output[offset + 3] = globalRoll;
        output[offset + 4] = Clamp((vertical / eyeWidth - .035f) / .18f, 0.f, 1.f);
    };
    fillEye(0, lOuter, lInner, lTop, lBottom);
    fillEye(5, rOuter, rInner, rTop, rBottom);

    float rawMinX = points[0].x, rawMinY = points[0].y;
    float rawMaxX = points[0].x, rawMaxY = points[0].y;
    for (int i = 1; i < kLandmarkCount; ++i) {
        rawMinX = std::min(rawMinX, points[i].x);
        rawMinY = std::min(rawMinY, points[i].y);
        rawMaxX = std::max(rawMaxX, points[i].x);
        rawMaxY = std::max(rawMaxY, points[i].y);
    }

    const float faceW = rawMaxX - rawMinX;
    const float faceH = rawMaxY - rawMinY;
    const float leftX = output[0] * width;
    const float leftY = output[1] * height;
    const float rightX = output[5] * width;
    const float rightY = output[6] * height;
    const float eyeDistance = Distance(Point{leftX, leftY}, Point{rightX, rightY});
    const Point eyeMid{(leftX + rightX) * .5f, (leftY + rightY) * .5f};
    const Point roiCenter{roi.cx, roi.cy};
    const float eyeMidOffset = Distance(eyeMid, roiCenter);
    const float roiSide = std::max(48.f, roi.side);

    // The mesh is allowed to move within the crop, but a single frame cannot teleport the eye
    // pair to the crop edge or rotate the eye line by ~70 degrees while the ROI remains level.
    // Reject such output and let the caller reacquire from BlazeFace on the same source frame.
    if (eyeDistance < roiSide * .10f || eyeDistance > roiSide * .55f ||
        eyeMidOffset > roiSide * .34f ||
        std::fabs(AngleDelta(globalRoll, roi.angle)) > .55f) {
        return false;
    }

    // Reject a self-propagating bad crop instead of storing obviously impossible eye geometry.
    // These bounds are intentionally broad: they reject the device failure (an eye hundreds of
    // pixels outside the face) while allowing strong head turns and perspective changes.
    if (faceW < 24.f || faceH < 24.f ||
        eyeDistance < faceW * .12f || eyeDistance > faceW * .78f ||
        leftX < rawMinX - faceW * .12f || leftX > rawMaxX + faceW * .12f ||
        rightX < rawMinX - faceW * .12f || rightX > rawMaxX + faceW * .12f ||
        leftY < rawMinY - faceH * .10f || leftY > rawMaxY + faceH * .70f ||
        rightY < rawMinY - faceH * .10f || rightY > rawMaxY + faceH * .70f ||
        !std::isfinite(globalRoll)) {
        return false;
    }

    const float minX = Clamp(rawMinX, 0.f, static_cast<float>(width));
    const float minY = Clamp(rawMinY, 0.f, static_cast<float>(height));
    const float maxX = Clamp(rawMaxX, 0.f, static_cast<float>(width));
    const float maxY = Clamp(rawMaxY, 0.f, static_cast<float>(height));

    output[10] = minX / width;
    output[11] = minY / height;
    output[12] = maxX / width;
    output[13] = maxY / height;

    const Point mouthL = points[61];
    const Point mouthR = points[291];
    const Point mouthT = points[13];
    const Point mouthB = points[14];
    output[14] = Clamp(std::min({mouthL.x, mouthR.x, mouthT.x, mouthB.x}) / width, 0.f, 1.f);
    output[15] = Clamp(std::min({mouthL.y, mouthR.y, mouthT.y, mouthB.y}) / height, 0.f, 1.f);
    output[16] = Clamp(std::max({mouthL.x, mouthR.x, mouthT.x, mouthB.x}) / width, 0.f, 1.f);
    output[17] = Clamp(std::max({mouthL.y, mouthR.y, mouthT.y, mouthB.y}) / height, 0.f, 1.f);

    const Roi next = face_tracking::LandmarkRoi(points, kLandmarkCount, globalRoll);
    if (!next.valid || next.side > 1.6f * std::max(width, height)) return false;
    for (int i = 0; i < 18; ++i) if (!std::isfinite(output[i])) return false;
    engine->roi = next;
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
    engine->roi = Roi{}; // Warm-up pixels must never seed the first real frame.
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

    // Periodically ask BlazeFace for an absolute reference, but do not replace a healthy mesh ROI
    // unless the detector says the tracked crop has materially drifted. This keeps #1724's drift
    // correction without reintroducing a fixed-cadence crop jump.
    if (engine->roi.valid && engine->frameCounter % 6 == 0) {
        Roi detected;
        if (RunDetector(engine, pixels, width, height, &detected) &&
            RoiNeedsDetectorCorrection(engine->roi, detected)) {
            engine->roi = detected;
        }
    }

    if (engine->roi.valid) {
        ok = RunMesh(engine, pixels, width, height, engine->roi, output);
    }

    if (!ok) {
        engine->roi.valid = false;
        Roi acquired;
        if (RunDetector(engine, pixels, width, height, &acquired)) {
            engine->roi = acquired;
            // One mesh pass only. The model's own reference pipeline uses landmarks->ROI for the
            // NEXT video frame; a second mesh pass on the same frame made the crop self-amplify.
            ok = RunMesh(engine, pixels, width, height, engine->roi, output);
        }
    }

    if (!ok) engine->roi.valid = false;
    engine->frameCounter += 1;

    const auto ended = std::chrono::steady_clock::now();
    engine->lastInferenceMs =
        std::chrono::duration<double, std::milli>(ended - started).count();
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

