package com.mobilespeaker.app.network

import com.mobilespeaker.app.core.Protocol
import com.mobilespeaker.app.model.StatusResponse
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class PcApiClient {
    fun getStatus(ip: String): StatusResponse {
        val conn = open(ip, "/api/status", "GET")
        conn.connect()
        conn.inputStream.use { input ->
            val raw = BufferedReader(InputStreamReader(input)).readText()
            val json = JSONObject(raw)
            return StatusResponse(
                connectionStatus = json.optString("connectionStatus", "idle"),
                deviceName = json.optString("deviceName", ""),
                deviceIp = json.optString("deviceIp", ""),
                networkType = json.optString("networkType", "unknown"),
                latencyMs = json.optInt("latencyMs", 0),
                volumePercent = json.optInt("volumePercent", 75),
                isMuted = json.optBoolean("isMuted", false),
                isLocalMonitorEnabled = json.optBoolean("isLocalMonitorEnabled", true),
                virtualAudioDeviceName = json.optString("virtualAudioDeviceName", "")
            )
        }
    }

    fun post(ip: String, path: String, body: JSONObject? = null) {
        val conn = open(ip, path, "POST")
        conn.doOutput = body != null
        if (body != null) {
            conn.outputStream.use { out ->
                out.write(body.toString().toByteArray(Charsets.UTF_8))
            }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $path failed: $code")
        }
    }

    private fun open(ip: String, path: String, method: String): HttpURLConnection {
        val url = URL("http://$ip:${Protocol.HTTP_PORT}$path")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2000
            readTimeout = 2000
            setRequestProperty("Content-Type", "application/json")
        }
    }
}
