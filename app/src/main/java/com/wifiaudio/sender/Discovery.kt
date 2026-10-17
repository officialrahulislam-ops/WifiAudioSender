package com.wifiaudio.sender

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * UDP discovery while the app is on screen:
 *  - listens for PC beacons (to fill the receiver list) and PC invites,
 *  - broadcasts its own beacon so the PC can list this phone.
 */
class Discovery(
    private val context: Context,
    private val deviceName: String,
    private val isBusy: () -> Boolean,
    private val onReceivers: (List<ReceiverInfo>) -> Unit,
    private val onInvite: (ReceiverInfo, String) -> Unit
) {
    data class ReceiverInfo(val name: String, val ip: String, val port: Int)

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val seen = LinkedHashMap<String, Pair<ReceiverInfo, Long>>()
    private var lastKey = ""

    fun start() {
        if (running) return
        running = true
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("wifi-audio").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (_: Exception) { }
        Thread({ listen() }, "discovery-rx").start()
        Thread({ beacon() }, "discovery-tx").start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Exception) { }
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) { }
    }

    private fun listen() {
        try {
            val s = DatagramSocket(null).apply {
                reuseAddress = true; broadcast = true
                bind(InetSocketAddress(Protocol.DISCOVERY_PORT)); soTimeout = 1000
            }
            socket = s
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    handle(String(p.data, 0, p.length), p.address.hostAddress ?: continue)
                } catch (_: SocketTimeoutException) { }
                prune()
            }
        } catch (_: Exception) {
            // Port busy or socket closed: manual IP entry still works.
        }
    }

    private fun handle(text: String, ip: String) {
        val j = try { JSONObject(text) } catch (_: Exception) { return }
        if (j.optString("app") != Protocol.APP_ID || j.optString("role") != "receiver") return
        val info = ReceiverInfo(j.optString("name", ip), ip, j.optInt("port", Protocol.DEFAULT_TCP_PORT))
        if (j.optString("type") == "invite") {
            val code = j.optString("code")
            main.post { onInvite(info, code) }
        } else {
            synchronized(seen) { seen[ip] = info to System.currentTimeMillis() }
        }
    }

    private fun prune() {
        val list: List<ReceiverInfo>
        synchronized(seen) {
            val now = System.currentTimeMillis()
            seen.entries.removeAll { now - it.value.second > 6000 }
            list = seen.values.map { it.first }.sortedBy { it.name }
        }
        val key = list.joinToString("|") { it.ip + it.name }
        if (key != lastKey) { lastKey = key; main.post { onReceivers(list) } }
    }

    private fun beacon() {
        while (running) {
            try {
                val s = socket
                if (s != null) {
                    val json = JSONObject().put("app", Protocol.APP_ID).put("v", Protocol.VERSION)
                        .put("role", "sender").put("name", deviceName).put("busy", isBusy())
                    val bytes = json.toString().toByteArray()
                    for (addr in broadcastTargets()) {
                        try { s.send(DatagramPacket(bytes, bytes.size, addr, Protocol.DISCOVERY_PORT)) }
                        catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { }
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val out = mutableListOf(InetAddress.getByName("255.255.255.255"))
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.linkAddresses?.forEach { la ->
                val a = la.address
                if (a is Inet4Address && la.prefixLength in 1..30) {
                    val ip = ByteBuffer.wrap(a.address).int
                    val mask = -1 shl (32 - la.prefixLength)
                    val b = ip or mask.inv()
                    out.add(InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(b).array()))
                }
            }
        } catch (_: Exception) { }
        return out
    }
}
