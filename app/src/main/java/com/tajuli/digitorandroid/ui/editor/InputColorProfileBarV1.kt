package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/** Optional clip-level camera Log/input transform. None/Bypass preserves the flat source image. */
@Composable
fun InputColorProfileBarV1(
    clip: TimelineClip?,
    vm: EditorViewModelV4,
    modifier: Modifier = Modifier,
) {
    if (clip == null) return
    val selected = clip.resolvedInputColorProfile()

    Column(
        modifier
            .fillMaxSize()
            .background(ICPPanel)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Input Color", fontSize = 10.sp, color = Color.White, modifier = Modifier.padding(top = 8.dp))
            Text(selected.displayName, fontSize = 8.sp, color = ICPAccent, modifier = Modifier.padding(top = 9.dp))
        }

        Text(
            "Default is None / Bypass. Log footage stays flat and can be graded or exported without choosing a profile.",
            fontSize = 8.sp,
            color = ICPMuted,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )

        InputColorProfile.entries
            .groupBy { it.family }
            .forEach { (family, profiles) ->
                Text(
                    family.uppercase(),
                    fontSize = 7.sp,
                    color = ICPMuted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    profiles.forEach { profile ->
                        val active = profile == selected
                        FilledTonalButton(
                            onClick = { vm.commitInputColorProfile(profile) },
                            modifier = Modifier.height(31.dp),
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
            }

        HorizontalDivider(color = ICPDivider, modifier = Modifier.padding(top = 7.dp))
        Text(
            if (selected == InputColorProfile.NONE) {
                "Bypass: source code values → node grade → export"
            } else {
                "${selected.displayName} → Rec.709 working space → node grade → export"
            },
            fontSize = 7.sp,
            color = ICPMuted,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
