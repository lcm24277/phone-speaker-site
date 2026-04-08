package com.mobilespeaker.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.mobilespeaker.app.core.Protocol
import com.mobilespeaker.app.diagnostics.CrashDiagnostics
import com.mobilespeaker.app.util.AppLogger
import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlaybackEngine {
    // Batch 3 tuning: add back a small, safer makeup gain after clipping was reduced.
    private val playbackGainBoost = 1.16f
    private val frameSamplesPerChannel = Protocol.AUDIO_SAMPLE_RATE / (1000 / Protocol.OPUS_FRAME_MS)
    private val frameTotalSamples = frameSamplesPerChannel * Protocol.AUDIO_CHANNELS
    private val running = AtomicBoolean(false)
    private val decoderAvailable = AtomicBoolean(false)
    private val opus = Opus()
    private val frameSize = Constants.FrameSize.Companion._960()
    private val silenceFrame = ShortArray(frameTotalSamples)
    private var lastGoodFrame = ShortArray(frameTotalSamples)
    private var hasLastGoodFrame = false
    private var concealmentRepeats = 0
    private var previousSample: Int = 0
    private var sessionToken: Int = 0

    private val track: AudioTrack by lazy {
        val minBuffer = AudioTrack.getMinBufferSize(
            Protocol.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val targetBuffer = (minBuffer * 4).coerceAtLeast(frameTotalSamples * 2 * 2)
        AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(Protocol.AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build(),
            targetBuffer,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    init {
        val ret = runCatching {
            opus.decoderInit(
                Constants.SampleRate.Companion._48000(),
                Constants.Channels.Companion.stereo()
            )
        }.getOrElse {
            AppLogger.log("ANDROID_OPUS_DECODER_INIT_FAIL error=${it.message}")
            -1
        }

        if (ret == 0) {
            decoderAvailable.set(true)
            AppLogger.log("ANDROID_OPUS_DECODER_READY")
        } else {
            AppLogger.log("ANDROID_OPUS_DECODER_INIT_FAIL code=$ret")
        }
    }

    fun start(token: Int) {
        sessionToken = token
        if (running.compareAndSet(false, true)) {
            CrashDiagnostics.recordEvent(
                category = "audiotrack",
                action = "start",
                sessionToken = token,
                extra = snapshot()
            )
            runCatching { track.play() }
                .onFailure {
                    running.set(false)
                    CrashDiagnostics.recordEvent(
                        category = "audiotrack",
                        action = "play_fail",
                        sessionToken = token,
                        extra = it.message.orEmpty()
                    )
                    AppLogger.log("ANDROID_AUDIO_TRACK_PLAY_FAIL error=${it.message}")
                }
        }
    }

    fun stop() {
        sessionToken = 0
        if (running.compareAndSet(true, false)) {
            CrashDiagnostics.recordEvent(category = "audiotrack", action = "stop", extra = snapshot())
            runCatching { track.pause() }
            runCatching { track.flush() }
            concealmentRepeats = 0
        }
    }

    fun release() {
        stop()
        CrashDiagnostics.recordEvent(category = "decoder", action = "release", extra = snapshot())
        runCatching { opus.decoderRelease() }
        runCatching { track.release() }
    }

    fun decodeAndPlay(token: Int, opusPayload: ByteArray, isMuted: Boolean, volumePercent: Int): PlaybackFrameResult {
        if (token == 0 || token != sessionToken) return PlaybackFrameResult(false)
        if (!running.get()) return PlaybackFrameResult(false)
        if (!decoderAvailable.get()) return PlaybackFrameResult(false)

        val decodedBytes = runCatching {
            opus.decode(opusPayload, frameSize, 0)
        }.getOrElse {
            CrashDiagnostics.recordEvent(
                category = "decoder",
                action = "decode_fail",
                sessionToken = token,
                extra = it.message.orEmpty()
            )
            AppLogger.log("ANDROID_OPUS_DECODE_FAIL error=${it.message}")
            return PlaybackFrameResult(false)
        } ?: return PlaybackFrameResult(false)

        val decoded = runCatching {
            opus.convert(decodedBytes)
        }.getOrElse {
            CrashDiagnostics.recordEvent(
                category = "decoder",
                action = "convert_fail",
                sessionToken = token,
                extra = it.message.orEmpty()
            )
            AppLogger.log("ANDROID_OPUS_CONVERT_FAIL error=${it.message}")
            return PlaybackFrameResult(false)
        } ?: return PlaybackFrameResult(false)

        if (decoded.isEmpty()) return PlaybackFrameResult(false)
        val total = decoded.size.coerceAtMost(frameTotalSamples)
        val metrics = analyze(decoded, total)

        if (isMuted) {
            for (i in 0 until total) {
                decoded[i] = 0
            }
        } else {
            // Keep enough loudness on phone while still avoiding hard clipping.
            val gain = (volumePercent.coerceIn(0, 100) / 100.0f) * playbackGainBoost
            for (i in 0 until total) {
                val scaled = (decoded[i].toInt() * gain).toInt()
                decoded[i] = scaled.coerceIn(-30000, 30000).toShort()
            }
        }

        val snapshot = ShortArray(frameTotalSamples)
        decoded.copyInto(snapshot, endIndex = total)
        lastGoodFrame = snapshot
        hasLastGoodFrame = true
        concealmentRepeats = 0

        val written = runCatching {
            track.write(decoded, 0, total, AudioTrack.WRITE_BLOCKING)
        }.getOrElse {
            CrashDiagnostics.recordEvent(
                category = "audiotrack",
                action = "write_throw",
                sessionToken = token,
                extra = it.message.orEmpty()
            )
            AppLogger.log("ANDROID_AUDIO_TRACK_WRITE_THROW error=${it.message}")
            return PlaybackFrameResult(false, metrics)
        }
        if (written < 0) {
            CrashDiagnostics.recordEvent(
                category = "audiotrack",
                action = "write_fail",
                sessionToken = token,
                extra = "code=$written"
            )
            AppLogger.log("ANDROID_AUDIO_TRACK_WRITE_FAIL code=$written")
            return PlaybackFrameResult(false, metrics)
        }
        return PlaybackFrameResult(true, metrics)
    }

    fun playSilenceFrame(token: Int) {
        if (token == 0 || token != sessionToken) return
        if (!running.get()) return
        val concealmentFrame =
            if (hasLastGoodFrame && concealmentRepeats < 2) {
                concealmentRepeats += 1
                lastGoodFrame
            } else {
                silenceFrame
            }
        val written = runCatching {
            track.write(concealmentFrame, 0, concealmentFrame.size, AudioTrack.WRITE_BLOCKING)
        }.getOrElse {
            CrashDiagnostics.recordEvent(
                category = "audiotrack",
                action = "silence_throw",
                sessionToken = token,
                extra = it.message.orEmpty()
            )
            AppLogger.log("ANDROID_AUDIO_TRACK_SILENCE_THROW error=${it.message}")
            return
        }
        if (written < 0) {
            CrashDiagnostics.recordEvent(
                category = "audiotrack",
                action = "silence_write_fail",
                sessionToken = token,
                extra = "code=$written"
            )
            AppLogger.log("ANDROID_AUDIO_TRACK_SILENCE_WRITE_FAIL code=$written")
        }
    }

    fun snapshot(): String {
        val trackState = runCatching { track.state }.getOrDefault(-1)
        val playState = runCatching { track.playState }.getOrDefault(-1)
        return "running=${running.get()} decoderAvailable=${decoderAvailable.get()} trackState=$trackState playState=$playState token=$sessionToken"
    }

    private fun analyze(decoded: ShortArray, total: Int): AudioQualityMetrics {
        var clippingCount = 0
        var spikeCount = 0
        var silenceCount = 0
        var squareSum = 0.0
        for (i in 0 until total) {
            val sample = decoded[i].toInt()
            val absSample = kotlin.math.abs(sample)
            if (absSample >= 32000) {
                clippingCount += 1
            }
            if (kotlin.math.abs(sample - previousSample) >= 18000) {
                spikeCount += 1
            }
            if (absSample <= 220) {
                silenceCount += 1
            }
            squareSum += sample.toDouble() * sample.toDouble()
            previousSample = sample
        }
        val rms = kotlin.math.sqrt(squareSum / total.coerceAtLeast(1)) / 32768.0
        val silenceRatio = silenceCount.toDouble() / total.coerceAtLeast(1).toDouble()
        return AudioQualityMetrics(
            sampleCount = total,
            clippingCount = clippingCount,
            spikeCount = spikeCount,
            rms = rms,
            silenceRatio = silenceRatio
        )
    }
}
