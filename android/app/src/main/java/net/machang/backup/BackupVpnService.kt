package net.machang.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlin.concurrent.thread

/**
 * Owns the mesh session: starts the EasyTier instance through [EasyTierBridge],
 * establishes the TUN (10.200.0.3/24, route 10.200.0.0/24 only) and hands its fd
 * to the native core via setTunFd.
 *
 * The route is deliberately narrow: only mesh traffic enters the TUN, so the
 * EasyTier peer connections to 36.133.114.164 and all normal app traffic keep
 * using the physical network — while this app's own backup connections to
 * 10.200.0.2 are routed into the TUN, which is exactly what we want. For that
 * reason we do NOT addDisallowedApplication(our own package).
 *
 * The mesh secret arrives via the start Intent (from MainActivity's settings)
 * and is never persisted by this service or baked into code.
 */
class BackupVpnService : VpnService() {

    companion object {
        const val ACTION_START = "net.machang.backup.action.START"
        const val ACTION_STOP = "net.machang.backup.action.STOP"
        const val EXTRA_SECRET = "mesh_secret"
        private const val CHANNEL_ID = "mesh"
        private const val NOTIF_ID = 1
    }

    @Volatile
    private var running = false
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMesh()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val secret = intent?.getStringExtra(EXTRA_SECRET).orEmpty()
                if (secret.isBlank()) {
                    AppLog.log("组网密钥为空，无法启动组网")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!running) {
                    running = true
                    thread(name = "mesh-start") { startMesh(secret) }
                }
                return START_STICKY
            }
        }
    }

    private fun startMesh(secret: String) {
        // NOTE: foreground promotion happens only after establish() below —
        // the systemExempted FGS type requires this app to already be the
        // active VPN, otherwise startForeground throws SecurityException.
        val config = EasyTierBridge.buildConfig(secret)
        val err = EasyTierBridge.startInstance(config)
        if (err.isNotEmpty()) {
            AppLog.log(err)
            running = false
            stopSelf()
            return
        }
        AppLog.log("EasyTier 实例已启动，正在建立 TUN…")

        val builder = Builder()
            .setSession("machang-backup")
            .addAddress("10.200.0.3", 24)
            .addRoute("10.200.0.0", 24)
            .setMtu(1500)
        val pfd = try {
            builder.establish()
        } catch (t: Throwable) {
            AppLog.log("建立 TUN 失败: ${t.message}")
            running = false
            EasyTierBridge.stopInstance()
            stopSelf()
            return
        }
        if (pfd == null) {
            AppLog.log("建立 TUN 失败: establish() 返回 null")
            running = false
            EasyTierBridge.stopInstance()
            stopSelf()
            return
        }
        tun = pfd
        promoteToForeground()
        val fdErr = EasyTierBridge.setTunFd(pfd.fd)
        if (fdErr.isNotEmpty()) {
            AppLog.log(fdErr)
        } else {
            AppLog.log("TUN fd 已交给 EasyTier (fd=${pfd.fd})，组网通道建立")
        }
        val infos = EasyTierBridge.collectInfos()
        if (infos.isNotBlank()) {
            AppLog.log("网络信息: ${infos.take(300)}")
        }
    }

    private fun stopMesh() {
        running = false
        try {
            tun?.close()
        } catch (t: Throwable) {
            // ignore
        }
        tun = null
        EasyTierBridge.stopInstance()
        AppLog.log("组网已停止")
    }

    override fun onRevoke() {
        stopMesh()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (running) stopMesh()
        super.onDestroy()
    }

    /**
     * Promote to a foreground service, retrying briefly: the platform grants
     * the `android:activate_vpn` appop (one of the systemExempted prerequisites)
     * when the VPN becomes active, which can lag establish() by a short moment.
     * If promotion ultimately fails we log and carry on instead of crashing —
     * the mesh itself does not depend on the notification.
     */
    private fun promoteToForeground() {
        var lastError: SecurityException? = null
        repeat(6) { attempt ->
            try {
                startForegroundCompat()
                if (attempt > 0) AppLog.log("前台服务已启动（第 ${attempt + 1} 次尝试）")
                return
            } catch (e: SecurityException) {
                lastError = e
                Thread.sleep(300)
            }
        }
        AppLog.log("警告: 前台服务启动失败，组网继续运行: ${lastError?.message}")
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "组网备份", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("组网备份")
            .setContentText("EasyTier 组网运行中 (10.200.0.3)")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }
}
