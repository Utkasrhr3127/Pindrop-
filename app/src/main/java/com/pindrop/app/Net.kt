package com.pindrop.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import java.net.UnknownHostException

/** Remembers the laptop address, the laptop's access code, this phone's key and the paired phones. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("pindrop", Context.MODE_PRIVATE)

    @Volatile
    private var keyCache: ByteArray? = null

    fun getAddr(c: Context): String = sp(c).getString("addr", "") ?: ""

    fun setAddr(c: Context, value: String) {
        sp(c).edit().putString("addr", value.trim()).apply()
    }

    /** The access code the laptop shows in its window. Stored encrypted. */
    fun getCode(c: Context): String = Vault.open(sp(c).getString("code", null)) ?: ""

    fun setCode(c: Context, value: String) {
        sp(c).edit().putString("code", Vault.seal(cleanCode(value))).apply()
    }

    /** This phone's own secret. Other phones get it from the QR code. Made once, kept encrypted. */
    fun getKey(c: Context): ByteArray {
        keyCache?.let { return it }
        synchronized(this) {
            keyCache?.let { return it }
            val saved = sp(c).getString("key", null)
            val plain = Vault.open(saved)
            val decoded = try {
                if (plain != null) Secure.unb64(plain) else null
            } catch (e: Exception) {
                null
            }
            val key: ByteArray
            if (decoded != null && decoded.size == 32) {
                key = decoded
                if (saved != null && !Vault.isSealed(saved)) {
                    sp(c).edit().putString("key", Vault.seal(Secure.b64(key))).apply() // upgrade an old plain-text key
                }
            } else {
                key = Secure.newKey()
                sp(c).edit().putString("key", Vault.seal(Secure.b64(key))).apply()
            }
            keyCache = key
            return key
        }
    }

    /** Makes a brand new key. Every paired phone must scan this phone's code again afterwards. */
    fun resetKey(c: Context): ByteArray {
        synchronized(this) {
            val key = Secure.newKey()
            sp(c).edit().putString("key", Vault.seal(Secure.b64(key))).apply()
            keyCache = key
            return key
        }
    }

    fun getPeersJson(c: Context): String {
        val stored = sp(c).getString("peers2", null)
        if (stored != null) return Vault.open(stored) ?: "[]"
        val legacy = sp(c).getString("peers", null) ?: return "[]"
        setPeersJson(c, legacy) // upgrade: keep the old list, now encrypted
        sp(c).edit().remove("peers").apply()
        return legacy
    }

    fun setPeersJson(c: Context, json: String) {
        sp(c).edit().putString("peers2", Vault.seal(json)).apply()
    }

    /** Whether to keep Pindrop ready to receive while it is in the background. */
    fun getBackground(c: Context): Boolean = sp(c).getBoolean("bg", true)

    fun setBackground(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("bg", on).apply()
    }
}

/** Upper-case letters and digits only, so "k7mq-2xr9" and "K7MQ2XR9" mean the same. */
fun cleanCode(raw: String): String = raw.uppercase().filter { it.isLetterOrDigit() }.take(16)

/** Understands "10.0.0.5", "10.0.0.5:8765" and a whole link like "http://10.0.0.5:8765/?c=K7MQ2XR9". */
fun splitLaptopInput(raw: String): Pair<String, String> {
    var s = raw.trim()
    var code = ""
    val q = s.indexOf('?')
    if (q >= 0) {
        s.substring(q + 1).split('&').forEach { if (it.startsWith("c=")) code = cleanCode(it.substring(2)) }
        s = s.substring(0, q)
    }
    s = s.removePrefix("http://").removePrefix("https://").trimEnd('/')
    return Pair(s, code)
}

/** Where a file is going: the laptop (plain) or a paired phone (encrypted with its key). */
class Target(val name: String, val addr: String, val key: ByteArray?, val code: String = "")

fun laptopTarget(c: Context) = Target("Laptop", Prefs.getAddr(c), null, Prefs.getCode(c))

/** One file to send: its name, size in bytes, and a way to read it. */
class FileInfo(
    val name: String,
    val size: Long,
    val open: () -> InputStream,
    val release: () -> Unit = {}
)

/** A file the laptop is holding for the phone. */
data class OutItem(val id: String, val name: String, val size: Long, val status: String)

fun fileInfo(ctx: Context, uri: Uri): FileInfo {
    var name = "file"
    var size = -1L
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (n >= 0) c.getString(n)?.let { name = it }
            val s = c.getColumnIndex(OpenableColumns.SIZE)
            if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
        }
    }
    if (size < 0) {
        // Size unknown: copy to a temporary file so we can tell the laptop how big it is.
        val tmp = File.createTempFile("pindrop", ".tmp", ctx.cacheDir)
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Can't open this file")
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        return FileInfo(name, tmp.length(), { tmp.inputStream() }, { tmp.delete() })
    }
    return FileInfo(name, size, {
        ctx.contentResolver.openInputStream(uri) ?: throw IOException("Can't open this file")
    })
}

