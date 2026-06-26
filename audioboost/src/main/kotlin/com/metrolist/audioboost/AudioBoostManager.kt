package com.metrolist.audioboost

import android.media.audiofx.LoudnessEnhancer
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioBoostManager @Inject constructor() {
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentAudioSessionId: Int = -1
    private var isEnabled: Boolean = false
    private var boostGainmB: Int = 0

    fun setAudioSessionId(sessionId: Int) {
        if (currentAudioSessionId == sessionId) return
        
        Timber.tag(TAG).d("Setting audio session ID: $sessionId (previous: $currentAudioSessionId)")
        
        // We only release if the session ID is different.
        // If we are crossfading, we might have multiple sessions, but LoudnessEnhancer
        // is usually applied to the session of the player that is playing.
        release()
        
        currentAudioSessionId = sessionId
        if (sessionId > 0) {
            try {
                loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                    enabled = isEnabled
                    setTargetGain(boostGainmB)
                }
                Timber.tag(TAG).d("LoudnessEnhancer initialized for session $sessionId, enabled=$isEnabled, gain=$boostGainmB")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to initialize LoudnessEnhancer for session $sessionId")
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (isEnabled == enabled) return
        isEnabled = enabled
        try {
            loudnessEnhancer?.enabled = enabled
            Timber.tag(TAG).d("Audio Boost enabled state changed to: $enabled")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set Audio Boost enabled state")
        }
    }

    fun setBoostGain(gainmB: Int) {
        if (boostGainmB == gainmB) return
        boostGainmB = gainmB
        try {
            loudnessEnhancer?.setTargetGain(gainmB)
            Timber.tag(TAG).d("Audio Boost gain set to $gainmB mB")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set LoudnessEnhancer gain")
        }
    }

    /**
     * Release the LoudnessEnhancer if it matches the given sessionId.
     * If sessionId is -1, release unconditionally.
     */
    fun release(sessionId: Int = -1) {
        if (sessionId != -1 && sessionId != currentAudioSessionId) {
            Timber.tag(TAG).d("Ignoring release request for session $sessionId (current is $currentAudioSessionId)")
            return
        }

        if (loudnessEnhancer != null) {
            Timber.tag(TAG).d("Releasing LoudnessEnhancer for session $currentAudioSessionId")
            try {
                loudnessEnhancer?.release()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error releasing LoudnessEnhancer")
            }
            loudnessEnhancer = null
        }
        currentAudioSessionId = -1
    }

    companion object {
        private const val TAG = "AudioBoostManager"
    }
}
