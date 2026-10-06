package com.phonetv.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PhoneDevice(val name: String, val host: String, val port: Int)
data class RemoteVideo(val id: String, val name: String, val duration: Long, val size: Long, val thumbnail: String, val stream: String)

class MainActivity : ComponentActivity() {
    private val devices = mutableStateListOf<PhoneDevice>()
    private val videos = mutableStateListOf<RemoteVideo>()
    private var selected by mutableStateOf<PhoneDevice?>(null)
    private var playing by mutableStateOf<RemoteVideo?>(null)
    private var message by mutableStateOf("正在搜索手机媒体服务器…")
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var player: ExoPlayer? = null
    private val resolving = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); startDiscovery()
        setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                val currentVideo = playing
                if (currentVideo != null) {
                    Column(Modifier.fillMaxSize().padding(28.dp)) {
                        Text(currentVideo.name, style = MaterialTheme.typography.headlineSmall)
                        AndroidView(factory = { ctx -> PlayerView(ctx).apply { player = this@MainActivity.player; useController = true } }, modifier = Modifier.fillMaxSize().padding(top = 18.dp))
                    }
                    BackHandler { stopPlayback() }
                } else if (selected != null) {
                    Column(Modifier.fillMaxSize().padding(32.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Button(onClick = { selected = null; videos.clear() }) { Text("← 设备") }; Text(selected!!.name, style = MaterialTheme.typography.headlineMedium) }
                        Text(message, Modifier.padding(vertical = 12.dp))
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(videos, key = { it.id }) { item ->
                                Button(onClick = { play(item) }, modifier = Modifier.fillMaxWidth().height(112.dp).focusable()) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                        AsyncImage(model = item.thumbnail, contentDescription = null, modifier = Modifier.size(160.dp, 90.dp), contentScale = ContentScale.Crop)
                                        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                                            Text("${formatDuration(item.duration)}    ${formatSize(item.size)}", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(40.dp)) {
                        Text("手机媒体", style = MaterialTheme.typography.headlineLarge)
                        Text("附近设备", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 18.dp, bottom = 12.dp))
                        if (devices.isEmpty()) Text(message)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(devices, key = { "${it.host}:${it.port}" }) { device ->
                                Button(onClick = { connect(device) }, modifier = Modifier.fillMaxWidth().height(82.dp).focusable()) {
                                    Text("📱  ${device.name}     在线")
                                }
                            }
                        }
                    }
                }
            }
        } }
    }

    private fun startDiscovery() {
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { runOnUiThread { message = "正在搜索手机媒体服务器…" } }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("_phonevideo._tcp") || !resolving.add(info.serviceName)) return
                nsd?.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { resolving.remove(serviceInfo.serviceName) }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving.remove(serviceInfo.serviceName)
                        val host = serviceInfo.host?.hostAddress ?: return
                        runOnUiThread { if (devices.none { it.host == host }) devices.add(PhoneDevice(serviceInfo.serviceName, host, serviceInfo.port)); message = "选择一台手机开始浏览视频" }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) { runOnUiThread { devices.removeAll { it.name == info.serviceName } } }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) { message = "搜索失败，错误 $errorCode"; nsd?.stopServiceDiscovery(this) }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) { nsd?.stopServiceDiscovery(this) }
        }
        discovery = listener
        nsd?.discoverServices("_phonevideo._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
    }
    private fun connect(device: PhoneDevice) {
        selected = device; message = "正在读取视频列表…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { JSONObject(URL("http://${device.host}:${device.port}/api/videos?page=1&pageSize=100").readText()) }
                val list = result.getJSONArray("items")
                videos.clear()
                for (i in 0 until list.length()) { val v = list.getJSONObject(i); videos.add(RemoteVideo(v.getString("id"), v.getString("name"), v.optLong("duration"), v.optLong("size"), v.optString("thumbnailUrl"), v.optString("streamUrl"))) }
                message = "${result.optInt("total")} 个视频"
            } catch (e: Exception) { message = "无法连接手机：${e.localizedMessage ?: "请重试"}" }
        }
    }
    private fun play(video: RemoteVideo) {
        playing = video
        player?.release()
        player = ExoPlayer.Builder(this).build().also { it.setMediaItem(MediaItem.fromUri(video.stream)); it.prepare(); it.playWhenReady = true }
    }
    private fun stopPlayback() { player?.release(); player = null; playing = null }
    override fun onDestroy() { player?.release(); discovery?.let { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }; super.onDestroy() }
    private fun formatDuration(ms: Long): String { val s = ms / 1000; return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }
    private fun formatSize(n: Long): String = when { n > 1_000_000_000 -> "%.1f GB".format(n / 1_000_000_000.0); n > 1_000_000 -> "%.0f MB".format(n / 1_000_000.0); else -> "$n B" }
}