/** A file being written into Downloads/Pindrop. finish(true) keeps it, finish(false) throws it away. */
class FileSink(val out: OutputStream, private val done: (Boolean) -> Unit) {
    fun finish(ok: Boolean) {
        try {
            out.close()
        } catch (e: Exception) {
        }
        done(ok)
    }
}

fun openSink(ctx: Context, name: String): FileSink {
    val mime = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, "Download/Pindrop")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = ctx.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IOException("Can't create the file in Downloads")
    val out = resolver.openOutputStream(uri)
    if (out == null) {
        resolver.delete(uri, null, null)
        throw IOException("Can't write to Downloads")
    }
    return FileSink(out) { ok ->
        if (ok) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } else {
            resolver.delete(uri, null, null)
        }
    }
}

fun fmtSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
    else -> String.format("%.2f GB", bytes / 1073741824.0)
}

/** What the laptop answered when we knocked. */
enum class LaptopState { OK, NEEDS_CODE, STOPPED, DOWN }

/** Passes progress on at most about 11 times a second, so the screen stays smooth even on fast Wi-Fi. */
private class Throttle(private val onProgress: (Long) -> Unit) {
    private var last = 0L

    fun tick(value: Long, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (force || now - last >= 90L) {
            last = now
            onProgress(value)
        }
    }
}

object Net {
    private const val PORT = 8765
    private const val CHUNK = 128 * 1024

    fun baseUrl(addr: String): String {
        val a = addr.trim().removePrefix("http://").trimEnd('/')
        return if (a.contains(":")) "http://$a" else "http://$a:$PORT"
    }

