package com.pindrop.app

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** A phone we are paired with. [key] is its secret, [host] and [port] say where to find it. */
data class Peer(val name: String, val host: String, val port: Int, val key: String) {
    val addr: String get() = "$host:$port"
}

object Peers {
    fun myName(): String = (Build.MODEL ?: "Android phone").take(30)

    fun list(c: Context): List<Peer> {
        return try {
            val arr = JSONArray(Prefs.getPeersJson(c))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Peer(o.getString("n"), o.getString("h"), o.getInt("p"), o.getString("k"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(c: Context, peers: List<Peer>) {
        val arr = JSONArray()
        peers.forEach { arr.put(JSONObject().put("n", it.name).put("h", it.host).put("p", it.port).put("k", it.key)) }
        Prefs.setPeersJson(c, arr.toString())
    }

    /** Adds a phone, or refreshes its address if we already know it. */
    fun add(c: Context, p: Peer) = save(c, list(c).filter { it.key != p.key } + p)

    fun remove(c: Context, p: Peer) = save(c, list(c).filter { it.key != p.key })

    /** What goes into this phone's QR code. */
    fun pairingUri(c: Context, ip: String, port: Int): String =
        "pindrop://pair?h=$ip&p=$port&k=${Secure.b64(Prefs.getKey(c))}&n=${Uri.encode(myName())}"

    /** Reads a scanned or pasted code. Returns null if it isn't a Pindrop code. */
    fun parse(raw: String): Peer? {
        return try {
            val u = Uri.parse(raw.trim())
            if (u.scheme != "pindrop") return null
            val h = u.getQueryParameter("h") ?: return null
            val p = u.getQueryParameter("p")?.toIntOrNull() ?: return null
            val k = u.getQueryParameter("k") ?: return null
            if (Secure.unb64(k).size != 32) return null
            Peer(u.getQueryParameter("n") ?: "Phone", h, p, k)
        } catch (e: Exception) {
            null
        }
    }

    /** This phone's address on the shared Wi-Fi or hotspot (not mobile data). */
    fun localIp(): String? {
        var best: String? = null
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = ni.name.lowercase()
                val ok = n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("eth")
                if (!ok) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        // when this phone is the hotspot, its own hotspot address wins
                        if (best == null || n.startsWith("ap") || n.startsWith("swlan")) best = a.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
        }
        return best
    }
}

/** Glue between Android and the plain-Java [PeerServer]. Runs while the Pindrop screen is open. */
object PhoneServer {
    private const val PORT = 8766
    private var app: Context? = null

    /** Called when a file arrives ("file") or a phone pairs with us ("pair"). May run on any thread. */
    @Volatile
    var listener: ((kind: String, name: String, size: Long) -> Unit)? = null

    /** True while this phone runs its own temporary Wi-Fi. */
    @Volatile
    var hotMode = false

    /** The phone that last joined during hotMode. */
    @Volatile
    var lastHot: Peer? = null

    private val server = PeerServer(object : PeerServer.Host {
        override fun key(): ByteArray = Prefs.getKey(app!!)

        override fun deviceName(): String = Peers.myName()

        override fun openFile(cleanName: String): PeerServer.Sink {
            val sink = openSink(app!!, cleanName)
            return object : PeerServer.Sink {
                override fun stream() = sink.out
                override fun finish(ok: Boolean) = sink.finish(ok)
            }
        }

        override fun received(name: String, size: Long) {
            listener?.invoke("file", name, size)
        }

        override fun paired(name: String, host: String, port: Int, keyB64: String) {
            val p = Peer(name, host, port, keyB64)
            if (hotMode) {
                // joined our temporary Wi-Fi: use it now, don't save it
                lastHot = p
                listener?.invoke("hot", name, 0)
            } else {
                Peers.add(app!!, p)
                listener?.invoke("pair", name, 0)
            }
        }
    })

    val port: Int get() = server.port

    fun start(ctx: Context) {
        app = ctx.applicationContext
        server.start(PORT)
    }

    fun stop() = server.stop()
}
