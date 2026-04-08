package com.mobilespeaker.app.model

data class PcEndpoint(
    val id: String,
    val name: String,
    val ip: String,
    val status: String = "available"
)

data class StatusResponse(
    val connectionStatus: String = "idle",
    val deviceName: String = "",
    val deviceIp: String = "",
    val networkType: String = "unknown",
    val latencyMs: Int = 0,
    val volumePercent: Int = 75,
    val isMuted: Boolean = false,
    val isLocalMonitorEnabled: Boolean = true,
    val virtualAudioDeviceName: String = ""
)

data class AndroidUiState(
    val connectionStatus: String = "idle",
    val sessionId: String = "session-idle",
    val sessionState: String = "IDLE",
    val lastReasonCode: String = "app-init",
    val isConnectionBusy: Boolean = false,
    val connectedPcName: String = "-",
    val connectedPcIp: String = "-",
    val networkName: String = "-",
    val volumePercent: Int = 75,
    val isMuted: Boolean = false,
    val waveLevel: Float = 0.2f,
    val playbackState: String = "stopped",
    val discoveredPcs: List<PcEndpoint> = emptyList(),
    val manualIpInput: String = "",
    val isDontShowAgain: Boolean = false,
    val logs: List<String> = emptyList()
)
