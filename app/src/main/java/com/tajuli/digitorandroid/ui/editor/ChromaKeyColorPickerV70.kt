package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Small HSV color picker used by Chroma Key. Green/Blue remain fast presets; this adds an
 * arbitrary key color without introducing another rendering path or dependency.
 */
@Composable
internal fun ChromaKeyColorPickerV70(
    red: Float,
    green: Float,
    blue: Float,
    onColorPicked: (red: Float, green: Float, blue: Float) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val currentColor = Color(
        red = red.coerceIn(0f, 1f),
        green = green.coerceIn(0f, 1f),
        blue = blue.coerceIn(0f, 1f),
        alpha = 1f,
    )

    OutlinedButton(onClick = { showPicker = true }) {
        Box(
            Modifier
                .size(14.dp)
                .background(currentColor, CircleShape)
                .border(1.dp, Color.White.copy(alpha = .62f), CircleShape),
        )
        Spacer(Modifier.width(5.dp))
        Text("Pick", fontSize = 8.sp)
    }

    if (!showPicker) return

    val initial = remember(red, green, blue, showPicker) { rgbToHsvV70(red, green, blue) }
    var hue by remember(red, green, blue, showPicker) { mutableFloatStateOf(initial[0]) }
    var saturation by remember(red, green, blue, showPicker) { mutableFloatStateOf(initial[1]) }
    var brightness by remember(red, green, blue, showPicker) { mutableFloatStateOf(initial[2]) }
    val preview = hsvToRgbV70(hue, saturation, brightness)
    val previewColor = Color(preview.first, preview.second, preview.third, 1f)

    AlertDialog(
        onDismissRequest = { showPicker = false },
        title = { Text("Pick chroma key color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose the screen/background color you want to remove.",
                    fontSize = 10.sp,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .background(previewColor, RoundedCornerShape(8.dp)),
                )
                PickerSliderV70("Hue", hue, 0f..360f) { hue = it }
                PickerSliderV70("Saturation", saturation, 0f..1f) { saturation = it }
                PickerSliderV70("Brightness", brightness, 0f..1f) { brightness = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onColorPicked(preview.first, preview.second, preview.third)
                    showPicker = false
                },
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = { showPicker = false }) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun PickerSliderV70(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (label == "Hue") "${value.roundToInt()}°" else "%.2f".format(value),
                fontSize = 9.sp,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
        )
    }
}

private fun rgbToHsvV70(red: Float, green: Float, blue: Float): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(
        (red.coerceIn(0f, 1f) * 255f).roundToInt(),
        (green.coerceIn(0f, 1f) * 255f).roundToInt(),
        (blue.coerceIn(0f, 1f) * 255f).roundToInt(),
        hsv,
    )
    return hsv
}

private fun hsvToRgbV70(
    hue: Float,
    saturation: Float,
    brightness: Float,
): Triple<Float, Float, Float> {
    val argb = android.graphics.Color.HSVToColor(
        floatArrayOf(
            hue.coerceIn(0f, 359.999f),
            saturation.coerceIn(0f, 1f),
            brightness.coerceIn(0f, 1f),
        ),
    )
    return Triple(
        android.graphics.Color.red(argb) / 255f,
        android.graphics.Color.green(argb) / 255f,
        android.graphics.Color.blue(argb) / 255f,
    )
}
