package com.mobilespeaker.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobilespeaker.app.model.AndroidUiState
import com.mobilespeaker.app.stream.StreamRuntime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val coordinator = StreamRuntime.get(application)
    private val _ui = MutableStateFlow(coordinator.ui.value)
    val ui: StateFlow<AndroidUiState> = _ui.asStateFlow()

    private var lastStableUi = coordinator.ui.value
    private var frozenUi: AndroidUiState? = null
    private var pendingStableUi: AndroidUiState? = null
    private var releaseFrozenUiJob: Job? = null

    init {
        viewModelScope.launch {
            coordinator.ui.collect { next ->
                if (shouldFreezeUi(next)) {
                    releaseFrozenUiJob?.cancel()
                    val base = frozenUi ?: lastStableUi
                    val frozen = frozenSnapshot(base, next)
                    frozenUi = frozen
                    pendingStableUi = next
                    if (_ui.value != frozen) {
                        _ui.value = frozen
                    }
                    return@collect
                }

                pendingStableUi = next
                if (frozenUi != null) {
                    scheduleFrozenUiRelease()
                } else {
                    lastStableUi = next
                    if (_ui.value != next) {
                        _ui.value = next
                    }
                }
            }
        }
    }

    fun startScan() = coordinator.startDiscovery()
    fun stopScan() = coordinator.stopDiscovery()
    fun connect(ip: String, name: String = "PC") = coordinator.connect(ip, name)
    fun reconnect() = coordinator.reconnect()
    fun disconnect() = coordinator.disconnect()
    fun volumeUp() = coordinator.volumeUp()
    fun volumeDown() = coordinator.volumeDown()
    fun toggleMute() = coordinator.toggleMute()
    fun setManualIp(ip: String) = coordinator.setManualIp(ip)
    fun setDontShowAgain(v: Boolean) = coordinator.setDontShowAgain(v)

    private fun shouldFreezeUi(ui: AndroidUiState): Boolean {
        return ui.isConnectionBusy ||
            ui.sessionState == "CONNECTING" ||
            ui.sessionState == "DISCONNECTING" ||
            ui.sessionState == "DISCONNECTED_CLEAN" ||
            ui.sessionState == "RECOVERING" ||
            ui.playbackState == "optimizing"
    }

    private fun frozenSnapshot(base: AndroidUiState, next: AndroidUiState): AndroidUiState {
        val stableConnectionStatus =
            if (base.connectionStatus == "connected") {
                base.connectionStatus
            } else {
                next.connectionStatus
            }
        val stablePlaybackState =
            when {
                next.playbackState == "optimizing" -> "optimizing"
                base.connectionStatus == "connected" -> base.playbackState
                else -> next.playbackState
            }
        return base.copy(
            isConnectionBusy = next.isConnectionBusy,
            connectionStatus = stableConnectionStatus,
            playbackState = stablePlaybackState
        )
    }

    private fun scheduleFrozenUiRelease() {
        releaseFrozenUiJob?.cancel()
        releaseFrozenUiJob =
            viewModelScope.launch {
                delay(750)
                val stable = pendingStableUi ?: return@launch
                frozenUi = null
                lastStableUi = stable
                if (_ui.value != stable) {
                    _ui.value = stable
                }
            }
    }
}
