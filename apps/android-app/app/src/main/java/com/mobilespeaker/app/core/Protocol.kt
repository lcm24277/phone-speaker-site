package com.mobilespeaker.app.core

object Protocol {
    const val SERVICE_TYPE = "_mobile-speaker._udp."
    const val HTTP_PORT = 18730
    const val WS_PORT = 18731
    const val RTP_PORT = 18732
    const val ANDROID_DISCOVERY_PORT = 18733
    const val DISCOVERY_BEACON_PORT = 18733
    const val AUDIO_SAMPLE_RATE = 48_000
    const val AUDIO_CHANNELS = 2
    const val OPUS_FRAME_MS = 20
    const val RTP_PAYLOAD_TYPE = 111
    // Batch 1 tuning: trim warm-up latency without changing buffer algorithm.
    const val JITTER_BUFFER_MS = 80
    const val JITTER_FRAMES = JITTER_BUFFER_MS / OPUS_FRAME_MS
}
