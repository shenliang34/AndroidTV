package com.phonetv.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val catalog by lazy { MediaCatalog(this) }
    private var sharing by mutableStateOf(false)
    private var count by mutableIntStateOf(0)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); refresh()
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("手机视频共享", style = MaterialTheme.typography.headlineMedium)
            Text(if (sharing) "● 服务已开启" else "当前未开启", style = MaterialTheme.typography.titleMedium)
            Text("设备名称：${getSharedPreferences("phone", MODE_PRIVATE).getString("deviceName", "${Build.MODEL}手机")}")
            Text("本机视频：$count 个")
            Text("连接设备：电视端尚未连接")
            Button(onClick = { if (sharing) stopSharing() else startOrAsk() }) { Text(if (sharing) "停止共享" else "开始共享") }
            Text("请确保手机和电视连接到同一个 Wi-Fi 网络。视频仅通过局域网提供。", style = MaterialTheme.typography.bodySmall)
        } } } }
    }
    override fun onResume() { super.onResume(); refresh() }
    private fun mediaPermission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
    private fun refresh() {
        val granted = ContextCompat.checkSelfPermission(this, mediaPermission()) == PackageManager.PERMISSION_GRANTED
        count = if (granted) try { catalog.scan().size } catch (_: Exception) { 0 } else 0
        sharing = MediaServerServiceState.running
    }
    private fun startOrAsk() {
        if (ContextCompat.checkSelfPermission(this, mediaPermission()) != PackageManager.PERMISSION_GRANTED) { permissionRequest.launch(arrayOf(mediaPermission())); return }
        count = catalog.scan().size
        MediaServerServiceState.running = true
        ContextCompat.startForegroundService(this, Intent(this, MediaServerService::class.java)); sharing = true
    }
    private fun stopSharing() { stopService(Intent(this, MediaServerService::class.java)); MediaServerServiceState.running = false; sharing = false }
}

object MediaServerServiceState { @Volatile var running: Boolean = false }
