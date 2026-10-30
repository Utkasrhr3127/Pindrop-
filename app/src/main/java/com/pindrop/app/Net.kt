package com.pindrop.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStream
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
}

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

    /** True if the Pindrop program answers on the laptop. */
    fun ping(addr: String): Boolean {
        return try {
            val c = URL(baseUrl(addr) + "/ping").openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            val ok = c.responseCode == 200 &&
                c.inputStream.bufferedReader().readText().trim() == "SEND_TO_LAPTOP_OK"
            c.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    /** Phone to laptop. */
    fun upload(addr: String, info: FileInfo, onProgress: (Long) -> Unit) {
        val c = URL(baseUrl(addr) + "/upload").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 120000
            c.setRequestProperty("X-Filename", URLEncoder.encode(info.name, "UTF-8").replace("+", "%20"))
            c.setFixedLengthStreamingMode(info.size)
            info.open().use { input ->
                c.outputStream.use { out ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
            }
            val code = c.responseCode
            if (code != 200) {
                val msg = c.errorStream?.bufferedReader()?.readText()
                throw IOException("Laptop said: " + (msg ?: code.toString()))
            }
        } finally {
            c.disconnect()
        }
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
            val mime = URLConnection.guessContentTypeFromName(item.name) ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Pindrop")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Can't create the file in Downloads")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Can't write to Downloads")
                c.inputStream.use { input ->
                    out.use { o ->
                        val buf = ByteArray(64 * 1024)
                        var got = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            got += n
                            onProgress(got)
                        }
                    }
                }
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } finally {
            c.disconnect()
        }
    }

    fun friendly(e: Exception): String = when (e) {
        is ConnectException, is SocketTimeoutException, is UnknownHostException, is NoRouteToHostException ->
            "Can't reach your laptop. Is Pindrop open, and are both on the same hotspot?"
        else -> e.message ?: "Something went wrong."
    }
}
