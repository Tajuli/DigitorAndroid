package com.tajuli.digitorandroid.editor.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.SystemClock
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.tajuli.digitorandroid.editor.model.BeautyFaceGeometryV28
import com.tajuli.digitorandroid.editor.model.BeautyFaceTrackV28
import com.tajuli.digitorandroid.editor.model.BeautyRectV28
import com.tajuli.digitorandroid.editor.model.CreatorEffectVectorV25
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.SpatialNodeGraphPlan
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.resolveCreatorEffectsV25
import com.tajuli.digitorandroid.editor.model.resolveTimedCreatorEffectsV26
import com.tajuli.digitorandroid.editor.model.resolvedCutoutV43
import com.tajuli.digitorandroid.editor.model.visibleEffects
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import com.tajuli.digitorandroid.editor.processing.BeautyFaceTrackStoreV28
import com.tajuli.digitorandroid.editor.processing.PersonCutoutMaskFrameV43
import com.tajuli.digitorandroid.editor.processing.PersonCutoutMaskStoreV43
import com.tajuli.digitorandroid.editor.processing.personCutoutMaxGapUsV47
import kotlin.math.abs

/**
 * V25 creator-effects renderer with V26 timed effect spans.
 *
 * Keeps Digitor's Resolve-style serial/parallel node topology while expanding the old four-effect
 * shader into a compact creator library: edge-aware video denoise, blur/sharpen/glow/grain plus
 * lens, RGB split, VHS lines, pixelation, waves, zoom blur, ghosting, flicker, vignette and
 * warm-film response. Preview and export use the same shader and source-time evaluation for parity
 * on the GPU path.
 */
