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
constexpr int kOutputCount = 24;
constexpr float kPi = 3.14159265358979323846f;

using face_tracking::Point;
using face_tracking::Point3;
using face_tracking::FaceOrientation;
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
    bool leftEyeClosed = false;
    bool rightEyeClosed = false;
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

Point3 MapMeshPoint3(const float* lm, int index, const Roi& roi) {
    const float u = lm[index * 3 + 0];
    const float v = lm[index * 3 + 1];
    const float z = lm[index * 3 + 2];
    const float unit = roi.side / kMeshSize;
    const float dx = (u - kMeshSize * 0.5f) * unit;
    const float dy = (v - kMeshSize * 0.5f) * unit;
    const float dz = z * unit;
    const float cosA = std::cos(roi.angle);
    const float sinA = std::sin(roi.angle);
    return Point3{
        roi.cx + cosA * dx - sinA * dy,
        roi.cy + sinA * dx + cosA * dy,
        dz,
    };
}

struct PupilEstimate {
    float x = 0.f;
    float y = 0.f;
    float confidence = 0.f;
};

PupilEstimate EstimatePupil(
        const jint* pixels,
        int width,
        int height,
        const Point& center,
        float roll,
        float halfWidth,
        float halfHeight,
        float openness) {
    PupilEstimate result;
    if (pixels == nullptr || width < 2 || height < 2 || openness < .34f ||
        halfWidth < 2.f || halfHeight < 1.f || !std::isfinite(roll)) {
        return result;
    }

    struct Sample { float u, v, luminance; };
    Sample samples[77];
    int count = 0;
    float sumLuminance = 0.f;
    float minLuminance = 255.f;
    const float c = std::cos(roll), s = std::sin(roll);

    for (int gy = 0; gy < 7; ++gy) {
        const float v = -0.62f + 1.24f * gy / 6.f;
        for (int gx = 0; gx < 11; ++gx) {
            const float u = -0.82f + 1.64f * gx / 10.f;
            if (u * u + 1.35f * v * v > .94f) continue;
            const float lx = u * halfWidth;
            const float ly = v * halfHeight;
            const float sx = center.x + c * lx - s * ly;
            const float sy = center.y + s * lx + c * ly;
            if (sx < 0.f || sy < 0.f || sx > width - 1.f || sy > height - 1.f) continue;
            const float luminance =
                .2126f * SampleChannel(pixels, width, height, sx, sy, 0) +
                .7152f * SampleChannel(pixels, width, height, sx, sy, 1) +
                .0722f * SampleChannel(pixels, width, height, sx, sy, 2);
            samples[count++] = Sample{u, v, luminance};
            sumLuminance += luminance;
            minLuminance = std::min(minLuminance, luminance);
        }
    }
    if (count < 16) return result;

    const float mean = sumLuminance / count;
    const float contrast = mean - minLuminance;
    if (!std::isfinite(contrast) || contrast < 8.f) return result;

    float weightSum = 0.f;
    float weightedX = 0.f;
    float weightedY = 0.f;
    for (int i = 0; i < count; ++i) {
        const float darkness = Clamp(
            (mean - samples[i].luminance) / std::max(contrast, 1.f), 0.f, 1.f);
        if (darkness < .14f) continue;
        const float centerPrior = std::max(
            .25f,
            1.f - .28f * (samples[i].u * samples[i].u + samples[i].v * samples[i].v));
        const float weight = darkness * darkness * centerPrior;
        weightSum += weight;
        weightedX += samples[i].u * weight;
        weightedY += samples[i].v * weight;
    }
    if (weightSum < 1e-4f) return result;

    result.x = Clamp(weightedX / weightSum, -1.f, 1.f);
    result.y = Clamp(weightedY / weightSum, -1.f, 1.f);
    result.confidence = Clamp(contrast / 72.f, 0.f, 1.f) *
        Clamp(weightSum / (count * .22f), 0.f, 1.f);
    if (result.confidence < .10f) return PupilEstimate{};
    return result;
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
    Point3 points3[kLandmarkCount];
    for (int i = 0; i < kLandmarkCount; ++i) {
        points3[i] = MapMeshPoint3(lm, i, roi);
        points[i] = Point{points3[i].x, points3[i].y};
        if (!std::isfinite(points3[i].x) || !std::isfinite(points3[i].y) ||
            !std::isfinite(points3[i].z)) return false;
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
    const float rollDelta = std::isfinite(globalRoll) ? AngleDelta(globalRoll, roi.angle) : 0.f;
    // A bad mesh roll must not poison either the current effect direction or the next ROI.
    // Fall back to the detector/tracked ROI angle for this frame instead of rejecting a face
    // whose eye centers are otherwise perfectly usable.
    const float stableRoll =
        std::isfinite(globalRoll) && std::fabs(rollDelta) <= .90f ? globalRoll : roi.angle;

    // Multi-pair EAR is substantially more reliable for blinks than one vertical lid pair.
    // Left: corners 33/133, lid pairs 159/145, 158/153, 160/144.
    // Right: corners 362/263, lid pairs 386/374, 385/380, 387/373.
    const float leftEar = face_tracking::EyeAspectRatio(
        points[33], points[133],
        points[159], points[145],
        points[158], points[153],
        points[160], points[144]);
    const float rightEar = face_tracking::EyeAspectRatio(
        points[362], points[263],
        points[386], points[374],
        points[385], points[380],
        points[387], points[373]);
    const float leftOpen = face_tracking::BlinkOpenness(leftEar, &engine->leftEyeClosed);
    const float rightOpen = face_tracking::BlinkOpenness(rightEar, &engine->rightEyeClosed);

    auto fillEye = [&](int offset, const Point& outer, const Point& inner, float openness) {
        const float eyeWidth = std::max(3.f, face_tracking::Distance(outer, inner));
        output[offset + 0] = ((outer.x + inner.x) * 0.5f) / width;
        output[offset + 1] = ((outer.y + inner.y) * 0.5f) / height;
        output[offset + 2] = (eyeWidth * 0.5f) / width;
        output[offset + 3] = stableRoll;
        output[offset + 4] = openness;
    };
    fillEye(0, lOuter, lInner, leftOpen);
    fillEye(5, rOuter, rInner, rightOpen);

    const FaceOrientation orientation = face_tracking::FaceOrientationFromPlane(
        points3[234], points3[454], points3[10], points3[152], stableRoll);
    const float headYaw = orientation.valid ? orientation.yaw : 0.f;
    const float headPitch = orientation.valid ? orientation.pitch : 0.f;
    const float headForward = orientation.valid ? orientation.forward : 1.f;

    const Point leftCenter{(lOuter.x + lInner.x) * .5f, (lOuter.y + lInner.y) * .5f};
    const Point rightCenter{(rOuter.x + rInner.x) * .5f, (rOuter.y + rInner.y) * .5f};
    const float leftWidth = std::max(3.f, face_tracking::Distance(lOuter, lInner));
    const float rightWidth = std::max(3.f, face_tracking::Distance(rOuter, rInner));
    const float leftHeight = (
        face_tracking::Distance(points[159], points[145]) +
        face_tracking::Distance(points[158], points[153]) +
        face_tracking::Distance(points[160], points[144])) / 3.f;
    const float rightHeight = (
        face_tracking::Distance(points[386], points[374]) +
        face_tracking::Distance(points[385], points[380]) +
        face_tracking::Distance(points[387], points[373])) / 3.f;

    const PupilEstimate leftPupil = EstimatePupil(
        pixels, width, height, leftCenter, stableRoll,
        leftWidth * .46f, std::max(1.2f, leftHeight * .58f), leftOpen);
    const PupilEstimate rightPupil = EstimatePupil(
        pixels, width, height, rightCenter, stableRoll,
        rightWidth * .46f, std::max(1.2f, rightHeight * .58f), rightOpen);

    const float pupilWeight = leftPupil.confidence + rightPupil.confidence;
    float pupilX = 0.f, pupilY = 0.f;
    if (pupilWeight > .12f) {
        pupilX = (leftPupil.x * leftPupil.confidence +
            rightPupil.x * rightPupil.confidence) / pupilWeight;
        pupilY = (leftPupil.y * leftPupil.confidence +
            rightPupil.y * rightPupil.confidence) / pupilWeight;
        const float agreement = 1.f - Clamp(
            std::fabs(leftPupil.x - rightPupil.x) * .65f +
            std::fabs(leftPupil.y - rightPupil.y) * .35f,
            0.f, 1.f);
        const float reliability =
            Clamp(pupilWeight * .70f, 0.f, 1.f) * (.35f + .65f * agreement);
        pupilX *= reliability;
        pupilY *= reliability;
    }

    const float gazeX = Clamp(headYaw + pupilX * .85f * headForward, -1.f, 1.f);
    const float gazeY = Clamp(headPitch + pupilY * .55f * headForward, -1.f, 1.f);
    const float pupilMagnitude =
        Clamp(std::sqrt(pupilX * pupilX + pupilY * pupilY), 0.f, 1.f);
    const float gazeForward =
        Clamp(headForward * (1.f - .62f * pupilMagnitude), 0.f, 1.f);

    output[18] = headYaw;
    output[19] = headPitch;
    output[20] = headForward;
    output[21] = gazeX;
    output[22] = gazeY;
    output[23] = gazeForward;

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
    const float eyeDistance = face_tracking::Distance(Point{leftX, leftY}, Point{rightX, rightY});
    const Point eyeMid{(leftX + rightX) * .5f, (leftY + rightY) * .5f};
    const Point roiCenter{roi.cx, roi.cy};
    const float eyeMidOffset = face_tracking::Distance(eyeMid, roiCenter);
    const float roiSide = std::max(48.f, roi.side);

    // Validate only the CURRENT eye pose here. Do not reject a good current frame merely because
    // the landmark-derived ROI for the NEXT frame is questionable. The v7 build coupled those two
    // decisions and could turn an otherwise visible face into an all-null track.
    const bool currentPosePlausible =
        std::isfinite(leftX) && std::isfinite(leftY) &&
        std::isfinite(rightX) && std::isfinite(rightY) &&
        std::isfinite(stableRoll) &&
        leftX >= -width * .15f && leftX <= width * 1.15f &&
        rightX >= -width * .15f && rightX <= width * 1.15f &&
        leftY >= -height * .15f && leftY <= height * 1.15f &&
        rightY >= -height * .15f && rightY <= height * 1.15f &&
        eyeDistance >= roiSide * .055f && eyeDistance <= roiSide * .70f &&
        eyeMidOffset <= roiSide * .60f;
    if (!currentPosePlausible) return false;

    // Face bounds are secondary metadata for regional effects. A few peripheral landmark outliers
    // must never invalidate otherwise correct eye tracking.
    if (!std::isfinite(faceW) || !std::isfinite(faceH) || faceW < 4.f || faceH < 4.f) {
        rawMinX = std::min(leftX, rightX) - eyeDistance;
        rawMaxX = std::max(leftX, rightX) + eyeDistance;
        rawMinY = std::min(leftY, rightY) - eyeDistance;
        rawMaxY = std::max(leftY, rightY) + eyeDistance * 2.f;
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

    const Roi next = face_tracking::LandmarkRoi(points, kLandmarkCount, stableRoll);
    const bool nextRoiPlausible =
        next.valid &&
        std::isfinite(next.cx) && std::isfinite(next.cy) && std::isfinite(next.side) &&
        next.side >= roiSide * .55f && next.side <= roiSide * 1.80f &&
        next.side <= 1.6f * std::max(width, height) &&
        std::fabs(AngleDelta(next.angle, stableRoll)) <= .35f &&
        next.cx >= -width * .30f && next.cx <= width * 1.30f &&
        next.cy >= -height * .30f && next.cy <= height * 1.30f;

    for (int i = 0; i < kOutputCount; ++i) if (!std::isfinite(output[i])) return false;

    // Crucial separation: return the valid current pose even when next-frame tracking state is bad.
    // Invalidating ROI makes the following frame reacquire with BlazeFace instead of poisoning the
    // whole analysis with null poses.
    engine->roi = nextRoiPlausible ? next : Roi{};
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
    float out[kOutputCount] = {};
    RunMesh(engine, pixels.data(), 256, 256, roi, out);
    engine->roi = Roi{}; // Warm-up pixels must never seed the first real frame.
    engine->leftEyeClosed = false;
    engine->rightEyeClosed = false;
    engine->frameCounter = 0;
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
        env->GetArrayLength(outputArray) < kOutputCount) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Invalid face tracking frame buffers");
        return JNI_FALSE;
    }

    auto* engine = reinterpret_cast<FaceEngine*>(handle);
    jint* pixels = env->GetIntArrayElements(pixelArray, nullptr);
    if (pixels == nullptr) return JNI_FALSE;

    float output[kOutputCount] = {};
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
    env->SetFloatArrayRegion(outputArray, 0, kOutputCount, output);
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

