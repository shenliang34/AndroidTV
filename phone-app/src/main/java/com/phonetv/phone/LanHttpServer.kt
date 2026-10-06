package com.phonetv.phone

import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import kotlin.concurrent.thread

class LanHttpServer(private val context: Context, private val catalog: MediaCatalog, private val deviceName: String) {
    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val id by lazy { context.getSharedPreferences("phone", Context.MODE_PRIVATE).let { p -> p.getString("deviceId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("deviceId", it).apply() } } }
    fun start() {
        if (running) return
        running = true
        thread(name = "phonevideo-http") {
            try {
                server = ServerSocket(8080)
                while (running) { val socket = server!!.accept(); thread { socket.use { handle(it) } } }
            } catch (_: Exception) { running = false }
        }
    }
    fun stop() { running = false; try { server?.close() } catch (_: Exception) {} }

    private fun sharedVideos(): List<VideoItem> {
        val disabled = context.getSharedPreferences("phone", Context.MODE_PRIVATE).getStringSet("disabledFolders", emptySet()) ?: emptySet()
        return catalog.videos().filter { it.folder !in disabled }
    }
    private fun handle(socket: Socket) {
        socket.soTimeout = 15000
        val input = BufferedInputStream(socket.getInputStream())
        val line = readLine(input) ?: return
        val parts = line.split(' '); if (parts.size < 2) return
        val method = parts[0]; val path = URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8")
        var range: String? = null
        while (true) { val h = readLine(input) ?: break; if (h.isEmpty()) break; if (h.startsWith("Range:", true)) range = h.substringAfter(':').trim() }
        val out = BufferedOutputStream(socket.getOutputStream())
        try {
            when {
                path == "/api/device" -> respond(out, 200, "application/json", JSONObject().put("deviceId", id).put("deviceName", deviceName).put("videoCount", sharedVideos().size).put("version", "1.0").toString().toByteArray())
                path.startsWith("/api/videos/") && path.endsWith("/stream") -> {
                    val videoId = path.removePrefix("/api/videos/").substringBefore('/').toLongOrNull() ?: return notFound(out)
                    stream(out, videoId, range)
                }
                path.startsWith("/api/videos/") && path.endsWith("/thumbnail") -> {
                    val videoId = path.removePrefix("/api/videos/").substringBefore('/').toLongOrNull() ?: return notFound(out)
                    val bitmap = catalog.thumbnail(videoId) ?: return notFound(out)
                    val bytes = java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it); it.toByteArray() }
                    respond(out, 200, "image/jpeg", bytes)
                }
                path.startsWith("/api/videos") -> {
                    val query = parts[1].substringAfter('?', "").split('&').mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                    val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1); val size = (query["pageSize"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                    val keyword = query["keyword"]?.lowercase(); val folder = query["folder"]
                    val filtered = sharedVideos().filter { (keyword == null || it.name.lowercase().contains(keyword)) && (folder == null || it.folder == folder) }
                    val host = socket.localAddress.hostAddress
                    val json = """{"page":$page,"pageSize":$size,"total":${filtered.size},"items":${catalog.json(filtered.drop((page-1)*size).take(size), host)}}"""
                    respond(out, 200, "application/json", json.toByteArray())
                }
                path == "/api/folders" -> {
                    val folders = JSONArray().apply { sharedVideos().groupBy { it.folder }.forEach { (name, videos) -> put(JSONObject().put("id", name.lowercase()).put("name", name).put("videoCount", videos.size)) } }
                    respond(out, 200, "application/json", folders.toString().toByteArray())
                }
                else -> notFound(out)
            }
        } catch (_: Exception) { try { respond(out, 500, "text/plain", "server error".toByteArray()) } catch (_: Exception) {} }
    }
    private fun stream(out: BufferedOutputStream, id: Long, range: String?) {
        val item = catalog.videos().firstOrNull { it.id == id } ?: return notFound(out)
        val length = item.size
        val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()?.coerceIn(0, length) ?: 0
        val end = range?.substringAfter('-', "")?.toLongOrNull()?.coerceIn(start, length - 1) ?: (length - 1)
        val count = (end - start + 1).coerceAtLeast(0)
        val uri = catalog.uri(id)
        val source = context.contentResolver.openInputStream(uri) ?: return notFound(out)
        val partial = range != null
        out.write("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\nContent-Type: ${item.mime}\r\nAccept-Ranges: bytes\r\nContent-Length: $count\r\n${if (partial) "Content-Range: bytes $start-$end/$length\r\n" else ""}Connection: close\r\n\r\n".toByteArray())
        source.use { stream ->
            var remaining = start
            while (remaining > 0) { val n = stream.skip(remaining); if (n <= 0) { if (stream.read() < 0) break else remaining-- } else remaining -= n }
            val buffer = ByteArray(64 * 1024); var left = count
            while (left > 0) { val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); if (n < 0) break; out.write(buffer, 0, n); left -= n }
        }
        out.flush()
    }
    private fun respond(out: BufferedOutputStream, code: Int, type: String, body: ByteArray) { val reason = if (code == 200) "OK" else if (code == 206) "Partial Content" else if (code == 404) "Not Found" else "Error"; out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body); out.flush() }
    private fun notFound(out: BufferedOutputStream) = respond(out, 404, "text/plain", "not found".toByteArray())
    private fun readLine(input: BufferedInputStream): String? { val b = java.io.ByteArrayOutputStream(); while (true) { val c = input.read(); if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8"); if (c == 10) break; if (c != 13 && b.size() < 8192) b.write(c) }; return b.toString("UTF-8") }
}
