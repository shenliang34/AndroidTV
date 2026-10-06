package com.phonetv.phone

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

data class VideoItem(val id: Long, val name: String, val duration: Long, val size: Long, val width: Int, val height: Int, val mime: String, val folder: String, val modified: Long)

class MediaCatalog(private val context: Context) {
    @Volatile private var cache: List<VideoItem> = emptyList()
    fun scan(): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME, MediaStore.Video.Media.DATE_MODIFIED)
        context.contentResolver.query(base, columns, null, null, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")?.use { c ->
            val ix = columns.map { c.getColumnIndexOrThrow(it) }
            while (c.moveToNext()) result += VideoItem(c.getLong(ix[0]), c.getString(ix[1]) ?: "video", c.getLong(ix[2]), c.getLong(ix[3]), c.getInt(ix[4]), c.getInt(ix[5]), c.getString(ix[6]) ?: "video/*", c.getString(ix[7]) ?: "其他", c.getLong(ix[8]) * 1000)
        }
        cache = result
        return result
    }
    fun videos() = cache
    fun uri(id: Long) = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
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
