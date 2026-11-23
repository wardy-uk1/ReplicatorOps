package dev.vantara.replicatorops.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A printer found on the local network. [address] is what goes in the address box. */
data class FoundPrinter(val address: String, val name: String, val needsKey: Boolean = false)

/**
 * Sensor sweep: finds Klipper/Moonraker printers on the phone's Wi-Fi.
 * 1. SSDP, with QIDI's own search target (what QIDI Maker uses).
 * 2. A sweep of the local /24 for Moonraker on port 7125, for other printers or
 *    when multicast is blocked.
 * Every hit is checked with Moonraker's /printer/info before it's listed.
 */
class SensorSweep(private val context: Context, http: OkHttpClient) {

    private val probe = http.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sweep(onFound: (FoundPrinter) -> Unit): List<FoundPrinter> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, FoundPrinter>()
        val tried = ConcurrentHashMap.newKeySet<String>()
        suspend fun check(ip: String) {
            if (!tried.add(ip)) return
            verify(ip)?.let { p -> if (found.putIfAbsent(ip, p) == null) onFound(p) }
        }
        coroutineScope {
            launch { ssdp().forEach { ip -> launch { check(ip) } } }
            launch {
                val gate = Semaphore(48)
                subnetHosts().map { ip ->
                    async { gate.withPermit { if (portOpen(ip, 7125)) check(ip) } }
                }.awaitAll()
            }
        }
        found.values.sortedBy { it.name.lowercase() }
    }

    /** M-SEARCH for QIDI printers; returns the addresses that answered. */
    private fun ssdp(): Set<String> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("replicatorops-ssdp")?.apply { setReferenceCounted(false) }
        val hosts = HashSet<String>()
        runCatching {
            lock?.acquire()
            DatagramSocket().use { socket ->
                socket.soTimeout = 700
                val group = InetAddress.getByName("239.255.255.250")
                val targets = listOf(QIDI_TARGET, "ssdp:all")
                repeat(2) {
                    for (st in targets) {
                        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                        val bytes = msg.toByteArray()
                        socket.send(DatagramPacket(bytes, bytes.size, group, 1900))
                    }
                }
                val buf = ByteArray(2048)
                val until = System.currentTimeMillis() + 3500
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length)
                    // Only follow up answers that look like a QIDI or a Klipper machine.
                    if (QIDI_TARGET in text || "qidi" in text.lowercase() || "klipper" in text.lowercase() || "moonraker" in text.lowercase()) {
                        packet.address?.hostAddress?.let { hosts += it }
                    }
                }
            }
        }
        runCatching { lock?.release() }
        return hosts
    }

    /** Every other address in the phone's Wi-Fi /24 (bigger networks are trimmed to the /24). */
    private fun subnetHosts(): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val net = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        } ?: return emptyList()
        val own = cm.getLinkProperties(net)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?: return emptyList()
        if (own.prefixLength < 16) return emptyList()
        val b = own.address.address
        val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
        val me = b[3].toInt() and 255
        return (1..254).filter { it != me }.map { prefix + it }
    }

    private fun portOpen(ip: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, port), 350); true }
    }.getOrDefault(false)

    /** Confirms Moonraker answers, and gets the printer's host name. */
    private fun verify(ip: String): FoundPrinter? {
        for (port in listOf(7125, 80)) {
            val url = "http://$ip${if (port == 80) "" else ":$port"}/printer/info"
            val result = runCatching {
                probe.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    when (r.code) {
                        401, 403 -> FoundPrinter(addressFor(ip, port), "Printer at $ip", needsKey = true)
                        in 200..299 -> {
                            val body = r.body?.string().orEmpty()
                            val res = runCatching { json.parseToJsonElement(body).jsonObject["result"] as? JsonObject }.getOrNull()
                            val name = (res?.get("hostname") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                            if (res == null) null else FoundPrinter(addressFor(ip, port), name ?: "Printer at $ip")
                        }
                        else -> null
                    }
                }
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun addressFor(ip: String, port: Int) = if (port == 80) ip else "$ip:$port"

    companion object {
        const val QIDI_TARGET = "urn:qiditech-com:device:3dprinter:1"
    }
}
