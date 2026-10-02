package com.shai.riven.ui.arcade.spire

import android.media.AudioManager
import android.media.ToneGenerator

interface CelestialSpireSoundPlayer {
    fun update(volume: Int, muted: Boolean)

    fun play(cue: SpireSoundCue)

    fun release()
}

class AndroidCelestialSpireSoundPlayer : CelestialSpireSoundPlayer {
    private var volume: Int = 55
    private var muted: Boolean = false
    private var landingToneGenerator: ToneGenerator? = null
    private var clearToneGenerator: ToneGenerator? = null

    override fun update(volume: Int, muted: Boolean) {
        val normalizedVolume = volume.coerceIn(0, 100)
        if (this.volume != normalizedVolume) {
            releaseGenerators()
        }
        this.volume = normalizedVolume
        this.muted = muted
    }

    override fun play(cue: SpireSoundCue) {
        if (muted || volume == 0) return
        when (cue) {
            SpireSoundCue.PIECE_LANDED -> {
                val generator = landingToneGenerator ?: newGenerator()?.also { landingToneGenerator = it }
                generator?.startTone(ToneGenerator.TONE_PROP_NACK, 32)
            }

            SpireSoundCue.LINE_CLEARED -> {
                val generator = clearToneGenerator ?: newGenerator()?.also { clearToneGenerator = it }
                generator?.startTone(ToneGenerator.TONE_PROP_ACK, 95)
            }
        }
    }

    override fun release() {
        releaseGenerators()
    }

    private fun newGenerator(): ToneGenerator? = runCatching {
        ToneGenerator(AudioManager.STREAM_MUSIC, volume)
    }.getOrNull()

    private fun releaseGenerators() {
        landingToneGenerator?.release()
        clearToneGenerator?.release()
        landingToneGenerator = null
        clearToneGenerator = null
    }
}
