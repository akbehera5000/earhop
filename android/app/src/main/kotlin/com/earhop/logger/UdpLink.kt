package com.earhop.logger

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** UDP broadcast sender and listener. Holds a multicast lock so Wi-Fi delivers broadcasts. */
class UdpLink(
    private val ctx: Context,
    private val port: Int,
    private val secret: String,
    private val selfName: String,
    private val onStatus: (Status) -> Unit,
) {
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var running = false
    @Volatile var lastHeard = 0L
    private val unicast = java.util.concurrent.CopyOnWriteArrayList<InetAddress>()
    /** Addresses learned from packets that passed the signature check, so peers are reached even when broadcasts are dropped. */
    private val learned = java.util.concurrent.ConcurrentHashMap<String, MutableList<InetAddress>>()
    private val lastFrom = java.util.concurrent.ConcurrentHashMap<String, Triple<InetAddress, Int, Long>>()
    private val duplicateAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** This device's own addresses, to tell its own echoed broadcasts from another device using the same name. */
    private fun ownAddresses(): Set<InetAddress> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
    } catch (e: Exception) { emptySet() }

    private fun reportDuplicate(dev: String) {
        val now = System.currentTimeMillis()
        if (now - (duplicateAt[dev] ?: 0L) < 60000) return
        duplicateAt[dev] = now
        EhState.duplicates[dev] = now
        EhLog.w("link: two devices are both named $dev. Give each device its own name (mac, phone, tab)")
    }
    private val rejectedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var nsd: NsdManager? = null
    private val resolveQueue = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val regListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(i: NsdServiceInfo) { EhLog.w("discovery: published ${i.serviceName}") }
        override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { EhLog.w("discovery: publish failed $e") }
        override fun onServiceUnregistered(i: NsdServiceInfo) {}
        override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
    }
    private val discListener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(i: NsdServiceInfo) {
            if (i.serviceName == selfName) return
            synchronized(resolveQueue) { resolveQueue.add(i) }
            resolveNext()
        }
        override fun onServiceLost(i: NsdServiceInfo) {}
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, e: Int) { EhLog.w("discovery: start failed $e") }
        override fun onStopDiscoveryFailed(t: String, e: Int) {}
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(resolveQueue) {
            if (resolving || resolveQueue.isEmpty()) return
            resolving = true
            resolveQueue.poll()
        }
        nsd?.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) { done() }
            override fun onServiceResolved(i: NsdServiceInfo) {
                val h = i.host
                if (h != null && h !is java.net.Inet6Address && !unicast.contains(h)) {
                    unicast.add(h)
                    EhLog.w("discovery: found ${i.serviceName} at ${h.hostAddress}")
                }
                done()
            }
            private fun done() { synchronized(resolveQueue) { resolving = false }; resolveNext() }
        })
    }

    private fun startDiscovery() {
        val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = m
        val info = NsdServiceInfo().apply { serviceName = selfName; serviceType = "_earhop._udp."; port = this@UdpLink.port }
        m.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
        m.discoverServices("_earhop._udp.", NsdManager.PROTOCOL_DNS_SD, discListener)
    }

    fun start() {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wm.createMulticastLock("earhop").apply { setReferenceCounted(false); acquire() }
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.broadcast = true
        s.bind(InetSocketAddress(port))
        socket = s
        running = true
        try { startDiscovery() } catch (e: Exception) { EhLog.w("discovery: unavailable ${e.message}") }
        Thread({
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    val st = Proto.decode(p.data, p.length, secret, System.currentTimeMillis())
                    if (st != null && st.dev == selfName && p.address != null && p.address !in ownAddresses()) {
                        reportDuplicate(selfName) // a signed packet with this device's own name from another device
                    } else if (st != null && st.dev != selfName) {
                        lastHeard = System.currentTimeMillis()
                        val from = p.address
                        val now = System.currentTimeMillis()
                        if (from != null) {
                            val list = learned.getOrPut(st.dev) { java.util.concurrent.CopyOnWriteArrayList() }
                            if (from !in list) { list.add(from); if (list.size > 4) list.removeAt(0); EhLog.w("link: ${st.dev} is at ${from.hostAddress}") }
                            // One device sends rising sequence numbers from every address it uses.
                            val last = lastFrom[st.dev]
                            if (last != null && last.first != from && st.seq + 3 < last.second && now - last.third < 10000) reportDuplicate(st.dev)
                            lastFrom[st.dev] = Triple(from, st.seq, now)
                        }
                        EhState.rejected.remove(st.dev)
                        onStatus(st)
                    } else if (st == null) {
                        val r = Proto.rejection(p.data, p.length, secret, System.currentTimeMillis())
                        if (r != null && r.first != selfName) {
                            val now = System.currentTimeMillis()
                            EhState.rejected[r.first] = r.second
                            if (now - (rejectedAt[r.first] ?: 0L) > 60000) {
                                rejectedAt[r.first] = now
                                EhLog.w("link: packets from ${r.first} refused: " +
                                    if (r.second == "clock") "its clock differs by more than 30 s" else "it uses a different secret, pair it again")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (running) EhLog.w("link: recv error ${e.message}")
                }
            }
        }, "earhop-recv").start()
    }

    private fun subnetBroadcast(): InetAddress? {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val d = wm.dhcpInfo ?: return null
        if (d.ipAddress == 0) return null
        val b = (d.ipAddress and d.netmask) or d.netmask.inv()
        val bytes = ByteArray(4) { i -> ((b shr (8 * i)) and 0xFF).toByte() }
        return InetAddress.getByAddress(bytes)
    }

    /** Addresses of the other devices, learned from their signed packets. */
    fun peerHosts(): List<InetAddress> = (learned.values.flatten() + unicast).distinct()

    /** The same, leaving out the devices in [skip] (for the clipboard's Send to setting). */
    fun peerHostsExcept(skip: Set<String>): List<InetAddress> {
        if (skip.isEmpty()) return peerHosts()
        val skipped = learned.filterKeys { it in skip }.values.flatten().toSet()
        return (learned.filterKeys { it !in skip }.values.flatten() + unicast.filter { it !in skipped }).distinct()
    }

    fun send(st: Status) {
        val s = socket ?: return
        val data = Proto.encode(st, secret)
        val targets = (listOfNotNull(subnetBroadcast(), InetAddress.getByName("255.255.255.255")) + unicast + learned.values.flatten()).distinct()
        for (t in targets) {
            try {
                s.send(DatagramPacket(data, data.size, t, port))
            } catch (e: Exception) {
                EhLog.w("link: send to ${t.hostAddress} failed ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try { nsd?.unregisterService(regListener); nsd?.stopServiceDiscovery(discListener) } catch (e: Exception) { }
        socket?.close()
        lock?.release()
    }
}
