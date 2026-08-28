package com.tajuli.digitorandroid.editor.model

import java.util.UUID

const val US_PER_SECOND = 1_000_000L

enum class TrackKind { VIDEO, AUDIO }
enum class NodeKind { IMPORT, SERIAL, PARALLEL, MIX, OUTPUT }

data class ColorGrade(
    val redScale: Float = 1f,
    val greenScale: Float = 1f,
    val blueScale: Float = 1f,
    val hueDegrees: Float = 0f,
    val saturationDelta: Float = 0f,
    val lightnessDelta: Float = 0f,
) {
    val isIdentity: Boolean
        get() = redScale == 1f && greenScale == 1f && blueScale == 1f &&
            hueDegrees == 0f && saturationDelta == 0f && lightnessDelta == 0f
}

data class NodeCorrections(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val hue: Float = 0f,
    val colorBoost: Float = 0f,
)

data class NodeEffect(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val amount: Float = 1f,
    val enabled: Boolean = true,
)

data class NodePosition(val x: Float, val y: Float)

data class ColorNode(
    val id: String = UUID.randomUUID().toString(),
    val kind: NodeKind,
    val label: String,
    val position: NodePosition,
    val corrections: NodeCorrections = NodeCorrections(),
    val advancedColor: AdvancedColorGrade = AdvancedColorGrade(),
    val effects: List<NodeEffect> = emptyList(),
)

data class NodeEdge(val fromId: String, val toId: String)

data class ClipNodeGraph(
    val nodes: List<ColorNode>,
    val edges: List<NodeEdge>,
    val selectedNodeId: String?,
    val revision: Long = 0L,
) {
    companion object {
        fun default(): ClipNodeGraph {
            val input = ColorNode(
                kind = NodeKind.IMPORT,
                label = "Import",
                position = NodePosition(36f, 88f),
            )
            val serial = ColorNode(
                kind = NodeKind.SERIAL,
                label = "01",
                position = NodePosition(172f, 88f),
            )
            val output = ColorNode(
                kind = NodeKind.OUTPUT,
                label = "Output",
                position = NodePosition(348f, 88f),
            )
            return ClipNodeGraph(
                nodes = listOf(input, serial, output),
                edges = listOf(NodeEdge(input.id, serial.id), NodeEdge(serial.id, output.id)),
                selectedNodeId = serial.id,
            )
        }
    }

    fun selectedNode(): ColorNode? = nodes.firstOrNull { it.id == selectedNodeId }

    /** Legacy compatibility bridge for processors that still consume ColorGrade. */
    fun effectiveColorGrade(): ColorGrade {
        val editable = nodes.filter { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
        if (editable.isEmpty()) return ColorGrade()
        val exposure = editable.sumOf { it.corrections.exposure.toDouble() }.toFloat()
        val saturation = editable.sumOf { it.corrections.saturation.toDouble() }.toFloat()
        val hue = editable.sumOf { it.corrections.hue.toDouble() }.toFloat()
        val temperature = editable.sumOf { it.corrections.temperature.toDouble() }.toFloat()
        val tint = editable.sumOf { it.corrections.tint.toDouble() }.toFloat()
        val exposureScale = Math.pow(2.0, exposure.toDouble()).toFloat()
        val warm = (temperature / 100f).coerceIn(-1f, 1f)
        val magenta = (tint / 100f).coerceIn(-1f, 1f)
        return ColorGrade(
            redScale = (exposureScale * (1f + warm * .12f + magenta * .05f)).coerceAtLeast(0f),
            greenScale = (exposureScale * (1f - magenta * .04f)).coerceAtLeast(0f),
            blueScale = (exposureScale * (1f - warm * .12f + magenta * .05f)).coerceAtLeast(0f),
            hueDegrees = hue,
            saturationDelta = (saturation / 100f).coerceIn(-1f, 2f),
        )
    }
}

data class TimelineClip(
    val id: String = UUID.randomUUID().toString(),
    val uri: String,
    val label: String,
    val timelineStartUs: Long,
    val sourceInUs: Long = 0L,
    val sourceOutUs: Long,
    val opacity: Float = 1f,
    val colorGrade: ColorGrade = ColorGrade(),
    val linkGroupId: String? = null,
    val nodeGraph: ClipNodeGraph = ClipNodeGraph.default(),
    val transform: ClipTransform = ClipTransform(),
    val nodeAnimations: NodeAnimations = NodeAnimations(),
    val transition: ClipTransition = ClipTransition(),
    val audioMix: AudioMix = AudioMix(),
) {
    val durationUs: Long get() = (sourceOutUs - sourceInUs).coerceAtLeast(1L)
    val timelineEndUs: Long get() = timelineStartUs + durationUs
}

data class TimelineTrack(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: TrackKind,
    val clips: List<TimelineClip> = emptyList(),
    val muted: Boolean = false,
) {
    fun sortedClips(): List<TimelineClip> = clips.sortedBy { it.timelineStartUs }
}

data class TimelineProject(
    val title: String = "Untitled",
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Int = 30,
    val tracks: List<TimelineTrack> = listOf(
        TimelineTrack(name = "V1", kind = TrackKind.VIDEO),
        TimelineTrack(name = "A1", kind = TrackKind.AUDIO),
    ),
    val textOverlays: List<TextOverlayClip> = emptyList(),
) {
    val durationUs: Long
        get() = maxOf(
            tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEndUs } ?: 0L,
            textOverlays.maxOfOrNull { it.timelineEndUs } ?: 0L,
        )

    fun track(id: String?): TimelineTrack? = tracks.firstOrNull { it.id == id }
    fun clip(id: String?): TimelineClip? = tracks.asSequence().flatMap { it.clips.asSequence() }.firstOrNull { it.id == id }
    fun trackContaining(clipId: String): TimelineTrack? = tracks.firstOrNull { track -> track.clips.any { it.id == clipId } }

    fun linkedClipIds(clipId: String): Set<String> {
        val target = clip(clipId) ?: return emptySet()
        val group = target.linkGroupId ?: return setOf(target.id)
        return tracks.flatMap { it.clips }
            .filter { it.linkGroupId == group }
            .mapTo(linkedSetOf()) { it.id }
    }

    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        require(width > 0 && height > 0 && frameRate > 0)
        tracks.forEach { track ->
            val clips = track.sortedClips()
            clips.zipWithNext().forEach { (a, b) ->
                if (a.timelineEndUs > b.timelineStartUs) {
                    errors += "${track.name}: clips '${a.label}' and '${b.label}' overlap inside one track"
                }
            }
        }
        return errors
    }
}
