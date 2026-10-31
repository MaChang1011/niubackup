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
 *
 * Foreground-service strategy (two steps, see promoteBoot /
 * promoteToForeground): onStartCommand first promotes with the dataSync
 * type — legal before the VPN exists — so the startForegroundService
 * contract is met even when mesh startup is slow; after establish() the
 * service switches to the systemExempted type its manifest primarily
 * declares.
 */
class BackupVpnService : VpnService() {

    companion object {
        const val ACTION_START = "net.machang.backup.action.START"
        const val ACTION_STOP = "net.machang.backup.action.STOP"
        const val EXTRA_SECRET = "mesh_secret"

        // M4c generalized start: a full config + TUN parameters, used by the
        // product (account) mode. The legacy EXTRA_SECRET path is unchanged.
        const val EXTRA_CONFIG = "mesh_config_toml"
        const val EXTRA_INSTANCE_NAME = "mesh_instance_name"
        const val EXTRA_TUN_IP = "mesh_tun_ip"
        const val EXTRA_TUN_PREFIX = "mesh_tun_prefix"
        const val EXTRA_ROUTE_NET = "mesh_route_net"
        const val EXTRA_ROUTE_PREFIX = "mesh_route_prefix"
        const val EXTRA_SESSION = "mesh_session_label"
        private const val CHANNEL_ID = "mesh"
        private const val NOTIF_ID = 1
    }

