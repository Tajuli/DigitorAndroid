#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>

namespace face_tracking {
struct Point { float x = 0.f; float y = 0.f; };
struct Point3 { float x = 0.f; float y = 0.f; float z = 0.f; };
struct FaceOrientation {
    float yaw = 0.f;
    float pitch = 0.f;
    float forward = 1.f;
    bool valid = false;
};

inline FaceOrientation FaceOrientationFromPlane(
        const Point3& left,
        const Point3& right,
        const Point3& top,
        const Point3& bottom,
        float roll) {
    const Point3 h{right.x - left.x, right.y - left.y, right.z - left.z};
    const Point3 v{bottom.x - top.x, bottom.y - top.y, bottom.z - top.z};
    Point3 n{
        h.y * v.z - h.z * v.y,
        h.z * v.x - h.x * v.z,
        h.x * v.y - h.y * v.x,
    };
    const float length = std::sqrt(n.x * n.x + n.y * n.y + n.z * n.z);
    FaceOrientation result;
    if (!std::isfinite(length) || length < 1e-5f || !std::isfinite(roll)) return result;
    n.x /= length; n.y /= length; n.z /= length;
    if (n.z < 0.f) { n.x = -n.x; n.y = -n.y; n.z = -n.z; }
    const float c = std::cos(roll), s = std::sin(roll);
    const float localX = c * n.x + s * n.y;
    const float localY = -s * n.x + c * n.y;
    result.yaw = std::clamp(localX * 1.25f, -1.f, 1.f);
    result.pitch = std::clamp(localY * 1.25f, -1.f, 1.f);
    result.forward = std::clamp(n.z, 0.f, 1.f);
    result.valid = std::isfinite(result.yaw) && std::isfinite(result.pitch) &&
        std::isfinite(result.forward);
    return result;
}

// FaceOrientationFromPlane uses the face-plane normal in a Cartesian-like local basis. The video
// pipeline and eye shader use image coordinates where +Y means DOWN. A real head-down pose therefore
// arrives as a negative geometric pitch and must be inverted once at this boundary.
inline float ScreenPitchDown(const FaceOrientation& orientation) {
    if (!orientation.valid || !std::isfinite(orientation.pitch)) return 0.f;
    return std::clamp(-orientation.pitch, -1.f, 1.f);
}

struct EyeGaze {
    float x = 0.f;
    float y = 0.f;
    float forward = 1.f;
    float confidence = 0.f;
};

inline float PupilAxisSignal(float value) {
    if (!std::isfinite(value)) return 0.f;
    const float magnitude = std::fabs(value);
    if (magnitude <= .015f) return 0.f;
    const float normalized = std::clamp((magnitude - .015f) / .55f, 0.f, 1.f);
    return std::copysign(normalized, value);
}

inline EyeGaze EyeDrivenGaze(
        float headYaw,
        float headPitch,
        float headForward,
        float pupilX,
        float pupilY,
        float pupilConfidence,
        float openness) {
    EyeGaze result;
    const float confidence = std::clamp(
        pupilConfidence * std::clamp((openness - .18f) / .42f, 0.f, 1.f), 0.f, 1.f);
    const float usable = std::clamp((confidence - .05f) / .34f, 0.f, 1.f);
    const float irisWeight = usable > 0.f ? (.35f + .65f * usable) : 0.f;
    const float depthScale = .55f + .45f * std::clamp(headForward, 0.f, 1.f);
    const float irisX = PupilAxisSignal(pupilX) * 1.08f * irisWeight * depthScale;
    const float irisY = PupilAxisSignal(pupilY) * 1.22f * irisWeight * depthScale;
    result.x = std::clamp(headYaw * .78f + irisX, -1.f, 1.f);
    result.y = std::clamp(headPitch * .78f + irisY, -1.f, 1.f);
    const float projected = std::clamp(
        std::sqrt(result.x * result.x + result.y * result.y), 0.f, 1.f);
    result.forward = std::clamp(headForward * (1.f - .58f * projected), 0.f, 1.f);
    result.confidence = confidence;
    return result;
}

struct Roi {
    float cx = 0.f;
    float cy = 0.f;
    float side = 0.f;
    float angle = 0.f;
    bool valid = false;
};


// Rotation-invariant multi-pair eye aspect ratio (EAR). One eyelid pair is too noisy at 192 px
// and can remain "open" during a real blink. Averaging three upper/lower pairs follows the actual
// lid contour and makes closed-eye detection much more reliable.
inline float Distance(const Point& a, const Point& b) {
    const float dx = b.x - a.x, dy = b.y - a.y;
    return std::sqrt(dx * dx + dy * dy);
}

inline float EyeAspectRatio(
        const Point& outer,
        const Point& inner,
        const Point& upper1,
        const Point& lower1,
        const Point& upper2,
        const Point& lower2,
        const Point& upper3,
        const Point& lower3) {
    const float width = Distance(outer, inner);
    if (!std::isfinite(width) || width < 1e-4f) return 0.f;
    float v1 = Distance(upper1, lower1);
    float v2 = Distance(upper2, lower2);
    float v3 = Distance(upper3, lower3);
    if (!std::isfinite(v1) || !std::isfinite(v2) || !std::isfinite(v3)) return 0.f;
    // Median rejects one noisy eyelid pair, which is common at 192 px during fast blinks.
    if (v1 > v2) std::swap(v1, v2);
    if (v2 > v3) std::swap(v2, v3);
    if (v1 > v2) std::swap(v1, v2);
    return v2 / width;
}

// Stateful blink hysteresis. Close quickly, but require a clearly reopened eye before turning the
// effect back on so a single noisy half-open frame does not flash Fire/Laser during a blink.
inline float BlinkOpenness(float ear, bool* closed) {
    if (closed == nullptr || !std::isfinite(ear)) return 0.f;
    constexpr float kCloseEar = 0.18f;
    constexpr float kReopenEar = 0.23f;
    if (*closed) {
        if (ear >= kReopenEar) *closed = false;
    } else if (ear <= kCloseEar) {
        *closed = true;
    }
    if (*closed) return 0.f;
    return std::clamp((ear - kCloseEar) / 0.10f, 0.f, 1.f);
}

// Measure extent in the eye-line coordinate system. Measuring an axis-aligned
// source box and then rotating it makes crop scale breathe as the head rolls.
inline Roi LandmarkRoi(const Point* points, std::size_t count, float angle) {
    Roi result;
    if (count == 0 || !std::isfinite(angle)) return result;
    const float c = std::cos(angle), s = std::sin(angle);
    float minX = std::numeric_limits<float>::infinity();
    float minY = minX, maxX = -minX, maxY = -minX;
    for (std::size_t i = 0; i < count; ++i) {
        if (!std::isfinite(points[i].x) || !std::isfinite(points[i].y)) return result;
        const float x = c * points[i].x + s * points[i].y;
        const float y = -s * points[i].x + c * points[i].y;
        minX = std::min(minX, x); maxX = std::max(maxX, x);
        minY = std::min(minY, y); maxY = std::max(maxY, y);
    }
    if (maxX - minX < 2.f || maxY - minY < 2.f) return result;
    const float x = (minX + maxX) * .5f, y = (minY + maxY) * .5f;
    result.cx = c * x - s * y;
    result.cy = s * x + c * y;
    // Match the detector crop's padding, keeping useful face detail at 192 px.
    result.side = std::max(48.f, 1.5f * std::max(maxX - minX, maxY - minY));
    result.angle = angle;
    result.valid = true;
    return result;
}
} // namespace face_tracking
