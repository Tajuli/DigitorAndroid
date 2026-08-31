package com.tajuli.digitorandroid.ui.editor

import com.tajuli.digitorandroid.editor.model.ClipNodeGraph
import com.tajuli.digitorandroid.editor.model.ColorNode
import com.tajuli.digitorandroid.editor.model.ColorWheelValue
import com.tajuli.digitorandroid.editor.model.Curve5
import com.tajuli.digitorandroid.editor.model.HslQualifier
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.RgbCurves
import com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19
import com.tajuli.digitorandroid.editor.model.resolvedVisualOverlaysV19
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

internal fun EditorViewModelV4.resetSelectedImageGradeV20() {
    updateSelectedImageGradeGraphV20("image-grade-reset", coalesce = false) {
        ClipNodeGraph.default()
    }
}

internal fun EditorViewModelV4.setImageNodeCorrectionV20(parameter: String, value: Float) =
    updateSelectedImageEditableNodeV20 { node ->
        val c = node.corrections
        val next = when (parameter.lowercase()) {
            "exposure" -> c.copy(exposure = value)
            "contrast" -> c.copy(contrast = value)
            "saturation" -> c.copy(saturation = value)
            "temperature", "temp" -> c.copy(temperature = value)
            "tint" -> c.copy(tint = value)
            "highlights" -> c.copy(highlights = value)
            "shadows" -> c.copy(shadows = value)
            "hue" -> c.copy(hue = value)
            "color boost", "colorboost" -> c.copy(colorBoost = value)
            else -> c
        }
        node.copy(corrections = next)
    }

internal fun EditorViewModelV4.setImagePrimaryWheelV20(wheel: String, component: String, value: Float) =
    updateSelectedImageEditableNodeV20 { node ->
        val p = node.advancedColor.primary
        val next = when (wheel.lowercase()) {
            "lift" -> p.copy(lift = p.lift.withImageComponentV20(component, value))
            "gamma" -> p.copy(gamma = p.gamma.withImageComponentV20(component, value))
            "gain" -> p.copy(gain = p.gain.withImageComponentV20(component, value))
            "offset" -> p.copy(offset = p.offset.withImageComponentV20(component, value))
            else -> p
        }
        node.copy(advancedColor = node.advancedColor.copy(primary = next))
    }

internal fun EditorViewModelV4.setImagePrimaryWheelPuckV20(wheel: String, x: Float, y: Float) =
    updateSelectedImageEditableNodeV20 { node ->
        val p = node.advancedColor.primary
        val next = when (wheel.lowercase()) {
            "lift" -> p.copy(lift = p.lift.withImagePuckV20(x, y, .72f))
            "gamma" -> p.copy(gamma = p.gamma.withImagePuckV20(x, y, .52f))
            "gain" -> p.copy(gain = p.gain.withImagePuckV20(x, y, .72f))
            "offset" -> p.copy(offset = p.offset.withImagePuckV20(x, y, .52f))
            else -> p
        }
        node.copy(advancedColor = node.advancedColor.copy(primary = next))
    }

internal fun EditorViewModelV4.setImageLogWheelV20(zone: String, component: String, value: Float) =
    updateSelectedImageEditableNodeV20 { node ->
        val log = node.advancedColor.log
        val next = when (zone.lowercase()) {
            "shadows" -> log.copy(shadows = log.shadows.withImageComponentV20(component, value))
            "midtones" -> log.copy(midtones = log.midtones.withImageComponentV20(component, value))
            "highlights" -> log.copy(highlights = log.highlights.withImageComponentV20(component, value))
            "global" -> log.copy(global = log.global.withImageComponentV20(component, value))
            else -> log
        }
        node.copy(advancedColor = node.advancedColor.copy(log = next))
    }

