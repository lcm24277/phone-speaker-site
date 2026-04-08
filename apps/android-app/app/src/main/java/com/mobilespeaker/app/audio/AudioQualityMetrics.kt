package com.mobilespeaker.app.audio

data class AudioQualityMetrics(
    val sampleCount: Int,
    val clippingCount: Int,
    val spikeCount: Int,
    val rms: Double,
    val silenceRatio: Double
)

enum class AudioQualityVerdict {
    HEALTHY,
    WARNING,
    BAD
}

data class AudioQualitySnapshot(
    val sessionId: String,
    val frameCount: Int,
    val clippingCount: Int,
    val spikeCount: Int,
    val underrunCount: Int,
    val avgRms: Double,
    val avgSilenceRatio: Double,
    val verdict: AudioQualityVerdict,
    val reasonCode: String
)

data class PlaybackFrameResult(
    val played: Boolean,
    val metrics: AudioQualityMetrics? = null
)
