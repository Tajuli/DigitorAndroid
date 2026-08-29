package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.InputColorProfile
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolvedInputColorProfile

private val ICPPanel = Color(0xFF101014)
private val ICPDivider = Color(0xFF292930)
private val ICPAccent = Color(0xFF30E0C3)
private val ICPMuted = Color(0xFF909098)

/** Clip-level camera log/input transform shown above the node grading controls. */
@Composable
fun InputColorProfileBarV1(
    clip: TimelineClip?,
    vm: EditorViewModelV4,
) {
    if (clip == null) return
    val selected = clip.resolvedInputColorProfile()

    Column(Modifier.fillMaxWidth().background(ICPPanel)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Input Color", fontSize = 9.sp, color = Color.White, modifier = Modifier.padding(top = 9.dp))
            Text(selected.displayName, fontSize = 8.sp, color = ICPAccent, modifier = Modifier.padding(top = 9.dp))
        }
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            InputColorProfile.entries.forEach { profile ->
                val active = profile == selected
                FilledTonalButton(
                    onClick = { vm.commitInputColorProfile(profile) },
                    modifier = Modifier.height(29.dp),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        if (active) "✓ ${profile.displayName}" else profile.displayName,
                        fontSize = 7.sp,
                        color = if (active) ICPAccent else Color.White.copy(alpha = .72f),
                    )
                }
            }
        }
        Text(
            "Camera Log/HDR → Rec.709 working space → node grade",
            fontSize = 7.sp,
            color = ICPMuted,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
        HorizontalDivider(color = ICPDivider)
    }
}