    @Volatile
    private var running = false
    private var tun: ParcelFileDescriptor? = null
    private var notifText = "EasyTier 组网运行中 (10.200.0.3)"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMesh()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // FGS contract first, slow work second. startForegroundService
                // gives us ~5 s to call startForeground; the mesh startup
                // (config fetch + JNI init + establish) can exceed that, which
                // is exactly what crashed us with
                // ForegroundServiceDidNotStartInTimeException. So promote
                // IMMEDIATELY with the dataSync type (legal pre-VPN — see
                // promoteBoot), then run startup on the worker thread; after
                // establish() succeeds, promoteToForeground() switches the
                // service to the systemExempted type. This also covers the
                // error paths below: they promote first, then fail gracefully
                // with a log line and stopSelf — never a crash.
                promoteBoot()
                MeshState.markStarting()
                val fullConfig = intent?.getStringExtra(EXTRA_CONFIG)
                if (!fullConfig.isNullOrBlank()) {
                    if (!running) {
                        running = true
                        val instance = intent.getStringExtra(EXTRA_INSTANCE_NAME)
                            ?: EasyTierBridge.PRODUCT_INSTANCE_NAME
                        val tunIp = intent.getStringExtra(EXTRA_TUN_IP) ?: "10.99.0.3"
                        val tunPrefix = intent.getIntExtra(EXTRA_TUN_PREFIX, 24)
                        val routeNet = intent.getStringExtra(EXTRA_ROUTE_NET) ?: "10.99.0.0"
                        val routePrefix = intent.getIntExtra(EXTRA_ROUTE_PREFIX, 24)
                        val session = intent.getStringExtra(EXTRA_SESSION) ?: "niubackup"
                        thread(name = "mesh-start") {
                            startMesh(fullConfig, instance, tunIp, tunPrefix,
                                routeNet, routePrefix, session)
                        }
                    }
                    return START_STICKY
                }
                val secret = intent?.getStringExtra(EXTRA_SECRET).orEmpty()
                if (secret.isBlank()) {
                    AppLog.log("组网密钥为空，无法启动组网")
                    demoteAndStop()
                    return START_NOT_STICKY
                }
                if (!running) {
                    running = true
                    thread(name = "mesh-start") {
                        startMesh(
                            EasyTierBridge.buildConfig(secret), EasyTierBridge.INSTANCE_NAME,
                            "10.200.0.3", 24, "10.200.0.0", 24, "machang-backup"
                        )
                    }
                }
                return START_STICKY
            }
        }
    }

    private fun startMesh(
        config: String, instanceName: String, tunIp: String, tunPrefix: Int,
        routeNet: String, routePrefix: Int, session: String
    ) {
        // NOTE: the boot promotion (dataSync type) already happened on the
        // main thread in onStartCommand. Switching to the systemExempted type
        // still has to wait for establish() below — that type requires this
        // app to already be the active VPN, otherwise startForeground throws
        // SecurityException.
        val err = EasyTierBridge.startInstance(config)
        if (err.isNotEmpty()) {
            // Core errors can be multi-hundred-line dumps (TOML parse
            // reports): UI shows the capped form, logcat keeps it all.
            AppLog.logEngineError(err)
            running = false
            MeshState.markFailed("组网核心启动失败")
            demoteAndStop()
            return
        }
        AppLog.log("EasyTier 实例已启动，正在建立 TUN…")
        notifText = "EasyTier 组网运行中 ($tunIp)"

        val builder = Builder()
            .setSession(session)
            .addAddress(tunIp, tunPrefix)
            .addRoute(routeNet, routePrefix)
            .setMtu(1500)
        val pfd = try {
            builder.establish()
        } catch (t: Throwable) {
            AppLog.log("建立 TUN 失败: ${t.message}")
            running = false
            MeshState.markFailed("建立 TUN 失败")
            EasyTierBridge.stopInstance()
            demoteAndStop()
            return
        }
        if (pfd == null) {
            AppLog.log("建立 TUN 失败: establish() 返回 null")
            running = false
            MeshState.markFailed("建立 TUN 失败")
            EasyTierBridge.stopInstance()
            demoteAndStop()
            return
        }
        tun = pfd
        promoteToForeground()
        val fdErr = EasyTierBridge.setTunFd(instanceName, pfd.fd)
        if (fdErr.isNotEmpty()) {
            AppLog.logEngineError(fdErr)
            MeshState.markFailed("组网通道建立失败")
        } else {
            AppLog.log("TUN fd 已交给 EasyTier (fd=${pfd.fd})，组网通道建立")
            MeshState.markUp()
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
        MeshState.markOff()
        AppLog.log("组网已停止")
    }

    override fun onRevoke() {
        // Another VPN app (e.g. FlClash) took the single Android VPN
        // slot. Record WHY, so the status pill can say so instead of
        // the mesh silently vanishing (design: 冲突时状态显示失败原因）.
        stopMesh()
        MeshState.markFailed("VPN 已被其他应用占用（如 FlClash），请先关闭它再打开牛备")
        AppLog.log("VPN 被系统撤销：另一个 VPN 应用接管了通道")
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (running) stopMesh()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /** Leave the foreground state and stop — the graceful failure exit. */
    private fun demoteAndStop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "组网备份", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("牛备组网")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()

    /**
     * Boot promotion: satisfies the startForegroundService contract
     * immediately, before any slow startup work runs.
     *
     * Why dataSync and not systemExempted: the systemExempted type carries a
     * runtime prerequisite — the FOREGROUND_SERVICE_SYSTEM_EXEMPTED permission
     * (held, in the manifest) PLUS one of SCHEDULE_EXACT_ALARM /
     * USE_EXACT_ALARM / the `android:activate_vpn` appop, and that appop only
     * exists once THIS app is the active VPN. Passing SYSTEM_EXEMPTED to
     * startForeground before establish() therefore throws SecurityException
     * (the M2 finding). dataSync's only prerequisite is the
     * FOREGROUND_SERVICE_DATA_SYNC permission, which we hold unconditionally,
     * so the typed call cannot throw it pre-VPN. The manifest declares both
     * types (systemExempted|dataSync), which is what makes the runtime type
     * argument legal. The dataSync phase is brief: promoteToForeground()
     * switches the service to systemExempted right after establish(), which
     * also keeps us clear of the Android 15 dataSync time limits.
     *
     * Fallbacks are belt-and-braces only: if the typed call ever threw, the
     * plain two-arg overload is tried next; if even that fails we log and let
     * startup proceed (killing the whole mesh over a notification would be
     * worse) — in practice the dataSync call is the one that lands.
     */
    private fun promoteBoot() {
        ensureChannel()
        val notif = buildNotification("组网启动中…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (t: Throwable) {
            try {
                startForeground(NOTIF_ID, notif)
            } catch (t2: Throwable) {
                AppLog.log("警告: 前台服务启动失败，组网继续启动: ${t2.message}")
            }
        }
    }

    /**
     * Post-establish promotion: switch the foreground type to systemExempted
     * (the correct long-run type for a VPN service) and refresh the
     * notification text. Retries briefly: the platform grants the
     * `android:activate_vpn` appop when the VPN becomes active, which can lag
     * establish() by a short moment. If the switch ultimately fails we log and
     * carry on with the dataSync boot promotion instead of crashing — the
     * mesh itself does not depend on the notification.
     */
    private fun promoteToForeground() {
        var lastError: SecurityException? = null
        repeat(6) { attempt ->
            try {
                ensureChannel()
                val notif = buildNotification(notifText)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIF_ID, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    )
                } else {
                    startForeground(NOTIF_ID, notif)
                }
                if (attempt > 0) AppLog.log("前台服务已启动（第 ${attempt + 1} 次尝试）")
                return
            } catch (e: SecurityException) {
                lastError = e
                Thread.sleep(300)
            }
        }
        AppLog.log("警告: 前台服务类型切换失败，组网继续运行: ${lastError?.message}")
    }
}
