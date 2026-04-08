package com.mobilespeaker.app.audio

import com.mobilespeaker.app.core.Protocol
import com.mobilespeaker.app.diagnostics.CrashDiagnostics
import java.util.TreeMap

class JitterBuffer(
    private val warmupFrames: Int = Protocol.JITTER_FRAMES,
    private val maxQueueFrames: Int = 16,
    private val latencyGuardFrames: Int = 10,
    private val targetQueueFrames: Int = 6,
    private val maxSkipSearch: Int = 3
) {
    private val queue = TreeMap<Int, ByteArray>()
    private var started = false
    private var expectedSeq = -1
    private var consecutiveMisses = 0
    private var sessionToken: Int = 0

    @Synchronized
    fun startSession(token: Int) {
        sessionToken = token
        resetInternal()
        CrashDiagnostics.recordEvent(
            category = "jitterbuffer",
            action = "start_session",
            sessionToken = token,
            extra = "warmup=$warmupFrames maxQueue=$maxQueueFrames"
        )
    }

    @Synchronized
    fun offer(token: Int, packet: RtpPacket) {
        if (token == 0 || token != sessionToken) return
        queue[packet.sequence] = packet.payload
        if (expectedSeq < 0) {
            expectedSeq = packet.sequence
        }
        while (queue.size > maxQueueFrames) {
            queue.pollFirstEntry()
        }
        alignExpectedToQueueHead()
    }

    @Synchronized
    fun reset() {
        sessionToken = 0
        resetInternal()
        CrashDiagnostics.recordEvent(category = "jitterbuffer", action = "reset")
    }

    @Synchronized
    fun poll(token: Int): ByteArray? {
        if (token == 0 || token != sessionToken) return null
        if (expectedSeq < 0) return null

        if (!started) {
            if (queue.size < warmupFrames) return null
            started = true
        }

        trimLatencyIfNeeded()

        val exact = queue.remove(expectedSeq)
        if (exact != null) {
            expectedSeq = (expectedSeq + 1) and 0xFFFF
            consecutiveMisses = 0
            return exact
        }

        consecutiveMisses += 1
        if (queue.isEmpty()) return null

        val headSeq = queue.firstKey()
        if (sequenceDistance(expectedSeq, headSeq) > maxSkipSearch) {
            expectedSeq = headSeq
            consecutiveMisses = 0
            return queue.remove(headSeq)
        }

        var probe = expectedSeq
        repeat(maxSkipSearch) {
            probe = (probe + 1) and 0xFFFF
            val candidate = queue.remove(probe)
            if (candidate != null) {
                expectedSeq = (probe + 1) and 0xFFFF
                consecutiveMisses = 0
                return candidate
            }
        }

        if (consecutiveMisses >= 3) {
            expectedSeq = (expectedSeq + 1) and 0xFFFF
            consecutiveMisses = 0
        }
        return null
    }

    @Synchronized
    private fun resetInternal() {
        queue.clear()
        started = false
        expectedSeq = -1
        consecutiveMisses = 0
    }

    @Synchronized
    fun bufferedFrames(): Int = queue.size

    @Synchronized
    fun snapshot(): String {
        return "started=$started queue=${queue.size} expectedSeq=$expectedSeq misses=$consecutiveMisses token=$sessionToken"
    }

    private fun trimLatencyIfNeeded() {
        if (!started || queue.size <= latencyGuardFrames) return
        while (queue.size > targetQueueFrames) {
            queue.pollFirstEntry()
        }
        alignExpectedToQueueHead()
        consecutiveMisses = 0
    }

    private fun alignExpectedToQueueHead() {
        val head = queue.firstKey() ?: return
        if (expectedSeq < 0 || sequenceDistance(expectedSeq, head) > queue.size + maxSkipSearch) {
            expectedSeq = head
        }
    }

    private fun sequenceDistance(from: Int, to: Int): Int = (to - from) and 0xFFFF
}
