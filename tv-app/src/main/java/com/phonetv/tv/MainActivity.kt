package com.phonetv.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class PhoneDevice(val name: String, val host: String, val port: Int)
data class RemoteVideo(val id: String, val name: String, val folder: String, val duration: Long, val size: Long, val thumbnail: String, val stream: String)

class MainActivity : ComponentActivity() {
    private val devices = mutableStateListOf<PhoneDevice>()
    private val videos = mutableStateListOf<RemoteVideo>()
    private var selected by mutableStateOf<PhoneDevice?>(null)
    private var folder by mutableStateOf<String?>(null)
    private var playing by mutableStateOf<RemoteVideo?>(null)
    private var message by mutableStateOf("正在搜索手机…")
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var player: ExoPlayer? = null
    private var bandwidthMeter: DefaultBandwidthMeter? = null
    private val resolving = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        startDiscovery()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, primary = Cyan)) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    val current = playing
                    val currentFolder = folder
                    when {
                        current != null -> PlayerScreen(current)
                        selected == null -> ConnectScreen()
                        currentFolder != null -> FolderScreen(currentFolder)
                        else -> LibraryScreen()
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && playing != null) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.systemBars())
            isAppearanceLightStatusBars = false
        }
    }

    @Composable
    private fun Modifier.screenInsets() = this.windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)

    @Composable
    private fun ConnectScreen() {
        BoxWithConstraints(Modifier.fillMaxSize().screenInsets()) {
            val phone = maxWidth < 600.dp
            val side = if (phone) 20.dp else 64.dp
            Column(Modifier.fillMaxSize().padding(horizontal = side, vertical = if (phone) 20.dp else 42.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("发现手机", color = Color.White, fontSize = if (phone) 32.sp else 40.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                    Text("同一网络", color = Color(0xFFB7BDC6), fontSize = if (phone) 16.sp else 20.sp, maxLines = 1)
                }
                Spacer(Modifier.height(22.dp))
                if (devices.isEmpty()) {
                    Text(if (message.contains("失败")) message else "正在搜索…", color = Sub, fontSize = 16.sp, modifier = Modifier.padding(bottom = 16.dp))
                }
                devices.forEach { device ->
                    FocusRow(onClick = { connect(device) }) {
                        Icon(Icons.Default.PhoneAndroid, null, tint = Color(0xFFD7DCE2), modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(device.name, color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("连接", color = Color(0xFFD7DCE2), fontSize = 16.sp)
                    }
                }
                FocusRow(onClick = { rescan() }) {
                    Icon(Icons.Default.Refresh, null, tint = Cyan, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("重新扫描", color = Color.White, fontSize = 18.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("请打开手机端，并连到同一网络", color = Color(0xFFB7BDC6), fontSize = 15.sp, modifier = Modifier.fillMaxWidth(), maxLines = 1)
            }
        }
    }

    @Composable
    private fun LibraryScreen() {
        val groups = videos.groupBy { it.folder }.entries.sortedByDescending { it.value.size }
        Column(Modifier.fillMaxSize().screenInsets().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("片库", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(Icons.Default.PhoneAndroid, null, tint = if (selected != null) Cyan else Color(0xFF8E949C))
                Spacer(Modifier.width(8.dp))
                Text(if (selected != null) "手机在线" else "未连接", color = if (selected != null) Cyan else Color(0xFF8E949C), fontSize = 18.sp)
            }
            Text("来自手机", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 28.dp, bottom = 16.dp))
            if (groups.isEmpty()) {
                Text(message, color = Sub, fontSize = 16.sp)
            } else groups.forEach { (name, items) ->
                FocusRow(onClick = { folder = name }) {
                    Icon(Icons.Default.Folder, null, tint = Color.White)
                    Spacer(Modifier.width(16.dp))
                    Text(name, color = Color.White, fontSize = 22.sp)
                    Spacer(Modifier.width(14.dp))
                    Text("${items.size} 部", color = Color(0xFFD0D4DA), fontSize = 18.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("设备：", color = Color.White, fontSize = 18.sp)
                Icon(Icons.Default.PhoneAndroid, null, tint = if (selected != null) Cyan else Sub, modifier = Modifier.padding(horizontal = 8.dp))
                Text(selected?.name ?: message, color = if (selected != null) Cyan else Sub, fontSize = 16.sp)
            }
        }
    }

    @Composable
    private fun FolderScreen(name: String) {
        val items = videos.filter { it.folder == name }
        BackHandler { folder = null }
        BoxWithConstraints(Modifier.fillMaxSize().screenInsets()) {
            val phone = maxWidth < 700.dp
            Column(Modifier.fillMaxSize().padding(horizontal = if (phone) 16.dp else 42.dp, vertical = if (phone) 16.dp else 32.dp)) {
                Text(name, color = Color.White, fontSize = if (phone) 22.sp else 34.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = if (phone) 28.sp else 40.sp, modifier = Modifier.fillMaxWidth())
                Text("来自手机 · ${items.size} 部", color = Sub, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    items(items, key = { it.id }) { video -> PosterCard(video, phone) { play(video) } }
                }
            }
        }
    }

    @Composable
    private fun PosterCard(video: RemoteVideo, phone: Boolean, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        val width = if (phone) 132.dp else if (focused) 210.dp else 168.dp
        Column(Modifier.width(width).onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).focusable()) {
            Box(Modifier.fillMaxWidth().height(if (phone) 186.dp else if (focused) 300.dp else 236.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C2026)).border(if (focused) 2.dp else 0.dp, Cyan, RoundedCornerShape(12.dp))) {
                AsyncImage(model = video.thumbnail, contentDescription = video.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Text(video.name.substringBefore('.'), color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
        }
    }

    @Composable
    private fun PlayerScreen(video: RemoteVideo) {
        val exo = player
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(video.duration) }
        var playingNow by remember { mutableStateOf(true) }
        var speed by remember { mutableStateOf("--") }
        val siblings = videos.filter { it.folder == video.folder }
        BackHandler { stopPlayback() }
        DisposableEffect(Unit) {
            hideSystemBars()
            onDispose { showSystemBars() }
        }
        LaunchedEffect(exo) {
            while (true) {
                position = exo?.currentPosition ?: 0L
                duration = exo?.duration?.takeIf { it > 0 } ?: video.duration
                playingNow = exo?.isPlaying == true
                val bps = bandwidthMeter?.bitrateEstimate ?: 0L
                speed = if (bps > 0) "%.2f Mbps".format(bps / 1_000_000.0) else "--"
                delay(500)
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = exo; useController = false } }, modifier = Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (siblings.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            items(siblings, key = { it.id }) { item ->
                                val active = item.id == video.id
                                Text(item.name.substringBefore('.'), color = if (active) Cyan else Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, if (active) Cyan else Color(0x88FFFFFF), RoundedCornerShape(16.dp)).clickable { play(item) }.focusable().padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }
                    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Cyan))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(video.name.substringBefore('.'), color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${formatClock(position)} / ${formatClock(duration)}", color = Color.White, fontSize = 12.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(speed, color = Color.White, fontSize = 12.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        RoundButton(Icons.Default.SkipPrevious) { playSibling(video, -1) }
                        Spacer(Modifier.width(10.dp))
                        RoundButton(Icons.Default.Replay10) { exo?.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)) }
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(64.dp).clip(CircleShape).border(2.dp, Cyan, CircleShape).clickable { if (exo?.isPlaying == true) exo.pause() else exo?.play() }.focusable(), contentAlignment = Alignment.Center) {
                            Icon(if (playingNow) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        RoundButton(Icons.Default.Forward10) { exo?.seekTo(exo.currentPosition + 10_000) }
                        Spacer(Modifier.width(10.dp))
                        RoundButton(Icons.Default.SkipNext) { playSibling(video, 1) }
                    }
                }
            }
        }
    }

    @Composable
    private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
        Box(Modifier.size(52.dp).clip(CircleShape).border(1.dp, Color(0x88FFFFFF), CircleShape).clickable(onClick = onClick).focusable(), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }

    @Composable
    private fun FocusRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF23282F)).border(if (focused) 2.dp else 0.dp, Cyan, RoundedCornerShape(14.dp)).onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).focusable().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }

    private fun playSibling(video: RemoteVideo, delta: Int) {
        val siblings = videos.filter { it.folder == video.folder }
        val index = siblings.indexOfFirst { it.id == video.id }
        val next = siblings.getOrNull(index + delta) ?: return
        play(next)
    }

    private fun startDiscovery() {
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { runOnUiThread { message = "正在搜索手机…" } }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains("_phonevideo._tcp") || !resolving.add(info.serviceName)) return
                nsd?.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { resolving.remove(serviceInfo.serviceName) }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving.remove(serviceInfo.serviceName)
                        val host = serviceInfo.host?.hostAddress ?: return
                        runOnUiThread {
                            if (devices.none { it.host == host }) devices.add(PhoneDevice(serviceInfo.serviceName, host, serviceInfo.port))
                            message = "选择一台手机"
                        }
                    }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) { runOnUiThread { devices.removeAll { it.name == info.serviceName }; if (selected?.name == info.serviceName) selected = null } }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) { message = "搜索失败 $errorCode" }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
        }
        discovery = listener
        nsd?.discoverServices("_phonevideo._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        probeLocal()
    }

    private fun probeLocal() {
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                try {
                    val connection = URL("http://127.0.0.1:8080/api/device").openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 800
                    connection.readTimeout = 800
                    connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
                } catch (_: Exception) { null }
            }
            if (found != null && devices.none { it.host == "127.0.0.1" }) {
                devices.add(0, PhoneDevice(found.optString("deviceName", "本机"), "127.0.0.1", 8080))
                message = "选择一台手机"
            }
        }
    }

    private fun rescan() {
        message = "正在重新扫描…"
        devices.clear()
        resolving.clear()
        discovery?.let { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }
        startDiscovery()
    }

    private fun connect(device: PhoneDevice) {
        selected = device
        message = "正在读取视频…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { JSONObject(URL("http://${device.host}:${device.port}/api/videos?page=1&pageSize=100").readText()) }
                val list = result.getJSONArray("items")
                videos.clear()
                for (i in 0 until list.length()) {
                    val v = list.getJSONObject(i)
                    videos.add(RemoteVideo(v.getString("id"), v.getString("name"), v.optString("folder", "其他"), v.optLong("duration"), v.optLong("size"), v.optString("thumbnailUrl"), v.optString("streamUrl")))
                }
                message = if (videos.isEmpty()) "手机上没有可共享的视频" else "已连接"
            } catch (e: Exception) { message = "无法连接手机" }
        }
    }

    private fun play(video: RemoteVideo) {
        playing = video
        if (bandwidthMeter == null) bandwidthMeter = DefaultBandwidthMeter.Builder(this).build()
        player?.release()
        player = ExoPlayer.Builder(this).setBandwidthMeter(bandwidthMeter!!).build().also { it.setMediaItem(MediaItem.fromUri(video.stream)); it.prepare(); it.playWhenReady = true }
    }

    private fun stopPlayback() { player?.release(); player = null; playing = null }
    override fun onDestroy() { player?.release(); discovery?.let { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }; super.onDestroy() }
    private fun formatDuration(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    private fun formatClock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}

private val Bg = Color(0xFF101114)
private val Cyan = Color(0xFF20C6BE)
private val Sub = Color(0xFF9AA1AA)