@UnstableApi
internal class CreatorEffectGraphV25 private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(context, clip, preview, useHdr)

    companion object {
        fun forClip(clip: TimelineClip, preview: Boolean): CreatorEffectGraphV25? {
            val editableNodes = clip.nodeGraph.nodes.filter { node ->
                node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL
            }
            if (preview) {
                return if (editableNodes.isNotEmpty()) CreatorEffectGraphV25(clip, true) else null
            }
            val hasFx = editableNodes.any { node ->
                !resolveCreatorEffectsV25(node.visibleEffects()).isIdentity ||
                    clip.nodeAnimations.hasAnimation(node.id, NodeAnimationDomain.EFFECTS)
            }
            return if (hasFx) CreatorEffectGraphV25(clip, false) else null
        }
    }

    private class Program(
        context: Context,
        private val clip: TimelineClip,
        private val preview: Boolean,
        private val useHighPrecisionColorComponents: Boolean,
    ) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ useHighPrecisionColorComponents,
        /* texturePoolCapacity = */ 1,
    ) {
        private val appContext = context.applicationContext
        private val plan = SpatialNodeGraphPlan.compile(clip.nodeGraph)
        private val supportsTrackedFx = clip.nodeGraph.nodes.any { node ->
            node.kind == NodeKind.SERIAL || node.kind == NodeKind.PARALLEL
        } && clip.nodeGraph.nodes.any { node ->
            vectorRequiresTrackedFx(resolveCreatorEffectsV25(node.visibleEffects()))
        }
        private val nodeProgram: GlProgram
        private val trackedNodePrograms = linkedMapOf<Int, GlProgram>()
        private val mixProgram: GlProgram
        private val copyProgram: GlProgram
        private var inputWidth = 1
        private var inputHeight = 1
        private var scratchTextures = IntArray(0)
        private var scratchFbos = IntArray(0)
        private var faceTrack: BeautyFaceTrackV28? =
            if (supportsTrackedFx) BeautyFaceTrackStoreV28.load(appContext, clip) else null
        private var lastFaceTrackRefreshMs = 0L
        private var personMaskTextureA = 0
        private var personMaskTextureB = 0
        private var loadedPersonMaskPathA: String? = null
        private var loadedPersonMaskPathB: String? = null

        init {
            try {
                nodeProgram = newProgram(
                    NODE_FRAGMENT_SHADER.replace("#define DIGITOR_TRACKED_FX 1", "#define DIGITOR_TRACKED_FX 0"),
                )
                mixProgram = newProgram(MIX_FRAGMENT_SHADER)
                copyProgram = newProgram(COPY_FRAGMENT_SHADER)
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            this.inputWidth = inputWidth.coerceAtLeast(1)
            this.inputHeight = inputHeight.coerceAtLeast(1)
            try {
                releaseScratch()
                val count = plan.maximumScratchPasses.coerceAtLeast(1)
                scratchTextures = IntArray(count)
                scratchFbos = IntArray(count)
                for (index in 0 until count) {
                    val texture = GlUtil.createTexture(
                        this.inputWidth,
                        this.inputHeight,
                        useHighPrecisionColorComponents,
                    )
                    scratchTextures[index] = texture
                    scratchFbos[index] = GlUtil.createFboForTexture(texture)
                }
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                val outputFboHolder = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, outputFboHolder, 0)
                val media3OutputFbo = outputFboHolder[0]
                val currentClip = if (preview) PreviewProjectRegistry.clip(clip.id) ?: clip else clip
                val sourceUs = ParityRenderContract.sourceTimeUs(currentClip, presentationTimeUs)
                val currentVectors = currentClip.nodeGraph.nodes
                    .asSequence()
                    .filter { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
                    .map { resolveCreatorEffectsV25(it.visibleEffects()) }
                    .toList()
                val currentRequiresTrackedFx = currentVectors.any(::vectorRequiresTrackedFx)
                val currentRequiresPersonMask = currentVectors.any(::vectorNeedsPersonMask)
                val faceGeometry = if (currentRequiresTrackedFx) {
                    refreshFaceTrack(currentClip)?.geometryAt(sourceUs)
                } else {
                    null
                }
                if (currentRequiresPersonMask) {
                    if (personMaskTextureA == 0) personMaskTextureA = createMaskTexture()
                    if (personMaskTextureB == 0) personMaskTextureB = createMaskTexture()
                }
                val personBracket = if (currentRequiresPersonMask) {
                    personBracket(currentClip, sourceUs)
                } else {
                    PersonMaskBracket.empty()
                }
                val hasPersonMaskA = currentRequiresPersonMask &&
                    bindPersonMask(personMaskTextureA, personBracket.a?.file?.absolutePath, true)
                val hasPersonMaskB = currentRequiresPersonMask &&
                    bindPersonMask(personMaskTextureB, personBracket.b?.file?.absolutePath, false)
                val slotTextures = IntArray(plan.operations.size) { inputTexId }
                var scratchCursor = 0

                fun nextScratch(): Pair<Int, Int> {
                    if (scratchCursor !in scratchTextures.indices) {
                        throw VideoFrameProcessingException(
                            IllegalStateException(
                                "Creator V25 scratch pool exhausted: $scratchCursor/${scratchTextures.size}",
                            ),
                            presentationTimeUs,
                        )
                    }
                    val pair = scratchTextures[scratchCursor] to scratchFbos[scratchCursor]
                    scratchCursor++
                    return pair
                }

                plan.operations.forEach { operation ->
                    when (operation.node.kind) {
                        NodeKind.IMPORT -> slotTextures[operation.slot] = inputTexId

                        NodeKind.SERIAL, NodeKind.PARALLEL -> {
                            val input = textureForSlot(slotTextures, operation.inputSlot, operation.slot, inputTexId)
                            val currentNode = if (preview) {
                                currentClip.nodeGraph.nodes.firstOrNull { node -> node.id == operation.node.id }
                                    ?: operation.node
                            } else {
                                operation.node
                            }
                            val evaluated = currentClip.nodeAnimations.evaluateNode(currentNode, sourceUs)
                            val animatedById = evaluated.visibleEffects().associateBy { it.id }
                            // Base membership is authoritative: deleting an effect must not let an old
                            // effect-keyframe snapshot resurrect it. Keyframes only animate amount/enabled;
                            // timing always comes from the current effect instance.
                            val effectsWithTiming = currentNode.visibleEffects().map { base ->
                                val animated = animatedById[base.id] ?: base
                                animated.copy(
                                    name = base.name,
                                    sourceStartUsV26 = base.sourceStartUsV26,
                                    sourceEndUsV26 = base.sourceEndUsV26,
                                )
                            }
                            val vector = resolveTimedCreatorEffectsV26(effectsWithTiming, currentClip, sourceUs)
                            if (vector.isIdentity) {
                                slotTextures[operation.slot] = input
                            } else {
                                val trackedMask = trackedFeatureMask(vector)
                                val tracked = trackedMask != 0
                                val renderProgram = if (tracked) {
                                    trackedNodePrograms.getOrPut(trackedMask) {
                                        newProgram(trackedShaderFor(trackedMask))
                                    }
                                } else {
                                    nodeProgram
                                }
                                val (texture, fbo) = nextScratch()
                                focus(fbo)
                                renderNode(
                                    renderProgram,
                                    input,
                                    vector,
                                    evaluated.id,
                                    sourceUs,
                                    faceGeometry,
                                    trackedMask,
                                    hasPersonMaskA,
                                    hasPersonMaskB,
                                    personBracket.mix,
                                )
                                slotTextures[operation.slot] = texture
                            }
                        }

                        NodeKind.MIX -> {
                            val base = textureForSlot(
                                slotTextures,
                                operation.mixerBaseSlot,
                                operation.slot,
                                inputTexId,
                            )
                            var accumulator = base
                            operation.mixerInputSlots.forEach { branchSlot ->
                                if (branchSlot !in 0 until operation.slot) return@forEach
                                val branch = slotTextures[branchSlot]
                                if (branch == base) return@forEach
                                val (texture, fbo) = nextScratch()
                                focus(fbo)
                                renderMix(mixProgram, accumulator, branch, base)
                                accumulator = texture
                            }
                            slotTextures[operation.slot] = accumulator
                        }

                        NodeKind.OUTPUT -> {
                            slotTextures[operation.slot] = textureForSlot(
                                slotTextures,
                                operation.inputSlot,
                                operation.slot,
                                inputTexId,
                            )
                        }
                    }
                }

                val finalTexture = if (plan.outputSlot in slotTextures.indices) {
                    slotTextures[plan.outputSlot]
                } else {
                    inputTexId
                }
                GlUtil.focusFramebufferUsingCurrentContext(media3OutputFbo, inputWidth, inputHeight)
                renderCopy(copyProgram, finalTexture)
                GlUtil.checkGlError()
            } catch (error: VideoFrameProcessingException) {
                throw error
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error, presentationTimeUs)
            }
        }

        private fun refreshFaceTrack(currentClip: TimelineClip): BeautyFaceTrackV28? {
            val now = SystemClock.elapsedRealtime()
            if (faceTrack == null || now - lastFaceTrackRefreshMs >= FACE_TRACK_REFRESH_MS) {
                lastFaceTrackRefreshMs = now
                BeautyFaceTrackStoreV28.load(appContext, currentClip)?.let { faceTrack = it }
            }
            return faceTrack
        }

        private fun personBracket(currentClip: TimelineClip, sourceUs: Long): PersonMaskBracket {
            val frames = PersonCutoutMaskStoreV43.index(appContext, currentClip).frames
                .filter { it.file.isFile }
            if (frames.isEmpty()) return PersonMaskBracket.empty()

            val maxGapUs = personCutoutMaxGapUsV47(currentClip.resolvedCutoutV43().analysisQualityV47)
            if (frames.size == 1) {
                val frame = frames[0]
                return if (abs(sourceUs - frame.sourceTimeUs) <= maxGapUs) {
                    PersonMaskBracket(frame, frame, 0f)
                } else {
                    PersonMaskBracket.empty()
                }
            }

            var rightIndex = frames.binarySearchBy(sourceUs) { it.sourceTimeUs }
            if (rightIndex >= 0) return PersonMaskBracket(frames[rightIndex], frames[rightIndex], 0f)
            rightIndex = -rightIndex - 1
            val right = frames.getOrNull(rightIndex)
            val left = frames.getOrNull(rightIndex - 1)

            if (left == null) {
                return right?.takeIf { abs(it.sourceTimeUs - sourceUs) <= maxGapUs }
                    ?.let { PersonMaskBracket(it, it, 0f) } ?: PersonMaskBracket.empty()
            }
            if (right == null) {
                return left.takeIf { abs(sourceUs - it.sourceTimeUs) <= maxGapUs }
                    ?.let { PersonMaskBracket(it, it, 0f) } ?: PersonMaskBracket.empty()
            }

            val leftDistance = abs(sourceUs - left.sourceTimeUs)
            val rightDistance = abs(right.sourceTimeUs - sourceUs)
            val span = (right.sourceTimeUs - left.sourceTimeUs).coerceAtLeast(1L)
            if (span > maxGapUs) {
                return when {
                    leftDistance <= rightDistance && leftDistance <= maxGapUs -> PersonMaskBracket(left, left, 0f)
                    rightDistance <= maxGapUs -> PersonMaskBracket(right, right, 0f)
                    else -> PersonMaskBracket.empty()
                }
            }
            if (leftDistance > maxGapUs && rightDistance > maxGapUs) return PersonMaskBracket.empty()
            val mix = ((sourceUs - left.sourceTimeUs).toDouble() / span.toDouble()).toFloat().coerceIn(0f, 1f)
            return PersonMaskBracket(left, right, mix)
        }

        private fun createMaskTexture(): Int {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val texture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            uploadMaskBitmap(texture, null)
            return texture
        }

        private fun bindPersonMask(texture: Int, path: String?, slotA: Boolean): Boolean {
            val loaded = if (slotA) loadedPersonMaskPathA else loadedPersonMaskPathB
            if (path == null) {
                if (loaded != null) {
                    uploadMaskBitmap(texture, null)
                    if (slotA) loadedPersonMaskPathA = null else loadedPersonMaskPathB = null
                }
                return false
            }
            if (loaded == path) return true
            val bitmap = BitmapFactory.decodeFile(path) ?: return false
            try {
                uploadMaskBitmap(texture, bitmap)
                if (slotA) loadedPersonMaskPathA = path else loadedPersonMaskPathB = path
            } finally {
                bitmap.recycle()
            }
            return true
        }

        private fun uploadMaskBitmap(texture: Int, bitmap: Bitmap?) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            if (bitmap != null) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            } else {
                val black = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                try {
                    black.eraseColor(android.graphics.Color.BLACK)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, black, 0)
                } finally {
                    black.recycle()
                }
            }
        }

        private fun bodyRect(geometry: BeautyFaceGeometryV28?): BeautyRectV28? {
            val face = geometry?.face?.normalized() ?: return null
            val faceWidth = (face.right - face.left).coerceAtLeast(.01f)
            val faceHeight = (face.bottom - face.top).coerceAtLeast(.01f)
            val centerX = (face.left + face.right) * .5f
            return BeautyRectV28(
                left = (centerX - faceWidth * 1.25f).coerceAtLeast(0f),
                top = (face.top - faceHeight * .08f).coerceAtLeast(0f),
                right = (centerX + faceWidth * 1.25f).coerceAtMost(1f),
                bottom = (face.bottom + faceHeight * 3.25f).coerceAtMost(1f),
            ).normalized()
        }

        private fun setRect(program: GlProgram, name: String, rect: BeautyRectV28?) {
            val r = rect?.normalized()
            program.setFloatsUniform(
                name,
                if (r == null) floatArrayOf(0f, 0f, 0f, 0f)
                else floatArrayOf(r.left, r.top, r.right, r.bottom),
            )
        }

        private fun textureForSlot(
            slots: IntArray,
            slot: Int,
            currentSlot: Int,
            sourceTexture: Int,
        ): Int = if (slot in 0 until currentSlot) slots[slot] else sourceTexture

        private fun focus(fbo: Int) {
            GlUtil.focusFramebufferUsingCurrentContext(fbo, inputWidth, inputHeight)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        private fun renderNode(
            program: GlProgram,
            inputTexture: Int,
            v: CreatorEffectVectorV25,
            nodeId: String,
            sourceUs: Long,
            geometry: BeautyFaceGeometryV28?,
            trackedMask: Int,
            hasPersonMaskA: Boolean,
            hasPersonMaskB: Boolean,
            personTemporalMix: Float,
        ) {
            val tracked = trackedMask != 0
            val needsPersonMask = trackedMask and PERSON_MASK_FEATURES != 0
            val needsEyes = trackedMask and EYE_FEATURES != 0
            val needsBodyRect = trackedMask and PERSON_MASK_FEATURES != 0
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexture, 0)
            if (needsPersonMask) {
                program.setSamplerTexIdUniform("uPersonMaskA", personMaskTextureA, 1)
                program.setSamplerTexIdUniform("uPersonMaskB", personMaskTextureB, 2)
            }
            program.setFloatsUniform(
                "uTexelSize",
                floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
            )
            program.setFloatUniform("uBlur", v.blur)
            program.setFloatUniform("uSharpen", v.sharpen)
            program.setFloatUniform("uGlow", v.glow)
            program.setFloatUniform("uGrain", v.grain)
            program.setFloatUniform("uVignette", v.vignette)
            program.setFloatUniform("uRgbSplit", v.rgbSplit)
            program.setFloatUniform("uScanlines", v.scanlines)
            program.setFloatUniform("uPixelate", v.pixelate)
            program.setFloatUniform("uWave", v.wave)
            program.setFloatUniform("uLens", v.lens)
            program.setFloatUniform("uZoomBlur", v.zoomBlur)
            program.setFloatUniform("uGhost", v.ghost)
            program.setFloatUniform("uFlicker", v.flicker)
            program.setFloatUniform("uWarm", v.warm)
            program.setFloatUniform("uDenoise", v.denoise)
            program.setFloatUniform("uCrossShift", v.crossShift)
            program.setFloatUniform("uSmear", v.smear)
            program.setFloatUniform("uEdgeGlow", v.edgeGlow)
            program.setFloatUniform("uElectric", v.electric)
            if (tracked) {
                if (trackedMask and FX_CLONE != 0) program.setFloatUniform("uClone", v.clone)
                if (trackedMask and FX_FIRE_EYES != 0) program.setFloatUniform("uFireEyes", v.fireEyes)
                if (trackedMask and FX_BODY_ELECTRIC != 0) program.setFloatUniform("uBodyElectric", v.bodyElectric)
                if (trackedMask and FX_BODY_AURA != 0) program.setFloatUniform("uBodyAura", v.bodyAura)
                if (trackedMask and FX_ELECTRIC_EYES != 0) program.setFloatUniform("uElectricEyes", v.electricEyes)
                if (trackedMask and FX_LASER_EYES != 0) program.setFloatUniform("uLaserEyes", v.laserEyes)
                if (trackedMask and FX_STROKE != 0) program.setFloatUniform("uStroke", v.stroke)
                if (trackedMask and FX_BODY_FIRE != 0) program.setFloatUniform("uBodyFire", v.bodyFire)
                program.setFloatUniform("uHasFace", if (geometry == null) 0f else 1f)
                if (needsPersonMask) {
                    program.setFloatUniform("uHasPersonMaskA", if (hasPersonMaskA) 1f else 0f)
                    program.setFloatUniform("uHasPersonMaskB", if (hasPersonMaskB) 1f else 0f)
                    program.setFloatUniform("uPersonTemporalMix", personTemporalMix)
                }
                if (needsEyes) {
                    setRect(program, "uLeftEyeRect", geometry?.leftEye)
                    setRect(program, "uRightEyeRect", geometry?.rightEye)
                }
                if (needsBodyRect) setRect(program, "uBodyRect", bodyRect(geometry))
            }
            program.setFloatUniform("uTime", (sourceUs % 10_000_000L).toFloat() / 1_000_000f)
            program.setFloatUniform("uSeed", ((nodeId.hashCode() ushr 1) % 10_000).toFloat() / 10_000f)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun renderMix(
            program: GlProgram,
            accumulatorTexture: Int,
            branchTexture: Int,
            baseTexture: Int,
        ) {
            program.use()
            program.setSamplerTexIdUniform("uAccumulator", accumulatorTexture, 0)
            program.setSamplerTexIdUniform("uBranch", branchTexture, 1)
            program.setSamplerTexIdUniform("uBase", baseTexture, 2)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun renderCopy(program: GlProgram, texture: Int) {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", texture, 0)
            program.bindAttributesAndUniforms()
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun newProgram(fragmentShader: String): GlProgram =
            GlProgram(VERTEX_SHADER, fragmentShader).also { program ->
                program.setBufferAttribute(
                    "aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(),
                    GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
                )
            }

        private fun releaseScratch() {
            scratchFbos.forEach { fbo -> if (fbo != 0) GlUtil.deleteFbo(fbo) }
            scratchTextures.forEach { texture -> if (texture != 0) GlUtil.deleteTexture(texture) }
            scratchFbos = IntArray(0)
            scratchTextures = IntArray(0)
        }

        override fun release() {
            super.release()
            try {
                releaseScratch()
                listOf(personMaskTextureA, personMaskTextureB).filter { it != 0 }.forEach { texture ->
                    GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                }
                personMaskTextureA = 0
                personMaskTextureB = 0
                nodeProgram.delete()
                trackedNodePrograms.values.forEach { it.delete() }
                trackedNodePrograms.clear()
                mixProgram.delete()
                copyProgram.delete()
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error)
            }
        }

        private data class PersonMaskBracket(
            val a: PersonCutoutMaskFrameV43?,
            val b: PersonCutoutMaskFrameV43?,
            val mix: Float,
        ) {
            companion object { fun empty() = PersonMaskBracket(null, null, 0f) }
        }

        companion object {
            private const val FACE_TRACK_REFRESH_MS = 700L

            private fun vectorRequiresTrackedFx(v: CreatorEffectVectorV25): Boolean =
                v.clone > .001f || v.fireEyes > .001f || v.bodyElectric > .001f ||
                    v.bodyAura > .001f || v.electricEyes > .001f || v.laserEyes > .001f ||
                    v.stroke > .001f || v.bodyFire > .001f

            private const val VERTEX_SHADER = """
                attribute vec4 aFramePosition;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aFramePosition;
                    vTexCoord = aFramePosition.xy * 0.5 + 0.5;
                }
            """

            private const val NODE_FRAGMENT_SHADER = """
                #define DIGITOR_TRACKED_FX 1
                precision highp float;
                uniform sampler2D uTexSampler;
                #if DIGITOR_TRACKED_FX
                uniform sampler2D uPersonMaskA;
                uniform sampler2D uPersonMaskB;
                #endif
                uniform vec2 uTexelSize;
                uniform float uBlur;
                uniform float uSharpen;
                uniform float uGlow;
                uniform float uGrain;
                uniform float uVignette;
                uniform float uRgbSplit;
                uniform float uScanlines;
                uniform float uPixelate;
                uniform float uWave;
                uniform float uLens;
                uniform float uZoomBlur;
                uniform float uGhost;
                uniform float uFlicker;
                uniform float uWarm;
                uniform float uDenoise;
                uniform float uCrossShift;
                uniform float uSmear;
                uniform float uEdgeGlow;
                uniform float uElectric;
                #if DIGITOR_TRACKED_FX
                uniform float uClone;
                uniform float uFireEyes;
                uniform float uBodyElectric;
                uniform float uBodyAura;
                uniform float uElectricEyes;
                uniform float uLaserEyes;
                uniform float uStroke;
                uniform float uBodyFire;
                uniform float uHasFace;
                uniform float uHasPersonMaskA;
                uniform float uHasPersonMaskB;
                uniform float uPersonTemporalMix;
                uniform vec4 uLeftEyeRect;
                uniform vec4 uRightEyeRect;
                uniform vec4 uBodyRect;
                #endif
                uniform float uTime;
                uniform float uSeed;
                varying vec2 vTexCoord;

                float hash21(vec2 p) {
                    p = fract(p * vec2(123.34 + uSeed * 17.0, 345.45 + uSeed * 31.0));
                    p += dot(p, p + 34.345 + uTime * 0.173 + uSeed * 13.7);
                    return fract(p.x * p.y);
                }

                float lumaOf(vec3 c) {
                    return dot(c, vec3(0.2126, 0.7152, 0.0722));
                }

                float denoiseWeight(vec3 sampleRgb, float centerLuma, float sigma) {
                    float delta = abs(lumaOf(sampleRgb) - centerLuma);
                    return exp(-delta / max(sigma, 0.001));
                }

                vec3 sampleCreator(vec2 uv) {
                    return texture2D(uTexSampler, clamp(uv, 0.001, 0.999)).rgb;
                }

                #if DIGITOR_TRACKED_FX
                float ellipseMask(vec2 p, vec4 rect, float inner, float outer) {
                    vec2 halfSize = max((rect.zw - rect.xy) * 0.5, vec2(0.0005));
                    vec2 center = (rect.xy + rect.zw) * 0.5;
                    vec2 q = (p - center) / halfSize;
                    float d = dot(q, q);
                    return 1.0 - smoothstep(inner, outer, d);
                }

                vec4 resolvedBodyRect() {
                    return uHasFace > 0.5 ? uBodyRect : vec4(0.22, 0.22, 0.78, 0.98);
                }

                vec4 resolvedLeftEyeRect() {
                    return uHasFace > 0.5 ? uLeftEyeRect : vec4(0.36, 0.32, 0.47, 0.43);
                }

                vec4 resolvedRightEyeRect() {
                    return uHasFace > 0.5 ? uRightEyeRect : vec4(0.53, 0.32, 0.64, 0.43);
                }

                float fallbackBodyMask(vec2 videoUv) {
                    vec2 topLeftUv = vec2(videoUv.x, 1.0 - videoUv.y);
                    vec4 rect = resolvedBodyRect();
                    return ellipseMask(topLeftUv, rect, 0.72, 1.08);
                }

                float rawPersonAt(vec2 videoUv) {
                    if (uHasPersonMaskA < 0.5 && uHasPersonMaskB < 0.5) return -1.0;
                    vec2 clamped = clamp(videoUv, vec2(0.0), vec2(1.0));
                    vec2 maskUv = vec2(clamped.x, 1.0 - clamped.y);
                    float a = uHasPersonMaskA > 0.5 ? texture2D(uPersonMaskA, maskUv).r : 0.0;
                    float b = uHasPersonMaskB > 0.5 ? texture2D(uPersonMaskB, maskUv).r : a;
                    if (uHasPersonMaskA < 0.5) a = b;
                    return mix(a, b, clamp(uPersonTemporalMix, 0.0, 1.0));
                }

                float subjectMaskAt(vec2 videoUv) {
                    float raw = rawPersonAt(videoUv);
                    if (raw < 0.0) return fallbackBodyMask(videoUv);
                    return smoothstep(0.035, 0.72, raw);
                }

                float subjectDilate(vec2 uv, float pixels) {
                    vec2 o = uTexelSize * pixels;
                    float m = subjectMaskAt(uv);
                    m = max(m, subjectMaskAt(uv + vec2( o.x, 0.0)));
                    m = max(m, subjectMaskAt(uv + vec2(-o.x, 0.0)));
                    m = max(m, subjectMaskAt(uv + vec2(0.0,  o.y)));
                    m = max(m, subjectMaskAt(uv + vec2(0.0, -o.y)));
                    m = max(m, subjectMaskAt(uv + vec2( o.x,  o.y)));
                    m = max(m, subjectMaskAt(uv + vec2(-o.x,  o.y)));
                    m = max(m, subjectMaskAt(uv + vec2( o.x, -o.y)));
                    m = max(m, subjectMaskAt(uv + vec2(-o.x, -o.y)));
                    return m;
                }

                float subjectErode(vec2 uv, float pixels) {
                    vec2 o = uTexelSize * pixels;
                    float m = subjectMaskAt(uv);
                    m = min(m, subjectMaskAt(uv + vec2( o.x, 0.0)));
                    m = min(m, subjectMaskAt(uv + vec2(-o.x, 0.0)));
                    m = min(m, subjectMaskAt(uv + vec2(0.0,  o.y)));
                    m = min(m, subjectMaskAt(uv + vec2(0.0, -o.y)));
                    return m;
                }

                float subjectStroke(vec2 uv, float pixels) {
                    return clamp(subjectDilate(uv, pixels) - subjectErode(uv, pixels * 0.62), 0.0, 1.0);
                }

                float eyeFireMask(vec2 p, vec4 rect, float phase) {
                    vec2 size = max(rect.zw - rect.xy, vec2(0.003));
                    vec2 center = (rect.xy + rect.zw) * 0.5;
                    vec2 q = (p - center) / size;
                    float core = exp(-dot(q * vec2(2.6, 3.4), q * vec2(2.6, 3.4)) * 2.4);
                    float up = (center.y - p.y) / max(size.y, 0.004);
                    float sway = sin(up * 8.0 + uTime * 8.5 + phase) * 0.16
                        + sin(up * 17.0 - uTime * 11.0 + phase * 1.7) * 0.06;
                    float taper = max(0.08, 0.42 - up * 0.10);
                    float plume = exp(-abs(q.x - sway) / taper)
                        * smoothstep(-0.10, 0.12, up)
                        * (1.0 - smoothstep(0.25, 3.20, up));
                    return clamp(core + plume * 0.92, 0.0, 1.0);
                }

                float segmentDistance(vec2 p, vec2 a, vec2 b) {
                    vec2 pa = p - a;
                    vec2 ba = b - a;
                    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.00001), 0.0, 1.0);
                    return length(pa - ba * h);
                }

                float lineGlow(vec2 p, vec2 a, vec2 b, float width) {
                    float d = segmentDistance(p, a, b);
                    float core = 1.0 - smoothstep(width * 0.18, width * 0.44, d);
                    float glow = 1.0 - smoothstep(width * 0.40, width * 2.25, d);
                    return clamp(core + glow * 0.58, 0.0, 1.0);
                }

                float jaggedEyeBolt(vec2 p, vec2 origin, vec2 target, float phase) {
                    vec2 d = target - origin;
                    vec2 n = normalize(vec2(-d.y, d.x) + vec2(0.0001));
                    vec2 p1 = origin + d * 0.32 + n * (0.018 * sin(uTime * 15.0 + phase));
                    vec2 p2 = origin + d * 0.64 + n * (0.025 * sin(uTime * 21.0 + phase * 1.7));
                    float a = lineGlow(p, origin, p1, 0.010);
                    float b = lineGlow(p, p1, p2, 0.009);
                    float c = lineGlow(p, p2, target, 0.008);
                    return max(a, max(b, c));
                }

                #endif

                float electricBand(vec2 uv, float phase) {
                    float path = 0.50
                        + 0.18 * sin(uv.x * 19.0 + uTime * 4.3 + phase)
                        + 0.065 * sin(uv.x * 53.0 - uTime * 7.1 + phase * 1.7)
                        + 0.025 * sin(uv.x * 113.0 + uTime * 11.0 + phase * 0.7);
                    float d = abs(uv.y - path);
                    return exp(-d * 180.0) + 0.32 * exp(-d * 38.0);
                }

                vec2 creatorUv(vec2 uv) {
                    vec2 p = uv * 2.0 - 1.0;
                    float r2 = dot(p, p);
                    p *= 1.0 + uLens * 0.28 * r2;
                    uv = p * 0.5 + 0.5;
                    uv.x += sin(uv.y * 28.0 + uTime * 5.7) * uWave * 0.010;
                    uv.y += sin(uv.x * 24.0 - uTime * 4.1) * uWave * 0.006;
                    if (uPixelate > 0.001) {
                        float cells = mix(420.0, 30.0, clamp(uPixelate, 0.0, 1.0));
                        uv = (floor(uv * cells) + 0.5) / cells;
                    }
                    return clamp(uv, 0.001, 0.999);
                }

                void main() {
                    vec2 uv = creatorUv(vTexCoord);
                    vec4 center = texture2D(uTexSampler, uv);
                    float radius = 1.0 + uBlur * 4.0 + uGlow * 1.7;
                    vec2 o = uTexelSize * radius;

                    vec3 n  = texture2D(uTexSampler, clamp(uv + vec2(0.0,  o.y), 0.001, 0.999)).rgb;
                    vec3 s  = texture2D(uTexSampler, clamp(uv + vec2(0.0, -o.y), 0.001, 0.999)).rgb;
                    vec3 e  = texture2D(uTexSampler, clamp(uv + vec2( o.x, 0.0), 0.001, 0.999)).rgb;
                    vec3 w  = texture2D(uTexSampler, clamp(uv + vec2(-o.x, 0.0), 0.001, 0.999)).rgb;
                    vec3 ne = texture2D(uTexSampler, clamp(uv + vec2( o.x,  o.y), 0.001, 0.999)).rgb;
                    vec3 nw = texture2D(uTexSampler, clamp(uv + vec2(-o.x,  o.y), 0.001, 0.999)).rgb;
                    vec3 se = texture2D(uTexSampler, clamp(uv + vec2( o.x, -o.y), 0.001, 0.999)).rgb;
                    vec3 sw = texture2D(uTexSampler, clamp(uv + vec2(-o.x, -o.y), 0.001, 0.999)).rgb;

                    vec3 denoiseBase = center.rgb;
                    if (uDenoise > 0.001) {
                        float strength = clamp(uDenoise, 0.0, 1.0);
                        float centerLuma = lumaOf(center.rgb);
                        float sigma = mix(0.022, 0.115, strength);
                        float wc = 2.5;
                        float wn = denoiseWeight(n, centerLuma, sigma);
                        float ws = denoiseWeight(s, centerLuma, sigma);
                        float we = denoiseWeight(e, centerLuma, sigma);
                        float ww = denoiseWeight(w, centerLuma, sigma);
                        float wne = denoiseWeight(ne, centerLuma, sigma) * 0.72;
                        float wnw = denoiseWeight(nw, centerLuma, sigma) * 0.72;
                        float wse = denoiseWeight(se, centerLuma, sigma) * 0.72;
                        float wsw = denoiseWeight(sw, centerLuma, sigma) * 0.72;
                        float weightSum = wc + wn + ws + we + ww + wne + wnw + wse + wsw;
                        vec3 filtered = (
                            center.rgb * wc + n * wn + s * ws + e * we + w * ww +
                            ne * wne + nw * wnw + se * wse + sw * wsw
                        ) / max(weightSum, 0.001);
                        float centerChroma = max(center.r, max(center.g, center.b)) - min(center.r, min(center.g, center.b));
                        float chromaProtection = 1.0 - smoothstep(0.22, 0.72, centerChroma);
                        float mixAmount = strength * mix(0.52, 0.78, chromaProtection);
                        denoiseBase = mix(center.rgb, filtered, mixAmount);
                    }

                    vec3 blurred = (center.rgb * 4.0 + (n + s + e + w) * 2.0 + ne + nw + se + sw) / 16.0;
                    vec3 rgb = mix(denoiseBase, blurred, clamp(uBlur * 0.92, 0.0, 0.92));

                    vec3 crossAverage = (n + s + e + w) * 0.25;
                    rgb += (center.rgb - crossAverage) * uSharpen * 1.35;

                    float glowMask = smoothstep(0.44, 0.88, dot(blurred, vec3(0.2126, 0.7152, 0.0722)));
                    rgb += blurred * glowMask * uGlow * 0.68;

                    if (uZoomBlur > 0.001) {
                        vec2 d = uv - vec2(0.5);
                        vec3 zb = vec3(0.0);
                        zb += texture2D(uTexSampler, clamp(uv - d * 0.015 * uZoomBlur, 0.001, 0.999)).rgb;
                        zb += texture2D(uTexSampler, clamp(uv - d * 0.035 * uZoomBlur, 0.001, 0.999)).rgb;
                        zb += texture2D(uTexSampler, clamp(uv - d * 0.060 * uZoomBlur, 0.001, 0.999)).rgb;
                        zb += texture2D(uTexSampler, clamp(uv - d * 0.090 * uZoomBlur, 0.001, 0.999)).rgb;
                        rgb = mix(rgb, zb * 0.25, clamp(uZoomBlur * 0.72, 0.0, 0.88));
                    }

                    if (uGhost > 0.001) {
                        vec2 go = vec2(0.012 + 0.010 * sin(uTime * 2.1), 0.006) * uGhost;
                        vec3 ghost = texture2D(uTexSampler, clamp(uv - go, 0.001, 0.999)).rgb;
                        rgb = mix(rgb, max(rgb, ghost), clamp(uGhost * 0.46, 0.0, 0.72));
                    }

                    #if DIGITOR_TRACKED_FX
                    if (uClone > 0.001) {
                        float cloneStrength = clamp(uClone, 0.0, 1.5);
                        float spread = mix(0.105, 0.175, min(cloneStrength, 1.0));
                        float breathe = 0.010 * sin(uTime * 1.7);
                        vec2 leftUv = clamp(uv + vec2(spread + breathe, 0.006 * sin(uTime * 1.2)), 0.001, 0.999);
                        vec2 rightUv = clamp(uv - vec2(spread + breathe, 0.006 * sin(uTime * 1.2)), 0.001, 0.999);
                        vec3 leftClone = sampleCreator(leftUv);
                        vec3 rightClone = sampleCreator(rightUv);

                        float originalBody = subjectMaskAt(uv);
                        float leftBody = subjectMaskAt(leftUv);
                        float rightBody = subjectMaskAt(rightUv);

                        // Opaque subject-only clone compositing. Keeping the original subject on top
                        // prevents the washed-out three-exposure look that the old frame averaging caused.
                        float alpha = clamp(0.94 + cloneStrength * 0.035, 0.0, 0.995);
                        float keepCenterClear = 1.0 - originalBody * 0.94;
                        rgb = mix(rgb, leftClone, leftBody * keepCenterClear * alpha);
                        rgb = mix(rgb, rightClone, rightBody * keepCenterClear * alpha);

                        if (cloneStrength > 1.05) {
                            float farSpread = spread * 1.72;
                            vec2 farLeftUv = clamp(uv + vec2(farSpread, -0.010), 0.001, 0.999);
                            vec2 farRightUv = clamp(uv - vec2(farSpread, -0.010), 0.001, 0.999);
                            float farLeftBody = subjectMaskAt(farLeftUv);
                            float farRightBody = subjectMaskAt(farRightUv);
                            float farAlpha = alpha * 0.90;
                            rgb = mix(rgb, sampleCreator(farLeftUv), farLeftBody * keepCenterClear * farAlpha);
                            rgb = mix(rgb, sampleCreator(farRightUv), farRightBody * keepCenterClear * farAlpha);
                        }
                    }

                    #endif

                    if (uSmear > 0.001) {
                        float smearStrength = clamp(uSmear, 0.0, 1.5);
                        vec2 smearStep = vec2(
                            0.010 + 0.006 * sin(uTime * 1.4),
                            0.003 + 0.004 * cos(uTime * 1.1)
                        ) * smearStrength;
                        vec3 smear = center.rgb;
                        smear += sampleCreator(uv - smearStep);
                        smear += sampleCreator(uv - smearStep * 2.0);
                        smear += sampleCreator(uv - smearStep * 3.0);
                        smear += sampleCreator(uv - smearStep * 4.0);
                        smear *= 0.20;
                        rgb = mix(rgb, max(rgb, smear), clamp(smearStrength * 0.62, 0.0, 0.82));
                    }

                    if (uCrossShift > 0.001) {
                        float crossStrength = clamp(uCrossShift, 0.0, 1.5);
                        vec2 p = vTexCoord - vec2(0.5);
                        vec2 cross = vec2(p.y, -p.x) * (0.055 + 0.025 * sin(uTime * 1.3)) * crossStrength;
                        vec3 crossA = sampleCreator(uv + cross);
                        vec3 crossB = sampleCreator(uv - cross);
                        float quadrant = step(0.0, p.x * p.y);
                        vec3 shifted = mix(crossA, crossB, quadrant);
                        rgb = mix(rgb, shifted, clamp(crossStrength * 0.68, 0.0, 0.86));
                    }

                    if (uRgbSplit > 0.001) {
                        vec2 ro = vec2(uTexelSize.x * (3.0 + 12.0 * uRgbSplit), 0.0);
                        float rr = texture2D(uTexSampler, clamp(uv + ro, 0.001, 0.999)).r;
                        float bb = texture2D(uTexSampler, clamp(uv - ro, 0.001, 0.999)).b;
                        rgb.r = mix(rgb.r, rr, clamp(uRgbSplit, 0.0, 1.0));
                        rgb.b = mix(rgb.b, bb, clamp(uRgbSplit, 0.0, 1.0));
                    }

                    if (uEdgeGlow > 0.001) {
                        float edgeH = abs(lumaOf(e) - lumaOf(w));
                        float edgeV = abs(lumaOf(n) - lumaOf(s));
                        float edgeD = abs(lumaOf(ne) - lumaOf(sw)) + abs(lumaOf(nw) - lumaOf(se));
                        float edge = smoothstep(0.035, 0.42, edgeH + edgeV + edgeD * 0.45);
                        float neonPhase = sin(uTime * 2.0 + vTexCoord.x * 7.0 + vTexCoord.y * 5.0) * 0.5 + 0.5;
                        vec3 neon = mix(vec3(0.08, 0.78, 1.00), vec3(0.88, 0.16, 1.00), neonPhase);
                        rgb += neon * edge * clamp(uEdgeGlow, 0.0, 1.5) * 0.58;
                    }

                    if (uElectric > 0.001) {
                        float electricStrength = clamp(uElectric, 0.0, 1.5);
                        float boltA = electricBand(vTexCoord, 0.0);
                        float boltB = electricBand(vec2(vTexCoord.x, 1.0 - vTexCoord.y), 2.4) * 0.72;
                        float pulse = 0.78 + 0.22 * sin(uTime * 18.0);
                        float bolt = clamp((boltA + boltB) * pulse, 0.0, 1.5);
                        vec3 electricColor = mix(
                            vec3(0.10, 0.72, 1.00),
                            vec3(0.70, 0.20, 1.00),
                            sin(uTime * 2.8 + vTexCoord.x * 8.0) * 0.5 + 0.5
                        );
                        rgb += electricColor * bolt * electricStrength * 0.52;
                        rgb += vec3(1.0) * smoothstep(0.60, 1.15, bolt) * electricStrength * 0.18;
                    }

                    #if DIGITOR_TRACKED_FX
                    vec2 topLeftP = vec2(vTexCoord.x, 1.0 - vTexCoord.y);

                    if (uFireEyes > 0.001) {
                        float fireStrength = clamp(uFireEyes, 0.0, 1.5);
                        float leftFire = eyeFireMask(topLeftP, resolvedLeftEyeRect(), 0.7);
                        float rightFire = eyeFireMask(topLeftP, resolvedRightEyeRect(), 2.3);
                        float fire = clamp(leftFire + rightFire, 0.0, 1.0);
                        float hot = smoothstep(0.42, 0.92, fire);
                        vec3 flameColor = mix(vec3(1.00, 0.10, 0.01), vec3(1.00, 0.90, 0.16), hot);
                        rgb = mix(rgb, max(rgb, flameColor * (0.78 + 0.42 * hot)),
                            clamp(fire * fireStrength * 0.96, 0.0, 0.98));
                        rgb += flameColor * fire * fireStrength * 0.28;
                    }

                    if (uElectricEyes > 0.001) {
                        float strength = clamp(uElectricEyes, 0.0, 1.5);
                        vec4 le = resolvedLeftEyeRect();
                        vec4 re = resolvedRightEyeRect();
                        vec2 lc = (le.xy + le.zw) * 0.5;
                        vec2 rc = (re.xy + re.zw) * 0.5;
                        vec2 lt = lc + vec2(-0.30, -0.12);
                        vec2 rt = rc + vec2( 0.30, -0.12);
                        float bolt = max(
                            jaggedEyeBolt(topLeftP, lc, lt, 0.8),
                            jaggedEyeBolt(topLeftP, rc, rt, 2.7)
                        );
                        float eyeCore = max(
                            ellipseMask(topLeftP, le, 0.18, 0.90),
                            ellipseMask(topLeftP, re, 0.18, 0.90)
                        );
                        float pulse = 0.82 + 0.18 * sin(uTime * 19.0);
                        vec3 electricEyeColor = mix(
                            vec3(0.18, 0.72, 1.00),
                            vec3(0.78, 0.26, 1.00),
                            sin(uTime * 2.6) * 0.5 + 0.5
                        );
                        float energy = clamp(max(bolt, eyeCore) * strength * pulse, 0.0, 1.0);
                        rgb = mix(rgb, max(rgb, electricEyeColor * 1.08), energy * 0.92);
                        rgb += vec3(1.0) * smoothstep(0.68, 1.0, energy) * 0.28;
                    }

                    if (uLaserEyes > 0.001) {
                        float strength = clamp(uLaserEyes, 0.0, 1.5);
                        vec4 le = resolvedLeftEyeRect();
                        vec4 re = resolvedRightEyeRect();
                        vec2 lc = (le.xy + le.zw) * 0.5;
                        vec2 rc = (re.xy + re.zw) * 0.5;
                        vec2 lt = vec2(0.02, max(0.03, lc.y - 0.22));
                        vec2 rt = vec2(0.98, max(0.03, rc.y - 0.22));
                        float beam = max(
                            lineGlow(topLeftP, lc, lt, 0.012),
                            lineGlow(topLeftP, rc, rt, 0.012)
                        );
                        float beamCore = max(
                            1.0 - smoothstep(0.0018, 0.0048, segmentDistance(topLeftP, lc, lt)),
                            1.0 - smoothstep(0.0018, 0.0048, segmentDistance(topLeftP, rc, rt))
                        );
                        vec3 laserColor = vec3(0.20, 0.64, 1.00);
                        rgb = mix(rgb, max(rgb, laserColor * 1.18), clamp(beam * strength * 0.88, 0.0, 0.96));
                        rgb += vec3(1.0) * beamCore * strength * 0.58;
                    }

                    if (uBodyElectric > 0.001) {
                        float strength = clamp(uBodyElectric, 0.0, 1.5);
                        vec4 rect = resolvedBodyRect();
                        vec2 bodySize = max(rect.zw - rect.xy, vec2(0.005));
                        vec2 local = (topLeftP - rect.xy) / bodySize;
                        float body = subjectMaskAt(vTexCoord);
                        float movingY = fract(local.y + uTime * 0.42);
                        vec2 electricUv = vec2(local.x, movingY);
                        float boltA = electricBand(electricUv, 0.4);
                        float boltB = electricBand(vec2(1.0 - electricUv.x, electricUv.y), 2.1) * 0.76;
                        float boltC = electricBand(vec2(electricUv.x, fract(electricUv.y + 0.34)), 4.4) * 0.55;
                        float bolt = clamp((boltA + boltB + boltC) * body, 0.0, 1.35);
                        vec3 currentColor = mix(
                            vec3(0.12, 0.70, 1.00),
                            vec3(0.66, 0.22, 1.00),
                            sin(uTime * 3.1 + local.y * 7.0) * 0.5 + 0.5
                        );
                        rgb += currentColor * bolt * strength * 0.58;
                        rgb += vec3(1.0) * smoothstep(0.68, 1.08, bolt) * strength * 0.26;
                    }

                    if (uStroke > 0.001) {
                        float strokeStrength = clamp(uStroke, 0.0, 1.5);
                        float narrow = subjectStroke(vTexCoord, 1.8);
                        float wide = subjectStroke(vTexCoord, 5.5);
                        float pulse = 0.84 + 0.16 * sin(uTime * 6.2);
                        vec3 strokeColor = mix(
                            vec3(0.04, 0.82, 1.00),
                            vec3(0.82, 0.18, 1.00),
                            sin(uTime * 2.0 + topLeftP.y * 4.0) * 0.5 + 0.5
                        );
                        rgb += strokeColor * (narrow * 0.92 + wide * 0.24) * strokeStrength * pulse;
                        rgb += vec3(1.0) * narrow * strokeStrength * 0.18;
                    }

                    if (uBodyFire > 0.001) {
                        float fireStrength = clamp(uBodyFire, 0.0, 1.5);
                        float subject = subjectMaskAt(vTexCoord);
                        float edge = subjectStroke(vTexCoord, 2.6);
                        float riseNear = max(
                            subjectMaskAt(vTexCoord - vec2(0.0, uTexelSize.y * 6.0)) - subject,
                            0.0
                        );
                        float riseFar = max(
                            subjectMaskAt(vTexCoord - vec2(0.0, uTexelSize.y * 13.0)) - subject,
                            0.0
                        );
                        float turbulence = 0.58
                            + 0.26 * sin(vTexCoord.x * 83.0 + uTime * 11.0)
                            + 0.16 * sin(vTexCoord.x * 157.0 - uTime * 17.0);
                        float flames = clamp(
                            edge * 0.74 + riseNear * 0.78 * turbulence + riseFar * 0.46 * turbulence,
                            0.0,
                            1.0
                        );
                        float core = smoothstep(0.52, 0.94, flames);
                        vec3 flameOuter = vec3(1.00, 0.12, 0.01);
                        vec3 flameInner = vec3(1.00, 0.88, 0.12);
                        vec3 flameColor = mix(flameOuter, flameInner, core);
                        rgb = mix(rgb, max(rgb, flameColor * (0.92 + core * 0.32)),
                            clamp(flames * fireStrength * 0.86, 0.0, 0.96));
                        rgb += flameColor * flames * fireStrength * 0.24;
                    }

                    if (uBodyAura > 0.001) {
                        float auraStrength = clamp(uBodyAura, 0.0, 1.5);
                        float subject = subjectMaskAt(vTexCoord);
                        float nearGlow = max(subjectDilate(vTexCoord, 4.0) - subject, 0.0);
                        float farGlow = max(subjectDilate(vTexCoord, 10.0) - subjectDilate(vTexCoord, 3.0), 0.0);
                        float rim = subjectStroke(vTexCoord, 2.4);
                        float pulse = 0.80 + 0.20 * sin(uTime * 5.0);
                        vec3 auraColor = mix(
                            vec3(0.04, 0.80, 1.00),
                            vec3(0.72, 0.16, 1.00),
                            sin(uTime * 1.7 + topLeftP.y * 5.0) * 0.5 + 0.5
                        );
                        rgb += auraColor * (rim * 0.86 + nearGlow * 0.55 + farGlow * 0.24)
                            * auraStrength * pulse;
                    }
                    #endif

                    float grain = (hash21(vTexCoord * vec2(1920.0, 1080.0)) - 0.5) * 2.0;
                    float luma = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                    float grainWeight = 0.55 + 0.45 * (1.0 - abs(luma * 2.0 - 1.0));
                    rgb += vec3(grain * uGrain * 0.10 * grainWeight);

                    float line = sin((vTexCoord.y * 1080.0 + uTime * 16.0) * 3.14159265);
                    rgb *= 1.0 - (0.025 + 0.035 * line) * clamp(uScanlines, 0.0, 1.0);

                    vec2 vp = vTexCoord * 2.0 - 1.0;
                    float edge = smoothstep(0.35, 1.35, dot(vp, vp));
                    rgb *= 1.0 - edge * uVignette * 0.42;

                    rgb.r += uWarm * 0.075;
                    rgb.g += uWarm * 0.020;
                    rgb.b -= uWarm * 0.055;

                    float flick = sin(uTime * 23.0 + uSeed * 9.0) * 0.5 + 0.5;
                    float flash = pow(flick, 8.0);
                    rgb *= 1.0 + (flick - 0.5) * uFlicker * 0.12;
                    rgb += vec3(flash * uFlicker * 0.22);

                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), center.a);
                }
            """

            private const val MIX_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uAccumulator;
                uniform sampler2D uBranch;
                uniform sampler2D uBase;
                varying vec2 vTexCoord;

                void main() {
                    vec4 accumulator = texture2D(uAccumulator, vTexCoord);
                    vec4 branch = texture2D(uBranch, vTexCoord);
                    vec4 base = texture2D(uBase, vTexCoord);
                    vec3 rgb = accumulator.rgb + (branch.rgb - base.rgb);
                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), accumulator.a);
                }
            """

            private const val COPY_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTexSampler;
                varying vec2 vTexCoord;
                void main() {
                    gl_FragColor = texture2D(uTexSampler, vTexCoord);
                }
            """
        }
    }
}
