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
import com.tajuli.digitorandroid.editor.model.BodyEffectValuesV100
import com.tajuli.digitorandroid.editor.model.BodyFaceTrackV100
import com.tajuli.digitorandroid.editor.model.BodyLandmarkV100
import com.tajuli.digitorandroid.editor.model.BodyPoseLandmarkIndexV100
import com.tajuli.digitorandroid.editor.model.BodyPoseTrackV100
import com.tajuli.digitorandroid.editor.model.CreatorEffectCatalogV25
import com.tajuli.digitorandroid.editor.model.CutoutAnalysisQualityV47
import com.tajuli.digitorandroid.editor.model.NodeAnimationDomain
import com.tajuli.digitorandroid.editor.model.NodeKind
import com.tajuli.digitorandroid.editor.model.SpatialNodeGraphPlan
import com.tajuli.digitorandroid.editor.model.TimelineClip
import com.tajuli.digitorandroid.editor.model.bodyValuesV100
import com.tajuli.digitorandroid.editor.model.resolveBodyEffectsV100
import com.tajuli.digitorandroid.editor.model.resolveTimedBodyEffectsV100
import com.tajuli.digitorandroid.editor.model.visibleEffects
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import com.tajuli.digitorandroid.editor.processing.BodyFaceTrackStoreV100
import com.tajuli.digitorandroid.editor.processing.BodyPoseTrackStoreV100
import com.tajuli.digitorandroid.editor.processing.PersonCutoutMaskFrameV43
import com.tajuli.digitorandroid.editor.processing.PersonCutoutMaskStoreV43
import com.tajuli.digitorandroid.editor.processing.personCutoutMaxGapUsV47
import kotlin.math.abs

/**
 * Dedicated subject-aware body FX engine.
 *
 * V25 used one large generic shader and guessed body placement from a face rectangle. V100 makes
 * body effects a separate render stage fed by three durable analysis streams:
 *
 *  - dense ML face geometry for eyes,
 *  - MediaPipe 33-point pose landmarks for limb-attached energy,
 *  - PP-MattingV2 alpha mattes for silhouette/clone compositing.
 *
 * The effect graph keeps the existing Serial/Parallel/Mix topology, but generic creator effects are
 * no longer responsible for tracked rendering. Preview and export call the same graph.
 */