    private fun open(url: String, code: String = ""): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (code.isNotBlank()) c.setRequestProperty("X-Pd-Code", code)
        return c
    }

    /** Knocks on the laptop. Tells apart "not there", "stopped" and "needs the access code". */
    fun pingLaptop(addr: String, code: String): LaptopState {
        return try {
            val c = open(baseUrl(addr) + "/ping", code)
            c.connectTimeout = 3000
            c.readTimeout = 3000
            try {
                when (c.responseCode) {
                    200 -> if (c.inputStream.bufferedReader().readText().trim() == "SEND_TO_LAPTOP_OK") LaptopState.OK else LaptopState.DOWN
                    401 -> LaptopState.NEEDS_CODE
                    503 -> LaptopState.STOPPED
                    else -> LaptopState.DOWN
                }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            LaptopState.DOWN
        }
    }

    /** True if a paired phone answers and proves it holds the shared key. */
    fun ping(addr: String, key: ByteArray): Boolean {
        return try {
            val nonce = Secure.randomBytes(16)
            val c = URL(baseUrl(addr) + "/ping?c=" + Secure.b64(nonce)).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            val proof = c.getHeaderField("X-Pd-Proof")
            val ok = c.responseCode == 200 && proof != null &&
                Secure.same(Secure.unb64(proof), Secure.hmac(key, "ping".toByteArray(), nonce))
            c.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** Asks a phone for a single-use challenge. Its answer is mixed into the keys, so nothing can be replayed. */
    private fun challenge(addr: String): ByteArray {
        val c = URL(baseUrl(addr) + "/challenge").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 5000
            if (c.responseCode != 200) throw IOException("That phone needs the newest Pindrop. Update it, then try again.")
            return try {
                val raw = Secure.unb64(c.inputStream.bufferedReader().readText().trim())
                if (raw.size != 16) throw IOException("That phone gave an odd answer. Try again.")
                raw
            } catch (e: IllegalArgumentException) {
                throw IOException("That phone gave an odd answer. Try again.")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun laptopProblem(state: LaptopState): IOException? = when (state) {
        LaptopState.OK -> null
        LaptopState.NEEDS_CODE -> IOException("The laptop needs its access code. Open Pindrop settings on the laptop to see it, then type it in the app.")
        LaptopState.STOPPED -> IOException("Pindrop is stopped on the laptop. Press Start there.")
        LaptopState.DOWN -> ConnectException("laptop not reachable")
    }

    /** Sends one file. No key: plain, to the laptop (with its access code). With a key: encrypted, to a paired phone. */
    fun upload(
        addr: String,
        info: FileInfo,
        key: ByteArray? = null,
        path: String = "/upload",
        code: String = "",
        onProgress: (Long) -> Unit
    ) {
        // Check the door first, so a wrong code gives a clear message instead of a dropped connection.
        if (key == null) laptopProblem(pingLaptop(addr, code))?.let { throw it }
        val sealer = if (key != null) Secure.Sealer(key, challenge(addr), info.name, info.size) else null
        val c = open(baseUrl(addr) + path, if (key == null) code else "")
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 120000
            if (sealer != null) {
                sealer.applyHeaders(c)
                c.setFixedLengthStreamingMode(sealer.total)
            } else {
                c.setRequestProperty("X-Filename", URLEncoder.encode(info.name, "UTF-8").replace("+", "%20"))
                c.setFixedLengthStreamingMode(info.size)
            }
            val throttle = Throttle(onProgress)
            try {
                info.open().use { input ->
                    c.outputStream.use { out ->
                        sealer?.begin(out)
                        val buf = ByteArray(CHUNK)
                        var sent = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (sealer != null) sealer.write(out, buf, n) else out.write(buf, 0, n)
                            sent += n
                            throttle.tick(sent)
                        }
                        sealer?.end(out)
                        throttle.tick(sent, true)
                    }
                }
            } catch (e: IOException) {
                val unreachable = e is ConnectException || e is SocketTimeoutException ||
                    e is UnknownHostException || e is NoRouteToHostException
                if (sealer != null && !unreachable && e.message?.startsWith("Can't") != true) {
                    throw IOException("The transfer didn't finish. Check that both phones are on the same Wi-Fi, or pair them again.")
                }
                throw e
            }
            val status = c.responseCode
            if (status != 200) {
                val msg = c.errorStream?.bufferedReader()?.readText()
                if (status == 403 && sealer != null) throw IOException("That phone doesn't accept this pairing. Pair again.")
                if (status == 401) throw IOException("The laptop needs its access code.")
                if (status == 503) throw IOException("Pindrop is stopped on the laptop. Press Start there.")
                if (status == 507) throw IOException("The laptop has no room left for this file.")
                throw IOException("They said: " + (msg ?: status.toString()))
            }
            if (sealer != null && !sealer.proofOk(c)) throw IOException("That isn't the phone you paired with.")
        } finally {
            c.disconnect()
        }
    }

    /** Introduces this phone to the one whose QR code was scanned, so it can send back too. */
    fun pair(peer: Peer, myName: String, myPort: Int, myKey: ByteArray) {
        val body = ("n=" + URLEncoder.encode(myName, "UTF-8") + "&p=" + myPort + "&k=" + Secure.b64(myKey)).toByteArray()
        val info = FileInfo("pair", body.size.toLong(), { ByteArrayInputStream(body) })
        upload(peer.addr, info, Secure.unb64(peer.key), "/pair") { }
    }

    /** The files the laptop is holding for this phone. */
    fun listOutbox(addr: String, code: String): List<OutItem> {
        val c = open(baseUrl(addr) + "/outbox", code)
        try {
            c.connectTimeout = 3000
            c.readTimeout = 5000
            when (c.responseCode) {
                200 -> {}
                401 -> throw IOException("The laptop needs its access code.")
                503 -> throw IOException("Pindrop is stopped on the laptop. Press Start there.")
                else -> throw IOException("The laptop didn't answer properly.")
            }
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OutItem(o.getString("id"), o.getString("name"), o.getLong("size"), o.getString("status"))
            }
        } finally {
            c.disconnect()
        }
    }

    /** Laptop to phone. Saves into Downloads/Pindrop. */
    fun download(ctx: Context, addr: String, code: String, item: OutItem, onProgress: (Long) -> Unit) {
        val c = open(baseUrl(addr) + "/outbox/file/" + item.id, code)
        try {
            c.connectTimeout = 5000
            c.readTimeout = 120000
            if (c.responseCode != 200) throw IOException("The laptop no longer has this file.")
            val sink = openSink(ctx, item.name)
            val throttle = Throttle(onProgress)
            try {
                c.inputStream.use { input ->
                    val buf = ByteArray(CHUNK)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.out.write(buf, 0, n)
                        got += n
                        throttle.tick(got)
                    }
                    throttle.tick(got, true)
                }
                sink.finish(true)
            } catch (e: Exception) {
                sink.finish(false)
                throw e
            }
        } finally {
            c.disconnect()
        }
    }

    fun friendly(e: Exception, who: String = "your laptop"): String = when (e) {
        is ConnectException, is SocketTimeoutException, is UnknownHostException, is NoRouteToHostException ->
            "Can't reach $who. Is Pindrop open there, and are both on the same Wi-Fi or hotspot?"
        else -> e.message ?: "Something went wrong."
    }
}
