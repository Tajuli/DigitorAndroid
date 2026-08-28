package com.tajuli.digitorandroid.editor

import com.tajuli.digitorandroid.editor.model.AudioMix
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioMixIdentityTest {
    @Test
    fun defaultAudioMixIsUnity() {
        val mix = AudioMix()
        assertEquals(1f, mix.volume, 0f)
        assertEquals(0L, mix.fadeInUs)
        assertEquals(0L, mix.fadeOutUs)
    }
}
