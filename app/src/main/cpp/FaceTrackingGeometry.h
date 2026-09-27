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
