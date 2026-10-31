package net.machang.backup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 * 备份 (Home) page, laid out per the approved design mockup (v1.1):
 * header title + persistent mesh status pill → 账户与配对 card (kv rows +
 * mesh control) → 立即备份 big button, which transforms IN PLACE into the
 * progress card while a session runs (no page jump, no modal) → 上次备份
 * card (summary + auto-backup toggle + live conditions dots) → 日志 card
 * (latest human-readable lines only).
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

    private lateinit var meshPill: LinearLayout
    private lateinit var accountValue: TextView
    private lateinit var pairingSlot: LinearLayout
    private lateinit var targetValue: TextView
    private lateinit var meshLine: TextView
    private lateinit var vpnButton: Button
    private lateinit var backupButton: Button
    private lateinit var progressCard: LinearLayout
    private lateinit var progressBadgeSlot: LinearLayout
    private lateinit var progressFile: TextView
    private lateinit var progressSize: TextView
    private lateinit var progressBar: Ui.ProgressBarView
    private lateinit var pauseButton: Button

    /** The manual session's client while one runs (null otherwise), so
     *  the pause button can ask THAT instance to stop. The auto-backup
     *  worker's client is a different instance and stays unaffected. */
    @Volatile
    private var runningClient: BackupClient? = null
    private lateinit var sessionLine: TextView
    private lateinit var logView: TextView

    private val condDots = HashMap<String, View>()
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

    /** Tab switches are instant in both directions (see [Ui.navBar]). */
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
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
        targetValue.text = "${PcConn.pcHost(this)} · 电脑备份目录"
        if (account == null) {
            accountValue.text = if (SessionRestore.expiredHintPending(this)) {
                "登录已过期 — 去「我的」页重新登录"
            } else {
                "未登录 — 去「我的」页注册或登录"
            }
            setPairingBadge("配对: —", Ui.StatusKind.NONE)
            return
        }
        accountValue.text = account
        setPairingBadge("配对: 查询中…", Ui.StatusKind.NONE)
        val devId = SecureStore.get(this, SecureStore.KEY_DEVICE_ID)?.toLongOrNull()
        val token = SecureStore.get(this, SecureStore.KEY_DEVICE_TOKEN)
        if (devId == null || token == null) {
            setPairingBadge("配对: —", Ui.StatusKind.NONE)
            return
        }
        thread(name = "home-pairing") {
            val (text, kind) = try {
                val (valid, reason) = AccountClient(PcConn.serverUrl(this)).validate(devId, token)
                if (valid) {
                    "已批准" to Ui.StatusKind.OK
                } else {
                    (reason + if (reason == "not_approved") "（等待电脑端批准）" else "") to
                        Ui.StatusKind.WAIT
                }
            } catch (t: Throwable) {
                "未知（账户服务不可达）" to Ui.StatusKind.NONE
            }
            runOnUiThread { if (!isFinishing) setPairingBadge(text, kind) }
        }
    }

    private fun setPairingBadge(text: String, kind: Ui.StatusKind) {
        pairingSlot.removeAllViews()
        pairingSlot.addView(Ui.badge(this, text, kind))
    }

    private fun refreshSessionLine() {
        if (BackupProgress.active) {
            // Design rule: the big button transforms in place into the
            // progress card while a session runs.
            backupButton.visibility = View.GONE
            progressCard.visibility = View.VISIBLE
            val path = BackupProgress.currentPath ?: ""
            progressFile.text = path.substringAfterLast('/').ifEmpty { "准备中…" }
            progressSize.text = "${BackupProgress.percent}%"
            progressBar.setPercent(BackupProgress.percent)
            progressBadgeSlot.removeAllViews()
            progressBadgeSlot.addView(
                Ui.badge(this, "备份中 · ${BackupProgress.percent}%", Ui.StatusKind.RUN)
            )
            // Pause belongs to the manual session only: during a worker
            // session there is no manual client to stop, so hide it.
            pauseButton.visibility = if (runningClient != null) View.VISIBLE else View.GONE
            if (BackupProgress.pauseRequested) {
                pauseButton.text = "正在暂停…"
                pauseButton.isEnabled = false
            } else {
                pauseButton.text = "暂停备份"
                pauseButton.isEnabled = true
            }
        } else {
            progressCard.visibility = View.GONE
            backupButton.visibility = View.VISIBLE
            backupButton.text = if (BackupProgress.paused) "继续备份" else "立即备份"
        }
        sessionLine.text =
            prefs().getString(KEY_LAST_SESSION, "暂无记录 — 完成一次备份后显示摘要")
                ?: "暂无记录 — 完成一次备份后显示摘要"
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

    /**
     * Live auto-backup conditions: 充电 / WiFi / 空闲 / 直连 dots (green when
     * met, hollow slate otherwise). Route cost is a JNI call, off-thread.
     * Also drives the header mesh pill （直连 / 等待直连）.
     */
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
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                setDot("充电", charging)
                setDot("WiFi", wifi)
                setDot("空闲", idle)
                setDot("直连", direct)
                when {
                    direct -> Ui.setPill(meshPill, "组网已连接 · 直连", Ui.StatusKind.OK)
                    meshRunning -> Ui.setPill(meshPill, "等待直连", Ui.StatusKind.WAIT)
                    else -> Ui.setPill(meshPill, "组网未连接", Ui.StatusKind.PC)
                }
            }
        }
    }

    private fun setDot(key: String, on: Boolean) {
        val dot = condDots[key] ?: return
        dot.background = if (on) {
            Ui.circleDrawable(this, Ui.OK)
        } else {
            Ui.rounded(this, Color.TRANSPARENT, 999, Ui.SLATE, 1).apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
            }
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
            meshLine.text = "未运行"
            Ui.setPill(meshPill, "组网未连接", Ui.StatusKind.PC)
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
        meshLine.text = "启动中…"
        Ui.setPill(meshPill, "等待直连", Ui.StatusKind.WAIT)
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
                ).apply {
                    // Feed the library page's local file index as the
                    // session runs (metadata / backed / receipt events).
                    setIndexSink(FileIndexDb.sink(this@MainActivity))
                }
            } else {
                BackupClient(logger, filesDir)
            }
            runningClient = client
            BackupProgress.paused = false
            BackupProgress.pauseRequested = false
            BackupProgress.begin()
            val failures = try {
                client.runBackup(host, port, BackupClient.DEVICE_NAME, dcim) { sent, total, path ->
                    BackupProgress.update(sent, total, path)
                }
            } finally {
                BackupProgress.end()
                if (runningClient === client) runningClient = null
            }
            val stoppedByPause = client.lastStoppedByPause
            if (stoppedByPause) BackupProgress.paused = true
            BackupProgress.pauseRequested = false
            val summary = buildString {
                append("上次备份: ")
                append(SimpleDateFormat("HH:mm", Locale.US).format(Date()))
                if (stoppedByPause) append(" · 已暂停")
                append(" · 成功 ${client.lastOkCount} 个 · ${fmtBytes(client.lastBytesSent)}")
                if (failures > 0) append(" · 失败 $failures 个")
                if (client.lastSkippedCount > 0) append(" · 跳过 ${client.lastSkippedCount} 个无法读取")
            }
            prefs().edit().putString(KEY_LAST_SESSION, summary).apply()
            if (identity != null && PcConn.isAccountMode(this)) {
                try {
                    AccountClient(PcConn.serverUrl(this)).postSessionLog(
                        identity.token, client.lastOkCount, client.lastBytesSent,
                        if (stoppedByPause) "paused" else if (failures == 0) "ok" else "partial"
                    )
                    AppLog.log("会话记录已上报（仅计数）")
                } catch (t: Throwable) {
                    AppLog.log("会话记录上报失败（不影响备份）: ${t.message}")
                }
            }
        }
    }

    /**
     * 暂停备份: ask the running manual session to stop at its next
     * checkpoint (between files / batches, or the chunk boundary of an
     * in-flight file). The client finishes gracefully — BYE epilogue
     * when between files, connection close mid-file with the receiver's
     * .part kept for resume — and this page then shows 已暂停 + 继续备份.
     */
    private fun onPauseBackup() {
        val client = runningClient ?: return
        BackupProgress.requestPause()
        client.requestStop()
        AppLog.log("已请求暂停：将在当前文件传完后停下；大文件会在块边界中断，未传完部分下次续传")
        refreshSessionLine()
    }

    private fun fmtBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
        else -> "%.2f GB".format(b / 1024.0 / 1024 / 1024)
    }

    // ------------------------------------------------------------------ UI

    private fun renderLog() {
        if (!::logView.isInitialized) return
        // Home shows only the latest human lines (mockup: 3–4 lines).
        val lines = AppLog.snapshot().trimEnd('\n').split('\n').filter { it.isNotBlank() }
        logView.text = lines.takeLast(6).joinToString("\n")
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
        val page = Ui.page(this, Ui.Page.HOME, "备份")
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        page.content.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        meshPill = Ui.pill(this, "组网未连接", Ui.StatusKind.PC)
        page.headerRight.addView(meshPill)

        // ---- 账户与配对 card (kv rows + mesh control)
        col.addView(Ui.cardBlock(this) {
            accountValue = Ui.kvValue(this@MainActivity, "")
            addView(Ui.kvRow(this@MainActivity, "账号", accountValue))
            pairingSlot = LinearLayout(this@MainActivity).apply {
                gravity = Gravity.END
            }
            addView(Ui.kvRow(this@MainActivity, "配对状态", pairingSlot))
            targetValue = Ui.kvValue(this@MainActivity, "")
            addView(Ui.kvRow(this@MainActivity, "备份目标", targetValue))
            addView(Ui.hairline(this@MainActivity))
            val meshRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val meshCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            meshCol.addView(TextView(this@MainActivity).apply {
                text = "产品组网"
                textSize = 13.5f
                setTextColor(Ui.INK)
                typeface = Ui.font(700)
            })
            meshLine = Ui.subText(this@MainActivity, "未运行")
            meshCol.addView(meshLine)
            meshRow.addView(
                meshCol,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            vpnButton = Ui.ghostButton(this@MainActivity, "启动组网").apply {
                setOnClickListener { onMeshButton() }
            }
            meshRow.addView(vpnButton)
            addView(meshRow)
            addView(
                Ui.subText(
                    this@MainActivity,
                    "组网连上后才能备份、下载和播放；备份只走直连，不走中继。"
                ),
                Ui.spaced(Ui.matchWrap(), 6, this@MainActivity)
            )
        })

        // ---- 立即备份 big button (transforms into the progress card)
        backupButton = Ui.bigButton(this, "立即备份").apply {
            setOnClickListener { onManualBackup() }
        }
        col.addView(backupButton)

        // ---- progress card (hidden until a session runs)
        progressCard = Ui.cardBlock(this) {
            val headRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headRow.addView(TextView(this@MainActivity).apply {
                text = "本次进度"
                textSize = 12f
                setTextColor(Ui.SLATE)
                typeface = Ui.font(700)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            progressBadgeSlot = LinearLayout(this@MainActivity)
            headRow.addView(progressBadgeSlot)
            addView(headRow)
            val fileRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 8))
            }
            progressFile = TextView(this@MainActivity).apply {
                textSize = 13.5f
                setTextColor(Ui.INK)
                typeface = Ui.font(800)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            fileRow.addView(
                progressFile,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            progressSize = TextView(this@MainActivity).apply {
                textSize = 11.5f
                setTextColor(Ui.SLATE)
            }
            fileRow.addView(progressSize)
            addView(fileRow)
            progressBar = Ui.progressBar(this@MainActivity, Ui.RUN)
            addView(progressBar)
            pauseButton = Ui.ghostButton(this@MainActivity, "暂停备份").apply {
                setOnClickListener { onPauseBackup() }
            }
            addView(pauseButton, Ui.spaced(Ui.matchWrap(), 10, this@MainActivity))
            addView(
                Ui.subText(this@MainActivity, "正在经直连传输；断点续传已启用，中断后可继续。"),
                Ui.spaced(Ui.matchWrap(), 8, this@MainActivity)
            )
        }
        progressCard.visibility = View.GONE
        col.addView(progressCard)

        // ---- 上次备份 card (+ auto-backup toggle + conditions dots)
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@MainActivity, "上次备份"))
            sessionLine = TextView(this@MainActivity).apply {
                textSize = 13f
                setTextColor(Ui.INK)
                typeface = Ui.font(700)
            }
            addView(sessionLine)
            addView(Ui.hairline(this@MainActivity))
            val autoBox = CheckBox(this@MainActivity).apply {
                text = "自动备份（充电 + WiFi + 空闲 + 直连时）"
                textSize = 13.5f
                setTextColor(Ui.INK)
                minHeight = Ui.dp(this@MainActivity, 44)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    buttonTintList = android.content.res.ColorStateList.valueOf(Ui.BRAND_600)
                }
                isChecked = prefs().getBoolean(KEY_AUTO, false)
                setOnCheckedChangeListener { _, checked ->
                    prefs().edit().putBoolean(KEY_AUTO, checked).apply()
                    AutoBackupWorker.setEnabled(this@MainActivity, checked)
                    AppLog.log(if (checked) "自动备份已开启" else "自动备份已关闭")
                }
            }
            addView(autoBox, Ui.spaced(Ui.matchWrap(), 2, this@MainActivity))
            val condRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Ui.dp(this@MainActivity, 8), 0, 0)
            }
            for (label in listOf("充电", "WiFi", "空闲", "直连")) {
                val group = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val dot = View(this@MainActivity).apply {
                    background = Ui.circleDrawable(this@MainActivity, Ui.SLATE_BG)
                }
                condDots[label] = dot
                group.addView(
                    dot,
                    LinearLayout.LayoutParams(
                        Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 8)
                    ).apply { rightMargin = Ui.dp(this@MainActivity, 5) }
                )
                group.addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 12f
                    setTextColor(Ui.INK_2)
                })
                condRow.addView(
                    group,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            addView(condRow)
        })

        // ---- 日志 card (latest lines only)
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@MainActivity, "日志"))
            logView = TextView(this@MainActivity).apply {
                textSize = 10.5f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(Ui.INK_2)
                setLineSpacing(0f, 1.6f)
            }
            addView(logView)
        })
        col.addView(View(this), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 12)
        ))
    }
}
