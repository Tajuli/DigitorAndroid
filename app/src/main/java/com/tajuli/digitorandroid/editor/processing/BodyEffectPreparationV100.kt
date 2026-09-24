package com.tajuli.digitorandroid.editor.processing

import android.content.Context
import com.tajuli.digitorandroid.editor.model.CreatorEffectPresetV25
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import com.tajuli.digitorandroid.editor.model.CutoutModeV43
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.bodyValuesV100
import com.tajuli.digitorandroid.editor.model.requirementsV100
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

data class BodyEffectPreparationResultV100(
    val ready: Boolean,
    val faceReady: Boolean,
    val poseReady: Boolean,
    val matteReady: Boolean,
    val message: String,
)

/**
 * One explicit preparation boundary for all tracked effects.
 *
 * The old implementation let UI, face analysis, cutout and rendering each make independent guesses.
 * V100 resolves those dependencies once before a tracked effect is inserted into the node graph.
 */
object BodyEffectPreparationV100 {
    suspend fun prepare(
        context: Context,
        clip: TimelineClip,
        preset: CreatorEffectPresetV25,
        prioritySourceUs: Long? = null,
    ): BodyEffectPreparationResultV100 = coroutineScope {
        val values = preset.vector.bodyValuesV100()
        val requirements = values.requirementsV100()
        if (!requirements.face && !requirements.pose && !requirements.matte) {
            return@coroutineScope BodyEffectPreparationResultV100(
                ready = true,
                faceReady = true,
                poseReady = true,
                matteReady = true,
                message = "No subject analysis required",
            )
        }

        val faceTask = if (requirements.face) {
            async(Dispatchers.Default) {
                runCatching { BeautyFaceAnalyzerV28(context).refineBodyFxAndStore(clip) }
            }
        } else null

        val poseTask = if (requirements.pose) {
            async(Dispatchers.Default) {
                runCatching { BodyEffectPoseAnalyzerV100(context).analyzeAndStore(clip) }
            }
        } else null

        val matteClip = if (requirements.matte) {
            clip.copy(
                cutoutV43 = clip.resolvedCutoutV43().copy(
                    mode = CutoutModeV43.PERSON,
                    analysisQualityV47 = CutoutAnalysisQualityV47.MEDIUM,
                    mattingSizeV69 = 384,
                    portraitLensBlurV99 = false,
                ),
            )
        } else null

        val matteTask = matteClip?.let { preparedClip ->
            async(Dispatchers.Default) {
                runCatching {
                    if (!hasPersonCutoutCoverageV43(context, preparedClip)) {
                        GpuPersonCutoutAnalyzerV47(context).analyzeAndStore(
                            preparedClip,
                            prioritySourceUs = prioritySourceUs ?: preparedClip.sourceInUs,
                        )
                    }
                    hasPersonCutoutCoverageV43(context, preparedClip)
                }
            }
        }

        val faceResult = faceTask?.await()
        val faceReady = if (!requirements.face) {
            true
        } else {
            val track = faceResult?.getOrNull()
            val relevant = track?.samples.orEmpty().filter {
                it.sourceTimeUs >= clip.sourceInUs && it.sourceTimeUs <= clip.sourceOutUs
            }
            relevant.isNotEmpty() &&
                relevant.count { it.geometry != null } * 100 >= relevant.size * FACE_READY_PERCENT
        }

        val poseResult = poseTask?.await()
        val poseReady = if (!requirements.pose) {
            true
        } else {
            poseResult?.getOrNull()?.let { track ->
                track.covers(clip.sourceInUs, clip.sourceOutUs) &&
                    track.detectedRatio() >= POSE_READY_RATIO
            } == true
        }

        val matteResult = matteTask?.await()
        val matteReady = if (!requirements.matte) {
            true
        } else {
            matteResult?.getOrNull() == true
        }

        val ready = faceReady && poseReady && matteReady
        val missing = buildList {
            if (!faceReady) add("face/eyes")
            if (!poseReady) add("pose")
            if (!matteReady) add("person matte")
        }
        BodyEffectPreparationResultV100(
            ready = ready,
            faceReady = faceReady,
            poseReady = poseReady,
            matteReady = matteReady,
            message = if (ready) {
                "Tracked subject data ready"
            } else {
                "Tracking incomplete: " + missing.joinToString(", ")
            },
        )
    }

    private const val FACE_READY_PERCENT = 60
    private const val POSE_READY_RATIO = .58f
}
