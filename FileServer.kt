package com.pinshare.app

import android.os.Environment
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

const val DEFAULT_PORT = 8765

fun downloadsDir(): File =
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

data class Peer(val name: String, val host: String, val port: Int)
data class RemoteFile(val name: String, val size: Long)
data class Pairing(val host: String, val port: Int, val pin: String, val name: String)

/**
 * Tiny HTTP server. Every request must carry this phone's PIN
 * (header X-Pin). Only the Downloads folder is exposed, top level only.
 *
 *   GET /ping          -> {"name": "<device>"}
 *   GET /list          -> [{"name": "...", "size": 123}, ...]   (names only)
 *   GET /file?name=x   -> the file bytes
 */
class FileServer(
    port: Int,
    private val pin: () -> String,
    private val deviceName: String
) : NanoHTTPD(port) {

    @Volatile private var fails = 0
    @Volatile private var blockedUntil = 0L

    override fun serve(session: IHTTPSession): Response {
        val now = System.currentTimeMillis()
        if (now < blockedUntil) return text(Response.Status.FORBIDDEN, "Too many wrong PINs")

        val given = session.headers["x-pin"] ?: session.parameters["pin"]?.firstOrNull()
        if (given != pin()) {
            fails++
            if (fails >= 10) {
                fails = 0
                blockedUntil = now + 30_000
            }
            return text(Response.Status.UNAUTHORIZED, "Wrong PIN")
        }
        fails = 0

        return when (session.uri) {
            "/ping" -> json(JSONObject().put("name", deviceName).toString())
            "/list" -> list()
            "/file" -> file(session.parameters["name"]?.firstOrNull())
            else -> text(Response.Status.NOT_FOUND, "Not found")
        }
    }

    private fun list(): Response {
        val arr = JSONArray()
        downloadsDir().listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { arr.put(JSONObject().put("name", it.name).put("size", it.length())) }
        return json(arr.toString())
    }

    private fun file(name: String?): Response {
        if (name.isNullOrBlank() || name.contains('/') || name.contains("..")) {
            return text(Response.Status.NOT_FOUND, "Bad name")
        }
        val dir = downloadsDir()
        val f = File(dir, name)
        if (!f.isFile || f.canonicalFile.parentFile != dir.canonicalFile) {
            return text(Response.Status.NOT_FOUND, "No such file")
        }
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK, "application/octet-stream", FileInputStream(f), f.length()
        )
    }

    private fun text(status: Response.IStatus, msg: String): Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/plain", msg)

    private fun json(body: String): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body)
}

/** Client side: talks to the other phone's server. Call off the main thread. */
object Api {
    private fun open(host: String, port: Int, path: String, pin: String): HttpURLConnection {
        val c = URL("http://$host:$port$path").openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 20000
        c.setRequestProperty("X-Pin", pin)
        when (c.responseCode) {
            200 -> {}
            401 -> throw Exception("Wrong PIN")
            403 -> throw Exception("Too many wrong tries, wait 30s")
            404 -> throw Exception("File not found")
            else -> throw Exception("Error ${c.responseCode}")
        }
        return c
    }

    /** Returns the other phone's device name if the PIN is right. */
    fun ping(host: String, port: Int, pin: String): String {
        val body = open(host, port, "/ping", pin).inputStream.bufferedReader().use { it.readText() }
        return JSONObject(body).getString("name")
    }

    fun list(p: Pairing): List<RemoteFile> {
        val body = open(p.host, p.port, "/list", p.pin).inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(body)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RemoteFile(o.getString("name"), o.getLong("size"))
        }
    }

    /** Saves one file into this phone's Downloads. Returns the saved file. */
    fun download(p: Pairing, name: String): File {
        val c = open(p.host, p.port, "/file?name=" + URLEncoder.encode(name, "UTF-8"), p.pin)
        val dir = downloadsDir().apply { mkdirs() }
        val tmp = File(dir, ".part-" + System.nanoTime())
        try {
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val dest = uniqueFile(dir, name)
            if (!tmp.renameTo(dest)) throw Exception("Could not save file")
            return dest
        } finally {
            tmp.delete()
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (true) {
            f = File(dir, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }
}
