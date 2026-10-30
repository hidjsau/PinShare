package com.pinshare.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface

/** This phone's Wi-Fi / hotspot IPv4 address, or null. */
fun localIp(): String? = try {
    val ifs = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
    val wifi = ifs.filter {
        it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")
    }
    (wifi + ifs).flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
} catch (e: Exception) {
    null
}

/**
 * Advertises this phone on the local network and finds other phones
 * running the app (NSD / mDNS). No IP typing needed on the same Wi-Fi.
 */
class Discovery(ctx: Context, private val onChange: (List<Peer>) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val type = "_pinshare._tcp."

    private val peers = LinkedHashMap<String, Peer>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var myName: String? = null
    private var reg: NsdManager.RegistrationListener? = null
    private var disc: NsdManager.DiscoveryListener? = null

    fun start(port: Int, name: String) {
        val info = NsdServiceInfo()
        info.serviceName = name
        info.serviceType = type
        info.setPort(port)

        val r = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { myName = i.serviceName }
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        reg = r
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, r)

        val d = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName != myName) enqueue(i)
            }
            override fun onServiceLost(i: NsdServiceInfo) {
                synchronized(this@Discovery) { peers.remove(i.serviceName) }
                publish()
            }
        }
        disc = d
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, d)
    }

    fun stop() {
        try { reg?.let { nsd.unregisterService(it) } } catch (e: Exception) {}
        try { disc?.let { nsd.stopServiceDiscovery(it) } } catch (e: Exception) {}
    }

    private fun enqueue(i: NsdServiceInfo) {
        synchronized(this) { pending.addLast(i) }
        next()
    }

    // NSD can only resolve one service at a time, so we queue them.
    private fun next() {
        var item: NsdServiceInfo? = null
        synchronized(this) {
            if (!resolving) {
                item = pending.removeFirstOrNull()
                if (item != null) resolving = true
            }
        }
        val i = item ?: return

        @Suppress("DEPRECATION")
        nsd.resolveService(i, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, e: Int) { done() }
            override fun onServiceResolved(s: NsdServiceInfo) {
                val h = s.host
                if (h is Inet4Address && h.hostAddress != localIp() && s.serviceName != myName) {
                    synchronized(this@Discovery) {
                        peers[s.serviceName] = Peer(
                            s.serviceName.removePrefix("PinShare-"),
                            h.hostAddress ?: "",
                            s.port
                        )
                    }
                    publish()
                }
                done()
            }
        })
    }

    private fun done() {
        synchronized(this) { resolving = false }
        next()
    }

    private fun publish() {
        val list = synchronized(this) { peers.values.toList() }
        main.post { onChange(list) }
    }
}
