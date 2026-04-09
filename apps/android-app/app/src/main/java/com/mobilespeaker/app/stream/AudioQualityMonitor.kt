package com.mobilespeaker.app.stream

import com.mobilespeaker.app.audio.AudioQualityMetrics
import com.mobilespeaker.app.audio.AudioQualitySnapshot
import com.mobilespeaker.app.audio.AudioQualityVerdict

class AudioQualityMonitor {
    private var sessionId: String = "session-idle"
    private var frameCount: Int = 0
    private var clippingCount: Int = 0
    private var spikeCount: Int = 0
    private var underrunCount: Int = 0
    private var rmsSum: Double = 0.0
    private var silenceRatioSum: Double = 0.0

    fun startSession(nextSessionId: String) {
        sessionId = nextSessionId
        reset()
    }

    fun reset() {
        frameCount = 0
        clippingCount = 0
        spikeCount = 0
        underrunCount = 0
        rmsSum = 0.0
        silenceRatioSum = 0.0
    }

    fun recordFrame(metrics: AudioQualityMetrics) {
        frameCount += 1
        clippingCount += metrics.clippingCount
        spikeCount += metrics.spikeCount
        rmsSum += metrics.rms
        silenceRatioSum += metrics.silenceRatio
    }

    fun recordUnderrun() {
        underrunCount += 1
    }

    fun currentSnapshot(): AudioQualitySnapshot {
        val avgRms = if (frameCount == 0) 0.0 else rmsSum / frameCount
        val avgSilenceRatio = if (frameCount == 0) 1.0 else silenceRatioSum / frameCount
        val verdict =
            when {
                underrunCount >= 12 || clippingCount >= 8 || spikeCount >= 72 -> AudioQualityVerdict.BAD
                underrunCount >= 4 || clippingCount >= 3 || spikeCount >= 28 -> AudioQualityVerdict.WARNING
                else -> AudioQualityVerdict.HEALTHY
            }
        val reason =
            when {
                underrunCount >= 12 -> "underrun-severe"
                clippingCount >= 8 -> "clipping-severe"
                spikeCount >= 72 -> "spike-severe"
                avgSilenceRatio >= 0.995 && avgRms < 0.003 && frameCount >= 12 -> "abnormal-silence"
                underrunCount >= 4 -> "underrun-warning"
                clippingCount >= 3 -> "clipping-warning"
                spikeCount >= 28 -> "spike-warning"
                else -> "healthy"
            }
        return AudioQualitySnapshot(
            sessionId = sessionId,
            frameCount = frameCount,
            clippingCount = clippingCount,
            spikeCount = spikeCount,
            underrunCount = underrunCount,
            avgRms = avgRms,
            avgSilenceRatio = avgSilenceRatio,
            verdict = verdict,
            reasonCode = reason
        )
    }

    fun badAudioOnConnectSnapshot(isMuted: Boolean): AudioQualitySnapshot? {
        val snapshot = currentSnapshot()
        val severeUnderrunWithAudibleSignal =
            snapshot.frameCount >= 18 &&
            snapshot.underrunCount >= 13 &&
            snapshot.avgRms >= 0.035
        val persistentUnderrunBadAudio =
            snapshot.frameCount >= 90 &&
                snapshot.underrunCount >= 12 &&
                snapshot.avgRms >= 0.022
        val audibleDistortionBurst =
            snapshot.frameCount >= 18 &&
                snapshot.avgRms >= 0.026 &&
                snapshot.clippingCount >= 6 &&
                snapshot.spikeCount >= 18
        val spikeDominantBadAudio =
            snapshot.frameCount >= 18 &&
                snapshot.avgRms >= 0.03 &&
                snapshot.spikeCount >= 34
        val moderateDistortionWithPersistentUnderrun =
            snapshot.frameCount >= 18 &&
                snapshot.avgRms >= 0.02 &&
                snapshot.underrunCount >= 9 &&
                snapshot.spikeCount >= 14
        val abnormalSilenceOnConnect =
            !isMuted &&
                snapshot.frameCount >= 18 &&
                snapshot.avgSilenceRatio >= 0.93 &&
                snapshot.avgRms < 0.017
        val bad =
            snapshot.clippingCount >= 14 ||
                snapshot.spikeCount >= 44 ||
                audibleDistortionBurst ||
                spikeDominantBadAudio ||
                moderateDistortionWithPersistentUnderrun ||
                persistentUnderrunBadAudio ||
                severeUnderrunWithAudibleSignal ||
                abnormalSilenceOnConnect
        if (!bad) return null
        return snapshot.copy(
            verdict = AudioQualityVerdict.BAD,
            reasonCode = "BAD_AUDIO_ON_CONNECT"
        )
    }
}
