package com.pindrop.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface

/** What a "no Wi-Fi" QR code carries: who to talk to, plus the temporary Wi-Fi name and password. */
class HotCode(val peer: Peer, val ssid: String, val pass: String, val wpa3: Boolean)

/**
 * Sending without a shared Wi-Fi.
 * The sending phone makes a temporary Wi-Fi (Android calls it a local-only hotspot) and shows a QR code.
 * The receiving phone scans it, joins that Wi-Fi, and then the normal Pindrop transfer runs over it.
 */
object Hotspot {
    // ------------------------------------------------------------ phone that SENDS

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    /** Every private address (192.168.x.x, 10.x.x.x ...) this phone has right now. */
    fun allIps(): Set<String> {
        val out = mutableSetOf<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses.toList()) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val h = a.hostAddress
                        if (h != null) out.add(h)
                    }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Starts the temporary Wi-Fi. Calls onStarted with its name and password, or onFail with a reason. */
    fun start(
        ctx: Context,
        whenStarted: (ssid: String, pass: String, wpa3: Boolean) -> Unit,
        whenFail: (String) -> Unit
    ) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    var ssid = ""
                    var pass = ""
                    var wpa3 = false
                    try {
                        if (Build.VERSION.SDK_INT >= 30) {
                            val c = r.softApConfiguration
                            ssid = c.ssid ?: ""
                            pass = c.passphrase ?: ""
                            wpa3 = c.securityType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE
                        } else {
                            val c = r.wifiConfiguration
                            ssid = (c?.SSID ?: "").trim('"')
                            pass = (c?.preSharedKey ?: "").trim('"')
                        }
                    } catch (e: Exception) {
                    }
                    if (ssid.isEmpty() || pass.isEmpty()) {
                        stop()
                        whenFail("This phone didn't give the Wi-Fi details. Try the normal way (same Wi-Fi or hotspot).")
                    } else {
                        whenStarted(ssid, pass, wpa3)
                    }
                }

                override fun onStopped() {
                    reservation = null
                }

                override fun onFailed(reason: Int) {
                    reservation = null
                    whenFail(
                        when (reason) {
                            WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
                                "Turn off your normal Hotspot and Wi-Fi sharing, then try again."
                            WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
                                "This phone doesn't allow a temporary Wi-Fi. Use the normal way instead."
                            else ->
                                "Couldn't make the temporary Wi-Fi. Turn on Location and try again."
                        }
                    )
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            whenFail("Pindrop needs the Location (or Nearby devices) permission to make a Wi-Fi.")
        } catch (e: Exception) {
            whenFail("Couldn't make the temporary Wi-Fi on this phone.")
        }
    }

    /** Turns the temporary Wi-Fi off. */
    fun stop() {
        try {
            reservation?.close()
        } catch (e: Exception) {
        }
        reservation = null
    }

    /** What goes into the QR code: the normal pairing code plus the Wi-Fi name and password. */
    fun hotUri(ctx: Context, ip: String, port: Int, ssid: String, pass: String, wpa3: Boolean): String {
        val security = if (wpa3) "wpa3" else "wpa2"
        return "pindrop://pair?h=" + ip +
            "&p=" + port +
            "&k=" + Secure.b64(Prefs.getKey(ctx)) +
            "&n=" + Uri.encode(Peers.myName().take(16)) +
            "&s=" + Uri.encode(ssid) +
            "&w=" + Uri.encode(pass) +
            "&t=" + security
    }

    // ------------------------------------------------------------ phone that RECEIVES

    private var cm: ConnectivityManager? = null
    private var cb: ConnectivityManager.NetworkCallback? = null

    /** Reads a scanned code. Returns null if it is not a "no Wi-Fi" Pindrop code. */
    fun parseHot(raw: String): HotCode? {
        val peer = Peers.parse(raw) ?: return null
        return try {
            val u = Uri.parse(raw.trim())
            val s = u.getQueryParameter("s")
            val w = u.getQueryParameter("w")
            if (s == null || w == null) null else HotCode(peer, s, w, u.getQueryParameter("t") == "wpa3")
        } catch (e: Exception) {
            null
        }
    }

    /** Joins the other phone's temporary Wi-Fi. Android shows one "Connect?" box. */
    fun join(ctx: Context, code: HotCode, whenReady: () -> Unit, whenFail: (String) -> Unit, whenLost: () -> Unit) {
        leave()
        val c = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val builder = WifiNetworkSpecifier.Builder().setSsid(code.ssid)
        if (code.wpa3) builder.setWpa3Passphrase(code.pass) else builder.setWpa2Passphrase(code.pass)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(builder.build())
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // From now on this app talks over the other phone's Wi-Fi.
                c.bindProcessToNetwork(network)
                whenReady()
            }

            override fun onUnavailable() {
                whenFail("Couldn't join the other phone's Wi-Fi.")
            }

            override fun onLost(network: Network) {
                c.bindProcessToNetwork(null)
                whenLost()
            }
        }
        cm = c
        cb = callback
        try {
            c.requestNetwork(request, callback, 30000)
        } catch (e: Exception) {
            whenFail("Couldn't join the other phone's Wi-Fi.")
        }
    }

    /** Leaves the temporary Wi-Fi and goes back to normal. */
    fun leave() {
        try {
            cm?.bindProcessToNetwork(null)
        } catch (e: Exception) {
        }
        try {
            val callback = cb
            if (callback != null) cm?.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
        }
        cb = null
    }
}