internal fun EditorViewModelV4.setImageLogWheelPuckV20(zone: String, x: Float, y: Float) =
    updateSelectedImageEditableNodeV20 { node ->
        val log = node.advancedColor.log
        val next = when (zone.lowercase()) {
            "shadows" -> log.copy(shadows = log.shadows.withImagePuckV20(x, y, .90f))
            "midtones" -> log.copy(midtones = log.midtones.withImagePuckV20(x, y, .90f))
            "highlights" -> log.copy(highlights = log.highlights.withImagePuckV20(x, y, .90f))
            "global" -> log.copy(global = log.global.withImagePuckV20(x, y, .75f))
            else -> log
        }
        node.copy(advancedColor = node.advancedColor.copy(log = next))
    }

internal fun EditorViewModelV4.setImageLogRangeV20(
    shadowRange: Float? = null,
    highlightRange: Float? = null,
) = updateSelectedImageEditableNodeV20 { node ->
    val log = node.advancedColor.log
    node.copy(
        advancedColor = node.advancedColor.copy(
            log = log.copy(
                shadowRange = (shadowRange ?: log.shadowRange).coerceIn(.05f, .48f),
                highlightRange = (highlightRange ?: log.highlightRange).coerceIn(.52f, .95f),
            ),
        ),
    )
}

internal fun EditorViewModelV4.setImageCurvePointV20(channel: String, index: Int, x: Float, y: Float) =
    updateImageCurveV20(channel) { it.withPoint(index, x, y) }

internal fun EditorViewModelV4.insertImageCurvePointV20(channel: String, x: Float, y: Float) =
    updateImageCurveV20(channel) { it.insertPoint(x, y) }

internal fun EditorViewModelV4.deleteImageCurvePointV20(channel: String, index: Int) =
    updateImageCurveV20(channel) { it.deletePoint(index) }

internal fun EditorViewModelV4.setImageQualifierEnabledV20(enabled: Boolean) =
    updateSelectedImageEditableNodeV20 { node ->
        node.copy(
            advancedColor = node.advancedColor.copy(
                qualifier = node.advancedColor.qualifier.copy(enabled = enabled),
            ),
        )
    }

internal fun EditorViewModelV4.setImageQualifierV20(parameter: String, value: Float) =
    updateSelectedImageEditableNodeV20 { node ->
        val q = node.advancedColor.qualifier
        val next: HslQualifier = when (parameter.lowercase()) {
            "hue" -> q.copy(hueCenterDegrees = value.coerceIn(0f, 360f))
            "width" -> q.copy(hueWidthDegrees = value.coerceIn(1f, 360f))
            "satmin" -> q.copy(saturationMin = value.coerceIn(0f, 1f))
            "satmax" -> q.copy(saturationMax = value.coerceIn(0f, 1f))
            "lummin" -> q.copy(luminanceMin = value.coerceIn(0f, 1f))
            "lummax" -> q.copy(luminanceMax = value.coerceIn(0f, 1f))
            "softness" -> q.copy(softness = value.coerceIn(0f, 1f))
            "hueshift" -> q.copy(hueShiftDegrees = value.coerceIn(-180f, 180f))
            "satshift" -> q.copy(saturationShift = value.coerceIn(-1f, 1f))
            "lumshift" -> q.copy(luminanceShift = value.coerceIn(-1f, 1f))
            else -> q
        }
        node.copy(advancedColor = node.advancedColor.copy(qualifier = next))
    }

private fun EditorViewModelV4.updateImageCurveV20(
    channel: String,
    transform: (Curve5) -> Curve5,
) = updateSelectedImageEditableNodeV20 { node ->
    val curves = node.advancedColor.curves
    val next: RgbCurves = when (channel.lowercase()) {
        "master", "rgb", "y" -> curves.copy(master = transform(curves.master))
        "red", "r" -> curves.copy(red = transform(curves.red))
        "green", "g" -> curves.copy(green = transform(curves.green))
        "blue", "b" -> curves.copy(blue = transform(curves.blue))
        else -> curves
    }
    node.copy(advancedColor = node.advancedColor.copy(curves = next))
}

