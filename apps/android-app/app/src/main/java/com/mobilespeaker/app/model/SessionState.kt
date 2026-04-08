package com.mobilespeaker.app.model

enum class SessionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    DISCONNECTED_CLEAN,
    RECOVERING,
    ERROR
}

data class SessionSnapshot(
    val sessionId: String = "session-idle",
    val sessionToken: Int = 0,
    val state: SessionState = SessionState.IDLE,
    val reasonCode: String = "app-init"
)
