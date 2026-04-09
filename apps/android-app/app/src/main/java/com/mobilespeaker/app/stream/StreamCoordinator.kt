package com.mobilespeaker.app.stream

import android.content.Context
import com.mobilespeaker.app.audio.AudioPlaybackEngine
import com.mobilespeaker.app.audio.JitterBuffer
import com.mobilespeaker.app.audio.RtpReceiver
import com.mobilespeaker.app.core.Protocol
import com.mobilespeaker.app.diagnostics.CrashDiagnostics
import com.mobilespeaker.app.diagnostics.DiagnosticStateSnapshot
import com.mobilespeaker.app.model.AndroidUiState
import com.mobilespeaker.app.model.SessionSnapshot
import com.mobilespeaker.app.model.SessionState
import com.mobilespeaker.app.network.AndroidPresenceAdvertiser
import com.mobilespeaker.app.network.PcApiClient
import com.mobilespeaker.app.network.PcDiscoveryManager
import com.mobilespeaker.app.util.AppLogger
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class StreamCoordinator(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashDiagnostics.coroutineHandler("stream-coordinator"))
    private val discovery = PcDiscoveryManager(context)
    private val presenceAdvertiser = AndroidPresenceAdvertiser(context)
    private val api = PcApiClient()
    private val receiver = RtpReceiver()
    private val jitter = JitterBuffer()
    private val playback = AudioPlaybackEngine()
    private val sessionManager = SessionManager()
    private val resourceGuard = ResourceGuard()
    private val qualityMonitor = AudioQualityMonitor()
    private val recoveryManager = RecoveryManager()

    private val _ui = MutableStateFlow(AndroidUiState())
    val ui: StateFlow<AndroidUiState> = _ui.asStateFlow()

    private val playbackLifecycleMutex = Mutex()

    @Volatile
    private var lastPacketAtMs: Long = 0L

    @Volatile
    private var lastRecoveryAtMs: Long = 0L

    @Volatile
    private var connectionEpochStartedAtMs: Long = 0L

    @Volatile
    private var connectionUnderrunFrames: Int = 0

    @Volatile
    private var connectionBadPolls: Int = 0

    @Volatile
    private var connectionGoodFrames: Int = 0

    @Volatile
    private var autoReconnectAttemptsLeft: Int = 0

    @Volatile
    private var lastTargetIp: String = ""

    @Volatile
    private var lastTargetName: String = "PC"

    @Volatile
    private var lastManualActionAtMs: Long = 0L
    @Volatile
    private var backgroundRecoveryUiFrozen: Boolean = false
    @Volatile
    private var lastWaveUiUpdateAtMs: Long = 0L
    @Volatile
    private var autoRecoveryInProgress: Boolean = false
    @Volatile
    private var manualReconnectInProgress: Boolean = false
    @Volatile
    private var optimizeStatusUntilMs: Long = 0L
    @Volatile
    private var reconnectUiQuietMode: Boolean = false
    private val reconnectUiQuietEpoch = AtomicInteger(0)
    private val recoveryGate = AtomicInteger(0)
    private val playbackLoopToken = AtomicInteger(0)
    private var debugStressJob: Job? = null
    private var pendingDiscoveryRestartJob: Job? = null

    init {
        AppLogger.init(context)
        CrashDiagnostics.registerStateProvider { diagnosticSnapshot() }
        AppLogger.log("ANDROID_SERVICE_INIT")
        presenceAdvertiser.start()
        CrashDiagnostics.recordEvent(category = "service", action = "stream_coordinator_init")
        scope.launch {
            sessionManager.session.collect { session ->
                publishSession(session)
            }
        }
        scope.launch {
            discovery.pcs.collect { pcs ->
                if (!reconnectUiQuietMode) {
                    _ui.value = _ui.value.copy(discoveredPcs = pcs)
                }
            }
        }
        startDiscovery()
    }

    fun startDiscovery() {
        cancelPendingDiscoveryRestart("start-now")
        CrashDiagnostics.recordEvent(category = "connect", action = "discovery_start")
        discovery.start()
        appendLog("ANDROID_MDNS_DISCOVERY_STARTED")
        if (sessionManager.current().state == SessionState.IDLE) {
            _ui.value = _ui.value.copy(connectionStatus = "scanning")
        }
    }

    fun stopDiscovery() {
        cancelPendingDiscoveryRestart("stop-now")
        CrashDiagnostics.recordEvent(category = "connect", action = "discovery_stop")
        discovery.stop()
        appendLog("ANDROID_MDNS_DISCOVERY_STOPPED")
        if (_ui.value.connectionStatus == "scanning") {
            _ui.value = _ui.value.copy(connectionStatus = "idle")
        }
    }

    fun setManualIp(ip: String) {
        _ui.value = _ui.value.copy(manualIpInput = ip)
    }

    fun setDontShowAgain(value: Boolean) {
        _ui.value = _ui.value.copy(isDontShowAgain = value)
    }

    fun connect(ip: String, name: String = "PC", actionSource: String = "ui_action", bypassDebounce: Boolean = false) {
        val targetIp = ip.trim()
        CrashDiagnostics.recordEvent(
            category = "ui_action",
            action = "connect",
            stateBefore = sessionManager.current().state.name,
            extra = "source=$actionSource targetIp=$targetIp"
        )
        if (targetIp.isBlank() || targetIp == "-") {
            transitionToError("empty-target-ip")
            appendLog("ANDROID_CONNECT_FAIL error=empty target ip")
            return
        }
        cancelPendingDiscoveryRestart("connect")
        if (!beginConnectionAction("connect:$actionSource", bypassDebounce)) return
        scope.launch {
            playbackLifecycleMutex.withLock {
                runCatching {
                    beginSessionConnect("manual-connect")
                    autoReconnectAttemptsLeft = 3
                    performConnectLocked(targetIp, name, "ANDROID_CONNECT_OK")
                }.onFailure {
                    transitionToError("connect-fail")
                    appendLog("ANDROID_CONNECT_FAIL error=${it.message}")
                }.also {
                    _ui.value = _ui.value.copy(isConnectionBusy = false)
                }
            }
        }
    }

    fun reconnect(actionSource: String = "ui_action", bypassDebounce: Boolean = false) {
        if (autoRecoveryInProgress) {
            appendLog("ANDROID_RECONNECT_SKIP reason=auto-recovery-active")
            return
        }
        CrashDiagnostics.recordEvent(
            category = "reconnect",
            action = "manual_reconnect_request",
            stateBefore = sessionManager.current().state.name,
            extra = "source=$actionSource"
        )
        CrashDiagnostics.recordEvent(
            category = "ui_action",
            action = "reconnect",
            stateBefore = sessionManager.current().state.name,
            extra = "source=$actionSource"
        )
        cancelPendingDiscoveryRestart("reconnect")
        if (!beginConnectionAction("reconnect:$actionSource", bypassDebounce)) return
        manualReconnectInProgress = true
        beginReconnectUiQuietWindow()
        scope.launch {
            playbackLifecycleMutex.withLock {
                val targetIp =
                    _ui.value.connectedPcIp.takeIf { it.isNotBlank() && it != "-" }
                        ?: lastTargetIp.takeIf { it.isNotBlank() }
                        ?: _ui.value.discoveredPcs.firstOrNull()?.ip
                val targetName =
                    _ui.value.discoveredPcs.firstOrNull { it.ip == targetIp }?.name
                        ?: if (lastTargetName.isNotBlank()) lastTargetName else "PC"
                if (targetIp.isNullOrBlank()) {
                    transitionToError("reconnect-no-target")
                    appendLog("ANDROID_RECONNECT_FAIL error=no target")
                    _ui.value = _ui.value.copy(isConnectionBusy = false)
                    return@withLock
                }
                runCatching {
                    beginSessionConnect("manual-reconnect")
                    autoReconnectAttemptsLeft = 3
                    performConnectLocked(
                        targetIp,
                        targetName,
                        "ANDROID_RECONNECT_OK",
                        reconnectDelayMs = 2000L
                    )
                }.onFailure {
                    endReconnectUiQuietWindowSoon(250)
                    transitionToError("reconnect-fail")
                    appendLog("ANDROID_RECONNECT_FAIL error=${it.message}")
                }.also {
                    manualReconnectInProgress = false
                    _ui.value = _ui.value.copy(isConnectionBusy = false)
                }
            }
        }
    }

    fun disconnect(actionSource: String = "ui_action", bypassDebounce: Boolean = false) {
        CrashDiagnostics.recordEvent(
            category = "ui_action",
            action = "disconnect",
            stateBefore = sessionManager.current().state.name,
            extra = "source=$actionSource"
        )
        if (!beginConnectionAction("disconnect:$actionSource", bypassDebounce)) return
        scope.launch {
            playbackLifecycleMutex.withLock {
                runCatching {
                    beginSessionDisconnect("manual-disconnect")
                }.onFailure {
                    appendLog("ANDROID_DISCONNECT_SKIP reason=${it.message}")
                }
                val ip = _ui.value.connectedPcIp
                if (ip != "-") {
                    runCatching { api.post(ip, "/api/disconnect") }
                }
                awaitCleanDisconnectLocked("manual-disconnect")
                optimizeStatusUntilMs = 0L
                reconnectUiQuietMode = false
                _ui.value = _ui.value.copy(
                    connectedPcName = "-",
                    connectedPcIp = "-",
                    networkName = "-",
                    playbackState = "stopped",
                    waveLevel = 0.2f
                )
                appendLog("ANDROID_DISCONNECTED")
                sessionManager.markIdle("disconnect-complete")
                scheduleDiscoveryRestart()
                _ui.value = _ui.value.copy(isConnectionBusy = false)
            }
        }
    }

    fun volumeUp() = adjustVolume(5, "/api/audio/volume/increase")

    fun volumeDown() = adjustVolume(-5, "/api/audio/volume/decrease")

    fun toggleMute() {
        val ip = _ui.value.connectedPcIp
        val next = !_ui.value.isMuted
        _ui.value = _ui.value.copy(isMuted = next)
        appendLog("ANDROID_MUTE_TOGGLED isMuted=$next")
        if (ip != "-") {
            scope.launch {
                runCatching { api.post(ip, "/api/audio/mute/toggle") }
            }
        }
    }

    fun startForegroundPlayback() {
        scope.launch {
            playbackLifecycleMutex.withLock {
                val ip = _ui.value.connectedPcIp
                if (ip.isBlank() || ip == "-") {
                    appendLog("ANDROID_PLAYBACK_WAITING_FOR_CONNECT")
                    return@withLock
                }
                startPlaybackLoopLocked()
            }
        }
    }

    fun stopAndRelease() {
        cancelPendingDiscoveryRestart("release")
        scope.launch {
            playbackLifecycleMutex.withLock {
                runCatching { beginSessionDisconnect("app-stop") }
                resourceGuard.releaseAll(playback, jitter)
                sessionManager.markIdle("release-complete")
                presenceAdvertiser.stop()
            }
        }
    }

    fun stopForegroundPlayback() {
        scope.launch {
            playbackLifecycleMutex.withLock {
                stopPlaybackLoopLocked()
                if (_ui.value.playbackState != "stopped") {
                    _ui.value = _ui.value.copy(playbackState = "stopped")
                }
                appendLog("ANDROID_PLAYBACK_STOPPED")
            }
        }
    }

    private fun adjustVolume(delta: Int, path: String) {
        val next = (_ui.value.volumePercent + delta).coerceIn(0, 100)
        _ui.value = _ui.value.copy(volumePercent = next)
        appendLog("ANDROID_VOLUME_CHANGED value=$next")
        val ip = _ui.value.connectedPcIp
        if (ip != "-") {
            scope.launch {
                runCatching { api.post(ip, path) }
            }
        }
    }

    private suspend fun startPlaybackLoopLocked() {
        val sessionToken = sessionManager.current().sessionToken
        CrashDiagnostics.recordEvent(
            category = "audiotrack",
            action = "playback_loop_start",
            sessionId = sessionManager.current().sessionId,
            sessionToken = sessionToken,
            extra = "connectedIp=${_ui.value.connectedPcIp}"
        )
        stopPlaybackLoopLocked()
        jitter.startSession(sessionToken)
        playback.start(sessionToken)
        val loopToken = playbackLoopToken.incrementAndGet()
        appendLog("ANDROID_PLAYBACK_LOOP_STARTED token=$sessionToken")

        val receiveJob = scope.launch {
            runCatching {
                var packetCount = 0
                receiver.receiveFlow(Protocol.RTP_PORT).collect { packet ->
                    if (!sessionManager.isActiveToken(sessionToken)) return@collect
                    if (packet.payloadType != Protocol.RTP_PAYLOAD_TYPE) return@collect
                    lastPacketAtMs = System.currentTimeMillis()
                    packetCount += 1
                    if (packetCount == 1 || packetCount % 50 == 0) {
                        appendLog("ANDROID_RTP_PACKET count=$packetCount seq=${packet.sequence}")
                    }
                    jitter.offer(sessionToken, packet)
                }
            }.onFailure {
                if (shouldIgnorePlaybackFailure(loopToken, it)) {
                    appendLog("ANDROID_RTP_RECEIVER_STOP token=$loopToken reason=${it.javaClass.simpleName}")
                } else {
                    transitionToError("rtp-receiver-fail")
                    appendLog("ANDROID_RTP_RECEIVER_FAIL error=${it.message}")
                    _ui.value = _ui.value.copy(playbackState = "stopped")
                }
            }
        }
        resourceGuard.registerReceiveJob(receiveJob)

        val playoutJob = scope.launch {
            runCatching {
                var silentFrames = 0
                var shouldRecover = false
                while (isActive) {
                    if (!sessionManager.isActiveToken(sessionToken)) {
                        appendLog("ANDROID_PLAYBACK_LOOP_STALE token=$sessionToken")
                        return@runCatching
                    }
                    val frame = jitter.poll(sessionToken)
                    if (frame == null) {
                        silentFrames += 1
                        connectionUnderrunFrames += 1
                        connectionBadPolls += 1
                        qualityMonitor.recordUnderrun()
                        if (
                            !autoRecoveryInProgress &&
                                !reconnectUiQuietMode &&
                                _ui.value.playbackState != "optimizing"
                        ) {
                            updateWaveLevel(0.05)
                        }
                        playback.playSilenceFrame(sessionToken)
                        if (
                            shouldAutoReconnect(silentFrames) &&
                                lastPacketAtMs > 0 &&
                                System.currentTimeMillis() - lastPacketAtMs < 1500
                        ) {
                            shouldRecover = true
                            appendLog(
                                "ANDROID_PLAYBACK_RECOVERY_REQUEST silentFrames=$silentFrames underruns=$connectionUnderrunFrames buffered=${jitter.bufferedFrames()}"
                            )
                            break
                        }
                        if (silentFrames % 50 == 0) {
                            appendLog(
                                "ANDROID_JITTER_UNDERRUN silentFrames=$silentFrames buffered=${jitter.bufferedFrames()}"
                            )
                        }
                    } else {
                        silentFrames = 0
                        val result =
                            playback.decodeAndPlay(
                                sessionToken,
                                frame,
                                _ui.value.isMuted,
                                _ui.value.volumePercent
                            )
                        if (
                            result.played &&
                                !reconnectUiQuietMode &&
                                _ui.value.playbackState != "playing" &&
                                System.currentTimeMillis() >= optimizeStatusUntilMs
                        ) {
                            _ui.value = _ui.value.copy(playbackState = "playing")
                            appendLog("ANDROID_PLAYBACK_PLAYING")
                        }
                        result.metrics?.let { qualityMonitor.recordFrame(it) }
                        if (
                            !autoRecoveryInProgress &&
                                !reconnectUiQuietMode &&
                                _ui.value.playbackState != "optimizing"
                        ) {
                            result.metrics?.let { updateWaveLevel(it.rms) }
                        }
                        if (result.played) {
                            connectionGoodFrames += 1
                        } else {
                            connectionBadPolls += 1
                        }
                    }
                }
                if (shouldRecover) {
                    requestPlaybackRecovery("underrun")
                }
            }.onFailure {
                if (shouldIgnorePlaybackFailure(loopToken, it)) {
                    appendLog("ANDROID_PLAYBACK_LOOP_STOP token=$loopToken reason=${it.javaClass.simpleName}")
                } else {
                    appendLog("ANDROID_PLAYBACK_LOOP_FAIL error=${it.message}")
                    resourceGuard.resetPlaybackResources(playback, jitter)
                    transitionToError("playback-loop-fail")
                    _ui.value = _ui.value.copy(playbackState = "stopped")
                }
            }
        }
        resourceGuard.registerPlayoutJob(playoutJob)
    }

    private fun requestPlaybackRecovery(reason: String) {
        CrashDiagnostics.recordEvent(
            category = "recovery",
            action = "request",
            stateBefore = sessionManager.current().state.name,
            extra = "reason=$reason"
        )
        if (manualReconnectInProgress) {
            appendLog("ANDROID_PLAYBACK_RECOVERY_SKIP reason=manual-reconnect-active")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastRecoveryAtMs <= 2600) return
        if (autoRecoveryInProgress) return
        if (!recoveryGate.compareAndSet(0, 1)) {
            appendLog("ANDROID_PLAYBACK_RECOVERY_SKIP reason=recovery-gate-busy")
            return
        }
        if (autoReconnectAttemptsLeft <= 0) {
            recoveryGate.set(0)
            appendLog("ANDROID_PLAYBACK_RECOVERY_GIVE_UP reason=$reason")
            return
        }
        lastRecoveryAtMs = now
        autoReconnectAttemptsLeft -= 1
        backgroundRecoveryUiFrozen = true
        autoRecoveryInProgress = true
        beginReconnectUiQuietWindow()
        optimizeStatusUntilMs = Long.MAX_VALUE
        _ui.value = _ui.value.copy(playbackState = "optimizing", waveLevel = 0.24f)
        scope.launch {
            playbackLifecycleMutex.withLock {
                val targetIp =
                    _ui.value.connectedPcIp.takeIf { it.isNotBlank() && it != "-" }
                        ?: lastTargetIp.takeIf { it.isNotBlank() }
                        ?: run {
                            recoveryGate.set(0)
                            return@withLock
                        }
                val targetName =
                    _ui.value.connectedPcName.takeIf { it.isNotBlank() && it != "-" }
                        ?: lastTargetName
                runCatching {
                    delay(180)
                    beginSessionRecover("auto-recovery-$reason")
                    performConnectLocked(
                        targetIp = targetIp,
                        requestedName = targetName,
                        successLog = "ANDROID_PLAYBACK_RECOVERY_OK",
                        reconnectDelayMs = 2000L
                    )
                }.onFailure {
                    backgroundRecoveryUiFrozen = false
                    endReconnectUiQuietWindowSoon(250)
                    transitionToError("recovery-fail")
                    CrashDiagnostics.recordEvent(category = "recovery", action = "fail", extra = "reason=$reason message=${it.message.orEmpty()}")
                    appendLog("ANDROID_PLAYBACK_RECOVERY_FAIL error=${it.message}")
                }.also {
                    autoRecoveryInProgress = false
                    recoveryGate.set(0)
                    CrashDiagnostics.recordEvent(category = "recovery", action = "finish", extra = "reason=$reason")
                }
            }
        }
    }

    private suspend fun stopPlaybackLoopLocked() {
        CrashDiagnostics.recordEvent(
            category = "audiotrack",
            action = "playback_loop_stop",
            sessionId = sessionManager.current().sessionId,
            sessionToken = sessionManager.current().sessionToken
        )
        playbackLoopToken.incrementAndGet()
        resourceGuard.resetPlaybackResources(playback, jitter)
    }

    private suspend fun awaitCleanDisconnectLocked(reasonCode: String): Boolean {
        playbackLoopToken.incrementAndGet()
        val cleaned = resourceGuard.awaitCleanDisconnect(playback, jitter)
        if (cleaned) {
            sessionManager.markDisconnectedClean("clean-disconnect")
            appendLog("ANDROID_DISCONNECTED_CLEAN reason=$reasonCode")
        } else {
            appendLog("ANDROID_DISCONNECTED_CLEAN_TIMEOUT reason=$reasonCode")
        }
        return cleaned
    }

    private suspend fun performConnectLocked(
        targetIp: String,
        requestedName: String,
        successLog: String,
        reconnectDelayMs: Long = 400L
    ) {
        CrashDiagnostics.recordEvent(
            category = "connect",
            action = "perform_connect_start",
            sessionId = sessionManager.current().sessionId,
            sessionToken = sessionManager.current().sessionToken,
            stateBefore = sessionManager.current().state.name,
            extra = "targetIp=$targetIp successLog=$successLog reconnectDelayMs=$reconnectDelayMs"
        )
        val pendingSessionToken = sessionManager.current().sessionToken
        val previousIp = _ui.value.connectedPcIp.takeIf { it.isNotBlank() && it != "-" }
        if (previousIp != null) {
            runCatching { api.post(previousIp, "/api/disconnect") }
            awaitCleanDisconnectLocked("reconnect")
            if (!backgroundRecoveryUiFrozen && !reconnectUiQuietMode) {
                _ui.value = _ui.value.copy(
                    connectedPcName = "-",
                    connectedPcIp = "-",
                    playbackState = "stopped",
                    waveLevel = 0.2f
                )
            }
            appendLog("ANDROID_RECONNECT_BREAK previousIp=$previousIp")
            delay(reconnectDelayMs)
            if (sessionManager.isActiveToken(pendingSessionToken)) {
                sessionManager.markConnecting("connect-after-clean-disconnect")
            }
        } else {
            stopPlaybackLoopLocked()
        }

        val localIp = getLocalIpv4Address()
        api.post(targetIp, "/api/connect", JSONObject().put("targetIp", localIp))
        if (!sessionManager.isActiveToken(pendingSessionToken)) {
            appendLog("ANDROID_CONNECT_ABORT_STALE token=$pendingSessionToken")
            return
        }
        val status = api.getStatus(targetIp)
        if (!sessionManager.isActiveToken(pendingSessionToken)) {
            appendLog("ANDROID_STATUS_ABORT_STALE token=$pendingSessionToken")
            return
        }
        lastTargetIp = targetIp
        lastTargetName = if (status.deviceName.isBlank()) requestedName else status.deviceName
        connectionEpochStartedAtMs = System.currentTimeMillis()
        connectionUnderrunFrames = 0
        connectionBadPolls = 0
        connectionGoodFrames = 0
        lastPacketAtMs = 0L
        qualityMonitor.startSession(sessionManager.current().sessionId)
        recoveryManager.resetForSession(sessionManager.current().sessionId)
        stopDiscovery()
        sessionManager.markConnected("connect-ok")
        _ui.value = _ui.value.copy(
            connectedPcName = lastTargetName,
            connectedPcIp = targetIp,
            networkName = status.networkType,
            volumePercent = status.volumePercent,
            isMuted = status.isMuted,
            playbackState =
                if (backgroundRecoveryUiFrozen) {
                    "optimizing"
                } else if (reconnectUiQuietMode) {
                    _ui.value.playbackState
                } else {
                    "buffering"
                },
            waveLevel = 0.24f
        )
        if (backgroundRecoveryUiFrozen) {
            optimizeStatusUntilMs = System.currentTimeMillis() + 1000L
            scope.launch {
                delay(1000)
                if (
                    !backgroundRecoveryUiFrozen &&
                        sessionManager.current().state == SessionState.CONNECTED &&
                        _ui.value.playbackState == "optimizing"
                ) {
                    _ui.value = _ui.value.copy(playbackState = "playing")
                    appendLog("ANDROID_PLAYBACK_PLAYING_AFTER_OPTIMIZE")
                }
            }
        } else {
            optimizeStatusUntilMs = 0L
        }
        backgroundRecoveryUiFrozen = false
        endReconnectUiQuietWindowSoon(if (manualReconnectInProgress) 900 else 1200)
        startPlaybackLoopLocked()
        launchHealthProbe()
        CrashDiagnostics.recordEvent(
            category = "connect",
            action = "perform_connect_ok",
            sessionId = sessionManager.current().sessionId,
            sessionToken = sessionManager.current().sessionToken,
            stateBefore = SessionState.CONNECTING.name,
            stateAfter = sessionManager.current().state.name,
            extra = "targetIp=$targetIp network=${status.networkType}"
        )
        appendLog("$successLog targetIp=$targetIp localIp=$localIp")
    }

    private fun launchHealthProbe() {
        val probeJob = scope.launch {
            val sessionId = sessionManager.current().sessionId
            val probeScheduleMs = listOf(2000L, 4200L, 6500L)
            probeScheduleMs.forEachIndexed { index, delayMs ->
                delay(if (index == 0) delayMs else delayMs - probeScheduleMs[index - 1])
                if (sessionManager.current().state != SessionState.CONNECTED) return@launch
                val snapshot = qualityMonitor.badAudioOnConnectSnapshot(_ui.value.isMuted)
                if (snapshot == null) {
                    val current = qualityMonitor.currentSnapshot()
                    appendLog(
                        "ANDROID_PLAYBACK_HEALTH_PROBE_OK round=${index + 1} frameCount=${current.frameCount} underruns=${current.underrunCount} clipping=${current.clippingCount} spike=${current.spikeCount}"
                    )
                    return@forEachIndexed
                }
                appendLog(
                    "ANDROID_BAD_AUDIO_ON_CONNECT round=${index + 1} clipping=${snapshot.clippingCount} spike=${snapshot.spikeCount} underruns=${snapshot.underrunCount} silenceRatio=${snapshot.avgSilenceRatio}"
                )
                if (recoveryManager.shouldRecover(sessionId, "BAD_AUDIO_ON_CONNECT_ROUND_${index + 1}")) {
                    requestPlaybackRecovery("BAD_AUDIO_ON_CONNECT")
                    return@launch
                }
            }
        }
        resourceGuard.registerHealthProbeJob(probeJob)
    }

    private fun shouldAutoReconnect(silentFrames: Int): Boolean {
        return false
    }

    private fun beginConnectionAction(reason: String, bypassDebounce: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (_ui.value.isConnectionBusy && !bypassDebounce) {
            CrashDiagnostics.recordEvent(category = "ui_action", action = "connection_action_skip_busy", extra = reason)
            appendLog("ANDROID_CONNECTION_ACTION_SKIPPED reason=busy")
            return false
        }
        if (!bypassDebounce && now - lastManualActionAtMs < 700) {
            CrashDiagnostics.recordEvent(category = "ui_action", action = "connection_action_skip_debounce", extra = reason)
            appendLog("ANDROID_CONNECTION_ACTION_SKIPPED reason=debounce")
            return false
        }
        lastManualActionAtMs = now
        _ui.value = _ui.value.copy(isConnectionBusy = true)
        CrashDiagnostics.recordEvent(category = "ui_action", action = "connection_action_begin", extra = "reason=$reason bypass=$bypassDebounce")
        return true
    }

    private fun shouldIgnorePlaybackFailure(loopToken: Int, error: Throwable): Boolean {
        if (loopToken != playbackLoopToken.get()) return true
        if (error is CancellationException) return true
        val message = error.message.orEmpty().lowercase()
        return message.contains("socket closed") ||
            message.contains("closed") ||
            message.contains("cancel")
    }

    private fun beginSessionConnect(reasonCode: String) {
        val snapshot = sessionManager.beginConnect(reasonCode)
        appendLog("ANDROID_SESSION_CONNECT_BEGIN sessionId=${snapshot.sessionId} reason=$reasonCode")
    }

    private fun beginSessionDisconnect(reasonCode: String) {
        val snapshot = sessionManager.beginDisconnect(reasonCode)
        appendLog("ANDROID_SESSION_DISCONNECT_BEGIN sessionId=${snapshot.sessionId} reason=$reasonCode")
    }

    private fun beginSessionRecover(reasonCode: String) {
        val current = sessionManager.current()
        val snapshot =
            when (current.state) {
                SessionState.CONNECTED, SessionState.ERROR, SessionState.IDLE -> sessionManager.beginRecover(reasonCode)
                else -> sessionManager.beginRecover(reasonCode)
            }
        appendLog("ANDROID_SESSION_RECOVER_BEGIN sessionId=${snapshot.sessionId} reason=$reasonCode")
    }

    private fun transitionToError(reasonCode: String) {
        val snapshot = sessionManager.markError(reasonCode)
        appendLog("ANDROID_SESSION_ERROR sessionId=${snapshot.sessionId} reason=$reasonCode")
    }

    private fun publishSession(session: SessionSnapshot) {
        val effectiveStatus =
            if (
                (backgroundRecoveryUiFrozen || reconnectUiQuietMode) &&
                    (session.state == SessionState.RECOVERING ||
                        session.state == SessionState.DISCONNECTING ||
                        session.state == SessionState.DISCONNECTED_CLEAN ||
                        session.state == SessionState.CONNECTING)
            ) {
                _ui.value.connectionStatus.ifBlank { "connected" }
            } else {
                mapSessionStateToUiStatus(session.state)
            }
        val preserveSessionMeta = autoRecoveryInProgress && reconnectUiQuietMode
        _ui.value =
            if (preserveSessionMeta) {
                _ui.value.copy(connectionStatus = effectiveStatus)
            } else {
                _ui.value.copy(
                    sessionId = session.sessionId,
                    sessionState = session.state.name,
                    lastReasonCode = session.reasonCode,
                    connectionStatus = effectiveStatus
                )
            }
    }

    fun runDebugStress(testCase: String, targetIp: String, requestedName: String = "Debug PC") {
        debugStressJob?.cancel()
        debugStressJob = scope.launch {
            CrashDiagnostics.recordEvent(
                category = "ui_action",
                action = "debug_stress_start",
                extra = "case=$testCase targetIp=$targetIp"
            )
            when (testCase.uppercase()) {
                "A" -> repeat(30) { round ->
                    connect(targetIp, requestedName, actionSource = "stress-A-$round", bypassDebounce = true)
                    delay(300)
                    disconnect(actionSource = "stress-A-$round", bypassDebounce = true)
                    delay(300)
                    reconnect(actionSource = "stress-A-$round", bypassDebounce = true)
                    delay(300)
                }
                "B" -> repeat(20) { round ->
                    connect(targetIp, requestedName, actionSource = "stress-B-$round", bypassDebounce = true)
                    delay(1000)
                    disconnect(actionSource = "stress-B-$round", bypassDebounce = true)
                    delay(500)
                    reconnect(actionSource = "stress-B-$round", bypassDebounce = true)
                    delay(800)
                }
                "C" -> repeat(20) { round ->
                    connect(targetIp, requestedName, actionSource = "stress-C-$round", bypassDebounce = true)
                    delay(1200)
                    requestPlaybackRecovery("DEBUG_STRESS_C_$round")
                    delay(100)
                    reconnect(actionSource = "stress-C-$round", bypassDebounce = true)
                    delay(100)
                    disconnect(actionSource = "stress-C-$round", bypassDebounce = true)
                    delay(600)
                }
                "D" -> repeat(10) { round ->
                    connect(targetIp, requestedName, actionSource = "stress-D-$round", bypassDebounce = true)
                    delay(1200)
                    requestPlaybackRecovery("BAD_AUDIO_ON_CONNECT_DEBUG_$round")
                    delay(2800)
                }
                else -> {
                    CrashDiagnostics.recordEvent(
                        category = "ui_action",
                        action = "debug_stress_unknown_case",
                        extra = testCase
                    )
                }
            }
            CrashDiagnostics.recordEvent(
                category = "ui_action",
                action = "debug_stress_finish",
                extra = "case=$testCase"
            )
        }
    }

    private fun updateWaveLevel(rms: Double) {
        val now = System.currentTimeMillis()
        if (now - lastWaveUiUpdateAtMs < 220) return
        lastWaveUiUpdateAtMs = now
        val normalized = ((rms * 8.5).coerceIn(0.0, 1.0)).toFloat()
        val current = _ui.value.waveLevel
        val next = (current * 0.55f) + (normalized * 0.45f)
        _ui.value = _ui.value.copy(waveLevel = next.coerceIn(0.08f, 1.0f))
    }

    private fun diagnosticSnapshot(): DiagnosticStateSnapshot {
        val session = sessionManager.current()
        val recoveryState =
            when {
                autoRecoveryInProgress -> "auto-recovering"
                manualReconnectInProgress -> "manual-reconnecting"
                reconnectUiQuietMode -> "reconnect-ui-quiet"
                else -> "idle"
            }
        return DiagnosticStateSnapshot(
            currentSessionId = session.sessionId,
            currentSessionToken = session.sessionToken,
            connectionState = _ui.value.connectionStatus,
            playbackState = _ui.value.playbackState,
            recoveryState = recoveryState,
            currentDeviceIp = _ui.value.connectedPcIp,
            currentSocketState = receiver.snapshot(),
            currentAudioTrackState = playback.snapshot(),
            decoderState = playback.snapshot(),
            jitterBufferState = jitter.snapshot()
        )
    }

    private fun mapSessionStateToUiStatus(state: SessionState): String {
        return when (state) {
            SessionState.IDLE -> "idle"
            SessionState.CONNECTING -> "connecting"
            SessionState.CONNECTED -> "connected"
            SessionState.DISCONNECTING -> "disconnected"
            SessionState.DISCONNECTED_CLEAN -> "disconnected"
            SessionState.RECOVERING -> "reconnecting"
            SessionState.ERROR -> "error"
        }
    }

    private fun appendLog(line: String) {
        val highFrequency =
            line.startsWith("ANDROID_RTP_PACKET") || line.startsWith("ANDROID_JITTER_UNDERRUN")
        val session = sessionManager.current()
        val enriched = "sessionId=${session.sessionId} state=${session.state.name} $line"
        val suppressUiLogDuringPlayback =
            _ui.value.connectionStatus == "connected" ||
                _ui.value.connectionStatus == "reconnecting" ||
                _ui.value.playbackState == "playing" ||
                _ui.value.playbackState == "optimizing"
        if (!highFrequency && !suppressUiLogDuringPlayback && !reconnectUiQuietMode) {
            val logs = listOf(enriched) + _ui.value.logs
            _ui.value = _ui.value.copy(logs = logs.take(20))
        }
        AppLogger.log(enriched)
    }

    private fun beginReconnectUiQuietWindow() {
        reconnectUiQuietMode = true
        reconnectUiQuietEpoch.incrementAndGet()
    }

    private fun endReconnectUiQuietWindowSoon(delayMs: Long) {
        val epoch = reconnectUiQuietEpoch.get()
        scope.launch {
            delay(delayMs)
            if (reconnectUiQuietEpoch.get() == epoch) {
                reconnectUiQuietMode = false
            }
        }
    }

    private fun scheduleDiscoveryRestart(delayMs: Long = 2000L) {
        cancelPendingDiscoveryRestart("reschedule")
        CrashDiagnostics.recordEvent(
            category = "connect",
            action = "discovery_restart_scheduled",
            stateBefore = sessionManager.current().state.name,
            extra = "delayMs=$delayMs"
        )
        pendingDiscoveryRestartJob = scope.launch {
            delay(delayMs)
            pendingDiscoveryRestartJob = null
            val session = sessionManager.current()
            if (session.state != SessionState.IDLE || _ui.value.isConnectionBusy) {
                CrashDiagnostics.recordEvent(
                    category = "connect",
                    action = "discovery_restart_skip",
                    sessionId = session.sessionId,
                    sessionToken = session.sessionToken,
                    stateBefore = session.state.name,
                    extra = "busy=${_ui.value.isConnectionBusy}"
                )
                return@launch
            }
            CrashDiagnostics.recordEvent(
                category = "connect",
                action = "discovery_restart_fire",
                sessionId = session.sessionId,
                sessionToken = session.sessionToken,
                stateBefore = session.state.name
            )
            startDiscovery()
        }
    }

    private fun cancelPendingDiscoveryRestart(reason: String) {
        val job = pendingDiscoveryRestartJob ?: return
        pendingDiscoveryRestartJob = null
        CrashDiagnostics.recordEvent(
            category = "connect",
            action = "discovery_restart_cancel",
            stateBefore = sessionManager.current().state.name,
            extra = reason
        )
        job.cancel()
    }

    private fun getLocalIpv4Address(): String {
        val ifaces = NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty()
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val addrs = Collections.list(iface.inetAddresses)
            val v4 =
                addrs.firstOrNull { addr ->
                    !addr.isLoopbackAddress && addr.hostAddress?.contains(":") == false
                }
            if (v4 != null) return v4.hostAddress ?: "127.0.0.1"
        }
        return "127.0.0.1"
    }
}

object StreamRuntime {
    @Volatile
    private var instance: StreamCoordinator? = null

    fun get(context: Context): StreamCoordinator {
        return instance ?: synchronized(this) {
            instance ?: StreamCoordinator(context.applicationContext).also { instance = it }
        }
    }
}
