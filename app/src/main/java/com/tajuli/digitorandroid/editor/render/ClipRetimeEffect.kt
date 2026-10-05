package com.tajuli.digitorandroid.editor.render

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.timelineTimeAtSource
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import java.util.concurrent.Executor

/** Native decoder surfaces carry source PTS plus the stream's fixed composition offset. */
@UnstableApi
internal fun nativeRetimeEffects(clip: TimelineClip, live: Boolean): List<Effect> = listOf(
    object : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
            RetimeTimestampProgram { inputUs ->
                val current = if (live) PreviewProjectRegistry.clip(clip.id) ?: clip else clip
                val sourceUs = inputUs - clip.timelineStartUs + clip.sourceInUs
                current.timelineTimeAtSource(sourceUs)
            }
    },
)

/**
 * Synchronous, zero-copy timestamp mapping on the graph GL thread. Media3's deprecated
 * TimestampAdjustmentShaderProgram throws from flush(), so it cannot be used by our seekable
 * native preview. The same flush-safe program is used in export to keep clock behaviour identical.
 * Textures are borrowed from the preceding stage; this stage never allocates or deletes them.
 */
@UnstableApi
internal class RetimeTimestampProgram(private val map: (Long) -> Long) : GlShaderProgram {
    private var input: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var output: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var held: GlTextureInfo? = null

    override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
        input = inputListener
        if (held == null) input.onReadyToAcceptInputFrame()
    }

    override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
        output = outputListener
    }

    override fun setErrorListener(executor: Executor, errorListener: GlShaderProgram.ErrorListener) = Unit

    override fun queueInputFrame(provider: GlObjectsProvider, texture: GlTextureInfo, presentationTimeUs: Long) {
        check(held == null) { "Retime stage received a frame before the previous frame was released" }
        held = texture
        output.onOutputFrameAvailable(texture, map(presentationTimeUs))
    }

    override fun releaseOutputFrame(texture: GlTextureInfo) {
        val previous = held ?: return // Already discarded by flush.
        check(previous.texId == texture.texId)
        held = null
        input.onInputFrameProcessed(previous)
        input.onReadyToAcceptInputFrame()
    }

    override fun signalEndOfCurrentInputStream() {
        // Mapping is synchronous: every accepted frame has already been handed downstream.
        output.onCurrentOutputStreamEnded()
    }

    override fun flush() {
        held = null
        input.onFlush()
        input.onReadyToAcceptInputFrame()
    }

    override fun release() { held = null }
}
