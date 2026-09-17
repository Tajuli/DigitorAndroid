package com.tajuli.digitorandroid.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.TrackKind
import com.tajuli.digitorandroid.editor.model.VisualOverlayKindV19
import com.tajuli.digitorandroid.editor.model.activeTextOverlaysAt
import com.tajuli.digitorandroid.editor.model.activeVisualOverlaysAtV19
import com.tajuli.digitorandroid.editor.model.resolvedVisualOverlaysV19

private val WorkspaceAccent = Color(0xFF30E0C3)

/** Canonical editor workspace tabs; the first five preserve the primary mobile workflow order. */
internal enum class EditorWorkspaceTab(val label: String, val icon: ImageVector) {
    EDIT("Edit", Icons.Rounded.ContentCut),
    CORRECTION("Correction", Icons.Rounded.Tune),
    EFFECTS("Effects", Icons.Rounded.AutoAwesome),
    FILTERS("Filters", Icons.Rounded.Palette),
    COLOR("Color", Icons.Rounded.Palette),
    TEXT("Text", Icons.Rounded.TextFields),
    OVERLAY("Overlay", Icons.Rounded.AddPhotoAlternate),
    AUDIO("Audio", Icons.Rounded.Audiotrack),
    MEDIA("Media", Icons.Rounded.VideoLibrary),
    NODES("Nodes", Icons.Rounded.AccountTree),
}

@Composable
internal fun EditorWorkspaceContent(
    selected: EditorWorkspaceTab,
    state: EditorUiState,
    selectedClip: TimelineClip?,
    cursorUs: Long,
    vm: EditorViewModel,
    onSeek: (Long) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when (selected) {
            EditorWorkspaceTab.EDIT -> EditWorkspace(
                project = state.project,
                selectedTrackId = state.selectedTrackId,
                selectedClipIds = state.selectedClipIds,
                selectedClip = selectedClip,
                cursorUs = cursorUs,
                vm = vm,
                onSeek = onSeek,
                onSelectTrack = vm::selectTrack,
                onSelectClip = vm::selectClip,
                onMoveClip = vm::moveClip,
                onMoveClipToTrack = vm::moveClipToTrack,
                onAddVideoTrack = { vm.addTrack(TrackKind.VIDEO) },
                onAddAudioTrack = { vm.addTrack(TrackKind.AUDIO) },
                onSplit = { vm.splitSelectedAt(cursorUs) },
                onDelete = vm::deleteSelected,
                onUnlink = vm::unlinkSelected,
                onImport = onImport,
                modifier = Modifier.fillMaxSize(),
            )

            EditorWorkspaceTab.CORRECTION -> KeyframedCorrectionWorkspace(
                selectedClip,
                state.project.frameRate,
                vm,
                Modifier.fillMaxSize(),
            )
            EditorWorkspaceTab.EFFECTS -> KeyframedEffectsWorkspace(
                selectedClip,
                state.project.frameRate,
                vm,
                Modifier.fillMaxSize(),
            )
            EditorWorkspaceTab.FILTERS -> CreatorFiltersWorkspace(selectedClip, vm, Modifier.fillMaxSize())
            EditorWorkspaceTab.COLOR -> KeyframedColorWorkspace(
                selectedClip,
                state.project.frameRate,
                vm,
                Modifier.fillMaxSize(),
            )
            EditorWorkspaceTab.TEXT -> TextWorkspaceV9(
                project = state.project,
                selectedTextId = state.selectedTextId,
                cursorUs = cursorUs,
                frameRate = state.project.frameRate,
                vm = vm,
                onSeek = onSeek,
                modifier = Modifier.fillMaxSize(),
            )
            EditorWorkspaceTab.OVERLAY -> VisualOverlayWorkspace(
                project = state.project,
                cursorUs = cursorUs,
                vm = vm,
                onSeek = onSeek,
                modifier = Modifier.fillMaxSize(),
            )
            EditorWorkspaceTab.AUDIO -> CreatorAudioWorkspace(
                state.project,
                state.selectedClipId,
                state.selectedClipIds,
                vm,
                Modifier.fillMaxSize(),
            )
            EditorWorkspaceTab.MEDIA -> CreatorMediaWorkspace(
                state.project,
                selectedClip,
                state.selectedTextId,
                cursorUs,
                state.busyOperation,
                vm,
                Modifier.fillMaxSize(),
            )
            EditorWorkspaceTab.NODES -> NodeGraph(selectedClip, vm, Modifier.fillMaxSize())
        }
    }
}

internal fun applyEditorWorkspaceSelection(
    next: EditorWorkspaceTab,
    state: EditorUiState,
    selectedClip: TimelineClip?,
    previewClip: TimelineClip?,
    cursorUs: Long,
    vm: EditorViewModel,
) {
    when (next) {
        EditorWorkspaceTab.TEXT -> {
            VisualOverlaySelectionBusV19.clear()
            val textTarget = state.project.activeTextOverlaysAt(cursorUs).lastOrNull()
                ?: state.project.textOverlays.lastOrNull()
            if (textTarget != null) vm.selectTextOverlay(textTarget.id)
        }

        EditorWorkspaceTab.OVERLAY -> {
            TimelineTextSelectionBusV10.clear()
            val overlayTarget = state.project.activeVisualOverlaysAtV19(cursorUs)
                .filter { it.kind != VisualOverlayKindV19.IMAGE }
                .lastOrNull()
                ?: state.project.resolvedVisualOverlaysV19()
                    .filter { it.kind != VisualOverlayKindV19.IMAGE }
                    .lastOrNull()
            if (overlayTarget != null) vm.selectVisualOverlayV19(overlayTarget.id)
        }

        else -> {
            val clipWorkspace = next == EditorWorkspaceTab.EDIT ||
                next == EditorWorkspaceTab.CORRECTION ||
                next == EditorWorkspaceTab.EFFECTS ||
                next == EditorWorkspaceTab.FILTERS ||
                next == EditorWorkspaceTab.COLOR ||
                next == EditorWorkspaceTab.NODES ||
                next == EditorWorkspaceTab.MEDIA
            val selectedIsActiveVideo = selectedClip?.let { clip ->
                state.project.trackContaining(clip.id)?.kind == TrackKind.VIDEO &&
                    cursorUs in clip.timelineStartUs until clip.timelineEndUs
            } == true
            if (clipWorkspace && !selectedIsActiveVideo && previewClip != null) {
                vm.selectClip(previewClip.id)
            }
        }
    }

    if (next != EditorWorkspaceTab.COLOR && state.qualifierPickerActive) {
        vm.setQualifierPickerActive(false)
    }
}

@Composable
internal fun EditorWorkspaceNavigationBar(
    selected: EditorWorkspaceTab,
    onSelected: (EditorWorkspaceTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .background(Color(0xFF0A0A0D))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        EditorWorkspaceTab.entries.filterNot { it == EditorWorkspaceTab.MEDIA }.forEach { item ->
            val active = item == selected
            Column(
                Modifier
                    .width(68.dp)
                    .fillMaxHeight()
                    .clickable { onSelected(item) }
                    .background(if (active) WorkspaceAccent.copy(alpha = .10f) else Color.Transparent),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    item.icon,
                    item.label,
                    modifier = Modifier.size(18.dp),
                    tint = if (active) WorkspaceAccent else Color.White.copy(alpha = .55f),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    item.label,
                    fontSize = 8.sp,
                    color = if (active) WorkspaceAccent else Color.White.copy(alpha = .55f),
                )
            }
        }
    }
}
