package com.shai.riven.ui.arcade.starstruck

import android.media.AudioManager
import android.media.ToneGenerator

interface StarstruckSoundPlayer {
    fun update(volume: Int, muted: Boolean)

    fun play(cue: StarstruckSoundCue)

    fun release()
}

class AndroidStarstruckSoundPlayer : StarstruckSoundPlayer {
    private var volume = 45
    private var muted = false
    private var generator: ToneGenerator? = null

    override fun update(volume: Int, muted: Boolean) {
        val normalizedVolume = volume.coerceIn(0, 100)
        if (this.volume != normalizedVolume) releaseGenerator()
        this.volume = normalizedVolume
        this.muted = muted
    }

    override fun play(cue: StarstruckSoundCue) {
        if (muted || volume == 0) return
        val tone = when (cue) {
            StarstruckSoundCue.SWAP -> ToneGenerator.TONE_PROP_BEEP
            StarstruckSoundCue.CASCADE -> ToneGenerator.TONE_PROP_ACK
            StarstruckSoundCue.POWERUP -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            StarstruckSoundCue.RESHUFFLE -> ToneGenerator.TONE_PROP_PROMPT
        }
        val duration = when (cue) {
            StarstruckSoundCue.SWAP -> 28
            StarstruckSoundCue.CASCADE -> 70
            StarstruckSoundCue.POWERUP -> 90
            StarstruckSoundCue.RESHUFFLE -> 60
        }
        val toneGenerator = generator ?: runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, volume)
        }.getOrNull()?.also { generator = it }
        toneGenerator?.startTone(tone, duration)
    }

    override fun release() {
        releaseGenerator()
    }

    private fun releaseGenerator() {
        generator?.release()
        generator = null
    }
}
