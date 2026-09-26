package com.tajuli.digitorandroid.editor.render

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import androidx.media3.common.util.*
import androidx.media3.effect.*
import com.tajuli.digitorandroid.editor.model.*
import com.tajuli.digitorandroid.editor.preview.PreviewProjectRegistry
import com.tajuli.digitorandroid.editor.processing.LiveSemanticWorker
import java.nio.ByteBuffer
import kotlin.math.max

/** A resident, preview-only tap ahead of color/transform effects. Readbacks are bounded and gated. */
@UnstableApi
internal class LiveSemanticPreviewEffect(private val clip: TimelineClip) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(context, clip, useHdr)
    private class Program(context: Context, private val clip: TimelineClip, hdr: Boolean) : BaseGlShaderProgram(hdr,1) {
        private val worker = LiveSemanticWorker(context.applicationContext)
        private val shader = GlProgram(VERTEX,FRAGMENT).apply {
            setBufferAttribute("aFramePosition",GlUtil.getNormalizedCoordinateBounds(),4)
        }
        private var width=1; private var height=1
        private var smallWidth=1; private var smallHeight=1
        private var texture=0; private var fbo=0
        private var bytes=ByteBuffer.allocateDirect(4)
        override fun configure(inputWidth: Int,inputHeight: Int): Size {
            width=inputWidth; height=inputHeight
            smallWidth=max(1,(inputWidth*minOf(1f,480f/max(inputWidth,inputHeight))).toInt())
            smallHeight=max(1,(inputHeight*minOf(1f,480f/max(inputWidth,inputHeight))).toInt())
            if(texture!=0) { GlUtil.deleteFbo(fbo); GlUtil.deleteTexture(texture) }
            texture=GlUtil.createTexture(smallWidth,smallHeight,false);fbo=GlUtil.createFboForTexture(texture)
            bytes=ByteBuffer.allocateDirect(smallWidth*smallHeight*4)
            return Size(width,height)
        }
        private fun copy(input: Int) {
            shader.use(); shader.setSamplerTexIdUniform("uTexSampler",input,0)
            shader.bindAttributesAndUniforms(); GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        }
        override fun drawFrame(inputTexId: Int,presentationTimeUs: Long) {
            val current=PreviewProjectRegistry.clip(clip.id)?:clip
            val time=ParityRenderContract.sourceTimeUs(current,presentationTimeUs)
            // Face effects are gated behind a durable full-clip ncnn Vulkan track. Do not
            // start the old MediaPipe live-face path here; preview consumes EyeTrackStore instead.
            val eyes=false
            val person=current.hasBodyEffectsV102()
            if(worker.reserve(current,time,eyes,person)) {
                val output=IntArray(1);GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING,output,0)
                try {
                    GlUtil.focusFramebufferUsingCurrentContext(fbo,smallWidth,smallHeight);copy(inputTexId)
                    bytes.clear();GLES20.glReadPixels(0,0,smallWidth,smallHeight,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes)
                    GlUtil.checkGlError();bytes.rewind()
                    val pixels=IntArray(smallWidth*smallHeight)
                    for(y in 0 until smallHeight) for(x in 0 until smallWidth) {
                        val r=bytes.get().toInt() and 255;val g=bytes.get().toInt() and 255
                        val b=bytes.get().toInt() and 255;bytes.get()
                        pixels[(smallHeight-1-y)*smallWidth+x]=(255 shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    worker.submit(current,time,Bitmap.createBitmap(pixels,smallWidth,smallHeight,Bitmap.Config.ARGB_8888),eyes,person)
                } catch(error: Exception) { worker.abandon(); android.util.Log.e("DigitorLiveTracking","Readback failed",error) }
                finally { GlUtil.focusFramebufferUsingCurrentContext(output[0],width,height) }
            }
            copy(inputTexId)
        }
        override fun release() {
            worker.close();shader.delete()
            if(texture!=0) { GlUtil.deleteFbo(fbo);GlUtil.deleteTexture(texture) }
            super.release()
        }
    }
    companion object {
        private const val VERTEX="""attribute vec4 aFramePosition; varying vec2 uv;
            void main(){gl_Position=aFramePosition;uv=aFramePosition.xy*.5+.5;}"""
        private const val FRAGMENT="""precision mediump float;uniform sampler2D uTexSampler;varying vec2 uv;
            void main(){gl_FragColor=texture2D(uTexSampler,uv);}"""
    }
}
