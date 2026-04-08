package com.mobilespeaker.app.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.mobilespeaker.app.core.Protocol
import com.mobilespeaker.app.util.AppLogger
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

class AndroidPresenceAdvertiser(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serviceInfo: NsdServiceInfo? = null

    fun start() {
        if (registrationListener != null) return
        val ip = localIpv4()
        val serviceName = "mobile-speaker-android-${UUID.randomUUID().toString().take(8)}"

        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = Protocol.SERVICE_TYPE
            this.port = Protocol.ANDROID_DISCOVERY_PORT
            setAttribute("role", "android")
            setAttribute("app", "mobile-speaker")
            setAttribute("version", "0.1.0")
            setAttribute("rx_port", Protocol.ANDROID_DISCOVERY_PORT.toString())
            setAttribute("ip_hint", ip.hostAddress ?: "")
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                AppLogger.log("ANDROID_MDNS_ADVERTISE_FAIL code=$errorCode")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                AppLogger.log("ANDROID_MDNS_UNREGISTER_FAIL code=$errorCode")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                AppLogger.log("ANDROID_MDNS_ADVERTISE_ON name=${serviceInfo.serviceName}")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                AppLogger.log("ANDROID_MDNS_ADVERTISE_OFF name=${serviceInfo.serviceName}")
            }
        }

        serviceInfo = info
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        val listener = registrationListener ?: return
        runCatching { nsdManager.unregisterService(listener) }
        registrationListener = null
        serviceInfo = null
    }

    private fun localIpv4(): InetAddress {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }.orEmpty()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            val address = Collections.list(iface.inetAddresses)
                .firstOrNull { !it.isLoopbackAddress && (it.hostAddress?.contains(":") == false) }
            if (address != null) return address
        }
        return InetAddress.getByName("127.0.0.1")
    }
}
