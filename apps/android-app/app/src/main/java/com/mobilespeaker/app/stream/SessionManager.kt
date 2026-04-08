package com.mobilespeaker.app.stream

import com.mobilespeaker.app.diagnostics.CrashDiagnostics
import com.mobilespeaker.app.model.SessionSnapshot
import com.mobilespeaker.app.model.SessionState
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionManager {
    private val counter = AtomicInteger(1)
    private val tokenCounter = AtomicInteger(1)
    private val _session = MutableStateFlow(SessionSnapshot())
    val session: StateFlow<SessionSnapshot> = _session.asStateFlow()

    fun beginConnect(reasonCode: String): SessionSnapshot {
        val current = _session.value
        val nextState =
            when (current.state) {
                SessionState.IDLE, SessionState.ERROR, SessionState.DISCONNECTED_CLEAN -> SessionState.CONNECTING
                SessionState.CONNECTED -> SessionState.RECOVERING
                else -> throw IllegalStateException("cannot connect from ${current.state}")
            }
        val snapshot =
            SessionSnapshot(
                sessionId = buildSessionId(),
                sessionToken = tokenCounter.getAndIncrement(),
                state = nextState,
                reasonCode = reasonCode
            )
        _session.value = snapshot
        CrashDiagnostics.recordEvent(
            category = "session",
            action = "begin_connect",
            sessionId = snapshot.sessionId,
            sessionToken = snapshot.sessionToken,
            stateBefore = current.state.name,
            stateAfter = nextState.name,
            extra = "reason=$reasonCode"
        )
        return snapshot
    }

    fun beginDisconnect(reasonCode: String): SessionSnapshot {
        val current = _session.value
        if (current.state !in listOf(SessionState.CONNECTED, SessionState.RECOVERING, SessionState.ERROR)) {
            throw IllegalStateException("cannot disconnect from ${current.state}")
        }
        val snapshot = current.copy(state = SessionState.DISCONNECTING, reasonCode = reasonCode)
        _session.value = snapshot
        CrashDiagnostics.recordEvent(
            category = "session",
            action = "begin_disconnect",
            sessionId = snapshot.sessionId,
            sessionToken = snapshot.sessionToken,
            stateBefore = current.state.name,
            stateAfter = snapshot.state.name,
            extra = "reason=$reasonCode"
        )
        return snapshot
    }

    fun beginRecover(reasonCode: String): SessionSnapshot {
        val current = _session.value
        if (current.state !in listOf(SessionState.CONNECTED, SessionState.ERROR, SessionState.IDLE, SessionState.DISCONNECTED_CLEAN)) {
            throw IllegalStateException("cannot recover from ${current.state}")
        }
        val snapshot =
            current.copy(
                sessionId = buildSessionId(),
                sessionToken = tokenCounter.getAndIncrement(),
                state = SessionState.RECOVERING,
                reasonCode = reasonCode
            )
        _session.value = snapshot
        CrashDiagnostics.recordEvent(
            category = "session",
            action = "begin_recover",
            sessionId = snapshot.sessionId,
            sessionToken = snapshot.sessionToken,
            stateBefore = current.state.name,
            stateAfter = snapshot.state.name,
            extra = "reason=$reasonCode"
        )
        return snapshot
    }

    fun markConnected(reasonCode: String): SessionSnapshot {
        val current = _session.value
        if (current.state !in listOf(SessionState.CONNECTING, SessionState.RECOVERING, SessionState.DISCONNECTED_CLEAN)) {
            throw IllegalStateException("cannot mark connected from ${current.state}")
        }
        val snapshot = current.copy(state = SessionState.CONNECTED, reasonCode = reasonCode)
        _session.value = snapshot
        CrashDiagnostics.recordEvent(
            category = "session",
            action = "mark_connected",
            sessionId = snapshot.sessionId,
            sessionToken = snapshot.sessionToken,
            stateBefore = current.state.name,
            stateAfter = snapshot.state.name,
            extra = "reason=$reasonCode"
        )
        return snapshot
    }

    fun markConnecting(reasonCode: String): SessionSnapshot {
        val current = _session.value
        if (current.state !in listOf(SessionState.DISCONNECTED_CLEAN, SessionState.IDLE, SessionState.ERROR)) {
            throw IllegalStateException("cannot mark connecting from ${current.state}")
        }
        val snapshot = current.copy(state = SessionState.CONNECTING, reasonCode = reasonCode)
        _session.value = snapshot
        CrashDiagnostics.recordEvent(
            category = "session",
            action = "mark_connecting",
            sessionId = snapshot.sessionId,
            sessionToken = snapshot.sessionToken,
            stateBefore = current.state.name,
            stateAfter = snapshot.state.name,
            extra = "reason=$reasonCode"
        )
        return snapshot
    }

    fun markIdle(reasonCode: String): SessionSnapshot {
        val current = _session.value
        val snapshot = current.copy(state = SessionState.IDLE, reasonCode = reasonCode)
        _session.value = snapshot
        CrashDiagnostics.recordEvent(
            category = "session",
            action = "mark_idle",
            sessionId = snapshot.sessionId,
            sessionToken = snapshot.sessionToken,
            stateBefore = current.state.name,
            stateAfter = snapshot.state.name,
            extra = "reason=$reasonCode"
        )
        return snapshot
    }

    fun markDisconnectedClean(reasonCode: String): SessionSnapshot {
        val current = _session.value
        val snapshot = current.copy(state = SessionState.DISCONNECTED_CLEAN, reasonCode = reasonCode)
        _session.value = snapshot
        CrashDiagnostics.recordEvent(
            category = "session",
            action = "mark_disconnected_clean",
            sessionId = snapshot.sessionId,
            sessionToken = snapshot.sessionToken,
            stateBefore = current.state.name,
            stateAfter = snapshot.state.name,
            extra = "reason=$reasonCode"
        )
        return snapshot
    }

    fun markError(reasonCode: String): SessionSnapshot {
        val current = _session.value
        val snapshot = current.copy(state = SessionState.ERROR, reasonCode = reasonCode)
        _session.value = snapshot
        CrashDiagnostics.recordEvent(
            category = "session",
            action = "mark_error",
            sessionId = snapshot.sessionId,
            sessionToken = snapshot.sessionToken,
            stateBefore = current.state.name,
            stateAfter = snapshot.state.name,
            extra = "reason=$reasonCode"
        )
        return snapshot
    }

    fun current(): SessionSnapshot = _session.value

    fun isActiveToken(token: Int): Boolean = token != 0 && _session.value.sessionToken == token

    private fun buildSessionId(): String {
        return "session-${System.currentTimeMillis()}-${counter.getAndIncrement()}"
    }
}
