package com.mobilespeaker.app.diagnostics

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineExceptionHandler
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.exitProcess

data class DiagnosticEvent(
    val ts: String,
    val category: String,
    val action: String,
    val sessionId: String,
    val sessionToken: Int,
    val stateBefore: String,
    val stateAfter: String,
    val extra: String
)

data class DiagnosticStateSnapshot(
    val currentSessionId: String = "session-idle",
    val currentSessionToken: Int = 0,
    val connectionState: String = "idle",
    val playbackState: String = "stopped",
    val recoveryState: String = "idle",
    val currentDeviceIp: String = "-",
    val currentSocketState: String = "unknown",
    val currentAudioTrackState: String = "unknown",
    val decoderState: String = "unknown",
    val jitterBufferState: String = "unknown"
)

object CrashDiagnostics {
    private const val TAG = "MobileSpeakerCrash"
    private const val MAX_EVENTS = 200
    private val lifecycleCategories = setOf("session", "connect", "disconnect", "reconnect", "service", "ui_action")
    private val audioCategories = setOf("socket", "decoder", "audiotrack", "jitterbuffer")
    private val recoveryCategories = setOf("recovery")
    private val lock = Any()
    private val events = ArrayDeque<DiagnosticEvent>(MAX_EVENTS)

    @Volatile
    private var crashDir: File? = null

    @Volatile
    private var stateProvider: (() -> DiagnosticStateSnapshot)? = null

    @Volatile
    private var installed = false

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (crashDir == null) {
                crashDir = File(context.filesDir, "crash_logs").apply { mkdirs() }
            }
        }
        installHandlers()
        recordEvent(category = "service", action = "app_init")
    }

    fun registerStateProvider(provider: () -> DiagnosticStateSnapshot) {
        stateProvider = provider
    }

    fun coroutineHandler(label: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            writeCrash(
                threadName = label,
                throwable = throwable,
                source = "coroutine"
            )
        }
    }

    fun captureHandledException(source: String, throwable: Throwable) {
        recordEvent(
            category = "service",
            action = "handled_exception",
            extra = "source=$source class=${throwable::class.java.name} message=${throwable.message.orEmpty()}"
        )
        writeJson("latest_handled_exception.json", buildCrashJson(Thread.currentThread().name, throwable, source))
    }

    fun recordEvent(
        category: String,
        action: String,
        sessionId: String = safeSnapshot().currentSessionId,
        sessionToken: Int = safeSnapshot().currentSessionToken,
        stateBefore: String = "",
        stateAfter: String = "",
        extra: String = ""
    ) {
        val event = DiagnosticEvent(
            ts = now(),
            category = category,
            action = action,
            sessionId = sessionId,
            sessionToken = sessionToken,
            stateBefore = stateBefore,
            stateAfter = stateAfter,
            extra = extra
        )
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) {
                events.removeFirst()
            }
            events.addLast(event)
            persistEventsLocked()
        }
        Log.d(TAG, "${event.ts} | ${event.category} | ${event.action} | ${event.extra}")
    }

    fun latestEvents(categorySet: Set<String>, limit: Int = 20): JSONArray {
        val filtered =
            synchronized(lock) {
                events.filter { it.category in categorySet }.takeLast(limit)
            }
        return JSONArray(filtered.map { it.toJson() })
    }

    private fun installHandlers() {
        if (installed) return
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(
                threadName = thread.name,
                throwable = throwable,
                source = "uncaught"
            )
            previousHandler?.uncaughtException(thread, throwable) ?: run {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
        installed = true
    }

    private fun writeCrash(threadName: String, throwable: Throwable, source: String) {
        writeJson("latest_crash.json", buildCrashJson(threadName, throwable, source))
    }

    private fun buildCrashJson(threadName: String, throwable: Throwable, source: String): JSONObject {
        val snapshot = safeSnapshot()
        return JSONObject()
            .put("timestamp", now())
            .put("threadName", threadName)
            .put("source", source)
            .put("exceptionClass", throwable::class.java.name)
            .put("message", throwable.message.orEmpty())
            .put("fullStacktrace", throwable.stackTraceToString())
            .put("currentSessionId", snapshot.currentSessionId)
            .put("currentSessionToken", snapshot.currentSessionToken)
            .put("connectionState", snapshot.connectionState)
            .put("playbackState", snapshot.playbackState)
            .put("recoveryState", snapshot.recoveryState)
            .put("currentDeviceIp", snapshot.currentDeviceIp)
            .put("currentSocketState", snapshot.currentSocketState)
            .put("currentAudioTrackState", snapshot.currentAudioTrackState)
            .put("decoderState", snapshot.decoderState)
            .put("jitterBufferState", snapshot.jitterBufferState)
            .put("last20LifecycleEvents", latestEvents(lifecycleCategories))
            .put("last20AudioEvents", latestEvents(audioCategories))
            .put("last20RecoveryEvents", latestEvents(recoveryCategories))
    }

    private fun safeSnapshot(): DiagnosticStateSnapshot {
        return runCatching { stateProvider?.invoke() ?: DiagnosticStateSnapshot() }
            .getOrElse { DiagnosticStateSnapshot() }
    }

    private fun persistEventsLocked() {
        writeJsonLocked(
            "latest_events.json",
            JSONObject().put("events", JSONArray(events.map { it.toJson() }))
        )
    }

    private fun writeJson(fileName: String, json: JSONObject) {
        synchronized(lock) {
            writeJsonLocked(fileName, json)
        }
    }

    private fun writeJsonLocked(fileName: String, json: JSONObject) {
        runCatching {
            val target = File(checkNotNull(crashDir), fileName)
            target.writeText(json.toString(2))
        }
    }

    private fun DiagnosticEvent.toJson(): JSONObject {
        return JSONObject()
            .put("ts", ts)
            .put("category", category)
            .put("action", action)
            .put("sessionId", sessionId)
            .put("sessionToken", sessionToken)
            .put("stateBefore", stateBefore)
            .put("stateAfter", stateAfter)
            .put("extra", extra)
    }

    private fun now(): String {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
