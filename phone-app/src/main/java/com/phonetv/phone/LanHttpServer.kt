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
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class LanHttpServer(private val context: Context, private val catalog: MediaCatalog, private val deviceName: String) {
    private sealed interface RangeRequest {
        data object Full : RangeRequest
        data class Partial(val start: Long, val end: Long) : RangeRequest
        data object Unsatisfiable : RangeRequest
    }

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private val pairing by lazy { PairingStore(context) }
    private data class PairAttempt(var windowStart: Long, var failures: Int)
    private val pairAttempts = ConcurrentHashMap<String, PairAttempt>()
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
    private fun sharedVideo(id: Long): VideoItem? = sharedVideos().firstOrNull { it.id == id }
    private fun handle(socket: Socket) {
        socket.soTimeout = 15000
        val input = BufferedInputStream(socket.getInputStream())
        val line = readLine(input) ?: return
        val parts = line.split(' '); if (parts.size < 2) return
        val method = parts[0]; val path = URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8")
        var range: String? = null
        var authorization: String? = null
        var contentLength = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            when {
                h.startsWith("Range:", true) -> range = h.substringAfter(':').trim()
                h.startsWith("Authorization:", true) -> authorization = h.substringAfter(':').trim()
                h.startsWith("Content-Length:", true) -> contentLength = h.substringAfter(':').trim().toIntOrNull() ?: -1
            }
        }
        val out = BufferedOutputStream(socket.getOutputStream())
        try {
            if (path == "/api/pair/request") {
                if (method != "POST") return respond(out, 405, "text/plain", ByteArray(0), mapOf("Allow" to "POST"), head = true)
                if (contentLength !in 1..4096) return respond(out, 400, "text/plain", ByteArray(0), head = true)
                val body = readBody(input, contentLength, out) ?: return
                val address = socket.inetAddress.hostAddress ?: "unknown"
                if (isPairingRateLimited(address)) return respond(out, 429, "application/json", JSONObject().put("error", "too_many_requests").toString().toByteArray(), mapOf("Retry-After" to "600"))
                val request = try { JSONObject(String(body, Charsets.UTF_8)) } catch (_: Exception) { null }
                val tvId = request?.optString("deviceId", "") ?: ""
                val tvName = request?.optString("deviceName", "") ?: ""
                val pairingRequest = pairing.requestPairing(tvId, tvName)
                    ?: return respond(out, 400, "application/json", JSONObject().put("error", "invalid_pairing_request").toString().toByteArray())
                return respond(out, 200, "application/json", JSONObject()
                    .put("requestId", pairingRequest.requestId)
                    .put("expiresAt", pairingRequest.expiresAt)
                    .toString().toByteArray())
            }
            if (path == "/api/pair/status") {
                if (method != "GET") return respond(out, 405, "text/plain", ByteArray(0), mapOf("Allow" to "GET"), head = true)
                val requestId = queryParameter(parts[1], "requestId")
                if (requestId.isNullOrBlank() || requestId.length > 64) return respond(out, 400, "application/json", ByteArray(0))
                val result = pairing.requestStatus(requestId)
                return respond(out, 200, "application/json", JSONObject()
                    .put("status", result.status)
                    .apply { result.token?.let { put("token", it) } }
                    .toString().toByteArray())
            }
            if (path == "/api/pair/cancel" || path == "/api/pair/complete") {
                if (method != "POST") return respond(out, 405, "text/plain", ByteArray(0), mapOf("Allow" to "POST"), head = true)
                if (contentLength !in 1..4096) return respond(out, 400, "text/plain", ByteArray(0), head = true)
                val body = readBody(input, contentLength, out) ?: return
                val requestId = try { JSONObject(String(body, Charsets.UTF_8)).optString("requestId") } catch (_: Exception) { "" }
                if (requestId.isBlank() || requestId.length > 64) return respond(out, 400, "application/json", ByteArray(0))
                if (path == "/api/pair/cancel") pairing.cancelRequest(requestId) else pairing.completeRequest(requestId)
                return respond(out, 200, "application/json", JSONObject().put("ok", true).toString().toByteArray())
            }
            if (path == "/api/pair") {
                if (method != "POST") return respond(out, 405, "text/plain", ByteArray(0), mapOf("Allow" to "POST"), head = true)
                if (contentLength !in 1..4096) return respond(out, 400, "text/plain", ByteArray(0), head = true)
                val body = readBody(input, contentLength, out) ?: return
                val address = socket.inetAddress.hostAddress ?: "unknown"
                if (isPairingRateLimited(address)) return respond(out, 429, "text/plain", ByteArray(0), mapOf("Retry-After" to "600"), head = true)
                val request = try { JSONObject(String(body, Charsets.UTF_8)) } catch (_: Exception) { null }
                val code = request?.optString("code", "") ?: ""
                val tvId = request?.optString("deviceId", "") ?: ""
                val tvName = request?.optString("deviceName", "") ?: ""
                val token = pairing.pair(code, tvId, tvName)
                if (token == null) {
                    recordPairingFailure(address)
                    return respond(out, 401, "application/json", JSONObject().put("error", "invalid_or_expired_code").toString().toByteArray())
                }
                pairAttempts.remove(address)
                return respond(out, 200, "application/json", JSONObject().put("token", token).toString().toByteArray())
            }
            if (path.startsWith("/api/") && path != "/api/device") {
                val token = authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substringAfter(' ')?.trim()
                if (!pairing.isAuthorized(token)) return respond(out, 401, "application/json", JSONObject().put("error", "unauthorized").toString().toByteArray(), mapOf("WWW-Authenticate" to "Bearer"))
            }
            when {
                path == "/api/device" -> respond(out, 200, "application/json", JSONObject().put("deviceId", id).put("deviceName", deviceName).put("videoCount", sharedVideos().size).put("version", "1.0").toString().toByteArray())
                path.startsWith("/api/videos/") && path.endsWith("/stream") -> {
                    val videoId = path.removePrefix("/api/videos/").substringBefore('/').toLongOrNull() ?: return notFound(out)
                    if (method != "GET" && method != "HEAD") return respond(out, 405, "text/plain", ByteArray(0), head = true)
                    stream(out, videoId, range, method == "HEAD")
                }
                path.startsWith("/api/videos/") && path.endsWith("/thumbnail") -> {
                    val videoId = path.removePrefix("/api/videos/").substringBefore('/').toLongOrNull() ?: return notFound(out)
                    if (sharedVideo(videoId) == null) return notFound(out)
                    if (method != "GET" && method != "HEAD") return respond(out, 405, "text/plain", ByteArray(0), head = true)
                    val bitmap = catalog.thumbnail(videoId) ?: return notFound(out)
                    val bytes = java.io.ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it); it.toByteArray() }
                    respond(out, 200, "image/jpeg", bytes, head = method == "HEAD")
                }
                path.startsWith("/api/videos") -> {
                    val query = parts[1].substringAfter('?', "").split('&').mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                    val page = (query["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1); val size = (query["pageSize"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                    val keyword = query["keyword"]?.lowercase(); val folder = query["folder"]
                    val filtered = sharedVideos().filter { (keyword == null || it.name.lowercase().contains(keyword)) && (folder == null || it.folder == folder) }
                    val host = socket.localAddress.hostAddress ?: "127.0.0.1"
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
    private fun parseRange(header: String?, length: Long): RangeRequest {
        if (header == null) return RangeRequest.Full
        if (length <= 0 || !header.startsWith("bytes=", ignoreCase = true)) return RangeRequest.Unsatisfiable
        val spec = header.substringAfter('=', "").trim()
        if (spec.isEmpty() || ',' in spec || spec.count { it == '-' } != 1) return RangeRequest.Unsatisfiable
        val first = spec.substringBefore('-').trim()
        val last = spec.substringAfter('-', "").trim()
        if (first.isNotEmpty() && first.any { !it.isDigit() }) return RangeRequest.Unsatisfiable
        if (last.isNotEmpty() && last.any { !it.isDigit() }) return RangeRequest.Unsatisfiable
        return try {
            when {
                first.isEmpty() -> {
                    val suffixLength = last.toLong()
                    if (suffixLength <= 0) RangeRequest.Unsatisfiable
                    else RangeRequest.Partial((length - suffixLength).coerceAtLeast(0), length - 1)
                }
                else -> {
                    val start = first.toLong()
                    val requestedEnd = if (last.isEmpty()) length - 1 else last.toLong()
                    if (start < 0 || requestedEnd < start || start >= length) RangeRequest.Unsatisfiable
                    else RangeRequest.Partial(start, requestedEnd.coerceAtMost(length - 1))
                }
            }
        } catch (_: NumberFormatException) {
            RangeRequest.Unsatisfiable
        }
    }

    private fun isPairingRateLimited(address: String): Boolean = synchronized(pairAttempts) {
        val now = System.currentTimeMillis()
        val attempt = pairAttempts[address] ?: return@synchronized false
        if (now - attempt.windowStart >= PAIR_ATTEMPT_WINDOW_MS) {
            pairAttempts.remove(address)
            false
        } else attempt.failures >= MAX_PAIR_FAILURES
    }

    private fun readBody(input: BufferedInputStream, length: Int, out: BufferedOutputStream): ByteArray? {
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) {
                respond(out, 400, "text/plain", ByteArray(0), head = true)
                return null
            }
            offset += count
        }
        return body
    }

    private fun queryParameter(target: String, name: String): String? = target.substringAfter('?', "")
        .split('&').firstNotNullOfOrNull { parameter ->
            val pair = parameter.split('=', limit = 2)
            if (pair.size == 2 && pair[0] == name) URLDecoder.decode(pair[1], "UTF-8") else null
        }

    private fun recordPairingFailure(address: String) = synchronized(pairAttempts) {
        val now = System.currentTimeMillis()
        val attempt = pairAttempts[address]
        if (attempt == null || now - attempt.windowStart >= PAIR_ATTEMPT_WINDOW_MS) {
            if (pairAttempts.size >= 256) pairAttempts.clear()
            pairAttempts[address] = PairAttempt(now, 1)
        } else attempt.failures++
    }

    private fun stream(out: BufferedOutputStream, id: Long, range: String?, head: Boolean) {
        val item = sharedVideo(id) ?: return notFound(out)
        val length = item.size
        val selectedRange = parseRange(range, length)
        if (selectedRange == RangeRequest.Unsatisfiable) {
            return respond(out, 416, "text/plain", ByteArray(0), mapOf("Content-Range" to "bytes */$length"), head = true)
        }
        val partial = selectedRange as? RangeRequest.Partial
        val start = partial?.start ?: 0L
        val end = partial?.end ?: (length - 1).coerceAtLeast(-1)
        val count = if (length == 0L) 0L else end - start + 1
        val headers = buildMap {
            put("Accept-Ranges", "bytes")
            if (partial != null) put("Content-Range", "bytes $start-$end/$length")
        }
        if (head) {
            writeHeaders(out, if (partial == null) 200 else 206, item.mime, count, headers)
            out.flush()
            return
        }
        val uri = catalog.uri(id)
        val source = context.contentResolver.openInputStream(uri) ?: return notFound(out)
        writeHeaders(out, if (partial == null) 200 else 206, item.mime, count, headers)
        source.use { stream ->
            var remaining = start
            while (remaining > 0) { val n = stream.skip(remaining); if (n <= 0) { if (stream.read() < 0) break else remaining-- } else remaining -= n }
            val buffer = ByteArray(64 * 1024); var left = count
            while (left > 0) { val n = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt()); if (n < 0) break; out.write(buffer, 0, n); left -= n }
        }
        out.flush()
    }
    private fun respond(out: BufferedOutputStream, code: Int, type: String, body: ByteArray, headers: Map<String, String> = emptyMap(), head: Boolean = false) {
        writeHeaders(out, code, type, body.size.toLong(), headers)
        if (!head) out.write(body)
        out.flush()
    }
    private fun writeHeaders(out: BufferedOutputStream, code: Int, type: String, contentLength: Long, headers: Map<String, String> = emptyMap()) {
        val reason = when (code) { 200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 416 -> "Range Not Satisfiable"; 429 -> "Too Many Requests"; else -> "Error" }
        val extra = headers.entries.joinToString(separator = "") { (name, value) -> "$name: $value\r\n" }
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: $contentLength\r\n${extra}Connection: close\r\n\r\n".toByteArray())
    }
    private fun notFound(out: BufferedOutputStream) = respond(out, 404, "text/plain", "not found".toByteArray())
    private fun readLine(input: BufferedInputStream): String? { val b = java.io.ByteArrayOutputStream(); while (true) { val c = input.read(); if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8"); if (c == 10) break; if (c != 13 && b.size() < 8192) b.write(c) }; return b.toString("UTF-8") }

    companion object {
        private const val MAX_PAIR_FAILURES = 5
        private const val PAIR_ATTEMPT_WINDOW_MS = 10 * 60 * 1000L
    }
}
