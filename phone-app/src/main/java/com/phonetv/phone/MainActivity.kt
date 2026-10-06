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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    private var showAdd by mutableStateOf(false)
    private var menu by mutableStateOf(false)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0x00000000), navigationBarStyle = SystemBarStyle.dark(0x00000000))
        refresh()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = CardBg, primary = Teal)) {
                ShareScreen()
            }
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    @Composable
    private fun ShareScreen() {
        val enabledCount = folders.count { it.enabled }
        val allOn = folders.isNotEmpty() && folders.all { it.enabled }
        Column(Modifier.fillMaxSize().background(Bg).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("共享", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多", tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("重新扫描") }, onClick = { menu = false; refresh() })
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            CardBlock {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    FolderBadge(Teal, Icons.Default.Folder)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("共享开关", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(if (sharing) "已开启，$enabledCount 个文件夹可被发现" else "未开启", color = Subtitle, fontSize = 13.sp)
                    }
                    Switch(checked = sharing, onCheckedChange = { if (it) startOrAsk() else stopSharing() }, colors = switchColors())
                }
            }
            SectionTitle("本机文件夹")
            CardBlock {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FolderBadge(Teal, Icons.Default.Folder)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("全部文件夹", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(if (allOn) "已全部开启" else "一键开关所有文件夹", color = Subtitle, fontSize = 13.sp)
                    }
                    Switch(checked = allOn, onCheckedChange = { setAllFolders(it) }, enabled = folders.isNotEmpty(), colors = switchColors())
                }
                if (folders.isEmpty()) {
                    Text("还没有扫描到视频。授予视频权限后会显示文件夹。", color = Subtitle, modifier = Modifier.padding(16.dp), fontSize = 13.sp)
                } else folders.forEachIndexed { index, folder ->
                    HorizontalDivider(color = Divider, thickness = 0.5.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        FolderBadge(if (folder.enabled) Purple else Blue, Icons.Default.Folder)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, color = Color.White, fontSize = 16.sp)
                            Text("${folder.count} 个视频", color = Subtitle, fontSize = 13.sp)
                        }
                        Switch(checked = folder.enabled, onCheckedChange = { setFolder(folder.name, it) }, colors = switchColors())
                    }
                }
            }
            SectionTitle("已连接设备")
            CardBlock {
                if (devices.isEmpty()) {
                    Text("还没有电视连接。电视打开应用后，会显示在这里。", color = Subtitle, modifier = Modifier.padding(16.dp), fontSize = 13.sp)
                } else devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider(color = Divider, thickness = 0.5.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(if (device.online) Teal.copy(alpha = 0.18f) else Color(0xFF2A2E34)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Tv, null, tint = if (device.online) Teal else Color(0xFF8E949C), modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(device.name, color = Color.White, fontSize = 16.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (device.online) Online else Offline))
                                Spacer(Modifier.width(6.dp))
                                Text(if (device.online) "在线" else "离线", color = Subtitle, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Teal, RoundedCornerShape(12.dp)).clickable { showAdd = true },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, null, tint = Teal, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加设备", color = Teal, fontSize = 16.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
        if (showAdd) AddDeviceDialog()
    }

    @Composable
    private fun AddDeviceDialog() {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("添加设备") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("电视名称") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) addDevice(name.trim()); showAdd = false }) { Text("添加") } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }

    @Composable private fun SectionTitle(text: String) { Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp)) }
    @Composable private fun CardBlock(content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg), content = content) }
    @Composable private fun FolderBadge(color: Color, icon: ImageVector) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
    @Composable private fun switchColors() = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Teal, uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF3A3F46), uncheckedBorderColor = Color.Transparent)

    private fun mediaPermission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun prefs() = getSharedPreferences("phone", MODE_PRIVATE)
    private fun refresh() {
        val granted = ContextCompat.checkSelfPermission(this, mediaPermission()) == PackageManager.PERMISSION_GRANTED
        val videos = if (granted) try { catalog.scan() } catch (_: Exception) { emptyList() } else emptyList()
        val disabled = prefs().getStringSet("disabledFolders", emptySet()) ?: emptySet()
        folders = videos.groupBy { it.folder }.map { (name, items) -> FolderShare(name, items.size, name !in disabled) }.sortedByDescending { it.count }
        devices = (prefs().getStringSet("devices", emptySet()) ?: emptySet()).map { ConnectedDevice(it, false) }
        sharing = MediaServerServiceState.running
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
    private fun addDevice(name: String) {
        val all = (prefs().getStringSet("devices", emptySet()) ?: emptySet()).toMutableSet()
        all.add(name)
        prefs().edit().putStringSet("devices", all).apply()
        devices = all.map { ConnectedDevice(it, false) }
    }
    private fun startOrAsk() {
        if (ContextCompat.checkSelfPermission(this, mediaPermission()) != PackageManager.PERMISSION_GRANTED) { permissionRequest.launch(arrayOf(mediaPermission())); return }
        catalog.scan()
        MediaServerServiceState.running = true
        ContextCompat.startForegroundService(this, Intent(this, MediaServerService::class.java))
        sharing = true
        refresh()
    }
    private fun stopSharing() { stopService(Intent(this, MediaServerService::class.java)); MediaServerServiceState.running = false; sharing = false }
}

data class FolderShare(val name: String, val count: Int, val enabled: Boolean)
data class ConnectedDevice(val name: String, val online: Boolean)
object MediaServerServiceState { @Volatile var running: Boolean = false }

private val Bg = Color(0xFF101114)
private val CardBg = Color(0xFF1C2026)
private val Teal = Color(0xFF20C6BE)
private val Purple = Color(0xFF7B68EE)
private val Blue = Color(0xFF4C8DFF)
private val Subtitle = Color(0xFF9AA1AA)
private val Divider = Color(0xFF2C323A)
private val Online = Color(0xFF2ED47A)
private val Offline = Color(0xFF8B919A)
