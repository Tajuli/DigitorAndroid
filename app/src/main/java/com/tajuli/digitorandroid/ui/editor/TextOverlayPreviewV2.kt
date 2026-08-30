package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TextAlignmentV2
import com.tajuli.digitorandroid.editor.model.TextFontV2
import com.tajuli.digitorandroid.editor.model.TextOverlayClip
import com.tajuli.digitorandroid.editor.model.resolvedTextStyleV2
import com.tajuli.digitorandroid.editor.model.textAnimationFrameV2
import com.tajuli.digitorandroid.editor.model.textManualFrameV2

/** Compose-side Text System V2 renderer. Export consumes the same model/evaluators. */
@Composable
fun TextOverlayPreviewV2(
    overlay: TextOverlayClip,
    timelineUs: Long,
    previewSize: IntSize,
    modifier: Modifier = Modifier,
) {
    val style = overlay.resolvedTextStyleV2()
    val preset = overlay.textAnimationFrameV2(timelineUs)
    val manual = overlay.textManualFrameV2(timelineUs)
    val combinedAlpha = (preset.alpha * manual.alpha).coerceIn(0f, 1f)
    if (combinedAlpha <= 0f) return

    val fontFamily = when (style.font) {
        TextFontV2.SANS -> FontFamily.SansSerif
        TextFontV2.SERIF -> FontFamily.Serif
        TextFontV2.MONO -> FontFamily.Monospace
        TextFontV2.CURSIVE -> FontFamily.Cursive
    }
    val textAlign = when (style.alignment) {
        TextAlignmentV2.LEFT -> TextAlign.Left
        TextAlignmentV2.CENTER -> TextAlign.Center
        TextAlignmentV2.RIGHT -> TextAlign.Right
    }
    val contentAlignment = when (style.alignment) {
        TextAlignmentV2.LEFT -> Alignment.CenterStart
        TextAlignmentV2.CENTER -> Alignment.Center
        TextAlignmentV2.RIGHT -> Alignment.CenterEnd
    }
    val fillColor = Color(style.colorArgb.toInt())
    val strokeColor = Color(style.strokeArgb.toInt())
    val shadow = if (style.shadowEnabled) {
        Shadow(
            color = Color(style.shadowArgb.toInt()),
            offset = Offset(style.shadowDx, style.shadowDy),
            blurRadius = style.shadowRadius,
        )
    } else {
        null
    }

    Box(
        modifier.graphicsLayer {
            alpha = combinedAlpha
            translationX = (manual.positionX + preset.offsetX) * previewSize.width * .5f
            translationY = (manual.positionY + preset.offsetY) * previewSize.height * .5f
            scaleX = manual.sizeScale
            scaleY = manual.sizeScale
        }.widthIn(max = 520.dp),
        contentAlignment = contentAlignment,
    ) {
        Box(
            Modifier.background(
                if (style.backgroundEnabled) Color(style.backgroundArgb.toInt()) else Color.Transparent,
                RoundedCornerShape(6.dp),
            ).padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = contentAlignment,
        ) {
            if (style.strokeWidth > 0f) {
                Text(
                    text = overlay.text,
                    textAlign = textAlign,
                    fontSize = 22.sp,
                    fontFamily = fontFamily,
                    fontWeight = if (overlay.bold) FontWeight.Bold else FontWeight.Normal,
                    color = strokeColor,
                    style = TextStyle(drawStyle = Stroke(width = style.strokeWidth)),
                )
            }
            Text(
                text = overlay.text,
                textAlign = textAlign,
                fontSize = 22.sp,
                fontFamily = fontFamily,
                fontWeight = if (overlay.bold) FontWeight.Bold else FontWeight.Normal,
                color = fillColor,
                style = TextStyle(shadow = shadow),
            )
        }
    }
}
