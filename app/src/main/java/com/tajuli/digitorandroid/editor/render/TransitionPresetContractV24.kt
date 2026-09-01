package com.tajuli.digitorandroid.editor.render

import com.tajuli.digitorandroid.editor.model.TransitionStyleV22

/** Keeps V24 preset rendering optional: unknown/missing ids always fall back to the stable V22 style. */
internal data class TransitionRenderKeyV24(
    val style: TransitionStyleV22,
    val presetId: String?,
)
