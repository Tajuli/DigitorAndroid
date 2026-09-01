package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.TransitionStyleV22

/**
 * Creator-facing CapCut-style transition preset catalog.
 *
 * V22 remains the stable rendering ABI. V24 expands the picker to the 50 commonly used names the
 * creator requested and maps each preset onto the closest proven V22 render family so preview,
 * export and old saved projects keep sharing one transition engine.
 */
internal enum class CapCutTransitionCategoryV24(val label: String) {
    BASIC("Basic"),
    CAMERA("Camera"),
    EFFECT("Effect"),
}

internal data class CapCutTransitionPresetV24(
    val id: String,
    val label: String,
    val category: CapCutTransitionCategoryV24,
    val engineStyle: TransitionStyleV22,
)

internal val CAPCUT_TRANSITION_PRESETS_V24: List<CapCutTransitionPresetV24> = listOf(
    CapCutTransitionPresetV24("pull_in", "Pull In", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.ZOOM_IN),
    CapCutTransitionPresetV24("pull_out", "Pull Out", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.ZOOM_OUT),
    CapCutTransitionPresetV24("zoom_in", "Zoom In", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.ZOOM_IN),
    CapCutTransitionPresetV24("zoom_out", "Zoom Out", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.ZOOM_OUT),
    CapCutTransitionPresetV24("zoom_lens", "Zoom Lens", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.ZOOM_IN),
    CapCutTransitionPresetV24("camera_zoom", "Camera Zoom", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.ZOOM_IN),
    CapCutTransitionPresetV24("swipe_left", "Swipe Left", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.PUSH_LEFT),
    CapCutTransitionPresetV24("swipe_right", "Swipe Right", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.PUSH_RIGHT),
    CapCutTransitionPresetV24("swipe_up", "Swipe Up", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.PUSH_UP),
    CapCutTransitionPresetV24("swipe_down", "Swipe Down", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.PUSH_DOWN),
    CapCutTransitionPresetV24("slide_left", "Slide Left", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.PUSH_LEFT),
    CapCutTransitionPresetV24("slide_right", "Slide Right", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.PUSH_RIGHT),
    CapCutTransitionPresetV24("slide_up", "Slide Up", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.PUSH_UP),
    CapCutTransitionPresetV24("slide_down", "Slide Down", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.PUSH_DOWN),
    CapCutTransitionPresetV24("spin", "Spin", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.SPIN),
    CapCutTransitionPresetV24("rotate", "Rotate", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.SPIN),
    CapCutTransitionPresetV24("blur", "Blur", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.BLUR),
    CapCutTransitionPresetV24("motion_blur", "Motion Blur", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.WHIP),
    CapCutTransitionPresetV24("vertical_blur", "Vertical Blur", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.BLUR),
    CapCutTransitionPresetV24("horizontal_blur", "Horizontal Blur", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.WHIP),
    CapCutTransitionPresetV24("blur_zoom", "Blur Zoom", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.BLUR),
    CapCutTransitionPresetV24("fade", "Fade", CapCutTransitionCategoryV24.BASIC, TransitionStyleV22.FADE),
    CapCutTransitionPresetV24("fade_in", "Fade In", CapCutTransitionCategoryV24.BASIC, TransitionStyleV22.FADE),
    CapCutTransitionPresetV24("fade_out", "Fade Out", CapCutTransitionCategoryV24.BASIC, TransitionStyleV22.FADE),
    CapCutTransitionPresetV24("black_fade", "Black Fade", CapCutTransitionCategoryV24.BASIC, TransitionStyleV22.DIP_TO_BLACK),
    CapCutTransitionPresetV24("white_fade", "White Fade", CapCutTransitionCategoryV24.BASIC, TransitionStyleV22.DIP_TO_WHITE),
    CapCutTransitionPresetV24("mix", "Mix", CapCutTransitionCategoryV24.BASIC, TransitionStyleV22.CROSS_DISSOLVE),
    CapCutTransitionPresetV24("dissolve", "Dissolve", CapCutTransitionCategoryV24.BASIC, TransitionStyleV22.CROSS_DISSOLVE),
    CapCutTransitionPresetV24("cross_fade", "Cross Fade", CapCutTransitionCategoryV24.BASIC, TransitionStyleV22.CROSS_DISSOLVE),
    CapCutTransitionPresetV24("flash", "Flash", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.FLASH),
    CapCutTransitionPresetV24("white_flash", "White Flash", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.FLASH),
    CapCutTransitionPresetV24("camera_flash", "Camera Flash", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.FLASH),
    CapCutTransitionPresetV24("flicker", "Flicker", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.FLASH),
    CapCutTransitionPresetV24("light_leak", "Light Leak", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.LIGHT_LEAK),
    CapCutTransitionPresetV24("lens_flare", "Lens Flare", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.LIGHT_LEAK),
    CapCutTransitionPresetV24("film_burn", "Film Burn", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.LIGHT_LEAK),
    CapCutTransitionPresetV24("glitch", "Glitch", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.WHIP),
    CapCutTransitionPresetV24("rgb_glitch", "RGB Glitch", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.WHIP),
    CapCutTransitionPresetV24("digital_glitch", "Digital Glitch", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.WHIP),
    CapCutTransitionPresetV24("distortion", "Distortion", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.WHIP),
    CapCutTransitionPresetV24("shake", "Shake", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.WHIP),
    CapCutTransitionPresetV24("camera_shake", "Camera Shake", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.WHIP),
    CapCutTransitionPresetV24("stretch", "Stretch", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.ZOOM_IN),
    CapCutTransitionPresetV24("warp", "Warp", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.WHIP),
    CapCutTransitionPresetV24("cube_3d", "3D Cube", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.SPIN),
    CapCutTransitionPresetV24("page_turn", "Page Turn", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.SPIN),
    CapCutTransitionPresetV24("mask_transition", "Mask Transition", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.MASK_WIPE),
    CapCutTransitionPresetV24("split", "Split", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.SPLIT),
    CapCutTransitionPresetV24("velocity", "Velocity", CapCutTransitionCategoryV24.CAMERA, TransitionStyleV22.WHIP),
    CapCutTransitionPresetV24("beat_sync", "Beat Sync", CapCutTransitionCategoryV24.EFFECT, TransitionStyleV22.FLASH),
)

internal fun presetsForCategoryV24(category: CapCutTransitionCategoryV24): List<CapCutTransitionPresetV24> =
    CAPCUT_TRANSITION_PRESETS_V24.filter { it.category == category }

internal fun presetForIdV24(id: String?): CapCutTransitionPresetV24? =
    id?.let { key -> CAPCUT_TRANSITION_PRESETS_V24.firstOrNull { it.id == key } }

internal fun defaultPresetForStyleV24(style: TransitionStyleV22): CapCutTransitionPresetV24? =
    CAPCUT_TRANSITION_PRESETS_V24.firstOrNull { it.engineStyle == style }
