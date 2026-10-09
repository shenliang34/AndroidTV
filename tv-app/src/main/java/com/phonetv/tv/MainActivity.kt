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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.view.KeyEvent
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class PhoneDevice(val name: String, val host: String, val port: Int)
data class RemoteVideo(val id: String, val name: String, val folder: String, val duration: Long, val size: Long, val thumbnail: String, val stream: String, val token: String)
data class RemoteCatalog(val total: Int, val videos: List<RemoteVideo>)

class MainActivity : ComponentActivity() {
    private val devices = mutableStateListOf<PhoneDevice>()
    private val videos = mutableStateListOf<RemoteVideo>()
    private var selected by mutableStateOf<PhoneDevice?>(null)
    private var pairingDevice by mutableStateOf<PhoneDevice?>(null)
    private var pairingPhoneId by mutableStateOf<String?>(null)
    private var pairingCode by mutableStateOf("")
    private var pairingError by mutableStateOf<String?>(null)
    private var authToken by mutableStateOf<String?>(null)
    private var serverVideoTotal by mutableIntStateOf(0)
    private var folder by mutableStateOf<String?>(null)
    private var playing by mutableStateOf<RemoteVideo?>(null)
    private var message by mutableStateOf("正在搜索手机…")
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var player: ExoPlayer? = null
    private var bandwidthMeter: DefaultBandwidthMeter? = null
    private val resolving = mutableSetOf<String>()
    private val secureImageLoader by lazy {
        ImageLoader.Builder(this).okHttpClient {
            OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val phone = selected
                val token = authToken
                if (phone != null && token != null && request.url.host == phone.host && request.url.port == phone.port) {
                    chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
                } else chain.proceed(request)
            }.build()
        }.build()
    }

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
                    BackHandler(playing != null || folder != null || selected != null) { goBack() }
                    val current = playing
                    val currentFolder = folder
                    when {
                        current != null -> PlayerScreen(current)
                        selected == null -> ConnectScreen()
                        currentFolder != null -> FolderScreen(currentFolder)
                        else -> LibraryScreen()
                    }
                    val deviceToPair = pairingDevice
                    if (deviceToPair != null) {
                        AlertDialog(
                            onDismissRequest = { dismissPairing() },
                            title = { Text("重新配对 ${deviceToPair.name}") },
                            text = {
                                Column {
                                    Text("手机已撤销授权或配对已失效。请查看手机上新的 6 位配对码。")
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedTextField(
                                        value = pairingCode,
                                        onValueChange = { pairingCode = it.filter(Char::isDigit).take(6); pairingError = null },
                                        label = { Text("配对码") },
                                        singleLine = true,
                                        isError = pairingError != null
                                    )
                                    pairingError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                                }
                            },
                            confirmButton = { TextButton(enabled = pairingCode.length == 6, onClick = { pairAndConnect(deviceToPair, pairingPhoneId, pairingCode) }) { Text("配对") } },
                            dismissButton = { TextButton(onClick = { dismissPairing() }) { Text("取消") } }
                        )
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
        val online = selected != null
        Column(Modifier.fillMaxSize().screenInsets().padding(horizontal = 28.dp, vertical = 12.dp)) {
            PageHeader("片库", "已载入 ${videos.size} / $serverVideoTotal 部")
            Spacer(Modifier.height(16.dp))
            if (groups.isEmpty()) {
                Text(message, color = Sub, fontSize = 16.sp)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(groups.toList(), key = { it.key }) { (name, items) ->
                        FolderCard(name, items.size) { folder = name }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(28.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Cyan))
                Spacer(Modifier.width(12.dp))
                Text("设备", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.width(12.dp))
                Text(if (online) "手机在线" else message, color = if (online) Cyan else Sub, fontSize = 16.sp)
            }
        }
    }

    @Composable
    private fun FolderCard(name: String, count: Int, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Column(Modifier.width(124.dp).height(112.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B2128)).border(if (focused) 2.dp else 0.dp, Cyan, RoundedCornerShape(16.dp)).onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Folder, null, tint = Color.White, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count 部", color = Color(0xFFE6E8EB), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }

    @Composable
    private fun FolderScreen(name: String) {
        val items = videos.filter { it.folder == name }
        var selectedIndex by remember(name) { mutableIntStateOf(if (items.isEmpty()) 0 else 1) }
        BackHandler { goBack() }
        Column(Modifier.fillMaxSize().screenInsets().background(Color(0xFF0B0E14)).padding(horizontal = 28.dp, vertical = 12.dp)) {
            PageHeader(name, "来自手机 ${selectedIndex}/${items.size} 部")
            Spacer(Modifier.height(16.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(end = 28.dp, bottom = 8.dp)) {
                itemsIndexed(items, key = { _, video -> video.id }) { index, video ->
                    PosterCard(video, onFocus = { selectedIndex = index + 1 }, onClick = { play(video) })
                }
            }
        }
    }

    @Composable
    private fun PosterCard(video: RemoteVideo, onFocus: () -> Unit, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Column(Modifier.width(150.dp).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }.focusable().clickable(onClick = onClick)) {
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C2026)).border(if (focused) 2.dp else 0.dp, Cyan, RoundedCornerShape(14.dp))) {
                AsyncImage(model = video.thumbnail, imageLoader = secureImageLoader, contentDescription = video.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Text(video.name.substringBefore('.'), color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().basicMarquee())
        }
    }

    @Composable
    private fun PageHeader(title: String, subtitle: String) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp).basicMarquee())
            Spacer(Modifier.width(12.dp))
            Text(subtitle, color = Color(0xFFB7BDC6), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).basicMarquee())
            BackButton()
        }
    }

    @Composable
    private fun BackButton() {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF23282F)).clickable { goBack() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }

    @Composable
    private fun PlayerScreen(video: RemoteVideo) {
        val exo = player
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(video.duration) }
        var playingNow by remember { mutableStateOf(true) }
        var speedText by remember { mutableStateOf("--") }
        var rate by remember { mutableFloatStateOf(1f) }
        var controls by remember { mutableStateOf(true) }
        var tick by remember { mutableIntStateOf(0) }
        val siblings = videos.filter { it.folder == video.folder }
        val focusRequester = remember { FocusRequester() }
        val rates = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        fun showControls() { controls = true; tick++ }
        fun seekBy(delta: Long) {
            val target = ((exo?.currentPosition ?: 0L) + delta).coerceAtLeast(0)
            val limit = exo?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE
            exo?.seekTo(target.coerceAtMost(limit))
            showControls()
        }
        fun togglePlay() {
            if (exo?.isPlaying == true) exo.pause() else exo?.play()
            showControls()
        }
        fun cycleRate() {
            val next = rates[(rates.indexOf(rate) + 1).mod(rates.size)]
            rate = next
            exo?.setPlaybackSpeed(next)
            showControls()
        }
        BackHandler { goBack() }
        DisposableEffect(Unit) {
            hideSystemBars()
            onDispose { showSystemBars() }
        }
        LaunchedEffect(exo, tick) {
            while (true) {
                position = exo?.currentPosition ?: 0L
                duration = exo?.duration?.takeIf { it > 0 } ?: video.duration
                playingNow = exo?.isPlaying == true
                val bps = bandwidthMeter?.bitrateEstimate ?: 0L
                speedText = if (bps > 0) "%.2f Mbps".format(bps / 1_000_000.0) else "--"
                delay(500)
            }
        }
        LaunchedEffect(tick, playingNow) {
            if (!playingNow) { controls = true; return@LaunchedEffect }
            controls = true
            delay(4000)
            controls = false
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focusRequester).focusable().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10_000); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (!controls) showControls() else togglePlay()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); false }
                else -> false
            }
        }.pointerInput(exo) {
            detectTapGestures { tick++; controls = !controls }
        }.pointerInput(exo) {
            detectHorizontalDragGestures(
                onDragStart = { controls = true; tick++ },
                onHorizontalDrag = { _, dx ->
                    tick++
                    val jump = (dx * 120).toLong()
                    exo?.seekTo((exo.currentPosition + jump).coerceIn(0, (exo.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)))
                }
            )
        }) {
            AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = exo; useController = false } }, modifier = Modifier.fillMaxSize())
            if (controls) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (siblings.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        items(siblings, key = { it.id }) { item ->
                            val active = item.id == video.id
                            Text(item.name.substringBefore('.'), color = if (active) Cyan else Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp).clip(RoundedCornerShape(16.dp)).border(if (active) 2.dp else 1.dp, if (active) Cyan else Color(0x88FFFFFF), RoundedCornerShape(16.dp)).clickable { play(item); tick++ }.focusable().padding(horizontal = 10.dp, vertical = 6.dp))
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
                    Text(speedText, color = Color.White, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(Icons.Default.SkipPrevious) { playSibling(video, -1); tick++ }
                    Spacer(Modifier.width(10.dp))
                    RoundButton(Icons.Default.Replay10) { exo?.seekTo((exo.currentPosition - 10_000).coerceAtLeast(0)); tick++ }
                    Spacer(Modifier.width(10.dp))
                    RoundButton(if (playingNow) Icons.Default.Pause else Icons.Default.PlayArrow, emphasized = true) { if (exo?.isPlaying == true) exo.pause() else exo?.play(); tick++ }
                    Spacer(Modifier.width(10.dp))
                    RoundButton(Icons.Default.Forward10) { exo?.seekTo(exo.currentPosition + 10_000); tick++ }
                    Spacer(Modifier.width(10.dp))
                    RoundButton(Icons.Default.SkipNext) { playSibling(video, 1); showControls() }
                    Spacer(Modifier.width(16.dp))
                    Text(if (rate == 1f) "1.0x" else "${rate}x", color = Color.White, fontSize = 16.sp, modifier = Modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, Color(0x88FFFFFF), RoundedCornerShape(18.dp)).clickable { cycleRate() }.focusable().padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
        }
    }

    @Composable
    private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, emphasized: Boolean = false, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Box(Modifier.size(if (emphasized) 64.dp else 52.dp).clip(CircleShape).background(if (focused) Cyan.copy(alpha = 0.28f) else Color.Transparent).border(if (focused) 3.dp else 1.dp, if (focused) Cyan else Color(0x88FFFFFF), CircleShape).onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(if (emphasized) 30.dp else 24.dp))
        }
    }

    @Composable
    private fun FocusRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp)).background(if (focused) Color(0xFF24343A) else Color(0xFF23282F)).border(if (focused) 3.dp else 0.dp, Cyan, RoundedCornerShape(14.dp)).onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, content = content)
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
                        runOnUiThread { addDevice(PhoneDevice(serviceInfo.serviceName, host, serviceInfo.port)) }
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
            if (found != null) addDevice(PhoneDevice(found.optString("deviceName", "本机"), "127.0.0.1", 8080))
        }
    }

    private fun addDevice(device: PhoneDevice) {
        val localHosts = localHosts()
        val host = device.host.removePrefix("/").substringBefore('%')
        val normalized = device.copy(host = host)
        if (host == "127.0.0.1" && devices.any { it.host != "127.0.0.1" && it.host in localHosts }) return
        if (host in localHosts && devices.any { it.host == "127.0.0.1" }) {
            devices.removeAll { it.host == "127.0.0.1" }
        }
        if (devices.any { it.host == host || it.name == normalized.name }) return
        devices.add(normalized)
        message = "选择一台手机"
    }

    private fun localHosts(): Set<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress?.removePrefix("/")?.substringBefore('%') }.toSet()
    } catch (_: Exception) { emptySet() }

    private fun rescan() {
        message = "正在重新扫描…"
        devices.clear()
        resolving.clear()
        discovery?.let { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }
        startDiscovery()
    }

    private fun connect(device: PhoneDevice) {
        message = "正在连接手机…"
        lifecycleScope.launch {
            try {
                val (phoneId, cachedToken) = withContext(Dispatchers.IO) {
                    val info = requestJson(URL("http://${device.host}:${device.port}/api/device"))
                    val id = info.getString("deviceId")
                    id to getSharedPreferences("pairing", MODE_PRIVATE).getString("token_$id", null)
                }
                if (!cachedToken.isNullOrBlank()) {
                    try {
                        val catalog = withContext(Dispatchers.IO) { loadVideos(device, cachedToken) }
                        completeConnection(device, cachedToken, catalog)
                        return@launch
                    } catch (error: HttpStatusException) {
                        if (error.statusCode != 401) throw error
                        getSharedPreferences("pairing", MODE_PRIVATE).edit().remove("token_$phoneId").apply()
                        selected = null
                        videos.clear()
                        authToken = null
                        pairingPhoneId = phoneId
                        pairingDevice = device
                        pairingCode = ""
                        pairingError = "授权已失效，请输入手机上的新配对码"
                        message = "请重新配对"
                    }
                }
                pairingPhoneId = phoneId
                pairingDevice = device
                pairingCode = ""
                pairingError = null
                message = "请输入手机上的配对码"
            } catch (_: Exception) { message = "无法连接手机，请确认手机共享已开启" }
        }
    }

    private fun pairAndConnect(device: PhoneDevice, phoneId: String?, code: String) {
        if (phoneId.isNullOrBlank()) {
            pairingError = "手机信息无效，请返回重新连接"
            return
        }
        pairingError = null
        lifecycleScope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    val body = JSONObject()
                        .put("code", code)
                        .put("deviceId", tvDeviceId())
                        .put("deviceName", android.os.Build.MODEL.ifBlank { "Android TV" })
                        .toString()
                    requestJson(URL("http://${device.host}:${device.port}/api/pair"), method = "POST", body = body).getString("token")
                }
                getSharedPreferences("pairing", MODE_PRIVATE).edit().putString("token_$phoneId", token).apply()
                val catalog = withContext(Dispatchers.IO) { loadVideos(device, token) }
                pairingDevice = null
                pairingPhoneId = null
                pairingCode = ""
                completeConnection(device, token, catalog)
            } catch (error: HttpStatusException) {
                pairingError = if (error.statusCode == 401) "配对码错误、已过期或已使用，请检查手机上的新配对码。" else "配对失败（HTTP ${error.statusCode}）"
            } catch (_: Exception) {
                pairingError = "连接失败，请确认两台设备在同一网络且手机共享已开启。"
            }
        }
    }

    private fun dismissPairing() {
        pairingDevice = null
        pairingPhoneId = null
        pairingCode = ""
        pairingError = null
    }

    private fun tvDeviceId(): String {
        val prefs = getSharedPreferences("pairing", MODE_PRIVATE)
        return prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }
    }

    private fun loadVideos(device: PhoneDevice, token: String): RemoteCatalog {
        val loaded = mutableListOf<RemoteVideo>()
        var page = 1
        var total = Int.MAX_VALUE
        while (loaded.size < total) {
            val result = requestJson(URL("http://${device.host}:${device.port}/api/videos?page=$page&pageSize=100"), token = token)
            val items = result.getJSONArray("items")
            total = result.optInt("total", loaded.size + items.length()).coerceAtLeast(0)
            if (items.length() == 0) break
            for (index in 0 until items.length()) {
                val video = items.getJSONObject(index)
                loaded += RemoteVideo(
                    video.getString("id"), video.getString("name"), video.optString("folder", "其他"),
                    video.optLong("duration"), video.optLong("size"), video.optString("thumbnailUrl"), video.optString("streamUrl"), token
                )
            }
            page++
        }
        return RemoteCatalog(total.coerceAtMost(Int.MAX_VALUE), loaded)
    }

    private fun requestJson(url: URL, token: String? = null, method: String = "GET", body: String? = null): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 10000
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
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun completeConnection(device: PhoneDevice, token: String, catalog: RemoteCatalog) {
        authToken = token
        videos.clear()
        videos.addAll(catalog.videos)
        serverVideoTotal = catalog.total
        selected = device
        message = if (catalog.total == 0) "手机上没有可共享的视频" else "已加载 ${videos.size} / ${catalog.total} 个视频"
    }

    private class HttpStatusException(val statusCode: Int) : IOException("HTTP $statusCode")

    private fun play(video: RemoteVideo) {
        playing = video
        if (bandwidthMeter == null) bandwidthMeter = DefaultBandwidthMeter.Builder(this).build()
        player?.release()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${video.token}"))
            .setTransferListener(bandwidthMeter!!)
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).setBandwidthMeter(bandwidthMeter!!).build()
            .also { it.setMediaItem(MediaItem.fromUri(video.stream)); it.prepare(); it.playWhenReady = true }
    }

    private fun goBack() {
        when {
            playing != null -> stopPlayback()
            folder != null -> folder = null
            selected != null -> { selected = null; authToken = null; videos.clear() }
            else -> finish()
        }
    }

    private fun stopPlayback() { player?.release(); player = null; playing = null }
    override fun onDestroy() { player?.release(); discovery?.let { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }; super.onDestroy() }
    private fun formatDuration(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    private fun formatClock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}

private val Bg = Color(0xFF101114)
private val Cyan = Color(0xFF20C6BE)
private val Sub = Color(0xFF9AA1AA)
