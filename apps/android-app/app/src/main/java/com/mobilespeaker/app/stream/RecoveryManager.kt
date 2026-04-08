package com.mobilespeaker.app.stream

import com.mobilespeaker.app.diagnostics.CrashDiagnostics

class RecoveryManager {
    private val attemptedReasons = mutableSetOf<String>()

    @Synchronized
    fun shouldRecover(sessionId: String, reasonCode: String): Boolean {
        val key = "$sessionId:$reasonCode"
        if (attemptedReasons.contains(key)) {
            CrashDiagnostics.recordEvent(
                category = "recovery",
                action = "skip_duplicate",
                sessionId = sessionId,
                extra = reasonCode
            )
            return false
        }
        attemptedReasons.add(key)
        CrashDiagnostics.recordEvent(
            category = "recovery",
            action = "allow",
            sessionId = sessionId,
            extra = reasonCode
        )
        return true
    }

    @Synchronized
    fun resetForSession(sessionId: String) {
        attemptedReasons.removeIf { it.startsWith("$sessionId:") }
        CrashDiagnostics.recordEvent(category = "recovery", action = "reset_session", sessionId = sessionId)
    }
}
