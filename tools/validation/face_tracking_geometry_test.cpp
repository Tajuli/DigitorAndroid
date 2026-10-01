#include "../../app/src/main/cpp/FaceTrackingGeometry.h"
#include <array>
#include <cassert>
#include <iostream>
#include <limits>

using face_tracking::Point;
using face_tracking::Point3;
using face_tracking::LandmarkRoi;
using face_tracking::FaceOrientationFromPlane;
using face_tracking::EyeAspectRatio;
using face_tracking::BlinkOpenness;
using face_tracking::EyeContourCenter;
using face_tracking::EffectDirectionFromLandmarks;

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

    // 3D face-plane direction: frontal faces point into camera; yaw projects to screen X.
    const Point3 frontLeft{-1, 0, 0}, frontRight{1, 0, 0};
    const Point3 frontTop{0, -1, 0}, frontBottom{0, 1, 0};
    const auto front = FaceOrientationFromPlane(
        frontLeft, frontRight, frontTop, frontBottom, 0.f);
    assert(front.valid); near(front.yaw, 0.f); near(front.pitch, 0.f); near(front.forward, 1.f);

    const float yaw = .5235987756f; // 30 degrees.
    const float cy = std::cos(yaw), sy = std::sin(yaw);
    const Point3 yawLeft{-cy, 0, sy}, yawRight{cy, 0, -sy};
    const auto turned = FaceOrientationFromPlane(
        yawLeft, yawRight, frontTop, frontBottom, 0.f);
    assert(turned.valid);
    assert(turned.yaw > .55f);
    assert(turned.forward > .84f && turned.forward < .88f);

    // With image-space +Y downward, a head-down plane has negative geometric pitch but must expose
    // positive screen pitch to the gaze shader.
    const Point3 downTop{0, -1, -.35f}, downBottom{0, 1, .35f};
    const auto down = FaceOrientationFromPlane(
        frontLeft, frontRight, downTop, downBottom, 0.f);
    assert(down.valid);
    assert(down.pitch < -.35f);
    assert(face_tracking::ScreenPitchDown(down) > .35f);

    // Eye-origin center uses the complete eyelid contour rather than only the two eye corners.
    const auto contourCenter = EyeContourCenter(
        Point{0, 0}, Point{8, 0},
        Point{2, -1}, Point{2, 1},
        Point{4, -1}, Point{4, 1},
        Point{6, -1}, Point{6, 1});
    near(contourCenter.x, 4.f); near(contourCenter.y, 0.f);

    // Long-effect direction fuses ear/side-face, cheek/nose, and mouth/nose/chin planes.
    const auto fusedFront = EffectDirectionFromLandmarks(
        Point3{-2, 0, 0}, Point3{2, 0, 0},
        Point3{-1.5f, .2f, 0}, Point3{1.5f, .2f, 0},
        Point3{-.7f, 1.f, 0}, Point3{.7f, 1.f, 0},
        Point3{0, -2, 0}, Point3{0, -.5f, 0}, Point3{0, 0, 0},
        Point3{0, .7f, 0}, Point3{0, 1.1f, 0}, Point3{0, 2, 0},
        0.f);
    assert(fusedFront.valid);
    near(fusedFront.yaw, 0.f); near(fusedFront.pitch, 0.f); near(fusedFront.forward, 1.f);

    const float fusedYawAngle = .5235987756f;
    const float fcy = std::cos(fusedYawAngle), fsy = std::sin(fusedYawAngle);
    auto yawPoint = [&](Point3 p) {
        return Point3{p.x * fcy, p.y, -p.x * fsy + p.z};
    };
    const auto fusedTurn = EffectDirectionFromLandmarks(
        yawPoint(Point3{-2, 0, 0}), yawPoint(Point3{2, 0, 0}),
        yawPoint(Point3{-1.5f, .2f, 0}), yawPoint(Point3{1.5f, .2f, 0}),
        yawPoint(Point3{-.7f, 1.f, 0}), yawPoint(Point3{.7f, 1.f, 0}),
        yawPoint(Point3{0, -2, 0}), yawPoint(Point3{0, -.5f, 0}), yawPoint(Point3{0, 0, 0}),
        yawPoint(Point3{0, .7f, 0}), yawPoint(Point3{0, 1.1f, 0}), yawPoint(Point3{0, 2, 0}),
        0.f);
    assert(fusedTurn.valid);
    assert(std::fabs(fusedTurn.yaw) > .5f);
    assert(fusedTurn.forward > .80f && fusedTurn.forward < .92f);

    // Independent pupil motion remains available as metadata, but long-beam direction no longer
    // consumes it; pupil tracking is reserved for the source point.
    const auto eyeDown = face_tracking::EyeDrivenGaze(0.f, 0.f, 1.f, 0.f, .48f, .9f, 1.f);
    const auto eyeUp = face_tracking::EyeDrivenGaze(0.f, 0.f, 1.f, 0.f, -.48f, .9f, 1.f);
    assert(eyeDown.y > .65f && eyeDown.confidence > .8f);
    assert(eyeUp.y < -.65f && eyeUp.confidence > .8f);
    const auto lowConfidence =
        face_tracking::EyeDrivenGaze(.25f, .15f, .9f, 1.f, -1.f, .01f, 1.f);
    assert(lowConfidence.x > .15f && lowConfidence.y > .08f);
    assert(lowConfidence.confidence < .02f);

    // Blink geometry: three lid pairs provide a robust rotation-invariant EAR.
    const Point outer{0, 0}, inner{10, 0};
    const float openEar = EyeAspectRatio(
        outer, inner,
        Point{2, -1.5f}, Point{2, 1.5f},
        Point{5, -1.5f}, Point{5, 1.5f},
        Point{8, -1.5f}, Point{8, 1.5f});
    const float closedEar = EyeAspectRatio(
        outer, inner,
        Point{2, -.5f}, Point{2, .5f},
        Point{5, -.5f}, Point{5, .5f},
        Point{8, -.5f}, Point{8, .5f});
    near(openEar, .3f); near(closedEar, .1f);

    bool closedState = false;
    assert(BlinkOpenness(openEar, &closedState) > .9f && !closedState);
    assert(BlinkOpenness(closedEar, &closedState) == 0.f && closedState);
    // Hysteresis prevents a half-open noisy frame from flashing the effect back on.
    assert(BlinkOpenness(.20f, &closedState) == 0.f && closedState);
    assert(BlinkOpenness(.24f, &closedState) > .5f && !closedState);

    std::cout << "PASS: crop geometry, pupil/eye source center, fused face direction, and blink geometry\n";
}
