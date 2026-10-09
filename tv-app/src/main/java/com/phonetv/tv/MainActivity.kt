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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.Slider
import androidx.compose.runtime.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import android.view.KeyEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
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
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
data class RemoteVideo(val id: String, val name: String, val folder: String, val duration: Long, val size: Long, val modified: Long, val thumbnail: String, val stream: String, val token: String)
data class RemoteCatalog(val total: Int, val videos: List<RemoteVideo>)

class MainActivity : ComponentActivity() {
    private val devices = mutableStateListOf<PhoneDevice>()
    private val videos = mutableStateListOf<RemoteVideo>()
    private var selected by mutableStateOf<PhoneDevice?>(null)
    private var pairingDevice by mutableStateOf<PhoneDevice?>(null)
    private var pairingPhoneId by mutableStateOf<String?>(null)
    private var pairingRequestId by mutableStateOf<String?>(null)
    private var pairingJob: Job? = null
    private var pairingError by mutableStateOf<String?>(null)
    private var authToken by mutableStateOf<String?>(null)
    private var serverVideoTotal by mutableIntStateOf(0)
    private var folder by mutableStateOf<String?>(null)
    private var browseMode by mutableStateOf("全部视频")
    private var searchText by mutableStateOf("")
    private var sortMode by mutableStateOf("最新优先")
    private var resumePrompt by mutableStateOf<RemoteVideo?>(null)
    private var pendingResume by mutableStateOf<Long?>(null)
    private var playbackError by mutableStateOf<String?>(null)
    private var isBuffering by mutableStateOf(false)
    private var playing by mutableStateOf<RemoteVideo?>(null)
    private var message by mutableStateOf("正在搜索手机…")
    private var showNetworkSpeed by mutableStateOf(false)
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
        showNetworkSpeed = getSharedPreferences("player", MODE_PRIVATE).getBoolean("showNetworkSpeed", false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, primary = Accent)) {
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
                    resumePrompt?.let { resumeVideo ->
                        val saved = savedPosition(resumeVideo)
                        AlertDialog(onDismissRequest = { resumePrompt = null; startVideo(resumeVideo, 0L) },
                            title = { Text("继续播放？") }, text = { Text("${resumeVideo.name}\n上次看到 ${formatClock(saved)}") },
                            confirmButton = { TextButton(onClick = { resumePrompt = null; startVideo(resumeVideo, saved) }) { Text("继续播放") } },
                            dismissButton = { TextButton(onClick = { resumePrompt = null; startVideo(resumeVideo, 0L) }) { Text("从头播放") } })
                    }
                    val deviceToPair = pairingDevice
                    if (deviceToPair != null) {
                        AlertDialog(
                            onDismissRequest = { dismissPairing() },
                            title = { Text("等待手机确认") },
                            text = {
                                Column {
                                    Text("${deviceToPair.name} 已向手机发送连接请求。请在手机上选择允许。")
                                    pairingError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                                }
                            },
                            confirmButton = { TextButton(onClick = {}) { Text("等待确认…") } },
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
        var refreshFocused by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(Bg).screenInsets().padding(horizontal = 28.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("发现手机", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text("同一网络", color = Color(0xFFB7BDC6), fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(52.dp).clip(CircleShape)
                    .background(if (refreshFocused) Color(0xFF30251C) else Panel)
                    .border(if (refreshFocused) 2.dp else 0.dp, Accent, CircleShape)
                    .onFocusChanged { refreshFocused = it.isFocused }.focusable().clickable { rescan() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Refresh, "重新扫描", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.height(26.dp))
            if (devices.isEmpty()) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel)
                    .border(1.dp, Color(0xFF34383D), RoundedCornerShape(18.dp)).padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PhoneAndroid, null, tint = Accent, modifier = Modifier.size(25.dp))
                    Spacer(Modifier.width(20.dp))
                    Text(if (message.contains("失败")) "未找到手机" else "正在搜索手机…", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(14.dp))
                    Text(if (message.contains("失败")) message else "请在手机上开启共享，并连接同一网络", color = Sub, fontSize = 15.sp, modifier = Modifier.weight(1f))
                }
            } else devices.forEach { device -> DeviceCard(device.name, onClick = { connect(device) }) }
            Spacer(Modifier.height(14.dp))
            Text(if (devices.isEmpty()) "打开手机端共享后，设备会自动显示在这里。" else "${devices.size} 台设备在线 · 选择设备即可连接。", color = Sub, fontSize = 14.sp)
        }
    }

    @Composable
    private fun DiscoveryArtwork(modifier: Modifier = Modifier) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Box(Modifier.size(236.dp).clip(CircleShape).border(1.dp, Accent.copy(alpha = 0.13f), CircleShape))
            Box(Modifier.size(184.dp).clip(CircleShape).border(1.dp, Accent.copy(alpha = 0.18f), CircleShape))
            Box(Modifier.size(138.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF44301C), Color(0xFF211B18))))
                .border(1.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = Accent, modifier = Modifier.size(64.dp))
            }
            Row(Modifier.align(Alignment.BottomCenter).clip(RoundedCornerShape(30.dp)).background(Color(0xCC18181B))
                .border(1.dp, Color(0xFF343034), RoundedCornerShape(30.dp)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Accent))
                Spacer(Modifier.width(8.dp))
                Text("LOCAL  ·  NO CLOUD", color = Color(0xFFD1C3B4), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp)
            }
        }
    }

    @Composable
    private fun DeviceCard(name: String, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(18.dp))
            .background(if (focused) Color(0xFF30251C) else Panel)
            .border(if (focused) 3.dp else 1.dp, if (focused) Accent else Color(0xFF272C33), RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF302B28)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PhoneAndroid, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("手机媒体库", color = Sub, fontSize = 14.sp)
            }
            Text("连接", color = if (focused) Color(0xFF071111) else Accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (focused) Accent else Accent.copy(alpha = 0.12f)).padding(horizontal = 18.dp, vertical = 9.dp))
        }
    }

    @Composable
    private fun LibraryScreen() {
        val groups = videos.groupBy { it.folder }.entries.sortedByDescending { it.value.size }
        val visibleVideos = videos.filter { it.name.contains(searchText, ignoreCase = true) }
        val sortedVideos = when (sortMode) {
            "文件名" -> visibleVideos.sortedBy { it.name.lowercase() }
            "文件大小" -> visibleVideos.sortedByDescending { it.size }
            "视频时长" -> visibleVideos.sortedByDescending { it.duration }
            "最旧优先" -> visibleVideos.sortedBy { it.modified }
            else -> visibleVideos.sortedByDescending { it.modified }
        }
        Column(Modifier.fillMaxSize().screenInsets().padding(horizontal = 28.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(selected?.name ?: "手机媒体库", color = Sub, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("片库", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
                }
                Box(Modifier.weight(1.2f), contentAlignment = Alignment.Center) {
                    OutlinedTextField(value = searchText, onValueChange = { searchText = it }, singleLine = true,
                        placeholder = { Text("搜索片名", fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.fillMaxWidth(0.88f).height(52.dp), textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(14.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFF333942), focusedBorderColor = Accent))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text("${videos.size} / $serverVideoTotal 部", color = Color(0xFFD0D5DB), fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Panel).padding(horizontal = 14.dp, vertical = 9.dp))
                    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("网速", color = Sub, fontSize = 13.sp)
                        Switch(checked = showNetworkSpeed, onCheckedChange = {
                            showNetworkSpeed = it
                            getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("showNetworkSpeed", it).apply()
                        }, modifier = Modifier.scale(0.78f))
                    }
                    Spacer(Modifier.width(4.dp))
                    BackButton()
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("全部视频", "最近添加", "文件夹").forEach { mode ->
                        BrowseChip(mode, selected = browseMode == mode) { browseMode = mode; folder = if (mode == "文件夹") folder else null }
                    }
                }
                Spacer(Modifier.weight(1f))
                var sortExpanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { sortExpanded = true }) { Text("排序：$sortMode", color = Accent, fontSize = 15.sp) }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        listOf("最新优先", "最旧优先", "文件名", "文件大小", "视频时长").forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { sortMode = option; sortExpanded = false })
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (groups.isEmpty()) {
                EmptyLibrary(message)
            } else if (browseMode == "文件夹") {
                LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 220.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 10.dp)) {
                    gridItems(groups.toList(), key = { it.key }) { (name, items) ->
                        FolderCard(name, items.size) { folder = name }
                    }
                }
            } else if (sortedVideos.isEmpty()) {
                EmptyLibrary("没有找到匹配的视频，换个片名或清除搜索条件试试。")
            } else {
                val list = if (browseMode == "最近添加") sortedVideos.take(30) else sortedVideos
                Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (browseMode == "最近添加") "最近添加" else "所有视频", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(10.dp))
                    Text("${list.size} 部", color = Sub, fontSize = 14.sp)
                }
                LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 220.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 10.dp)) {
                    gridItems(list, key = { it.id }) { video -> PosterCard(video, onFocus = {}, onClick = { requestPlay(video) }, fillCell = true) }
                }
            }
        }
    }

    @Composable
    private fun FeaturedVideo(video: RemoteVideo, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().height(214.dp).clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF202832), Color(0xFF15191F))))
            .border(if (focused) 3.dp else 1.dp, if (focused) Accent else Color(0xFF2A313A), RoundedCornerShape(22.dp))
            .onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick)) {
            Box(Modifier.width(310.dp).fillMaxHeight().background(Color(0xFF20252C))) {
                AsyncImage(model = video.thumbnail, imageLoader = secureImageLoader, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xAA15191F)))))
            }
            Column(Modifier.fillMaxHeight().weight(1f).padding(horizontal = 28.dp, vertical = 22.dp), verticalArrangement = Arrangement.Center) {
                Text("最近添加", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(8.dp))
                Text(video.name.substringBeforeLast('.'), color = Color.White, fontSize = 25.sp, lineHeight = 31.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text("${video.folder}  ·  ${formatDuration(video.duration)}", color = Sub, fontSize = 15.sp)
                Spacer(Modifier.height(13.dp))
                Text("▶  立即播放", color = if (focused) Color(0xFF071111) else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(if (focused) Accent else Color(0xFF303942)).padding(horizontal = 17.dp, vertical = 9.dp))
            }
        }
    }

    @Composable
    private fun BrowseChip(label: String, selected: Boolean, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        val active = selected || focused
        Text(label, color = if (active) Color(0xFF071111) else Color(0xFFCBD0D6), fontSize = 15.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(if (active) Accent else Panel)
                .border(if (focused && !selected) 2.dp else 0.dp, Accent, RoundedCornerShape(22.dp))
                .onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick).padding(horizontal = 17.dp, vertical = 10.dp))
    }

    @Composable
    private fun EmptyLibrary(text: String) {
        Column(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(20.dp)).background(Panel).padding(24.dp),
            verticalArrangement = Arrangement.Center) {
            Text(if (videos.isEmpty()) "这里还没有视频" else "没有找到匹配的视频", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(if (videos.isEmpty()) text else "换个片名试试，或清除搜索条件。", color = Sub, fontSize = 15.sp)
        }
    }

    @Composable
    private fun FolderCard(name: String, count: Int, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(18.dp)).background(if (focused) Color(0xFF30251C) else Panel)
            .border(if (focused) 3.dp else 1.dp, if (focused) Accent else Color(0xFF2A3038), RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick).padding(18.dp),
            horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Folder, null, tint = Accent, modifier = Modifier.size(30.dp))
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
                    PosterCard(video, onFocus = { selectedIndex = index + 1 }, onClick = { requestPlay(video) })
                }
            }
        }
    }

    @Composable
    private fun PosterCard(video: RemoteVideo, onFocus: () -> Unit, onClick: () -> Unit, fillCell: Boolean = false) {
        var focused by remember { mutableStateOf(false) }
        val tileWidth = if (fillCell) Modifier.fillMaxWidth() else Modifier.width(228.dp)
        Column(tileWidth.scale(if (focused) 1.04f else 1f)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }.focusable().clickable(onClick = onClick)) {
            Box(Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C2026))
                .border(if (focused) 3.dp else 1.dp, if (focused) Accent else Color(0xFF30353D), RoundedCornerShape(14.dp))) {
                AsyncImage(model = video.thumbnail, imageLoader = secureImageLoader, contentDescription = video.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC090B0D)).padding(horizontal = 7.dp, vertical = 4.dp)) {
                    Text(formatDuration(video.duration), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            Text(video.name.substringBeforeLast('.'), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.dp).fillMaxWidth().basicMarquee())
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
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF1B1A1D)).clickable { goBack() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }

    @Composable
    @OptIn(UnstableApi::class)
    private fun PlayerScreen(video: RemoteVideo) {
        val exo = player
        var position by remember { mutableLongStateOf(0L) }
        var duration by remember { mutableLongStateOf(video.duration) }
        var playingNow by remember { mutableStateOf(true) }
        var speedText by remember { mutableStateOf("--") }
        var rate by remember { mutableFloatStateOf(1f) }
        var controls by remember { mutableStateOf(true) }
        var tick by remember { mutableIntStateOf(0) }
        var draggingProgress by remember { mutableStateOf(false) }
        var sliderPosition by remember(video.id) { mutableFloatStateOf(0f) }
        var trackGroups by remember(exo) { mutableStateOf(exo?.currentTracks?.groups.orEmpty()) }
        var audioMenuExpanded by remember { mutableStateOf(false) }
        var subtitleMenuExpanded by remember { mutableStateOf(false) }
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
                isBuffering = exo?.playbackState == Player.STATE_BUFFERING
                trackGroups = exo?.currentTracks?.groups.orEmpty()
                if (!draggingProgress && duration > 0) sliderPosition = position.toFloat() / duration
                val bps = bandwidthMeter?.bitrateEstimate ?: 0L
                speedText = if (bps > 0) "%.2f Mbps".format(bps / 1_000_000.0) else "--"
                delay(500)
            }
        }
        LaunchedEffect(tick, playingNow) {
            if (!playingNow || !controls) return@LaunchedEffect
            delay(4000)
            controls = false
        }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focusRequester).focusable().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val repeatCount = event.nativeKeyEvent.repeatCount.coerceAtLeast(0)
            val seekStep = (10_000L * (1 + repeatCount / 3)).coerceAtMost(60_000L)
            when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-seekStep); true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(seekStep); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    if (!controls) showControls() else togglePlay()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); false }
                else -> false
            }
        }.pointerInput(exo) {
            detectTapGestures {
                controls = !controls
                if (controls) tick++
            }
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
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = false } },
                update = { view -> view.player = exo },
                modifier = Modifier.fillMaxSize()
            )
            AnimatedVisibility(
                visible = controls,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                enter = fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 12 },
                exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 14 }
            ) {
                Column(Modifier.fillMaxWidth().background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (siblings.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        items(siblings, key = { it.id }) { item ->
                            val active = item.id == video.id
                            Text(item.name.substringBefore('.'), color = if (active) Accent else Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp).clip(RoundedCornerShape(16.dp)).border(if (active) 2.dp else 1.dp, if (active) Accent else Color(0x88FFFFFF), RoundedCornerShape(16.dp)).clickable { play(item); tick++ }.focusable().padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatClock(position)} / ${formatClock(duration)}", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    if (showNetworkSpeed) Text(speedText, color = Color.White, fontSize = 12.sp)
                }
                Slider(value = sliderPosition.coerceIn(0f, 1f), onValueChange = { draggingProgress = true; sliderPosition = it },
                    onValueChangeFinished = { exo?.seekTo((sliderPosition * duration).toLong()); draggingProgress = false },
                    modifier = Modifier.fillMaxWidth().height(28.dp))
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
                    Box(Modifier.width(88.dp).height(52.dp).clip(RoundedCornerShape(18.dp))
                        .border(1.dp, Color(0x88FFFFFF), RoundedCornerShape(18.dp)).clickable { cycleRate() }.focusable(),
                        contentAlignment = Alignment.Center) {
                        Text(if (rate == 1f) "1.0x" else "${rate}x", color = Color.White, fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box {
                        TextButton(onClick = { subtitleMenuExpanded = true }) { Text("字幕", color = Color.White) }
                        DropdownMenu(expanded = subtitleMenuExpanded, onDismissRequest = { subtitleMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("自动") }, onClick = {
                                exo?.trackSelectionParameters = exo?.trackSelectionParameters?.buildUpon()
                                    ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                    ?.clearOverridesOfType(C.TRACK_TYPE_TEXT)?.build() ?: return@DropdownMenuItem
                                subtitleMenuExpanded = false
                            })
                            DropdownMenuItem(text = { Text("关闭字幕") }, onClick = {
                                exo?.trackSelectionParameters = exo?.trackSelectionParameters?.buildUpon()
                                    ?.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                    ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)?.build() ?: return@DropdownMenuItem
                                subtitleMenuExpanded = false
                            })
                            trackGroups.filter { it.type == C.TRACK_TYPE_TEXT }.forEachIndexed { groupIndex, group ->
                                repeat(group.length) { trackIndex ->
                                    if (group.isTrackSupported(trackIndex)) {
                                        val format = group.getTrackFormat(trackIndex)
                                        val label = listOfNotNull(format.label, format.language?.takeUnless { it == "und" })
                                            .distinct().joinToString(" · ").ifBlank { "字幕 ${groupIndex + 1}.${trackIndex + 1}" }
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            exo?.trackSelectionParameters = exo?.trackSelectionParameters?.buildUpon()
                                                ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                ?.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)))
                                                ?.build() ?: return@DropdownMenuItem
                                            subtitleMenuExpanded = false
                                        }, trailingIcon = { if (group.isTrackSelected(trackIndex)) Text("✓") })
                                    }
                                }
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { audioMenuExpanded = true }) { Text("音轨", color = Color.White) }
                        DropdownMenu(expanded = audioMenuExpanded, onDismissRequest = { audioMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("自动") }, onClick = {
                                exo?.trackSelectionParameters = exo?.trackSelectionParameters?.buildUpon()
                                    ?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                    ?.clearOverridesOfType(C.TRACK_TYPE_AUDIO)?.build() ?: return@DropdownMenuItem
                                audioMenuExpanded = false
                            })
                            trackGroups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEachIndexed { groupIndex, group ->
                                repeat(group.length) { trackIndex ->
                                    if (group.isTrackSupported(trackIndex)) {
                                        val format = group.getTrackFormat(trackIndex)
                                        val label = listOfNotNull(format.label, format.language?.takeUnless { it == "und" })
                                            .distinct().joinToString(" · ").ifBlank { "音轨 ${groupIndex + 1}.${trackIndex + 1}" }
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            exo?.trackSelectionParameters = exo?.trackSelectionParameters?.buildUpon()
                                                ?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                                ?.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex)))
                                                ?.build() ?: return@DropdownMenuItem
                                            audioMenuExpanded = false
                                        }, trailingIcon = { if (group.isTrackSelected(trackIndex)) Text("✓") })
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
            AnimatedVisibility(
                visible = controls,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 28.dp, top = 24.dp, end = 28.dp),
                enter = fadeIn(tween(240)), exit = fadeOut(tween(220))
            ) {
                Text(video.name.substringBeforeLast('.'), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.72f))
            }
            AnimatedVisibility(
                visible = isBuffering || playbackError != null,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 28.dp),
                enter = fadeIn(tween(180)), exit = fadeOut(tween(180))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0x66000000)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    if (isBuffering && playbackError == null) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(playbackError ?: "正在缓冲…", color = if (playbackError != null) Color(0xFFFF8A80) else Color.White, fontSize = 14.sp)
                    if (playbackError != null) {
                        TextButton(onClick = { startVideo(video, position) }) { Text("重试") }
                        TextButton(onClick = { goBack() }) { Text("返回") }
                    }
                }
            }
        }
    }

    @Composable
    private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, emphasized: Boolean = false, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Box(Modifier.size(if (emphasized) 64.dp else 52.dp).clip(CircleShape).background(if (focused) Accent.copy(alpha = 0.28f) else Color.Transparent).border(if (focused) 3.dp else 1.dp, if (focused) Accent else Color(0x88FFFFFF), CircleShape).onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(if (emphasized) 30.dp else 24.dp))
        }
    }

    @Composable
    private fun FocusRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(14.dp)).background(if (focused) Color(0xFF30251C) else Color(0xFF1B1A1D)).border(if (focused) 3.dp else 0.dp, Accent, RoundedCornerShape(14.dp)).onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, content = content)
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
                        message = "等待手机确认"
                    }
                }
                pairingPhoneId = phoneId
                pairingDevice = device
                pairingError = null
                message = "等待手机确认"
                requestPhoneApproval(device, phoneId)
            } catch (_: Exception) { message = "无法连接手机，请确认手机共享已开启" }
        }
    }

    private fun requestPhoneApproval(device: PhoneDevice, phoneId: String?) {
        if (phoneId.isNullOrBlank()) {
            pairingError = "手机信息无效，请返回重新连接"
            return
        }
        pairingError = null
        pairingJob = lifecycleScope.launch {
            try {
                val requestId = withContext(Dispatchers.IO) {
                    val body = JSONObject()
                        .put("deviceId", tvDeviceId())
                        .put("deviceName", android.os.Build.MODEL.ifBlank { "Android TV" })
                        .toString()
                    requestJson(URL("http://${device.host}:${device.port}/api/pair/request"), method = "POST", body = body).getString("requestId")
                }
                pairingRequestId = requestId
                while (true) {
                    delay(1400)
                    val status = withContext(Dispatchers.IO) { requestJson(URL("http://${device.host}:${device.port}/api/pair/status?requestId=$requestId")) }
                    when (status.optString("status")) {
                        "pending" -> Unit
                        "approved" -> {
                            val token = status.optString("token").takeIf { it.isNotBlank() } ?: error("授权结果无效")
                            getSharedPreferences("pairing", MODE_PRIVATE).edit().putString("token_$phoneId", token).apply()
                            val catalog = withContext(Dispatchers.IO) {
                                val loaded = loadVideos(device, token)
                                requestJson(URL("http://${device.host}:${device.port}/api/pair/complete"), method = "POST", body = JSONObject().put("requestId", requestId).toString())
                                loaded
                            }
                            pairingDevice = null
                            pairingPhoneId = null
                            pairingRequestId = null
                            completeConnection(device, token, catalog)
                            return@launch
                        }
                        "rejected" -> { pairingError = "手机拒绝了本次连接请求。"; return@launch }
                        else -> { pairingError = "配对请求已过期，请重新连接手机。"; return@launch }
                    }
                }
            } catch (error: HttpStatusException) {
                pairingError = "配对请求失败（HTTP ${error.statusCode}）"
            } catch (_: Exception) {
                pairingError = "连接失败，请确认两台设备在同一网络且手机共享已开启。"
            }
        }
    }

    private fun dismissPairing() {
        pairingJob?.cancel()
        pairingJob = null
        val id = pairingRequestId
        val device = pairingDevice
        if (id != null && device != null) lifecycleScope.launch(Dispatchers.IO) {
            try { requestJson(URL("http://${device.host}:${device.port}/api/pair/cancel"), method = "POST", body = JSONObject().put("requestId", id).toString()) } catch (_: Exception) {}
        }
        pairingDevice = null
        pairingPhoneId = null
        pairingRequestId = null
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
                    video.optLong("duration"), video.optLong("size"), video.optLong("modifiedTime"), video.optString("thumbnailUrl"), video.optString("streamUrl"), token
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

    private fun requestPlay(video: RemoteVideo) {
        val saved = savedPosition(video)
        if (saved > 5_000) resumePrompt = video else startVideo(video, 0L)
    }

    private fun savedPosition(video: RemoteVideo): Long = getSharedPreferences("playback", MODE_PRIVATE).getLong("${selected?.host}:${video.id}", 0L)

    private fun startVideo(video: RemoteVideo, position: Long) {
        pendingResume = position
        playbackError = null
        play(video)
    }

    private fun play(video: RemoteVideo) {
        val previousVideo = playing
        player?.let { previous -> saveProgress(previousVideo, previous); previous.release() }
        playing = video
        if (bandwidthMeter == null) bandwidthMeter = DefaultBandwidthMeter.Builder(this).build()
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${video.token}"))
            .setTransferListener(bandwidthMeter!!)
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).setBandwidthMeter(bandwidthMeter!!).build()
            .also { exo ->
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { playbackError = "播放失败：${error.errorCodeName}" }
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) playbackError = null }
                })
                exo.setMediaItem(MediaItem.fromUri(video.stream)); exo.prepare()
                if ((pendingResume ?: 0L) > 0L) exo.seekTo(pendingResume!!)
                exo.playWhenReady = true
            }
    }

    private fun goBack() {
        when {
            playing != null -> stopPlayback()
            folder != null -> folder = null
            selected != null -> { selected = null; authToken = null; videos.clear() }
            else -> finish()
        }
    }

    private fun stopPlayback() {
        player?.let { exo ->
            saveProgress(playing, exo)
            exo.release()
        }
        player = null; playing = null; playbackError = null
    }
    private fun saveProgress(video: RemoteVideo?, exo: ExoPlayer) {
        if (video == null) return
        val key = "${selected?.host}:${video.id}"
        val prefs = getSharedPreferences("playback", MODE_PRIVATE)
        if (exo.currentPosition > 5_000 && exo.duration > 0 && exo.currentPosition < exo.duration - 5_000) {
            prefs.edit().putLong(key, exo.currentPosition).apply()
        } else prefs.edit().remove(key).apply()
    }
    override fun onDestroy() { stopPlayback(); discovery?.let { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }; super.onDestroy() }
    private fun formatDuration(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    private fun formatClock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}

private val Bg = Color(0xFF101014)
private val Accent = Color(0xFFF3AE69)
private val Sub = Color(0xFFAAA6A2)
private val Panel = Color(0xFF1B1A1D)
