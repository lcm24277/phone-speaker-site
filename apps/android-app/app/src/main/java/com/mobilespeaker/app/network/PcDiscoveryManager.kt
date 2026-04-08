package com.mobilespeaker.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.mobilespeaker.app.core.Protocol
import com.mobilespeaker.app.diagnostics.CrashDiagnostics
import com.mobilespeaker.app.model.PcEndpoint
import com.mobilespeaker.app.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

class PcDiscoveryManager(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val found = ConcurrentHashMap<String, PcEndpoint>()
    private val _pcs = MutableStateFlow<List<PcEndpoint>>(emptyList())
    val pcs: StateFlow<List<PcEndpoint>> = _pcs

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val beaconLock = Any()
    @Volatile
    private var beaconThread: Thread? = null
    @Volatile
    private var beaconSocket: DatagramSocket? = null

    fun start() {
        if (discoveryListener != null) return
        acquireMulticastLock()
        startBeaconListener()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                AppLogger.log("ANDROID_MDNS_DISCOVERY_START_FAIL code=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                AppLogger.log("ANDROID_MDNS_DISCOVERY_STOP_FAIL code=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String?) {
                AppLogger.log("ANDROID_MDNS_DISCOVERY_ON serviceType=$serviceType")
            }

            override fun onDiscoveryStopped(serviceType: String?) {
                AppLogger.log("ANDROID_MDNS_DISCOVERY_OFF serviceType=$serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.startsWith("_mobile-speaker._udp")) return
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        AppLogger.log("ANDROID_MDNS_RESOLVE_FAIL code=$errorCode")
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        val attrs = resolved.attributes
                        val role = attrs["role"]?.toString(Charsets.UTF_8) ?: "unknown"
                        if (role != "pc") return
                        upsert(
                            PcEndpoint(
                                id = "${resolved.serviceName}-$host",
                                name = resolved.serviceName ?: "PC",
                                ip = host,
                                status = "available"
                            ),
                            "mdns"
                        )
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                found.entries.removeIf { it.key.startsWith(serviceInfo.serviceName ?: "") }
                publish()
            }
        }

        discoveryListener = listener
        nsdManager.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        val listener = discoveryListener
        if (listener != null) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            discoveryListener = null
        }
        stopBeaconListener()
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val lock = wifiManager.createMulticastLock("mobile-speaker-mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = lock
        AppLogger.log("ANDROID_MULTICAST_LOCK_ON")
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.takeIf { it.isHeld }?.release()
        }
        multicastLock = null
        AppLogger.log("ANDROID_MULTICAST_LOCK_OFF")
    }

    private fun startBeaconListener() {
        synchronized(beaconLock) {
            if (beaconThread?.isAlive == true) return
        }
        val thread = Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1200
                    bind(InetSocketAddress(Protocol.DISCOVERY_BEACON_PORT))
                }
                beaconSocket = socket
                CrashDiagnostics.recordEvent(
                    category = "socket",
                    action = "pc_discovery_bind_ok",
                    extra = "port=${Protocol.DISCOVERY_BEACON_PORT} thread=${Thread.currentThread().name}"
                )
                AppLogger.log("ANDROID_DISCOVERY_BEACON_LISTEN_ON port=${Protocol.DISCOVERY_BEACON_PORT}")
                val buffer = ByteArray(2048)
                while (!Thread.currentThread().isInterrupted && socket?.isClosed == false) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val body = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = JSONObject(body)
                        if (json.optString("role") != "pc") continue
                        val ip = json.optString("ip").ifBlank { packet.address.hostAddress ?: "" }
                        if (ip.isBlank()) continue
                        upsert(
                            PcEndpoint(
                                id = "beacon-${json.optString("id", ip)}",
                                name = json.optString("name", "PC"),
                                ip = ip,
                                status = "available"
                            ),
                            "beacon"
                        )
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (e: Exception) {
                        AppLogger.log("ANDROID_DISCOVERY_BEACON_PACKET_FAIL error=${e.message}")
                    }
                }
            } catch (e: SocketException) {
                if (Thread.currentThread().isInterrupted || socket?.isClosed == true) {
                    AppLogger.log("ANDROID_DISCOVERY_BEACON_LISTEN_STOPPED")
                } else {
                    CrashDiagnostics.recordEvent(
                        category = "socket",
                        action = "pc_discovery_bind_fail",
                        extra = e.message.orEmpty()
                    )
                    AppLogger.log("ANDROID_DISCOVERY_BEACON_LISTEN_FAIL error=${e.message}")
                }
            } finally {
                runCatching { socket?.close() }
                synchronized(beaconLock) {
                    if (beaconSocket === socket) {
                        beaconSocket = null
                    }
                    if (beaconThread === Thread.currentThread()) {
                        beaconThread = null
                    }
                }
                CrashDiagnostics.recordEvent(category = "socket", action = "pc_discovery_listener_off")
                AppLogger.log("ANDROID_DISCOVERY_BEACON_LISTEN_OFF")
            }
        }
        thread.isDaemon = true
        thread.name = "PcDiscoveryBeacon"
        synchronized(beaconLock) {
            beaconThread = thread
            CrashDiagnostics.recordEvent(
                category = "socket",
                action = "pc_discovery_bind_start",
                extra = "port=${Protocol.DISCOVERY_BEACON_PORT}"
            )
            thread.start()
        }
    }

    private fun stopBeaconListener() {
        val thread: Thread?
        val socket: DatagramSocket?
        synchronized(beaconLock) {
            thread = beaconThread
            socket = beaconSocket
            beaconSocket = null
        }
        if (thread == null) return
        CrashDiagnostics.recordEvent(
            category = "socket",
            action = "pc_discovery_stop_requested",
            extra = "thread=${thread.name}"
        )
        runCatching { socket?.close() }
        thread.interrupt()
        runCatching { thread.join(1500) }
        synchronized(beaconLock) {
            if (!thread.isAlive && beaconThread === thread) {
                beaconThread = null
            }
        }
    }

    private fun upsert(endpoint: PcEndpoint, source: String) {
        val existing = found[endpoint.id]
        if (existing?.ip == endpoint.ip && existing.name == endpoint.name) return
        found[endpoint.id] = endpoint
        publish()
        AppLogger.log("ANDROID_DISCOVERY_FOUND source=$source name=${endpoint.name} ip=${endpoint.ip}")
    }

    private fun publish() {
        val next = found.values.sortedWith(compareBy({ it.name }, { it.ip }))
        if (_pcs.value != next) {
            _pcs.value = next
        }
    }
}
