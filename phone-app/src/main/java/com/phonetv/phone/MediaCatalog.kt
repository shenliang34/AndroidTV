package com.phonetv.phone

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class VideoItem(val id: Long, val name: String, val duration: Long, val size: Long, val width: Int, val height: Int, val mime: String, val folder: String, val modified: Long, val sourceUri: String? = null)

class MediaCatalog(private val context: Context) {
    @Volatile private var cache: List<VideoItem> = emptyList()
    private val prefs get() = context.getSharedPreferences("phone", Context.MODE_PRIVATE)

    fun hasPickedVideos(): Boolean = prefs.getBoolean("pickedVideosEnabled", false)

    fun setPickedVideos(uris: List<Uri>) {
        prefs.edit().putStringSet("pickedVideoUris", uris.map { it.toString() }.toSet())
            .putBoolean("pickedVideosEnabled", true).apply()
    }

    fun clearPickedVideos() {
        prefs.edit().remove("pickedVideoUris").putBoolean("pickedVideosEnabled", false).apply()
    }

    fun scan(): List<VideoItem> {
        val result = if (hasPickedVideos()) scanPickedVideos() else scanMediaStore()
        cache = result
        return result
    }

    private fun scanPickedVideos(): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val uris = prefs.getStringSet("pickedVideoUris", emptySet())?.toList().orEmpty().sorted()
        for (value in uris) {
            try {
                val uri = Uri.parse(value)
                var name = "video"
                var size = 0L
                var duration = 0L
                var width = 0
                var height = 0
                var modified = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        fun long(column: String): Long = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong) ?: 0L
                        fun string(column: String): String? = cursor.getColumnIndex(column).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getString)
                        name = string(OpenableColumns.DISPLAY_NAME) ?: name
                        size = long(OpenableColumns.SIZE)
                        duration = long(MediaStore.Video.VideoColumns.DURATION)
                        width = long(MediaStore.Video.VideoColumns.WIDTH).toInt()
                        height = long(MediaStore.Video.VideoColumns.HEIGHT).toInt()
                        modified = long(MediaStore.MediaColumns.DATE_MODIFIED).let { if (it < 10_000_000_000L) it * 1000 else it }
                    }
                }
                if (name == "video") name = uri.lastPathSegment?.substringAfterLast('/') ?: name
                result += VideoItem(stableId(value), name, duration, size, width, height,
                    context.contentResolver.getType(uri) ?: "video/*", "已选视频", modified, value)
            } catch (_: Exception) {
                // Ignore individual URIs that were removed or whose grant was revoked.
            }
        }
        return result
    }

    private fun scanMediaStore(): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_MODIFIED)
        try {
            context.contentResolver.query(base, columns, null, null, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")?.use { c ->
                val ix = columns.map { c.getColumnIndexOrThrow(it) }
                while (c.moveToNext()) result += VideoItem(c.getLong(ix[0]), c.getString(ix[1]) ?: "video", c.getLong(ix[2]), c.getLong(ix[3]), c.getInt(ix[4]), c.getInt(ix[5]), c.getString(ix[6]) ?: "video/*", c.getString(ix[7]) ?: "其他", c.getLong(ix[8]) * 1000)
            }
        } catch (_: SecurityException) { }
        return result
    }

    private fun stableId(value: String): Long = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }.and(Long.MAX_VALUE)

    fun videos() = cache
    fun uri(id: Long): Uri = cache.firstOrNull { it.id == id }?.sourceUri?.let(Uri::parse)
        ?: ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
    fun thumbnail(id: Long): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri(id), android.util.Size(640, 360), null)
        else MediaStore.Video.Thumbnails.getThumbnail(context.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
    } catch (_: Exception) { null }
    fun json(items: List<VideoItem>, host: String): JSONArray = JSONArray().apply {
        items.forEach { v -> put(JSONObject().put("id", v.id.toString()).put("name", v.name).put("duration", v.duration).put("size", v.size)
            .put("width", v.width).put("height", v.height).put("mimeType", v.mime).put("folder", v.folder).put("modifiedTime", v.modified)
            .put("thumbnailUrl", "http://$host:8080/api/videos/${v.id}/thumbnail").put("streamUrl", "http://$host:8080/api/videos/${v.id}/stream")) }
    }
}