@UnstableApi
internal class BodyEffectGraphV100 private constructor(
    private val clip: TimelineClip,
    private val preview: Boolean,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        Program(context, clip, preview, useHdr)

    companion object {
        fun forClip(clip: TimelineClip, preview: Boolean): BodyEffectGraphV100? {
            val editableNodes = clip.nodeGraph.nodes.filter {
                it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL
            }
            if (editableNodes.isEmpty()) return null
            if (preview) return BodyEffectGraphV100(clip, true)

            val hasBodyFx = editableNodes.any { node ->
                node.visibleEffects().any { effect ->
                    CreatorEffectCatalogV25.find(effect.name)
                        ?.vector
                        ?.bodyValuesV100()
                        ?.isIdentity == false
                } || clip.nodeAnimations.hasAnimation(node.id, NodeAnimationDomain.EFFECTS)
            }
            return if (hasBodyFx) BodyEffectGraphV100(clip, false) else null
        }
    }

    private class Program(
        context: Context,
        private val clip: TimelineClip,
        private val preview: Boolean,
        private val highPrecision: Boolean,
    ) : BaseGlShaderProgram(
        /* useHighPrecisionColorComponents = */ highPrecision,
        /* texturePoolCapacity = */ 1,
    ) {
        private val appContext = context.applicationContext
        private val plan = SpatialNodeGraphPlan.compile(clip.nodeGraph)
        private val bodyProgram: GlProgram
        private val mixProgram: GlProgram
        private val copyProgram: GlProgram
        private val allowSyntheticFallback =
            clip.uri.startsWith("content://digitor/filter-effect-thumbnail")

        private var inputWidth = 1
        private var inputHeight = 1
        private var scratchTextures = IntArray(0)
        private var scratchFbos = IntArray(0)

        private var faceTrack: BodyFaceTrackV100? = BodyFaceTrackStoreV100.load(appContext, clip)
        private var poseTrack: BodyPoseTrackV100? = BodyPoseTrackStoreV100.load(appContext, clip)
        private var lastTrackRefreshMs = 0L

        private var personMaskTextureA = 0
        private var personMaskTextureB = 0
        private var loadedMaskPathA: String? = null
        private var loadedMaskPathB: String? = null

        init {
            try {
                bodyProgram = newProgram(BODY_FRAGMENT_SHADER)
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
                        highPrecision,
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
                val outputFbo = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, outputFbo, 0)
                val media3OutputFbo = outputFbo[0]

                val currentClip = if (preview) PreviewProjectRegistry.clip(clip.id) ?: clip else clip
                val sourceUs = ParityRenderContract.sourceTimeUs(currentClip, presentationTimeUs)
                refreshTracks(currentClip)

                val liveEyes = faceTrack?.eyesAt(sourceUs)
                val livePose = poseTrack?.landmarksAt(sourceUs)
                val needsAnyMatte = currentClip.nodeGraph.nodes
                    .asSequence()
                    .filter { it.kind == NodeKind.SERIAL || it.kind == NodeKind.PARALLEL }
                    .map { resolveBodyEffectsV100(it.visibleEffects()) }
                    .any(::needsMatte)

                if (needsAnyMatte) {
                    if (personMaskTextureA == 0) personMaskTextureA = createMaskTexture()
                    if (personMaskTextureB == 0) personMaskTextureB = createMaskTexture()
                }

                val bracket = if (needsAnyMatte) personBracket(currentClip, sourceUs) else PersonMaskBracket.empty()
                val hasMaskA = needsAnyMatte &&
                    bindPersonMask(personMaskTextureA, bracket.a?.file?.absolutePath, true)
                val hasMaskB = needsAnyMatte &&
                    bindPersonMask(personMaskTextureB, bracket.b?.file?.absolutePath, false)

                val slotTextures = IntArray(plan.operations.size) { inputTexId }
                var scratchCursor = 0

                fun nextScratch(): Pair<Int, Int> {
                    if (scratchCursor !in scratchTextures.indices) {
                        throw VideoFrameProcessingException(
                            IllegalStateException("Body V100 scratch pool exhausted"),
                            presentationTimeUs,
                        )
                    }
                    val out = scratchTextures[scratchCursor] to scratchFbos[scratchCursor]
                    scratchCursor++
                    return out
                }

                plan.operations.forEach { operation ->
                    when (operation.node.kind) {
                        NodeKind.IMPORT -> slotTextures[operation.slot] = inputTexId

                        NodeKind.SERIAL, NodeKind.PARALLEL -> {
                            val input = textureForSlot(
                                slotTextures,
                                operation.inputSlot,
                                operation.slot,
                                inputTexId,
                            )
                            val currentNode = if (preview) {
                                currentClip.nodeGraph.nodes.firstOrNull { it.id == operation.node.id }
                                    ?: operation.node
                            } else {
                                operation.node
                            }
                            val evaluated = currentClip.nodeAnimations.evaluateNode(currentNode, sourceUs)
                            val animatedById = evaluated.visibleEffects().associateBy { it.id }
                            val effectsWithTiming = currentNode.visibleEffects().map { base ->
                                val animated = animatedById[base.id] ?: base
                                animated.copy(
                                    name = base.name,
                                    sourceStartUsV26 = base.sourceStartUsV26,
                                    sourceEndUsV26 = base.sourceEndUsV26,
                                )
                            }
                            val values = resolveTimedBodyEffectsV100(
                                effectsWithTiming,
                                currentClip,
                                sourceUs,
                            )
                            if (values.isIdentity) {
                                slotTextures[operation.slot] = input
                            } else {
                                val (texture, fbo) = nextScratch()
                                focus(fbo)
                                renderBody(
                                    inputTexture = input,
                                    values = values,
                                    sourceUs = sourceUs,
                                    nodeId = evaluated.id,
                                    eyes = liveEyes,
                                    pose = livePose,
                                    hasMaskA = hasMaskA,
                                    hasMaskB = hasMaskB,
                                    maskMix = bracket.mix,
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
                                renderMix(accumulator, branch, base)
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
                GlUtil.focusFramebufferUsingCurrentContext(
                    media3OutputFbo,
                    inputWidth,
                    inputHeight,
                )
                renderCopy(finalTexture)
                GlUtil.checkGlError()
            } catch (error: VideoFrameProcessingException) {
                throw error
            } catch (error: GlUtil.GlException) {
                throw VideoFrameProcessingException(error, presentationTimeUs)
            }
        }

        private fun refreshTracks(currentClip: TimelineClip) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTrackRefreshMs < TRACK_REFRESH_MS) return
            lastTrackRefreshMs = now
            BodyFaceTrackStoreV100.load(appContext, currentClip)?.let { faceTrack = it }
            BodyPoseTrackStoreV100.load(appContext, currentClip)?.let { poseTrack = it }
        }

        private fun needsMatte(values: BodyEffectValuesV100): Boolean =
            values.clone > .001f || values.bodyElectric > .001f ||
                values.bodyAura > .001f || values.stroke > .001f ||
                values.bodyFire > .001f

        private fun renderBody(
            inputTexture: Int,
            values: BodyEffectValuesV100,
            sourceUs: Long,
            nodeId: String,
            eyes: Pair<
                com.tajuli.digitorandroid.editor.model.BeautyRectV28,
                com.tajuli.digitorandroid.editor.model.BeautyRectV28
            >?,
            pose: List<BodyLandmarkV100>?,
            hasMaskA: Boolean,
            hasMaskB: Boolean,
            maskMix: Float,
        ) {
            bodyProgram.use()
            bodyProgram.setSamplerTexIdUniform("uTexSampler", inputTexture, 0)
            ensureMaskTextures()
            bodyProgram.setSamplerTexIdUniform("uPersonMaskA", personMaskTextureA, 1)
            bodyProgram.setSamplerTexIdUniform("uPersonMaskB", personMaskTextureB, 2)
            bodyProgram.setFloatsUniform(
                "uTexelSize",
                floatArrayOf(1f / inputWidth.toFloat(), 1f / inputHeight.toFloat()),
            )
            bodyProgram.setFloatUniform("uAspect", inputWidth.toFloat() / inputHeight.toFloat())
            bodyProgram.setFloatUniform("uClone", values.clone)
            bodyProgram.setFloatUniform("uFireEyes", values.fireEyes)
            bodyProgram.setFloatUniform("uBodyElectric", values.bodyElectric)
            bodyProgram.setFloatUniform("uBodyAura", values.bodyAura)
            bodyProgram.setFloatUniform("uElectricEyes", values.electricEyes)
            bodyProgram.setFloatUniform("uLaserEyes", values.laserEyes)
            bodyProgram.setFloatUniform("uStroke", values.stroke)
            bodyProgram.setFloatUniform("uBodyFire", values.bodyFire)
            bodyProgram.setFloatUniform("uHasFace", if (eyes == null) 0f else 1f)
            bodyProgram.setFloatUniform("uHasPose", if ((pose?.size ?: 0) >= 29) 1f else 0f)
            bodyProgram.setFloatUniform("uAllowFallback", if (allowSyntheticFallback) 1f else 0f)
            bodyProgram.setFloatUniform("uHasPersonMaskA", if (hasMaskA) 1f else 0f)
            bodyProgram.setFloatUniform("uHasPersonMaskB", if (hasMaskB) 1f else 0f)
            bodyProgram.setFloatUniform("uPersonTemporalMix", maskMix)
            bodyProgram.setFloatUniform("uTime", (sourceUs % 10_000_000L).toFloat() / 1_000_000f)

            setRect("uLeftEyeRect", eyes?.first)
            setRect("uRightEyeRect", eyes?.second)
            setPoseUniforms(pose)

            bodyProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun setPoseUniforms(pose: List<BodyLandmarkV100>?) {
            fun point(index: Int, fallbackX: Float, fallbackY: Float): BodyLandmarkV100 =
                pose?.getOrNull(index) ?: BodyLandmarkV100(fallbackX, fallbackY)

            fun pair(
                name: String,
                leftIndex: Int,
                rightIndex: Int,
                leftFallback: Pair<Float, Float>,
                rightFallback: Pair<Float, Float>,
            ) {
                val l = point(leftIndex, leftFallback.first, leftFallback.second)
                val r = point(rightIndex, rightFallback.first, rightFallback.second)
                bodyProgram.setFloatsUniform(name, floatArrayOf(l.x, l.y, r.x, r.y))
            }

            pair("uShoulders", BodyPoseLandmarkIndexV100.LEFT_SHOULDER, BodyPoseLandmarkIndexV100.RIGHT_SHOULDER, .38f to .35f, .62f to .35f)
            pair("uElbows", BodyPoseLandmarkIndexV100.LEFT_ELBOW, BodyPoseLandmarkIndexV100.RIGHT_ELBOW, .30f to .52f, .70f to .52f)
            pair("uWrists", BodyPoseLandmarkIndexV100.LEFT_WRIST, BodyPoseLandmarkIndexV100.RIGHT_WRIST, .25f to .70f, .75f to .70f)
            pair("uHips", BodyPoseLandmarkIndexV100.LEFT_HIP, BodyPoseLandmarkIndexV100.RIGHT_HIP, .43f to .62f, .57f to .62f)
            pair("uKnees", BodyPoseLandmarkIndexV100.LEFT_KNEE, BodyPoseLandmarkIndexV100.RIGHT_KNEE, .42f to .79f, .58f to .79f)
            pair("uAnkles", BodyPoseLandmarkIndexV100.LEFT_ANKLE, BodyPoseLandmarkIndexV100.RIGHT_ANKLE, .41f to .96f, .59f to .96f)
        }

        private fun setRect(name: String, rect: com.tajuli.digitorandroid.editor.model.BeautyRectV28?) {
            val r = rect?.normalized()
            bodyProgram.setFloatsUniform(
                name,
                if (r == null) floatArrayOf(0f, 0f, 0f, 0f)
                else floatArrayOf(r.left, r.top, r.right, r.bottom),
            )
        }

        private fun personBracket(currentClip: TimelineClip, sourceUs: Long): PersonMaskBracket {
            val frames = PersonCutoutMaskStoreV43.index(appContext, currentClip).frames
                .filter { it.file.isFile }
            if (frames.isEmpty()) return PersonMaskBracket.empty()
            if (frames.size == 1) return PersonMaskBracket(frames[0], frames[0], 0f)

            val maxGapUs = personCutoutMaxGapUsV47(CutoutAnalysisQualityV47.MEDIUM)
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
                    leftDistance <= rightDistance && leftDistance <= maxGapUs ->
                        PersonMaskBracket(left, left, 0f)
                    rightDistance <= maxGapUs -> PersonMaskBracket(right, right, 0f)
                    else -> PersonMaskBracket.empty()
                }
            }
            val mix = ((sourceUs - left.sourceTimeUs).toDouble() / span.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
            return PersonMaskBracket(left, right, mix)
        }

        private fun ensureMaskTextures() {
            if (personMaskTextureA == 0) personMaskTextureA = createMaskTexture()
            if (personMaskTextureB == 0) personMaskTextureB = createMaskTexture()
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
            if (texture == 0) return false
            val loaded = if (slotA) loadedMaskPathA else loadedMaskPathB
            if (path == null) {
                if (loaded != null) {
                    uploadMaskBitmap(texture, null)
                    if (slotA) loadedMaskPathA = null else loadedMaskPathB = null
                }
                return false
            }
            if (loaded == path) return true
            val bitmap = BitmapFactory.decodeFile(path) ?: return false
            try {
                uploadMaskBitmap(texture, bitmap)
                if (slotA) loadedMaskPathA = path else loadedMaskPathB = path
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

        private fun textureForSlot(slots: IntArray, slot: Int, currentSlot: Int, sourceTexture: Int): Int =
            if (slot in 0 until currentSlot) slots[slot] else sourceTexture

        private fun focus(fbo: Int) {
            GlUtil.focusFramebufferUsingCurrentContext(fbo, inputWidth, inputHeight)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        private fun renderMix(accumulator: Int, branch: Int, base: Int) {
            mixProgram.use()
            mixProgram.setSamplerTexIdUniform("uAccumulator", accumulator, 0)
            mixProgram.setSamplerTexIdUniform("uBranch", branch, 1)
            mixProgram.setSamplerTexIdUniform("uBase", base, 2)
            mixProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun renderCopy(texture: Int) {
            copyProgram.use()
            copyProgram.setSamplerTexIdUniform("uTexSampler", texture, 0)
            copyProgram.bindAttributesAndUniforms()
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
            scratchFbos.forEach { if (it != 0) GlUtil.deleteFbo(it) }
            scratchTextures.forEach { if (it != 0) GlUtil.deleteTexture(it) }
            scratchFbos = IntArray(0)
            scratchTextures = IntArray(0)
        }

        override fun release() {
            super.release()
            try {
                releaseScratch()
                listOf(personMaskTextureA, personMaskTextureB)
                    .filter { it != 0 }
                    .forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
                personMaskTextureA = 0
                personMaskTextureB = 0
                bodyProgram.delete()
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
            private const val TRACK_REFRESH_MS = 120L

            private const val VERTEX_SHADER = """
                attribute vec4 aFramePosition;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aFramePosition;
                    vTexCoord = aFramePosition.xy * 0.5 + 0.5;
                }
            """

            private const val BODY_FRAGMENT_SHADER = """
                precision highp float;
                uniform sampler2D uTexSampler;
                uniform sampler2D uPersonMaskA;
                uniform sampler2D uPersonMaskB;
                uniform vec2 uTexelSize;
                uniform float uAspect;
                uniform float uClone;
                uniform float uFireEyes;
                uniform float uBodyElectric;
                uniform float uBodyAura;
                uniform float uElectricEyes;
                uniform float uLaserEyes;
                uniform float uStroke;
                uniform float uBodyFire;
                uniform float uHasFace;
                uniform float uHasPose;
                uniform float uAllowFallback;
                uniform float uHasPersonMaskA;
                uniform float uHasPersonMaskB;
                uniform float uPersonTemporalMix;
                uniform float uTime;
                uniform vec4 uLeftEyeRect;
                uniform vec4 uRightEyeRect;
                uniform vec4 uShoulders;
                uniform vec4 uElbows;
                uniform vec4 uWrists;
                uniform vec4 uHips;
                uniform vec4 uKnees;
                uniform vec4 uAnkles;
                varying vec2 vTexCoord;

                vec2 safeUv(vec2 uv) { return clamp(uv, vec2(.001), vec2(.999)); }
                vec3 sampleRgb(vec2 uv) { return texture2D(uTexSampler, safeUv(uv)).rgb; }

                float ellipseMask(vec2 p, vec4 rect, float inner, float outer) {
                    vec2 halfSize = max((rect.zw - rect.xy) * .5, vec2(.0005));
                    vec2 center = (rect.xy + rect.zw) * .5;
                    vec2 q = (p - center) / halfSize;
                    return 1.0 - smoothstep(inner, outer, dot(q, q));
                }

                float fallbackBodyMask(vec2 videoUv) {
                    vec2 p = vec2(videoUv.x, 1.0 - videoUv.y);
                    return ellipseMask(p, vec4(.20, .18, .80, 1.02), .72, 1.08);
                }

                float rawPersonAt(vec2 videoUv) {
                    if (uHasPersonMaskA < .5 && uHasPersonMaskB < .5) return -1.0;
                    vec2 maskUv = vec2(clamp(videoUv.x, 0.0, 1.0), 1.0 - clamp(videoUv.y, 0.0, 1.0));
                    float a = uHasPersonMaskA > .5 ? texture2D(uPersonMaskA, maskUv).r : 0.0;
                    float b = uHasPersonMaskB > .5 ? texture2D(uPersonMaskB, maskUv).r : a;
                    if (uHasPersonMaskA < .5) a = b;
                    return mix(a, b, clamp(uPersonTemporalMix, 0.0, 1.0));
                }

                float subjectMaskAt(vec2 videoUv) {
                    float raw = rawPersonAt(videoUv);
                    if (raw < 0.0) return uAllowFallback > .5 ? fallbackBodyMask(videoUv) : 0.0;
                    return smoothstep(.025, .72, raw);
                }

                float subjectDilate(vec2 uv, float px) {
                    vec2 o = uTexelSize * px;
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

                float subjectErode(vec2 uv, float px) {
                    vec2 o = uTexelSize * px;
                    float m = subjectMaskAt(uv);
                    m = min(m, subjectMaskAt(uv + vec2( o.x, 0.0)));
                    m = min(m, subjectMaskAt(uv + vec2(-o.x, 0.0)));
                    m = min(m, subjectMaskAt(uv + vec2(0.0,  o.y)));
                    m = min(m, subjectMaskAt(uv + vec2(0.0, -o.y)));
                    return m;
                }

                float subjectStroke(vec2 uv, float px) {
                    return clamp(subjectDilate(uv, px) - subjectErode(uv, px * .62), 0.0, 1.0);
                }

                vec2 metric(vec2 p) { return vec2(p.x * uAspect, p.y); }

                float segmentDistance(vec2 p, vec2 a, vec2 b) {
                    vec2 pm = metric(p), am = metric(a), bm = metric(b);
                    vec2 pa = pm - am, ba = bm - am;
                    float h = clamp(dot(pa, ba) / max(dot(ba, ba), .000001), 0.0, 1.0);
                    return length(pa - ba * h);
                }

                float lineGlow(vec2 p, vec2 a, vec2 b, float width) {
                    float d = segmentDistance(p, a, b);
                    float core = 1.0 - smoothstep(width * .16, width * .48, d);
                    float glow = 1.0 - smoothstep(width * .48, width * 2.5, d);
                    return clamp(core + glow * .52, 0.0, 1.0);
                }

                float skeletonArc(vec2 p, vec2 a, vec2 b, float phase) {
                    vec2 pm = metric(p), am = metric(a), bm = metric(b);
                    vec2 ba = bm - am;
                    float t = clamp(dot(pm - am, ba) / max(dot(ba, ba), .000001), 0.0, 1.0);
                    vec2 dir = normalize(ba + vec2(.00001));
                    vec2 normal = vec2(-dir.y, dir.x);
                    float wobble =
                        sin(t * 31.0 + uTime * 14.0 + phase) * .0065 +
                        sin(t * 67.0 - uTime * 21.0 + phase * 1.71) * .0035;
                    float d = length(pm - (am + ba * t + normal * wobble));
                    float core = 1.0 - smoothstep(.0016, .0048, d);
                    float glow = 1.0 - smoothstep(.0048, .022, d);
                    return clamp(core + glow * .42, 0.0, 1.0);
                }

                float bodySkeleton(vec2 p) {
                    if (uHasPose < .5 && uAllowFallback < .5) return 0.0;
                    vec2 ls = uShoulders.xy, rs = uShoulders.zw;
                    vec2 le = uElbows.xy, re = uElbows.zw;
                    vec2 lw = uWrists.xy, rw = uWrists.zw;
                    vec2 lh = uHips.xy, rh = uHips.zw;
                    vec2 lk = uKnees.xy, rk = uKnees.zw;
                    vec2 la = uAnkles.xy, ra = uAnkles.zw;
                    float e = 0.0;
                    e = max(e, skeletonArc(p, ls, le, .3));
                    e = max(e, skeletonArc(p, le, lw, 1.1));
                    e = max(e, skeletonArc(p, rs, re, 2.2));
                    e = max(e, skeletonArc(p, re, rw, 3.0));
                    e = max(e, skeletonArc(p, ls, rs, 4.1));
                    e = max(e, skeletonArc(p, ls, lh, 5.2));
                    e = max(e, skeletonArc(p, rs, rh, 6.0));
                    e = max(e, skeletonArc(p, lh, rh, 7.1));
                    e = max(e, skeletonArc(p, lh, lk, 8.0));
                    e = max(e, skeletonArc(p, lk, la, 9.2));
                    e = max(e, skeletonArc(p, rh, rk, 10.0));
                    e = max(e, skeletonArc(p, rk, ra, 11.1));
                    e = max(e, skeletonArc(p, ls, rh, 12.2) * .58);
                    e = max(e, skeletonArc(p, rs, lh, 13.0) * .58);
                    return e;
                }

                vec4 resolvedLeftEyeRect() {
                    return uHasFace > .5 ? uLeftEyeRect : vec4(.36, .30, .47, .42);
                }
                vec4 resolvedRightEyeRect() {
                    return uHasFace > .5 ? uRightEyeRect : vec4(.53, .30, .64, .42);
                }

                float eyeFire(vec2 p, vec4 rect, float phase) {
                    vec2 size = max(rect.zw - rect.xy, vec2(.003));
                    vec2 center = (rect.xy + rect.zw) * .5;
                    vec2 q = (p - center) / size;
                    float core = exp(-dot(q * vec2(2.7, 3.6), q * vec2(2.7, 3.6)) * 2.3);
                    float up = (center.y - p.y) / max(size.y, .004);
                    float sway =
                        sin(up * 9.0 + uTime * 9.5 + phase) * .14 +
                        sin(up * 19.0 - uTime * 13.0 + phase * 1.7) * .055;
                    float plume = exp(-abs(q.x - sway) / max(.08, .40 - up * .08))
                        * smoothstep(-.08, .10, up)
                        * (1.0 - smoothstep(.25, 3.1, up));
                    return clamp(core + plume * .94, 0.0, 1.0);
                }

                void main() {
                    vec4 source = texture2D(uTexSampler, vTexCoord);
                    vec3 rgb = source.rgb;
                    vec2 p = vec2(vTexCoord.x, 1.0 - vTexCoord.y);

                    if (uClone > .001) {
                        float s = clamp(uClone, 0.0, 1.5);
                        float spread = mix(.105, .175, min(s, 1.0));
                        float breathe = .008 * sin(uTime * 1.7);
                        vec2 leftUv = safeUv(vTexCoord + vec2(spread + breathe, 0.0));
                        vec2 rightUv = safeUv(vTexCoord - vec2(spread + breathe, 0.0));
                        float centerBody = subjectMaskAt(vTexCoord);
                        float protectCenter = 1.0 - centerBody * .985;
                        float alpha = clamp(.965 + s * .02, 0.0, .995);
                        rgb = mix(rgb, sampleRgb(leftUv), subjectMaskAt(leftUv) * protectCenter * alpha);
                        rgb = mix(rgb, sampleRgb(rightUv), subjectMaskAt(rightUv) * protectCenter * alpha);
                        if (s > 1.05) {
                            float farSpread = spread * 1.72;
                            vec2 farL = safeUv(vTexCoord + vec2(farSpread, -.008));
                            vec2 farR = safeUv(vTexCoord - vec2(farSpread, -.008));
                            rgb = mix(rgb, sampleRgb(farL), subjectMaskAt(farL) * protectCenter * .90);
                            rgb = mix(rgb, sampleRgb(farR), subjectMaskAt(farR) * protectCenter * .90);
                        }
                    }

                    if (uBodyElectric > .001 && (uHasPose > .5 || uAllowFallback > .5)) {
                        float strength = clamp(uBodyElectric, 0.0, 1.5);
                        float body = subjectMaskAt(vTexCoord);
                        float arc = bodySkeleton(p) * body;
                        float secondary = bodySkeleton(
                            p + vec2(sin(uTime * 17.0) * .004, cos(uTime * 13.0) * .003)
                        ) * body * .55;
                        float energy = clamp(max(arc, secondary), 0.0, 1.0);
                        vec3 c = mix(
                            vec3(.04, .66, 1.00),
                            vec3(.72, .16, 1.00),
                            sin(uTime * 3.0 + p.y * 8.0) * .5 + .5
                        );
                        rgb += c * energy * strength * .72;
                        rgb += vec3(1.0) * smoothstep(.68, 1.0, energy) * strength * .38;
                    }

                    if (uStroke > .001) {
                        float strength = clamp(uStroke, 0.0, 1.5);
                        float core = subjectStroke(vTexCoord, 1.6);
                        float halo = subjectStroke(vTexCoord, 5.2);
                        vec3 c = mix(
                            vec3(.04, .84, 1.00),
                            vec3(.84, .16, 1.00),
                            sin(uTime * 2.2 + p.y * 5.0) * .5 + .5
                        );
                        rgb += c * (core * .98 + halo * .23) * strength;
                        rgb += vec3(1.0) * core * strength * .22;
                    }

                    if (uBodyAura > .001) {
                        float strength = clamp(uBodyAura, 0.0, 1.5);
                        float subject = subjectMaskAt(vTexCoord);
                        float nearGlow = max(subjectDilate(vTexCoord, 4.0) - subject, 0.0);
                        float farGlow = max(subjectDilate(vTexCoord, 10.0) - subjectDilate(vTexCoord, 3.0), 0.0);
                        float rim = subjectStroke(vTexCoord, 2.2);
                        float pulse = .82 + .18 * sin(uTime * 5.0);
                        vec3 c = mix(
                            vec3(.03, .80, 1.00),
                            vec3(.74, .13, 1.00),
                            sin(uTime * 1.8 + p.y * 5.0) * .5 + .5
                        );
                        rgb += c * (rim * .88 + nearGlow * .58 + farGlow * .26) * strength * pulse;
                    }

                    if (uBodyFire > .001) {
                        float strength = clamp(uBodyFire, 0.0, 1.5);
                        float subject = subjectMaskAt(vTexCoord);
                        float edge = subjectStroke(vTexCoord, 2.4);
                        float rise1 = max(subjectMaskAt(vTexCoord - vec2(0.0, uTexelSize.y * 7.0)) - subject, 0.0);
                        float rise2 = max(subjectMaskAt(vTexCoord - vec2(0.0, uTexelSize.y * 15.0)) - subject, 0.0);
                        float turbulence = .56
                            + .26 * sin(vTexCoord.x * 87.0 + uTime * 12.0)
                            + .18 * sin(vTexCoord.x * 163.0 - uTime * 18.0);
                        float flame = clamp(edge * .72 + rise1 * .78 * turbulence + rise2 * .46 * turbulence, 0.0, 1.0);
                        float hot = smoothstep(.48, .93, flame);
                        vec3 c = mix(vec3(1.0, .08, .005), vec3(1.0, .88, .10), hot);
                        rgb = mix(rgb, max(rgb, c * (.92 + hot * .34)), flame * strength * .88);
                        rgb += c * flame * strength * .24;
                    }

                    if ((uHasFace > .5 || uAllowFallback > .5) && uFireEyes > .001) {
                        float strength = clamp(uFireEyes, 0.0, 1.5);
                        float fire = clamp(
                            eyeFire(p, resolvedLeftEyeRect(), .7) +
                            eyeFire(p, resolvedRightEyeRect(), 2.3),
                            0.0,
                            1.0
                        );
                        float hot = smoothstep(.42, .92, fire);
                        vec3 c = mix(vec3(1.0, .08, .005), vec3(1.0, .92, .13), hot);
                        rgb = mix(rgb, max(rgb, c * (1.0 + hot * .22)), fire * strength * .96);
                        rgb += c * fire * strength * .25;
                    }

                    if ((uHasFace > .5 || uAllowFallback > .5) && uElectricEyes > .001) {
                        float strength = clamp(uElectricEyes, 0.0, 1.5);
                        vec4 le = resolvedLeftEyeRect(), re = resolvedRightEyeRect();
                        vec2 lc = (le.xy + le.zw) * .5, rc = (re.xy + re.zw) * .5;
                        vec2 eyeDir = normalize(metric(rc) - metric(lc) + vec2(.0001));
                        vec2 leftDir = normalize(vec2(-eyeDir.x / uAspect, -eyeDir.y));
                        vec2 rightDir = normalize(vec2(eyeDir.x / uAspect, eyeDir.y));
                        vec2 lt = lc + leftDir * .26 + vec2(0.0, -.06);
                        vec2 rt = rc + rightDir * .26 + vec2(0.0, -.06);
                        float bolt = max(skeletonArc(p, lc, lt, .8), skeletonArc(p, rc, rt, 2.7));
                        float eyeCore = max(
                            ellipseMask(p, le, .18, .92),
                            ellipseMask(p, re, .18, .92)
                        );
                        float energy = clamp(max(bolt, eyeCore) * (.84 + .16 * sin(uTime * 19.0)), 0.0, 1.0);
                        vec3 c = mix(
                            vec3(.10, .70, 1.00),
                            vec3(.76, .20, 1.00),
                            sin(uTime * 2.8) * .5 + .5
                        );
                        rgb = mix(rgb, max(rgb, c * 1.10), energy * strength * .92);
                        rgb += vec3(1.0) * smoothstep(.70, 1.0, energy) * strength * .30;
                    }

                    if ((uHasFace > .5 || uAllowFallback > .5) && uLaserEyes > .001) {
                        float strength = clamp(uLaserEyes, 0.0, 1.5);
                        vec4 le = resolvedLeftEyeRect(), re = resolvedRightEyeRect();
                        vec2 lc = (le.xy + le.zw) * .5, rc = (re.xy + re.zw) * .5;
                        vec2 dir = normalize(rc - lc + vec2(.0001));
                        vec2 lt = lc - dir * 1.10;
                        vec2 rt = rc + dir * 1.10;
                        float beam = max(lineGlow(p, lc, lt, .010), lineGlow(p, rc, rt, .010));
                        float core = max(
                            1.0 - smoothstep(.0015, .0042, segmentDistance(p, lc, lt)),
                            1.0 - smoothstep(.0015, .0042, segmentDistance(p, rc, rt))
                        );
                        vec3 c = vec3(.10, .62, 1.00);
                        rgb = mix(rgb, max(rgb, c * 1.20), beam * strength * .88);
                        rgb += vec3(1.0) * core * strength * .62;
                    }

                    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), source.a);
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
