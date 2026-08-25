package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.AnimatedFloat
import com.tajuli.digitorandroid.editor.model.ClipTransform
import com.tajuli.digitorandroid.editor.model.KeyframeInterpolation
import com.tajuli.digitorandroid.editor.model.TransformProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformModelsTest {
    @Test
    fun staticValueBecomesAutoKeyframedAfterFirstDiamond() {
        var transform = ClipTransform()
            .setEditorValue(TransformProperty.SCALE, 0L, 1.25f)
        assertTrue(transform.scale.keyframes.isEmpty())
        assertEquals(1.25f, transform.scale.baseValue, 0.0001f)

        transform = transform.toggleKeyframe(TransformProperty.SCALE, 0L)
        transform = transform.setEditorValue(TransformProperty.SCALE, 1_000_000L, 2f)

        assertEquals(2, transform.scale.keyframes.size)
        assertEquals(1.25f, transform.scale.valueAt(0L), 0.0001f)
        assertEquals(2f, transform.scale.valueAt(1_000_000L), 0.0001f)
    }

    @Test
    fun easeInOutInterpolatesBetweenKeyframes() {
        val animated = AnimatedFloat(0f)
            .upsertKeyframe(0L, 0f, KeyframeInterpolation.EASE_IN_OUT)
            .upsertKeyframe(1_000_000L, 1f, KeyframeInterpolation.EASE_IN_OUT)

        assertEquals(0.15625f, animated.valueAt(250_000L), 0.0001f)
        assertEquals(0.5f, animated.valueAt(500_000L), 0.0001f)
        assertEquals(0.84375f, animated.valueAt(750_000L), 0.0001f)
    }

    @Test
    fun splitRebasesLinearKeyframesWithoutChangingMotion() {
        val animated = AnimatedFloat(0f)
            .upsertKeyframe(0L, 0f)
            .upsertKeyframe(2_000_000L, 1f)
            .upsertKeyframe(4_000_000L, 0f)

        val originalAtSplit = animated.valueAt(2_500_000L)
        val (left, right) = animated.splitAt(2_500_000L)

        assertEquals(originalAtSplit, left.valueAt(2_500_000L), 0.0001f)
        assertEquals(originalAtSplit, right.valueAt(0L), 0.0001f)
        assertEquals(animated.valueAt(3_500_000L), right.valueAt(1_000_000L), 0.0001f)
    }

    @Test
    fun allKeyframeToggleAddsThenRemovesAtSamePlayhead() {
        val added = ClipTransform().toggleAllKeyframes(333_333L)
        assertTrue(TransformProperty.entries.all { added.hasKeyframeAt(it, 333_333L) })

        val removed = added.toggleAllKeyframes(333_333L)
        assertFalse(removed.hasAnimation)
    }
}
