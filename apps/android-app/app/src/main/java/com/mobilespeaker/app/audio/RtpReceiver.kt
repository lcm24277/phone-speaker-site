package com.mobilespeaker.app.audio

import com.mobilespeaker.app.core.Protocol
import com.mobilespeaker.app.diagnostics.CrashDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class RtpReceiver {
    @Volatile
    private var socketState: String = "idle"

    @Volatile
    private var lastBoundPort: Int = 0

    @Volatile
    private var receiverThreadName: String = "-"

    fun receiveFlow(port: Int = Protocol.RTP_PORT): Flow<RtpPacket> = callbackFlow {
        socketState = "binding"
        CrashDiagnostics.recordEvent(category = "socket", action = "bind_start", extra = "port=$port")
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        socketState = "bound"
        lastBoundPort = port
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        val thread = Thread {
            while (!socket.isClosed) {
                runCatching {
                    socket.receive(packet)
                    val parsed = RtpPacket.parse(packet.data, packet.length)
                    if (parsed != null) trySend(parsed)
                }.onFailure {
                    if (!socket.isClosed) {
                        socketState = "error"
                        CrashDiagnostics.recordEvent(
                            category = "socket",
                            action = "receive_fail",
                            extra = "port=$port message=${it.message.orEmpty()}"
                        )
                    }
                }
            }
            socketState = "closed"
        }
        thread.name = "rtp-receiver-$port"
        thread.isDaemon = true
        receiverThreadName = thread.name
        thread.start()
        CrashDiagnostics.recordEvent(category = "socket", action = "bind_ok", extra = "port=$port thread=${thread.name}")
        awaitClose {
            CrashDiagnostics.recordEvent(category = "socket", action = "close", extra = "port=$port")
            runCatching { socket.close() }
            socketState = "closed"
        }
    }.flowOn(Dispatchers.IO)

    fun snapshot(): String {
        return "state=$socketState port=$lastBoundPort thread=$receiverThreadName"
    }
}
