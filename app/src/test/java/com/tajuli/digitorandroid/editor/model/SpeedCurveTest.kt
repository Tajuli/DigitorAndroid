package com.tajuli.digitorandroid.editor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeedCurveTest {
    @Test
    fun constantCurveHasExpectedDuration() {
        val schedule = SpeedCurveSpec.constant(.5f).sampledSchedule(2_000_000L)
        assertTrue(abs(schedule.outputDurationUs - 4_000_000L) <= 30_000L)
        assertEquals(.5f, schedule.speedAtSourceTime(1_000_000L), .01f)
    }

    @Test
    fun presetScheduleIsMonotonicAndBounded() {
        val schedule = SpeedCurveSpec.preset(SpeedCurvePreset.HERO).sampledSchedule(8_000_000L)
        assertEquals(0L, schedule.startTimesUs.first())
        assertTrue(schedule.startTimesUs.zipWithNext().all { (a, b) -> b > a })
        assertTrue(schedule.speeds.all { it in MIN_SPEED_CURVE_SPEED..MAX_SPEED_CURVE_SPEED })
        assertTrue(schedule.outputDurationUs > 0L)
    }

    @Test
    fun sourceToOutputMappingNeverMovesBackwards() {
        val schedule = SpeedCurveSpec.preset(SpeedCurvePreset.MONTAGE).sampledSchedule(5_000_000L)
        val mapped = (0..100).map { schedule.outputTimeForSourceTime(it * 50_000L) }
        assertTrue(mapped.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(schedule.outputDurationUs, schedule.outputTimeForSourceTime(5_000_000L))
    }

    @Test
    fun normalizationAddsEndpointsAndSorts() {
        val curve = SpeedCurveSpec(
            listOf(
                SpeedCurvePoint(.8f, 2f),
                SpeedCurvePoint(.2f, .2f),
            ),
        ).normalized()
        assertEquals(0f, curve.points.first().position, 0f)
        assertEquals(1f, curve.points.last().position, 0f)
        assertTrue(curve.points.zipWithNext().all { (a, b) -> b.position > a.position })
    }

    @Test
    fun transformKeyframesUseExactCurveMap() {
        val schedule = SpeedCurveSpec.constant(.5f).sampledSchedule(4_000_000L)
        val transform = ClipTransform(
            positionX = AnimatedFloat(
                baseValue = 0f,
                keyframes = listOf(FloatKeyframe(timeUs = 1_000_000L, value = .75f)),
            ),
        )
        val mapped = transform.retimedBy(schedule::outputTimeForSourceTime)
        assertTrue(abs(mapped.positionX.keyframes.single().timeUs - 2_000_000L) <= 30_000L)
        assertEquals(.75f, mapped.positionX.keyframes.single().value, 0f)
    }

    @Test
    fun timedEffectBoundsAndNodeKeysMoveToBakedSource() {
        val sourceInUs = 1_000_000L
        val sourceOutUs = 5_000_000L
        val schedule = SpeedCurveSpec.constant(.5f).sampledSchedule(sourceOutUs - sourceInUs)
        val effect = NodeEffect(
            name = "Glow",
            sourceStartUsV26 = 2_000_000L,
            sourceEndUsV26 = 3_000_000L,
        )
        val mappedEffect = effect.retimedForBakedClipV26(sourceInUs, sourceOutUs, schedule)
        assertTrue(abs(checkNotNull(mappedEffect.sourceStartUsV26) - 2_000_000L) <= 30_000L)
        assertTrue(abs(checkNotNull(mappedEffect.sourceEndUsV26) - 4_000_000L) <= 30_000L)

        val node = ColorNode(
            kind = NodeKind.SERIAL,
            label = "01",
            position = NodePosition(0f, 0f),
        )
        val animations = NodeAnimations().apply {
            toggle(node, NodeAnimationDomain.CORRECTION, 2_000_000L)
        }
        val mappedAnimations = animations.retimedForBakedClip(sourceInUs, sourceOutUs, schedule)
        val mappedTime = mappedAnimations.keyframeTimes(node.id, NodeAnimationDomain.CORRECTION).single()
        assertTrue(abs(mappedTime - 2_000_000L) <= 30_000L)
    }

}
