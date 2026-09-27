#include "../../app/src/main/cpp/FaceTrackingGeometry.h"
#include <array>
#include <cassert>
#include <iostream>
#include <limits>

using face_tracking::Point;
using face_tracking::LandmarkRoi;

static void near(float actual, float expected) {
    assert(std::fabs(actual - expected) < .001f);
}

int main() {
    // Same face translated and rolled: crop size must not pump with head tilt.
    const std::array<Point, 4> local{{{-60, -90}, {60, -90}, {60, 90}, {-60, 90}}};
    for (float angle : {-1.3f, -.7f, 0.f, .7f, 1.3f}) {
        for (float cx : {-15.f, 240.f, 970.f}) {
            std::array<Point, 4> source;
            const float c = std::cos(angle), s = std::sin(angle);
            for (std::size_t i = 0; i < local.size(); ++i) {
                source[i] = {cx + c * local[i].x - s * local[i].y,
                             200.f + s * local[i].x + c * local[i].y};
            }
            const auto roi = LandmarkRoi(source.data(), source.size(), angle);
            assert(roi.valid);
            near(roi.cx, cx); near(roi.cy, 200.f); near(roi.side, 270.f);
            near(roi.angle, angle);
        }
    }
    // Reject unusable geometry instead of feeding NaN/degenerate crops back forever.
    assert(!LandmarkRoi(local.data(), 0, 0).valid);
    assert(!LandmarkRoi(local.data(), local.size(), std::numeric_limits<float>::quiet_NaN()).valid);
    const Point bad[] = {{0, 0}, {std::numeric_limits<float>::infinity(), 10}};
    assert(!LandmarkRoi(bad, 2, 0).valid);
    const Point flat[] = {{0, 0}, {30, 0}};
    assert(!LandmarkRoi(flat, 2, 0).valid);
    const Point small[] = {{0, 0}, {3, 3}};
    const auto minimum = LandmarkRoi(small, 2, 0);
    assert(minimum.valid); near(minimum.side, 48.f);
    std::cout << "PASS: roll/translation/edge invariance, invalid geometry and minimum crop\n";
}
