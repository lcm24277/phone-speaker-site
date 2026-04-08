package com.mobilespeaker.app.stream

import com.mobilespeaker.app.audio.AudioPlaybackEngine
import com.mobilespeaker.app.audio.JitterBuffer
import com.mobilespeaker.app.diagnostics.CrashDiagnostics
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeoutOrNull

class ResourceGuard {
    private var receiveJob: Job? = null
    private var playoutJob: Job? = null
    private var healthProbeJob: Job? = null

    fun registerReceiveJob(job: Job?) {
        receiveJob = job
    }

    fun registerPlayoutJob(job: Job?) {
        playoutJob = job
    }

    fun registerHealthProbeJob(job: Job?) {
        healthProbeJob = job
    }

    suspend fun resetPlaybackResources(
        playback: AudioPlaybackEngine,
        jitter: JitterBuffer
    ) {
        CrashDiagnostics.recordEvent(category = "disconnect", action = "reset_resources_start")
        val oldHealth = healthProbeJob
        val oldReceive = receiveJob
        val oldPlayout = playoutJob
        healthProbeJob = null
        receiveJob = null
        playoutJob = null

        runCatching { oldHealth?.cancelAndJoin() }
        runCatching { oldReceive?.cancelAndJoin() }
        runCatching { oldPlayout?.cancelAndJoin() }
        runCatching { playback.stop() }
        runCatching { jitter.reset() }
        CrashDiagnostics.recordEvent(category = "disconnect", action = "reset_resources_done")
    }

    suspend fun awaitCleanDisconnect(
        playback: AudioPlaybackEngine,
        jitter: JitterBuffer,
        timeoutMs: Long = 1500L
    ): Boolean {
        CrashDiagnostics.recordEvent(category = "disconnect", action = "await_clean_start", extra = "timeoutMs=$timeoutMs")
        val completed =
            withTimeoutOrNull(timeoutMs) {
                resetPlaybackResources(playback, jitter)
                true
            }
        if (completed == null) {
            clearJobRefs()
            runCatching { playback.stop() }
            runCatching { jitter.reset() }
            CrashDiagnostics.recordEvent(category = "disconnect", action = "await_clean_timeout")
            return false
        }
        CrashDiagnostics.recordEvent(category = "disconnect", action = "await_clean_done")
        return true
    }

    fun clearJobRefs() {
        healthProbeJob = null
        receiveJob = null
        playoutJob = null
    }

    suspend fun releaseAll(
        playback: AudioPlaybackEngine,
        jitter: JitterBuffer
    ) {
        resetPlaybackResources(playback, jitter)
        runCatching { playback.release() }
        runCatching { jitter.reset() }
        clearJobRefs()
    }
}
