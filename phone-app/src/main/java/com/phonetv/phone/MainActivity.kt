package com.phonetv.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val catalog by lazy { MediaCatalog(this) }
    private var sharing by mutableStateOf(false)
    private var folders by mutableStateOf(listOf<FolderShare>())
    private var devices by mutableStateOf(listOf<ConnectedDevice>())
    private var menu by mutableStateOf(false)
    private var pairingCode by mutableStateOf<String?>(null)
    private var mediaPermissionGranted by mutableStateOf(false)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0x00000000), navigationBarStyle = SystemBarStyle.dark(0x00000000))
        refresh()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = CardBg, primary = Accent)) {
                ShareScreen()
            }
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    @Composable
    private fun ShareScreen() {
        val enabledCount = folders.count { it.enabled }
        val allOn = folders.isNotEmpty() && folders.all { it.enabled }
        val videoCount = folders.sumOf { it.count }
        Column(Modifier.fillMaxSize().background(Bg).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FAMILIA  ·  PRIVATE MEDIA", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Text("家庭影音", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多", tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("重新扫描") }, onClick = { menu = false; refresh() })
                        if (sharing) DropdownMenuItem(text = { Text("刷新配对码") }, onClick = { menu = false; pairingCode = PairingStore(this@MainActivity).rotateCode() })
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF34271E), Color(0xFF1D1A1A), Color(0xFF17171B))))
                .border(1.dp, Color(0xFF41352B), RoundedCornerShape(24.dp))) {
                Box(Modifier.align(Alignment.CenterEnd).offset(x = 34.dp).size(180.dp).clip(RoundedCornerShape(48.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF6E4A2A), Color(0xFF33251C)))))
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 48.dp).size(82.dp).clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF211B17)).border(1.dp, Accent.copy(alpha = 0.45f), RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Tv, null, tint = Accent, modifier = Modifier.size(38.dp))
                }
                Column(Modifier.align(Alignment.CenterStart).padding(start = 22.dp, end = 138.dp)) {
                    Text("YOUR PRIVATE CINEMA", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("今晚，接着看。", color = Color.White, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text("把手机里的影片，带到电视大屏。", color = Color(0xFFBEB5AC), fontSize = 12.sp, maxLines = 2)
                }
            }
            Spacer(Modifier.height(14.dp))
            CardBlock {
                Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF30251C), Color(0xFF1B1A1D)))).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Tv, null, tint = Accent, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("手机视频共享", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (sharing) Online else Offline))
                                Spacer(Modifier.width(7.dp))
                                Text(if (sharing) "正在共享 · $enabledCount 个文件夹" else "共享已关闭", color = if (sharing) Accent else Subtitle, fontSize = 13.sp)
                            }
                        }
                        Switch(checked = sharing, onCheckedChange = { if (it) startOrAsk() else stopSharing() }, colors = switchColors())
                    }
                    Spacer(Modifier.height(15.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("$videoCount", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
                            Text("个本地视频", color = Subtitle, fontSize = 13.sp)
                        }
                        Text("仅在本地网络传输", color = Color(0xFFB7C0C7), fontSize = 12.sp,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Accent.copy(alpha = 0.12f)).padding(horizontal = 11.dp, vertical = 7.dp))
                    }
                }
                if (sharing) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("电视配对码", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text("5 分钟有效 · 配对后立即失效", color = Subtitle, fontSize = 12.sp)
                        }
                        Text(pairingCode ?: "------", color = Accent, fontSize = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF111719)).padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
            }
            SectionTitle("共享内容", "选择允许电视访问的文件夹")
            CardBlock {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    FolderBadge(Accent.copy(alpha = 0.18f), Icons.Default.Folder, iconTint = Accent)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text("所有文件夹", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (allOn) "全部已共享" else "${folders.count { it.enabled }} / ${folders.size} 个已共享", color = Subtitle, fontSize = 13.sp)
                    }
                    Switch(checked = allOn, onCheckedChange = { setAllFolders(it) }, enabled = folders.isNotEmpty(), colors = switchColors())
                }
                if (folders.isEmpty()) {
                    HorizontalDivider(color = Divider, thickness = 0.5.dp)
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text(if (mediaPermissionGranted) "还没有找到视频" else "需要访问视频权限", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(5.dp))
                        Text(if (mediaPermissionGranted) "手机本地视频会自动显示在这里。" else "授权后，电视才能浏览你选择共享的本地视频。", color = Subtitle, fontSize = 13.sp)
                        if (!mediaPermissionGranted) {
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = { permissionRequest.launch(arrayOf(mediaPermission())) }) { Text("授予视频权限", color = Accent) }
                        }
                    }
                } else folders.forEachIndexed { index, folder ->
                    HorizontalDivider(color = Divider, thickness = 0.5.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        FolderBadge(if (folder.enabled) Color(0xFF302D4D) else Color(0xFF292F37), Icons.Default.Folder, iconTint = if (folder.enabled) Color(0xFFB5A9FF) else Subtitle)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("${folder.count} 个视频", color = Subtitle, fontSize = 12.sp)
                        }
                        Switch(checked = folder.enabled, onCheckedChange = { setFolder(folder.name, it) }, colors = switchColors())
                    }
                }
            }
            SectionTitle("已授权设备", "可随时撤销电视的访问权限")
            CardBlock {
                if (devices.isEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        FolderBadge(Color(0xFF292F37), Icons.Default.Tv, iconTint = Subtitle)
                        Spacer(Modifier.width(13.dp))
                        Column {
                            Text("还没有授权电视", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("电视输入上方配对码后会显示在这里。", color = Subtitle, fontSize = 12.sp)
                        }
                    }
                } else devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider(color = Divider, thickness = 0.5.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        FolderBadge(if (device.online) Color(0xFF163532) else Color(0xFF292F37), Icons.Default.Tv, iconTint = if (device.online) Accent else Subtitle)
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (device.online) Online else Offline))
                                Spacer(Modifier.width(6.dp))
                                Text(if (device.online) "在线 · 已授权" else "离线 · 已授权", color = Subtitle, fontSize = 12.sp)
                            }
                        }
                        IconButton(onClick = { revokeDevice(device.id) }) { Icon(Icons.Default.Delete, "撤销授权", tint = Subtitle) }
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
        }
    }

    @Composable private fun SectionTitle(text: String, subtitle: String) {
        Column(Modifier.padding(top = 24.dp, bottom = 10.dp)) {
            Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = Subtitle, fontSize = 12.sp)
        }
    }
    @Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(CardBg)
            .border(1.dp, Color(0xFF292F37), RoundedCornerShape(20.dp)), content = content)
    }
    @Composable private fun FolderBadge(color: Color, icon: ImageVector, iconTint: Color = Color.White) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(21.dp))
        }
    }
    @Composable private fun switchColors() = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Accent, uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF3A3F46), uncheckedBorderColor = Color.Transparent)

    private fun mediaPermission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun prefs() = getSharedPreferences("phone", MODE_PRIVATE)
    private fun refresh() {
        val granted = ContextCompat.checkSelfPermission(this, mediaPermission()) == PackageManager.PERMISSION_GRANTED
        mediaPermissionGranted = granted
        val videos = if (granted) try { catalog.scan() } catch (_: Exception) { emptyList() } else emptyList()
        val disabled = prefs().getStringSet("disabledFolders", emptySet()) ?: emptySet()
        folders = videos.groupBy { it.folder }.map { (name, items) -> FolderShare(name, items.size, name !in disabled) }.sortedByDescending { it.count }
        sharing = MediaServerServiceState.running
        val pairing = PairingStore(this)
        devices = pairing.devices().map { ConnectedDevice(it.id, it.name, false) }
        pairingCode = if (sharing) pairing.activeCode() else null
    }
    private fun setFolder(name: String, enabled: Boolean) {
        val disabled = (prefs().getStringSet("disabledFolders", emptySet()) ?: emptySet()).toMutableSet()
        if (enabled) disabled.remove(name) else disabled.add(name)
        prefs().edit().putStringSet("disabledFolders", disabled).apply()
        folders = folders.map { if (it.name == name) it.copy(enabled = enabled) else it }
    }
    private fun setAllFolders(enabled: Boolean) {
        val disabled = if (enabled) emptySet() else folders.map { it.name }.toSet()
        prefs().edit().putStringSet("disabledFolders", disabled).apply()
        folders = folders.map { it.copy(enabled = enabled) }
    }
    private fun revokeDevice(id: String) {
        val store = PairingStore(this)
        store.revoke(id)
        pairingCode = store.rotateCode()
        devices = store.devices().map { ConnectedDevice(it.id, it.name, false) }
    }
    private fun startOrAsk() {
        if (ContextCompat.checkSelfPermission(this, mediaPermission()) != PackageManager.PERMISSION_GRANTED) { permissionRequest.launch(arrayOf(mediaPermission())); return }
        catalog.scan()
        pairingCode = PairingStore(this).rotateCode()
        MediaServerServiceState.running = true
        ContextCompat.startForegroundService(this, Intent(this, MediaServerService::class.java))
        sharing = true
        refresh()
    }
    private fun stopSharing() { stopService(Intent(this, MediaServerService::class.java)); MediaServerServiceState.running = false; PairingStore(this).invalidateCode(); pairingCode = null; sharing = false }
}

data class FolderShare(val name: String, val count: Int, val enabled: Boolean)
data class ConnectedDevice(val id: String, val name: String, val online: Boolean)
object MediaServerServiceState { @Volatile var running: Boolean = false }

private val Bg = Color(0xFF101014)
private val CardBg = Color(0xFF1B1A1D)
private val Accent = Color(0xFFF3AE69)
private val Purple = Color(0xFFB5A9FF)
private val Blue = Color(0xFF8D9AAA)
private val Subtitle = Color(0xFFAAA6A2)
private val Divider = Color(0xFF302B28)
private val Online = Color(0xFF2ED47A)
private val Offline = Color(0xFF8B919A)
