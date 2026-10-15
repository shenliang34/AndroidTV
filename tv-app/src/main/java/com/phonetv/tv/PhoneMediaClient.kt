package com.phonetv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RemoteFolder(val name: String, val videoCount: Int)

data class VideoPage(
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val items: List<RemoteVideo>,
)

/**
 * Thin HTTP client for the phone LAN media API.
 * Uses the existing paged `/api/videos` contract so the TV never needs the full library in memory.
 */
class PhoneMediaClient {
    fun fetchFolders(device: PhoneDevice, token: String): List<RemoteFolder> {
        val array = requestArray(URL("http://${device.host}:${device.port}/api/folders"), token = token)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(RemoteFolder(item.optString("name", "其他"), item.optInt("videoCount", 0)))
            }
        }.sortedByDescending { it.videoCount }
    }

    fun fetchVideoPage(
        device: PhoneDevice,
        token: String,
        page: Int,
        pageSize: Int,
        folder: String? = null,
        keyword: String? = null,
        sort: String? = null,
    ): VideoPage {
        val params = mutableListOf(
            "page=$page",
            "pageSize=$pageSize",
        )
        if (!folder.isNullOrBlank()) params += "folder=${enc(folder)}"
        if (!keyword.isNullOrBlank()) params += "keyword=${enc(keyword)}"
        if (!sort.isNullOrBlank()) params += "sort=${enc(sort)}"
        val result = requestObject(URL("http://${device.host}:${device.port}/api/videos?${params.joinToString("&")}"), token = token)
        val items = result.getJSONArray("items")
        val videos = buildList {
            for (index in 0 until items.length()) {
                val video = items.getJSONObject(index)
                add(
                    RemoteVideo(
                        video.getString("id"),
                        video.getString("name"),
                        video.optString("folder", "其他"),
                        video.optLong("duration"),
                        video.optLong("size"),
                        video.optLong("modifiedTime"),
                        video.optString("thumbnailUrl"),
                        video.optString("streamUrl"),
                        token,
                    ),
                )
            }
        }
        return VideoPage(
            page = result.optInt("page", page),
            pageSize = result.optInt("pageSize", pageSize),
            total = result.optInt("total", videos.size).coerceAtLeast(0),
            items = videos,
        )
    }

    fun fetchDevice(device: PhoneDevice): JSONObject =
        requestObject(URL("http://${device.host}:${device.port}/api/device"))

    fun requestObject(
        url: URL,
        token: String? = null,
        method: String = "GET",
        body: String? = null,
    ): JSONObject {
        val text = requestText(url, token, method, body)
        return JSONObject(text)
    }

    fun requestArray(
        url: URL,
        token: String? = null,
        method: String = "GET",
        body: String? = null,
    ): JSONArray {
        val text = requestText(url, token, method, body)
        return JSONArray(text)
    }

    private fun requestText(
        url: URL,
        token: String? = null,
        method: String = "GET",
        body: String? = null,
    ): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 15000
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw HttpStatusException(status)
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    class HttpStatusException(val statusCode: Int) : IOException("HTTP $statusCode")
}
