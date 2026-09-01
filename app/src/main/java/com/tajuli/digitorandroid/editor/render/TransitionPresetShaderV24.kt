package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TransitionPresetV24

/** Maps V24 presets onto distinct shader programs/codes used by TransitionVisualEffectV22. */
internal object TransitionPresetShaderV24 {
    fun code(presetId: String?): Float = when (presetId) {
        TransitionPresetV24.BLUR -> 101f
        TransitionPresetV24.MOTION_BLUR -> 102f
        TransitionPresetV24.VERTICAL_BLUR -> 103f
        TransitionPresetV24.HORIZONTAL_BLUR -> 104f
        TransitionPresetV24.BLUR_ZOOM -> 105f
        TransitionPresetV24.WHITE_FLASH -> 106f
        TransitionPresetV24.CAMERA_FLASH -> 107f
        TransitionPresetV24.FLICKER -> 108f
        TransitionPresetV24.LENS_FLARE -> 109f
        TransitionPresetV24.FILM_BURN -> 110f
        TransitionPresetV24.GLITCH -> 111f
        TransitionPresetV24.RGB_GLITCH -> 112f
        TransitionPresetV24.DIGITAL_GLITCH -> 113f
        TransitionPresetV24.DISTORTION -> 114f
        TransitionPresetV24.WARP -> 115f
        TransitionPresetV24.PAGE_TURN -> 116f
        TransitionPresetV24.MASK_TRANSITION -> 117f
        TransitionPresetV24.BEAT_SYNC -> 118f
        TransitionPresetV24.ZOOM_LENS -> 119f
        TransitionPresetV24.CAMERA_ZOOM -> 120f
        else -> 0f
    }
}
