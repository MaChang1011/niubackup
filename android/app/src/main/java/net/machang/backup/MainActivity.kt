package net.machang.backup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.function.IntSupplier
import kotlin.concurrent.thread

/**
 * 备份 (Home) page:
 *  - 状态卡： account / pairing summary (details live on the 我的 page).
 *  - 组网卡： start/stop the mesh (product config when logged in, else the
 *    self-use machang-net secret) via [BackupVpnService].
 *  - 备份卡： big manual-backup button, live session progress, last-session
 *    summary, the auto-backup toggle (charging + WiFi + idle + direct route,
 *    WorkManager) with a live conditions indicator.
 *  - 日志卡： scrolling view fed by [AppLog].
 *
 * Account forms, settings and developer overrides live in [AccountActivity];
 * the file library and PC records are their own pages (bottom navigation).
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN = 1001
        private const val REQ_PERMS = 1002
        private const val PREFS = "settings"
        private const val KEY_AUTO = "auto_backup"
        private const val KEY_LAST_SESSION = "last_session_summary"
    }

    private lateinit var accountLine: TextView
    private lateinit var pairingLine: TextView
    private lateinit var meshLine: TextView
    private lateinit var vpnButton: Button
    private lateinit var sessionLine: TextView
    private lateinit var conditionsLine: TextView
    private lateinit var logView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var poller: Runnable? = null

    @Volatile
    private var meshRunning = false

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        AppLog.listener = { _ -> runOnUiThread { renderLog() } }
        renderLog()
        requestStoragePermissions()
        AppLog.log("应用就绪。登录账号后组网会自动使用你的专属网络；也可填密钥用自用网络。")
        // Saved-session restore (once per process): rotate the stored
        // refresh token when the access token has lapsed; a definitive
        // server rejection flips the status card to 登录已过期. Offline
        // cold starts keep the saved login untouched.
        SessionRestore.restoreAsync(this) { outcome ->
            if (outcome == SessionRestore.Outcome.EXPIRED) {
                AppLog.log("登录已过期，请到「我的」页重新登录")
            }
            runOnUiThread { if (!isFinishing) refreshStatusCard() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatusCard()
        startPolling()
    }

    override fun onPause() {
        stopPolling()
        super.onPause()
    }

    override fun onDestroy() {
        AppLog.listener = null
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                startMesh()
            } else {
                AppLog.log("VPN 权限被拒绝，组网未启动")
            }
        }
    }

    // --------------------------------------------------------------- status

    private fun refreshStatusCard() {
        val account = SecureStore.get(this, SecureStore.KEY_ACCOUNT)
        val deviceId = SecureStore.get(this, SecureStore.KEY_DEVICE_ID)
        val network = prefs().getString("network_name", null)
        if (account == null) {
            accountLine.text = if (SessionRestore.expiredHintPending(this)) {
                "登录已过期 — 去「我的」页重新登录"
            } else {
                "未登录 — 去「我的」页注册或登录"
            }
            pairingLine.text = "配对: —"
            return
        }
        accountLine.text = "已登录: $account（设备 #$deviceId，网络 ${network ?: "—"}）"
        pairingLine.text = "配对: 查询中…"
        val devId = deviceId?.toLongOrNull()
        val token = SecureStore.get(this, SecureStore.KEY_DEVICE_TOKEN)
        if (devId == null || token == null) {
            pairingLine.text = "配对: —"
            return
        }
        thread(name = "home-pairing") {
            val text = try {
                val (valid, reason) = AccountClient(PcConn.serverUrl(this)).validate(devId, token)
                if (valid) "配对: 已批准 ✔" else "配对: $reason" +
                    if (reason == "not_approved") "（等待电脑端批准）" else ""
            } catch (t: Throwable) {
                "配对: 未知（账户服务不可达）"
            }
            runOnUiThread { if (!isFinishing) pairingLine.text = text }
        }
    }

    private fun refreshSessionLine() {
        sessionLine.text = if (BackupProgress.active) {
            val p = BackupProgress.currentPath ?: ""
            "备份进行中：$p（${BackupProgress.percent}%）"
        } else {
            prefs().getString(KEY_LAST_SESSION, "上次备份: 暂无记录") ?: "上次备份: 暂无记录"
        }
    }

    // ------------------------------------------------------- conditions UI

    private fun startPolling() {
        stopPolling()
        val r = object : Runnable {
            override fun run() {
                refreshSessionLine()
                refreshConditionsAsync()
                handler.postDelayed(this, 2000)
            }
        }
        poller = r
        handler.post(r)
    }

    private fun stopPolling() {
        poller?.let { handler.removeCallbacks(it) }
        poller = null
    }

    /** Renders 充电 ● / WiFi ● / 空闲 ○ / 直连 ○ — route cost is a JNI call, off-thread. */
    private fun refreshConditionsAsync() {
        val charging = isCharging()
        val wifi = isOnWifi()
        val idle = isIdle()
        thread(name = "cond") {
            val direct = try {
                EasyTierBridge.peerRouteCost(PcConn.pcHost(this)) == 1
            } catch (t: Throwable) {
                false
            }
            val dot = { on: Boolean -> if (on) "●" else "○" }
            val text = "自动备份条件：充电 ${dot(charging)} · WiFi ${dot(wifi)} · " +
                "空闲 ${dot(idle)} · 直连 ${dot(direct)}"
            runOnUiThread { if (!isFinishing) conditionsLine.text = text }
        }
    }

    private fun isCharging(): Boolean {
        val bm = getSystemService(BatteryManager::class.java)
        if (bm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return bm.isCharging
        }
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun isOnWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isIdle(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm.isDeviceIdleMode
    }

    // ----------------------------------------------------------------- mesh

    private fun onMeshButton() {
        if (meshRunning) {
            startService(
                Intent(this, BackupVpnService::class.java)
                    .setAction(BackupVpnService.ACTION_STOP)
            )
            meshRunning = false
            vpnButton.text = "启动组网"
            meshLine.text = "组网: 未运行"
            AppLog.log("已请求停止组网")
            return
        }
        if (PcConn.meshStartIntent(this) == null) {
            AppLog.log("没有可用组网配置：先登录账号，或填写自用组网密钥")
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            AppLog.log("请求 VPN 权限…")
            @Suppress("DEPRECATION")
            startActivityForResult(prepare, REQ_VPN)
        } else {
            startMesh()
        }
    }

    private fun startMesh() {
        PcConn.startMesh(this)
        meshRunning = true
        vpnButton.text = "停止组网"
        meshLine.text = "组网: 启动中…"
        AppLog.log("组网启动中…")
    }

    // --------------------------------------------------------------- backup

    private fun onManualBackup() {
        val identity = PcConn.identity(this)
        val host = PcConn.pcHost(this)
        val port = PcConn.pcPort(this)
        AppLog.log("开始备份 DCIM → $host:$port…")
        thread(name = "backup") {
            val dcim = File(Environment.getExternalStorageDirectory(), "DCIM")
            val logger = BackupClient.Logger { msg -> AppLog.log(msg) }
            val client = if (identity != null) {
                BackupClient(
                    logger, filesDir, identity.userId, identity.deviceId, identity.token,
                    IntSupplier { EasyTierBridge.peerRouteCost(host) }
                )
            } else {
                BackupClient(logger, filesDir)
            }
            BackupProgress.begin()
            val failures = try {
                client.runBackup(host, port, BackupClient.DEVICE_NAME, dcim) { sent, total, path ->
                    BackupProgress.update(sent, total, path)
                }
            } finally {
                BackupProgress.end()
            }
            val summary = buildString {
                append("上次备份: ")
                append(SimpleDateFormat("HH:mm", Locale.US).format(Date()))
                append(" · 成功 ${client.lastOkCount} 个 · ${fmtBytes(client.lastBytesSent)}")
                if (failures > 0) append(" · 失败 $failures 个")
            }
            prefs().edit().putString(KEY_LAST_SESSION, summary).apply()
            if (identity != null && PcConn.isAccountMode(this)) {
                try {
                    AccountClient(PcConn.serverUrl(this)).postSessionLog(
                        identity.token, client.lastOkCount, client.lastBytesSent,
                        if (failures == 0) "ok" else "partial"
                    )
                    AppLog.log("会话记录已上报（仅计数）")
                } catch (t: Throwable) {
                    AppLog.log("会话记录上报失败（不影响备份）: ${t.message}")
                }
            }
        }
    }

    private fun fmtBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
        else -> "%.2f GB".format(b / 1024.0 / 1024 / 1024)
    }

    // ------------------------------------------------------------------ UI

    private fun renderLog() {
        if (::logView.isInitialized) logView.text = AppLog.snapshot()
    }

    private fun requestStoragePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            perms += Manifest.permission.READ_MEDIA_IMAGES
            perms += Manifest.permission.READ_MEDIA_VIDEO
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = perms.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    private fun buildUi() {
        val page = Ui.page(this, Ui.Page.HOME)
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        page.content.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        col.addView(Ui.header(this, "备份", "手机文件自动备份到你自己的电脑"))

        // ---- status card
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@MainActivity, "状态"))
            accountLine = Ui.bodyText(this@MainActivity)
            addView(accountLine)
            pairingLine = Ui.bodyText(this@MainActivity)
            addView(pairingLine, Ui.spaced(Ui.matchWrap(), 4, this@MainActivity))
        })

        // ---- mesh card
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@MainActivity, "组网"))
            meshLine = Ui.bodyText(this@MainActivity, "组网: 未运行")
            addView(meshLine)
            vpnButton = Ui.accentButton(this@MainActivity, "启动组网").apply {
                setOnClickListener { onMeshButton() }
            }
            addView(vpnButton, Ui.spaced(Ui.matchWrap(), 8, this@MainActivity))
            addView(
                Ui.subText(this@MainActivity, "组网连上后才能备份、下载和播放；备份只走直连，不走中继。"),
                Ui.spaced(Ui.matchWrap(), 6, this@MainActivity)
            )
        })

        // ---- backup card
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@MainActivity, "备份"))
            addView(Ui.accentButton(this@MainActivity, "立即备份 DCIM").apply {
                setOnClickListener { onManualBackup() }
            }, Ui.matchWrap())
            sessionLine = Ui.bodyText(this@MainActivity)
            addView(sessionLine, Ui.spaced(Ui.matchWrap(), 8, this@MainActivity))
            val autoBox = CheckBox(this@MainActivity).apply {
                text = "自动备份（充电 + WiFi + 空闲 + 直连时）"
                textSize = 14f
                isChecked = prefs().getBoolean(KEY_AUTO, false)
                setOnCheckedChangeListener { _, checked ->
                    prefs().edit().putBoolean(KEY_AUTO, checked).apply()
                    AutoBackupWorker.setEnabled(this@MainActivity, checked)
                    AppLog.log(if (checked) "自动备份已开启" else "自动备份已关闭")
                }
            }
            addView(autoBox, Ui.spaced(Ui.matchWrap(), 8, this@MainActivity))
            conditionsLine = Ui.subText(this@MainActivity)
            addView(conditionsLine, Ui.spaced(Ui.matchWrap(), 2, this@MainActivity))
        })

        // ---- log card
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@MainActivity, "日志"))
            logView = TextView(this@MainActivity).apply {
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Ui.TEXT_SUB)
            }
            addView(logView)
        })
    }
}