private fun EditorViewModelV4.updateSelectedImageEditableNodeV20(
    transform: (ColorNode) -> ColorNode,
) = updateSelectedImageGradeGraphV20("image-grade", coalesce = true) { graph ->
    val selectedId = graph.selectedNodeId
        ?: graph.nodes.firstOrNull { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }?.id
        ?: return@updateSelectedImageGradeGraphV20 graph
    val node = graph.nodes.firstOrNull { it.id == selectedId }
        ?: return@updateSelectedImageGradeGraphV20 graph
    if (node.kind != NodeKind.SERIAL && node.kind != NodeKind.PARALLEL) {
        return@updateSelectedImageGradeGraphV20 graph
    }
    graph.copy(
        selectedNodeId = selectedId,
        nodes = graph.nodes.map { current -> if (current.id == selectedId) transform(current) else current },
    )
}

private fun EditorViewModelV4.updateSelectedImageGradeGraphV20(
    label: String,
    coalesce: Boolean,
    transform: (ClipNodeGraph) -> ClipNodeGraph,
) {
    val snapshot = state.value
    val id = VisualOverlaySelectionBusV19.selectedId.value ?: return
    val project = snapshot.project
    val current = project.resolvedVisualOverlaysV19().firstOrNull { it.id == id } ?: return
    if (current.kind != VisualOverlayKindV19.IMAGE) return

    val base = current.imageNodeGraphV20 ?: ClipNodeGraph.default()
    val transformed = transform(base)
    if (current.imageNodeGraphV20 != null && transformed == base) return
    val nextGraph = transformed.copy(revision = base.revision + 1L)
    val updated = current.copy(imageNodeGraphV20 = nextGraph)
    val nextProject = project.copy(
        visualOverlaysV19 = project.resolvedVisualOverlaysV19().map { overlay ->
            if (overlay.id == updated.id) updated else overlay
        },
    )
    commitProjectV19(label, nextProject, status = "Image grade updated", coalesce = coalesce)
}

private fun ColorWheelValue.withImageComponentV20(component: String, value: Float): ColorWheelValue =
    when (component.lowercase()) {
        "red", "r" -> copy(red = value.coerceIn(-1f, 1f))
        "green", "g" -> copy(green = value.coerceIn(-1f, 1f))
        "blue", "b" -> copy(blue = value.coerceIn(-1f, 1f))
        "luma", "y" -> copy(luma = value.coerceIn(-1f, 1f))
        else -> this
    }

private fun ColorWheelValue.withImagePuckV20(x: Float, y: Float, scale: Float): ColorWheelValue {
    var nx = x.coerceIn(-1f, 1f)
    var ny = y.coerceIn(-1f, 1f)
    val length = sqrt(nx * nx + ny * ny)
    if (length > 1f) {
        nx /= length
        ny /= length
    }
    val radius = sqrt(nx * nx + ny * ny).coerceIn(0f, 1f)
    if (radius < .0001f) {
        return copy(red = 0f, green = 0f, blue = 0f, puckX = 0f, puckY = 0f)
    }
    var hue = atan2((-ny).toDouble(), nx.toDouble()) / (2.0 * PI)
    if (hue < 0.0) hue += 1.0
    val rgb = imageHueToRgbV20(hue.toFloat())
    val average = (rgb[0] + rgb[1] + rgb[2]) / 3f
    return copy(
        red = ((rgb[0] - average) * radius * scale).coerceIn(-1f, 1f),
        green = ((rgb[1] - average) * radius * scale).coerceIn(-1f, 1f),
        blue = ((rgb[2] - average) * radius * scale).coerceIn(-1f, 1f),
        puckX = nx,
        puckY = ny,
    )
}

private fun imageHueToRgbV20(h: Float): FloatArray {
    val hh = ((h % 1f) + 1f) % 1f * 6f
    val sector = hh.toInt().coerceIn(0, 5)
    val f = hh - sector
    return when (sector) {
        0 -> floatArrayOf(1f, f, 0f)
        1 -> floatArrayOf(1f - f, 1f, 0f)
        2 -> floatArrayOf(0f, 1f, f)
        3 -> floatArrayOf(0f, 1f - f, 1f)
        4 -> floatArrayOf(f, 0f, 1f)
        else -> floatArrayOf(1f, 0f, 1f - f)
    }
}
