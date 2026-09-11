package com.tajuli.digitorandroid.editor.processing

import com.tajuli.digitorandroid.editor.model.TimelineProject
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportSettingsV72Test {
    @Test
    fun originalKeepsProjectGeometryAndFps() {
        val project = TimelineProject(width = 2560, height = 1440, frameRate = 60)
        val resolved = ExportSettingsV72().applyTo(project)
        assertEquals(2560, resolved.width)
        assertEquals(1440, resolved.height)
        assertEquals(60, resolved.frameRate)
    }

    @Test
    fun standardResolutionsPreserveLandscapeAspect() {
        val project = TimelineProject(width = 2560, height = 1440, frameRate = 60)
        val fullHd = ExportSettingsV72(resolution = ExportResolutionV72.P1080).applyTo(project)
        val uhd = ExportSettingsV72(resolution = ExportResolutionV72.P2160).applyTo(project)
        assertEquals(1920, fullHd.width)
        assertEquals(1080, fullHd.height)
        assertEquals(3840, uhd.width)
        assertEquals(2160, uhd.height)
    }

    @Test
    fun standardResolutionsPreservePortraitAspect() {
        val project = TimelineProject(width = 1080, height = 1920, frameRate = 30)
        val hd = ExportSettingsV72(resolution = ExportResolutionV72.P720).applyTo(project)
        assertEquals(720, hd.width)
        assertEquals(1280, hd.height)
    }

    @Test
    fun explicitFrameRateOverridesProjectFps() {
        val project = TimelineProject(frameRate = 60)
        val resolved = ExportSettingsV72(frameRate = ExportFrameRateV72.FPS_25).applyTo(project)
        assertEquals(25, resolved.frameRate)
    }

    @Test
    fun codecSafeRetryCaps1440p60To1080p30WithoutUpscaling() {
        val requested = TimelineProject(width = 2560, height = 1440, frameRate = 60)
        val retry = codecSafeGpuRetryProjectV73(requested)
        assertEquals(1920, retry.width)
        assertEquals(1080, retry.height)
        assertEquals(30, retry.frameRate)
        assertEquals(ExportQuality.MEDIUM, codecSafeGpuRetryQualityV73(ExportQuality.HIGH))
    }

    @Test
    fun codecSafeRetryPreservesAlreadySafePortraitGeometry() {
        val requested = TimelineProject(width = 1080, height = 1920, frameRate = 60)
        val retry = codecSafeGpuRetryProjectV73(requested)
        assertEquals(1080, retry.width)
        assertEquals(1920, retry.height)
        assertEquals(30, retry.frameRate)
        assertEquals(ExportQuality.LOW, codecSafeGpuRetryQualityV73(ExportQuality.LOW))
    }

    @Test
    fun codecSafeRetryFitsUltrawideInside1080pEncoderBox() {
        val requested = TimelineProject(width = 3440, height = 1440, frameRate = 30)
        val retry = codecSafeGpuRetryProjectV73(requested)
        assertEquals(1920, retry.width)
        assertEquals(804, retry.height)
        assertEquals(30, retry.frameRate)
    }
}
