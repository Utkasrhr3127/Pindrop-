package com.pindrop.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
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

/** Remembers the laptop address on the phone. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("pindrop", Context.MODE_PRIVATE)

    fun getAddr(c: Context): String = sp(c).getString("addr", "") ?: ""

    fun setAddr(c: Context, value: String) {
        sp(c).edit().putString("addr", value.trim()).apply()
    }

    /** This phone's own secret. Other phones get it from the QR code. Made once, kept forever. */
    fun getKey(c: Context): ByteArray {
        val saved = sp(c).getString("key", null)
        if (saved != null) return Secure.unb64(saved)
        val fresh = Secure.newKey()
        sp(c).edit().putString("key", Secure.b64(fresh)).apply()
        return fresh
    }

    fun getPeersJson(c: Context): String = sp(c).getString("peers", "[]") ?: "[]"

    fun setPeersJson(c: Context, json: String) {
        sp(c).edit().putString("peers", json).apply()
    }
}

/** Where a file is going: the laptop (plain) or a paired phone (encrypted with its key). */
class Target(val name: String, val addr: String, val key: ByteArray?)

fun laptopTarget(c: Context) = Target("Laptop", Prefs.getAddr(c), null)

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

object Net {
    private const val PORT = 8765

    fun baseUrl(addr: String): String {
        val a = addr.trim().removePrefix("http://").trimEnd('/')
        return if (a.contains(":")) "http://$a" else "http://$a:$PORT"
    }

    /** True if the other side answers. With a key, also checks it really is the paired phone. */
    fun ping(addr: String, key: ByteArray? = null): Boolean {
        return try {
            if (key == null) {
                val c = URL(baseUrl(addr) + "/ping").openConnection() as HttpURLConnection
                c.connectTimeout = 3000
                c.readTimeout = 3000
                val ok = c.responseCode == 200 &&
                    c.inputStream.bufferedReader().readText().trim() == "SEND_TO_LAPTOP_OK"
                c.disconnect()
                ok
            } else {
                val nonce = Secure.randomBytes(16)
                val c = URL(baseUrl(addr) + "/ping?c=" + Secure.b64(nonce)).openConnection() as HttpURLConnection
                c.connectTimeout = 3000
                c.readTimeout = 3000
                val proof = c.getHeaderField("X-Pd-Proof")
                val ok = c.responseCode == 200 && proof != null &&
                    Secure.same(Secure.unb64(proof), Secure.hmac(key, "ping".toByteArray(), nonce))
                c.disconnect()
                ok
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Sends one file. No key: plain, to the laptop. With a key: encrypted, to a paired phone. */
    fun upload(
        addr: String,
        info: FileInfo,
        key: ByteArray? = null,
        path: String = "/upload",
        onProgress: (Long) -> Unit
    ) {
        val c = URL(baseUrl(addr) + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 120000
            val sealer = if (key != null) Secure.Sealer(key, info.name, info.size) else null
            if (sealer != null) {
                sealer.applyHeaders(c)
                c.setFixedLengthStreamingMode(sealer.total)
            } else {
                c.setRequestProperty("X-Filename", URLEncoder.encode(info.name, "UTF-8").replace("+", "%20"))
                c.setFixedLengthStreamingMode(info.size)
            }
            try {
            info.open().use { input ->
                c.outputStream.use { out ->
                    sealer?.begin(out)
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (sealer != null) sealer.write(out, buf, n) else out.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                    sealer?.end(out)
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
            val code = c.responseCode
            if (code != 200) {
                val msg = c.errorStream?.bufferedReader()?.readText()
                if (code == 403 && sealer != null) throw IOException("That phone doesn't accept this pairing. Pair again.")
                throw IOException("They said: " + (msg ?: code.toString()))
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
    fun listOutbox(addr: String): List<OutItem> {
        val c = URL(baseUrl(addr) + "/outbox").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3000
            c.readTimeout = 5000
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
    fun download(ctx: Context, addr: String, item: OutItem, onProgress: (Long) -> Unit) {
        val c = URL(baseUrl(addr) + "/outbox/file/" + item.id).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 120000
            if (c.responseCode != 200) throw IOException("The laptop no longer has this file.")
            val sink = openSink(ctx, item.name)
            try {
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.out.write(buf, 0, n)
                        got += n
                        onProgress(got)
                    }
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
