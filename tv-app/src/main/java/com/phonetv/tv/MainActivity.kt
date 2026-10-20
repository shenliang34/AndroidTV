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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.UUID

data class PhoneDevice(val name: String, val host: String, val port: Int, val deviceId: String = "", val serviceName: String = name)
data class RemoteVideo(val id: String, val name: String, val folder: String, val duration: Long, val size: Long, val modified: Long, val thumbnail: String, val stream: String, val token: String)

class MainActivity : ComponentActivity() {
    private val mediaClient = PhoneMediaClient()
    private val devices = mutableStateListOf<PhoneDevice>()
    private val libraryFolders = mutableStateListOf<RemoteFolder>()
    private val videos = mutableStateListOf<RemoteVideo>()
    private var selected by mutableStateOf<PhoneDevice?>(null)
    private var pairingDevice by mutableStateOf<PhoneDevice?>(null)
    private var pairingPhoneId by mutableStateOf<String?>(null)
    private var pairingRequestId by mutableStateOf<String?>(null)
    private var pairingJob: Job? = null
    private var pairingError by mutableStateOf<String?>(null)
    private var authToken by mutableStateOf<String?>(null)
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
    private var catalogTotal by mutableStateOf(0)
    private var loadedPage by mutableStateOf(0)
    private var hasMoreVideos by mutableStateOf(false)
    private var loadingCatalog by mutableStateOf(false)
    private var catalogEpoch by mutableStateOf(0)
    private var catalogJob: Job? = null
    private var loadMoreJob: Job? = null
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var player by mutableStateOf<ExoPlayer?>(null)
    private var reconnectJob: Job? = null
    private var progressJob: Job? = null
    private var recoveryPlayWhenReady: Boolean? = null
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
        val gridState = rememberLazyGridState()
        LaunchedEffect(selected, authToken, browseMode, sortMode, searchText, folder, catalogEpoch) {
            if (selected == null || authToken == null) return@LaunchedEffect
            // Folder list mode also goes through reloadCatalog() (loads /api/folders).
            delay(if (searchText.isNotBlank() && (browseMode != "文件夹" || folder != null)) 350 else 0)
            reloadCatalog()
        }
        LaunchedEffect(gridState, hasMoreVideos, loadingCatalog, browseMode, folder) {
            snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .collect { lastVisible ->
                    if (lastVisible != null && lastVisible >= videos.size - 8) maybeLoadMore()
                }
        }
        val currentSection = when {
            folder != null -> folder!!
            browseMode == "最近添加" -> "最近添加"
            browseMode == "文件夹" -> "文件夹"
            else -> "所有视频"
        }
        val currentVideoCount = when {
            browseMode == "文件夹" && folder == null -> null
            else -> catalogTotal
        }
        Column(Modifier.fillMaxSize().screenInsets().padding(horizontal = 28.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text("片库", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text("/ $currentSection${currentVideoCount?.let { " ${it}部" } ?: ""}", color = Color.White, fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.weight(1.2f), contentAlignment = Alignment.Center) {
                    OutlinedTextField(value = searchText, onValueChange = { searchText = it }, singleLine = true,
                        placeholder = { Text("搜索片名", fontSize = 14.sp) }, leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
                        modifier = Modifier.fillMaxWidth(0.88f).height(52.dp), textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(14.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color(0xFF333942), focusedBorderColor = Accent),
                        enabled = browseMode != "文件夹" || folder != null)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    BackButton()
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("全部视频", "最近添加", "文件夹").forEach { mode ->
                        BrowseChip(mode, selected = browseMode == mode && folder == null) {
                            browseMode = mode
                            folder = null
                            if (mode != "文件夹") catalogEpoch++
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (browseMode != "文件夹" || folder != null) {
                    var sortExpanded by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { sortExpanded = true }) { Text("排序：$sortMode", color = Accent, fontSize = 15.sp) }
                        DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                            listOf("最新优先", "最旧优先", "文件名", "文件大小", "视频时长").forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = { sortMode = option; sortExpanded = false; catalogEpoch++ })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (message.startsWith("连接已断开") || message.startsWith("无法连接") || message.startsWith("授权已失效")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = Accent, modifier = Modifier.weight(1f))
                    TextButton(onClick = { recoverConnection() }) { Text("重试连接") }
                }
            }
            when {
                browseMode == "文件夹" && folder == null -> {
                    if (libraryFolders.isEmpty()) {
                        EmptyLibrary(if (loadingCatalog) "正在加载文件夹…" else message)
                    } else {
                        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 220.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 10.dp)) {
                            gridItems(libraryFolders.toList(), key = { it.name }) { item ->
                                FolderCard(item.name, item.videoCount) {
                                    folder = item.name
                                    browseMode = "文件夹"
                                    catalogEpoch++
                                }
                            }
                        }
                    }
                }
                loadingCatalog && videos.isEmpty() -> EmptyLibrary("正在加载视频…")
                videos.isEmpty() -> EmptyLibrary(message)
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 220.dp),
                        state = gridState,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 10.dp),
                    ) {
                        gridItems(videos.toList(), key = { it.id }) { video ->
                            PosterCard(video, onFocus = {}, onClick = { requestPlay(video) }, fillCell = true)
                        }
                        if (loadingCatalog && videos.isNotEmpty()) {
                            item(key = "loading-more") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
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
            Text(if (loadingCatalog) "正在加载" else if (searchText.isNotBlank()) "没有找到匹配的视频" else "这里还没有视频", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(text, color = Sub, fontSize = 15.sp)
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
        val listState = rememberLazyListState()
        var selectedIndex by remember(name) { mutableIntStateOf(if (videos.isEmpty()) 0 else 1) }
        LaunchedEffect(name, selected, authToken, sortMode, searchText, catalogEpoch) {
            if (selected == null || authToken == null) return@LaunchedEffect
            delay(if (searchText.isNotBlank()) 350 else 0)
            reloadCatalog()
        }
        LaunchedEffect(listState, hasMoreVideos, loadingCatalog, name) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .collect { lastVisible ->
                    if (lastVisible != null && lastVisible >= videos.size - 4) maybeLoadMore()
                }
        }
        BackHandler { goBack() }
        Column(Modifier.fillMaxSize().screenInsets().background(Color(0xFF0B0E14)).padding(horizontal = 28.dp, vertical = 12.dp)) {
            PageHeader(name, if (catalogTotal > 0) "来自手机 ${selectedIndex}/${catalogTotal} 部 · 已加载 ${videos.size}" else "来自手机")
            Spacer(Modifier.height(16.dp))
            when {
                loadingCatalog && videos.isEmpty() -> EmptyLibrary("正在加载文件夹…")
                videos.isEmpty() -> EmptyLibrary("这个文件夹里没有视频")
                else -> {
                    LazyRow(
                        state = listState,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(end = 28.dp, bottom = 8.dp),
                    ) {
                        itemsIndexed(videos.toList(), key = { _, video -> video.id }) { index, video ->
                            PosterCard(video, onFocus = { selectedIndex = index + 1 }, onClick = { requestPlay(video) })
                        }
                        if (loadingCatalog) {
                            item(key = "folder-loading") {
                                Box(Modifier.width(80.dp).height(128.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp)
                                }
                            }
                        }
                    }
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
                        TextButton(onClick = { recoverConnection() }) { Text("重试连接") }
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
        startVideo(next, 0L)
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
            override fun onServiceLost(info: NsdServiceInfo) { runOnUiThread {
                devices.removeAll { it.serviceName == info.serviceName }
                if (selected?.serviceName == info.serviceName) recoverConnection()
            } }
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
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    mediaClient.fetchDevice(device)
                }
                rememberDevice(device.copy(deviceId = info.getString("deviceId"), name = info.getString("deviceName")))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* An advertisement is not proof that the server is ready. */ }
        }
    }

    private fun rememberDevice(device: PhoneDevice) {
        val localHosts = localHosts()
        val host = device.host.removePrefix("/").substringBefore('%')
        val normalized = device.copy(host = host)
        if (host == "127.0.0.1" && devices.any { it.host != "127.0.0.1" && it.host in localHosts }) return
        if (host in localHosts && devices.any { it.host == "127.0.0.1" }) {
            devices.removeAll { it.host == "127.0.0.1" }
        }
        val index = devices.indexOfFirst { it.deviceId == normalized.deviceId }
        if (index >= 0) devices[index] = normalized else devices.add(normalized)
        val current = selected
        if (current != null && current.deviceId == normalized.deviceId &&
            (current.host != normalized.host || current.port != normalized.port || playbackError != null)) recoverConnection()
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
                    val info = mediaClient.fetchDevice(device)
                    val id = info.getString("deviceId")
                    id to getSharedPreferences("pairing", MODE_PRIVATE).getString("token_$id", null)
                }
                val identifiedDevice = device.copy(deviceId = phoneId)
                if (!cachedToken.isNullOrBlank()) {
                    try {
                        withContext(Dispatchers.IO) { mediaClient.fetchFolders(device, cachedToken) }
                        completeConnection(identifiedDevice, cachedToken)
                        return@launch
                    } catch (error: PhoneMediaClient.HttpStatusException) {
                        if (error.statusCode != 401) throw error
                        getSharedPreferences("pairing", MODE_PRIVATE).edit().remove("token_$phoneId").apply()
                        selected = null
                        clearCatalog()
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
                requestPhoneApproval(identifiedDevice, phoneId)
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
                    mediaClient.requestObject(URL("http://${device.host}:${device.port}/api/pair/request"), method = "POST", body = body).getString("requestId")
                }
                pairingRequestId = requestId
                while (true) {
                    delay(1400)
                    val status = withContext(Dispatchers.IO) {
                        mediaClient.requestObject(URL("http://${device.host}:${device.port}/api/pair/status?requestId=$requestId"))
                    }
                    when (status.optString("status")) {
                        "pending" -> Unit
                        "approved" -> {
                            val token = status.optString("token").takeIf { it.isNotBlank() } ?: error("授权结果无效")
                            getSharedPreferences("pairing", MODE_PRIVATE).edit().putString("token_$phoneId", token).apply()
                            withContext(Dispatchers.IO) {
                                mediaClient.requestObject(
                                    URL("http://${device.host}:${device.port}/api/pair/complete"),
                                    method = "POST",
                                    body = JSONObject().put("requestId", requestId).toString(),
                                )
                            }
                            pairingDevice = null
                            pairingPhoneId = null
                            pairingRequestId = null
                            completeConnection(device, token)
                            return@launch
                        }
                        "rejected" -> { pairingError = "手机拒绝了本次连接请求。"; return@launch }
                        else -> { pairingError = "配对请求已过期，请重新连接手机。"; return@launch }
                    }
                }
            } catch (error: PhoneMediaClient.HttpStatusException) {
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
            try {
                mediaClient.requestObject(
                    URL("http://${device.host}:${device.port}/api/pair/cancel"),
                    method = "POST",
                    body = JSONObject().put("requestId", id).toString(),
                )
            } catch (_: Exception) {}
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

    private fun sortParam(): String = when (sortMode) {
        "文件名" -> "name"
        "文件大小" -> "size"
        "视频时长" -> "duration"
        "最旧优先" -> "oldest"
        else -> "newest"
    }

    private fun pageSizeForCurrentMode(): Int =
        if (browseMode == "最近添加" && folder == null) RECENT_PAGE_SIZE else DEFAULT_PAGE_SIZE

    private fun clearCatalog() {
        catalogJob?.cancel()
        catalogJob = null
        loadMoreJob?.cancel()
        loadMoreJob = null
        videos.clear()
        libraryFolders.clear()
        catalogTotal = 0
        loadedPage = 0
        hasMoreVideos = false
        loadingCatalog = false
    }

    private fun reloadCatalog() {
        val device = selected ?: return
        val token = authToken ?: return
        loadMoreJob?.cancel()
        loadMoreJob = null
        catalogJob?.cancel()
        catalogJob = lifecycleScope.launch {
            loadingCatalog = true
            videos.clear()
            loadedPage = 0
            hasMoreVideos = false
            catalogTotal = 0
            try {
                if (browseMode == "文件夹" && folder == null) {
                    val folders = withContext(Dispatchers.IO) { mediaClient.fetchFolders(device, token) }
                    libraryFolders.clear()
                    libraryFolders.addAll(folders)
                    message = if (folders.isEmpty()) "手机上没有可共享的视频" else "${folders.size} 个文件夹"
                    return@launch
                }
                val page = withContext(Dispatchers.IO) {
                    mediaClient.fetchVideoPage(
                        device = device,
                        token = token,
                        page = 1,
                        pageSize = pageSizeForCurrentMode(),
                        folder = folder,
                        keyword = searchText.trim().takeIf { it.isNotEmpty() },
                        sort = sortParam(),
                    )
                }
                videos.addAll(page.items)
                catalogTotal = page.total
                loadedPage = 1
                val allowMore = folder != null || browseMode != "最近添加"
                hasMoreVideos = allowMore && videos.size < page.total && page.items.isNotEmpty()
                message = when {
                    page.total == 0 && searchText.isNotBlank() -> "没有找到匹配的视频，换个片名或清除搜索条件试试。"
                    page.total == 0 -> "手机上没有可共享的视频"
                    else -> "已加载 ${videos.size} / $catalogTotal 个视频"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message = "无法加载视频列表，请检查手机共享是否仍在运行"
            } finally {
                loadingCatalog = false
            }
        }
    }

    private fun maybeLoadMore() {
        if (!hasMoreVideos || loadingCatalog) return
        val device = selected ?: return
        val token = authToken ?: return
        if (browseMode == "最近添加" && folder == null) return
        loadMoreJob?.cancel()
        loadMoreJob = lifecycleScope.launch {
            loadingCatalog = true
            try {
                val next = loadedPage + 1
                val page = withContext(Dispatchers.IO) {
                    mediaClient.fetchVideoPage(
                        device = device,
                        token = token,
                        page = next,
                        pageSize = DEFAULT_PAGE_SIZE,
                        folder = folder,
                        keyword = searchText.trim().takeIf { it.isNotEmpty() },
                        sort = sortParam(),
                    )
                }
                if (page.items.isEmpty()) {
                    hasMoreVideos = false
                } else {
                    val seen = videos.mapTo(HashSet()) { it.id }
                    page.items.filter { it.id !in seen }.forEach { videos.add(it) }
                    catalogTotal = page.total
                    loadedPage = next
                    hasMoreVideos = videos.size < page.total
                    message = "已加载 ${videos.size} / $catalogTotal 个视频"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                hasMoreVideos = false
            } finally {
                loadingCatalog = false
            }
        }
    }

    private fun completeConnection(device: PhoneDevice, token: String) {
        authToken = token
        selected = device
        searchText = ""
        sortMode = "最新优先"
        browseMode = "全部视频"
        folder = null
        clearCatalog()
        message = "正在加载片库…"
        lifecycleScope.launch {
            try {
                val folders = withContext(Dispatchers.IO) { mediaClient.fetchFolders(device, token) }
                libraryFolders.clear()
                libraryFolders.addAll(folders)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Video reload is triggered by selected/authToken; folder list can retry from 文件夹 tab.
            }
        }
    }

    private fun requestPlay(video: RemoteVideo) {
        val saved = savedPosition(video)
        if (saved > 5_000) resumePrompt = video else startVideo(video, 0L)
    }

    private fun progressKey(video: RemoteVideo): String? = selected?.deviceId
        ?.takeIf { it.isNotBlank() }?.let { "$it:${video.id}" }

    private fun savedPosition(video: RemoteVideo): Long {
        val key = progressKey(video) ?: return 0L
        val prefs = getSharedPreferences("playback", MODE_PRIVATE)
        if (!prefs.contains(key)) {
            val legacy = "${selected?.host}:${video.id}"
            if (prefs.contains(legacy)) prefs.edit().putLong(key, prefs.getLong(legacy, 0L)).remove(legacy).apply()
        }
        return prefs.getLong(key, 0L)
    }

    private fun recoverConnection() {
        val phone = selected ?: return
        val token = authToken ?: return
        if (reconnectJob?.isActive == true) return
        val video = playing
        val position = player?.currentPosition ?: 0L
        val shouldPlay = recoveryPlayWhenReady ?: (player?.playWhenReady ?: true)
        recoveryPlayWhenReady = shouldPlay
        player?.let { saveProgress(video, it); it.pause() }
        reconnectJob = lifecycleScope.launch {
            try {
                for (wait in listOf(1_000L, 2_000L, 5_000L, 10_000L)) {
                    message = "连接已断开，正在重新连接手机…"
                    if (video != null) playbackError = message
                    delay(wait)
                    val candidate = devices.firstOrNull { it.deviceId == phone.deviceId } ?: phone
                    try {
                        val refreshed = withContext(Dispatchers.IO) {
                            val info = mediaClient.fetchDevice(candidate)
                            if (info.getString("deviceId") != phone.deviceId) throw IOException("手机地址已被其他设备使用")
                            // Validate authorization without fetching every video page.
                            mediaClient.fetchFolders(candidate, token)
                            video?.let { mediaClient.refreshVideo(candidate, token, it) }
                        }
                        // Cached pages contain absolute URLs for the previous phone address.
                        // Reload lazily when browsing resumes instead of reusing stale links.
                        clearCatalog()
                        selected = candidate
                        catalogEpoch++
                        message = "已重新连接手机"
                        if (video != null) {
                            if (refreshed == null) {
                                playbackError = "视频已删除或停止共享，请返回列表"
                            } else {
                                // The replacement player must not overwrite the last good checkpoint.
                                player?.release(); player = null
                                pendingResume = position
                                playbackError = null
                                play(refreshed)
                                player?.playWhenReady = shouldPlay
                                recoveryPlayWhenReady = null
                            }
                        }
                        return@launch
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: PhoneMediaClient.HttpStatusException) {
                        if (error.statusCode == 401) {
                            message = "授权已失效，请返回设备列表重新连接并在手机确认"
                            if (video != null) playbackError = message
                            return@launch
                        }
                    } catch (_: Exception) { /* Retry with the latest discovery address. */ }
                }
                message = "无法连接手机，请确认共享已开启后重试"
                if (video != null) playbackError = message
            } finally { reconnectJob = null }
        }
    }

    private fun startVideo(video: RemoteVideo, position: Long) {
        reconnectJob?.cancel(); reconnectJob = null
        recoveryPlayWhenReady = null
        pendingResume = position
        playbackError = null
        play(video)
    }

    private fun play(video: RemoteVideo) {
        progressJob?.cancel()
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
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        playbackError = "播放失败：${error.errorCodeName}"
                        if (error.errorCode in 2000..2002 || error.errorCode == 2004) recoverConnection()
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY && reconnectJob?.isActive != true) playbackError = null
                        if (state == Player.STATE_ENDED) saveProgress(video, exo)
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveProgress(video, exo) }
                })
                exo.setMediaItem(MediaItem.fromUri(video.stream)); exo.prepare()
                if ((pendingResume ?: 0L) > 0L) exo.seekTo(pendingResume!!)
                pendingResume = null
                exo.playWhenReady = true
            }
        progressJob = lifecycleScope.launch {
            while (true) {
                delay(5_000)
                player?.let { saveProgress(playing, it) }
            }
        }
    }

    private fun goBack() {
        when {
            playing != null -> stopPlayback()
            folder != null -> folder = null
            selected != null -> { reconnectJob?.cancel(); selected = null; authToken = null; folder = null; clearCatalog() }
            else -> finish()
        }
    }

    private fun stopPlayback() {
        recoveryPlayWhenReady = null
        reconnectJob?.cancel(); reconnectJob = null
        progressJob?.cancel(); progressJob = null
        player?.let { exo ->
            saveProgress(playing, exo)
            exo.release()
        }
        player = null; playing = null; playbackError = null
    }
    private fun saveProgress(video: RemoteVideo?, exo: ExoPlayer) {
        if (video == null) return
        val key = progressKey(video) ?: return
        val prefs = getSharedPreferences("playback", MODE_PRIVATE)
        // Preparing/failed players may have no timeline yet: retain the existing checkpoint.
        if (exo.duration <= 0 || exo.playbackState == Player.STATE_IDLE) return
        if (exo.currentPosition > 5_000 && exo.currentPosition < exo.duration - 5_000 && exo.playbackState != Player.STATE_ENDED) {
            prefs.edit().putLong(key, exo.currentPosition).apply()
        } else prefs.edit().remove(key).apply()
    }
    override fun onStop() {
        player?.let { saveProgress(playing, it) }
        super.onStop()
    }
    override fun onDestroy() { stopPlayback(); discovery?.let { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) {} }; super.onDestroy() }
    private fun formatDuration(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    private fun formatClock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }

    companion object {
        private const val DEFAULT_PAGE_SIZE = 50
        private const val RECENT_PAGE_SIZE = 30
    }
}

private val Bg = Color(0xFF101014)
private val Accent = Color(0xFFF3AE69)
private val Sub = Color(0xFFAAA6A2)
private val Panel = Color(0xFF1B1A1D)
