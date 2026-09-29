#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <limits>

namespace face_tracking {
struct Point { float x = 0.f; float y = 0.f; };
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
    const float vertical =
        (Distance(upper1, lower1) + Distance(upper2, lower2) + Distance(upper3, lower3)) / 3.f;
    if (!std::isfinite(vertical)) return 0.f;
    return vertical / width;
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
