package com.phonetv.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.nsd.NsdServiceInfo
import android.net.nsd.NsdManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder

class MediaServerService : Service() {
    private lateinit var catalog: MediaCatalog
    private var server: LanHttpServer? = null
    private var nsd: NsdManager? = null
    private var registered = false
    private var multicast: WifiManager.MulticastLock? = null
    private var registration: NsdManager.RegistrationListener? = null
    override fun onCreate() {
        super.onCreate()
        val channel = "phonevideo-sharing"
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, "视频共享", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel).setContentTitle("手机视频共享正在运行").setContentText("电视可在本地网络浏览视频").setSmallIcon(android.R.drawable.ic_media_play).build() else Notification.Builder(this).setContentTitle("手机视频共享正在运行").setSmallIcon(android.R.drawable.ic_media_play).build()
        startForeground(1001, notification)
        catalog = MediaCatalog(this).also { it.scan() }
        val name = getSharedPreferences("phone", MODE_PRIVATE).getString("deviceName", "${Build.MODEL}手机") ?: "${Build.MODEL}手机"
        server = LanHttpServer(this, catalog, name).also { it.start() }
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicast = wifi.createMulticastLock("phonevideo-nsd").apply { setReferenceCounted(false); acquire() }
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply { serviceName = name.take(50); serviceType = "_phonevideo._tcp."; port = 8080 }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) { registered = true }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) { registered = false }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registration = listener
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() {
        if (registered) try { registration?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        server?.stop(); try { multicast?.release() } catch (_: Exception) {}; super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
