package net.machang.backup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.function.IntSupplier
import kotlin.concurrent.thread

/**
 * Main screen (built in code, no XML layouts):
 *  - 账号区 (M4c): account-service URL + register/login; on login this phone is
 *    registered as a device, provisioning is fetched, and the product mesh is
 *    auto-configured (dev override: manual secret below still works).
 *  - 组网： start/stop the mesh (product config when logged in, else the
 *    self-use machang-net secret) -> BackupVpnService.
 *  - 备份： manual DCIM backup, file library （文件库）, backup records （备份记录）,
 *    auto-backup toggle (charging + WiFi + idle + direct, WorkManager).
 *  - 日志： scrolling view fed by [AppLog].
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_VPN = 1001
        private const val REQ_PERMS = 1002
        private const val PREFS = "settings"
        private const val KEY_SECRET = "mesh_secret"
        private const val KEY_USER_ID = "v2_user_id"
        private const val KEY_DEVICE_ID = "v2_device_id"
        private const val KEY_DEVICE_TOKEN = "v2_device_token"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ACCOUNT = "account_name"
        private const val KEY_PC_PORT = "pc_port"
        private const val KEY_AUTO = "auto_backup"
    }

    private lateinit var serverInput: EditText
    private lateinit var accountInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var accountStatus: TextView
    private lateinit var secretInput: EditText
    private lateinit var userIdInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var portInput: EditText
    private lateinit var vpnButton: Button
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    @Volatile
    private var meshRunning = false

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        AppLog.listener = { _ -> runOnUiThread { renderLog() } }
        renderLog()

        val p = prefs()
        serverInput.setText(p.getString(KEY_SERVER_URL, "http://10.200.0.1:18090"))
        accountInput.setText(p.getString(KEY_ACCOUNT, ""))
        secretInput.setText(p.getString(KEY_SECRET, ""))
        userIdInput.setText(p.getString(KEY_USER_ID, ""))
        deviceIdInput.setText(p.getString(KEY_DEVICE_ID, ""))
        tokenInput.setText(p.getString(KEY_DEVICE_TOKEN, ""))
        portInput.setText(p.getInt(KEY_PC_PORT, PcConn.DEFAULT_PC_PORT).toString())
        updateAccountStatus()

        vpnButton.setOnClickListener {
            if (meshRunning) {
                startService(
                    Intent(this, BackupVpnService::class.java)
                        .setAction(BackupVpnService.ACTION_STOP)
                )
                meshRunning = false
                vpnButton.text = "启动组网"
                AppLog.log("已请求停止组网")
            } else {
                persistSettings()
                if (PcConn.meshStartIntent(this) == null) {
                    AppLog.log("没有可用组网配置：先登录账号，或填写自用组网密钥")
                    return@setOnClickListener
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
        }

        requestStoragePermissions()
        AppLog.log("应用就绪。登录账号后组网会自动使用你的专属网络；也可填密钥用自用网络。")
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

    private fun persistSettings() {
        prefs().edit()
            .putString(KEY_SERVER_URL, serverInput.text.toString().trim())
            .putString(KEY_ACCOUNT, accountInput.text.toString().trim())
            .putString(KEY_SECRET, secretInput.text.toString().trim())
            .putString(KEY_USER_ID, userIdInput.text.toString().trim())
            .putString(KEY_DEVICE_ID, deviceIdInput.text.toString().trim())
            .putString(KEY_DEVICE_TOKEN, tokenInput.text.toString().trim())
            .putInt(
                KEY_PC_PORT,
                portInput.text.toString().trim().toIntOrNull() ?: PcConn.DEFAULT_PC_PORT
            )
            .apply()
    }

    private fun startMesh() {
        PcConn.startMesh(this)
        meshRunning = true
        vpnButton.text = "停止组网"
        AppLog.log("组网启动中…")
    }

    // ------------------------------------------------------------- account

    private fun updateAccountStatus() {
        val account = SecureStore.get(this, SecureStore.KEY_ACCOUNT)
        val deviceId = SecureStore.get(this, SecureStore.KEY_DEVICE_ID)
        val network = prefs().getString("network_name", null)
        accountStatus.text = if (account == null) {
            "未登录（填写账号密码后点注册或登录）"
        } else {
            "已登录: $account（设备 #$deviceId，网络 $network）"
        }
    }

    private fun onRegisterOrLogin(register: Boolean) {
        persistSettings()
        val account = accountInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (account.isEmpty() || password.isEmpty()) {
            AppLog.log("请填写账号和密码")
            return
        }
        val server = serverInput.text.toString().trim()
        AppLog.log(if (register) "正在注册…" else "正在登录…")
        thread(name = "account") {
            try {
                val client = AccountClient(server)
                val session: AccountClient.Session
                var userId: Long = -1
                if (register) {
                    userId = client.register(account, password)
                    session = client.login(account, password)
                } else {
                    session = client.login(account, password)
                }
                SecureStore.put(this, SecureStore.KEY_ACCESS_TOKEN, session.accessToken)
                SecureStore.put(this, SecureStore.KEY_REFRESH_TOKEN, session.refreshToken)
                SecureStore.put(this, SecureStore.KEY_ACCOUNT, account)

                // Register THIS phone once; the token is shown exactly once,
                // so it goes straight into Keystore-wrapped storage.
                var deviceId = SecureStore.get(this, SecureStore.KEY_DEVICE_ID)?.toLongOrNull()
                var deviceToken = SecureStore.get(this, SecureStore.KEY_DEVICE_TOKEN)
                if (deviceId == null || deviceToken == null) {
                    val devName = (Build.MANUFACTURER + " " + Build.MODEL).trim()
                    val (id, tok) = client.registerDevice(session, "phone", devName)
                    deviceId = id
                    deviceToken = tok
                    SecureStore.put(this, SecureStore.KEY_DEVICE_ID, id.toString())
                    SecureStore.put(this, SecureStore.KEY_DEVICE_TOKEN, tok)
                    AppLog.log("本机已注册为设备 #$id")
                }
                val devId: Long = deviceId
                    ?: throw IllegalStateException("device registration failed")
                val devToken: String = deviceToken
                    ?: throw IllegalStateException("device registration failed")
                if (userId < 0) {
                    // Login path: learn our numeric user_id from the license.
                    userId = client.licenseUserId(devToken)
                }
                SecureStore.put(this, SecureStore.KEY_USER_ID, userId.toString())

                // Provisioning: per-user network for the product mesh.
                val prov = client.provisioning(devToken)
                SecureStore.put(this, SecureStore.KEY_NETWORK_SECRET, prov.networkSecret)
                prefs().edit()
                    .putString("network_name", prov.networkName)
                    .putString("public_node", prov.publicNode)
                    .apply()
                AppLog.log("已获取专属组网配置: ${prov.networkName}（经 ${prov.publicNode}）")
                val (valid, reason) = client.validate(devId, devToken)
                AppLog.log(
                    if (valid) "配对状态: 已批准，可以备份" else "配对状态: $reason" +
                        if (reason == "not_approved") "（等待电脑端批准）" else ""
                )
                runOnUiThread { updateAccountStatus() }
            } catch (t: Throwable) {
                AppLog.log("账号操作失败: ${t.message}")
            }
        }
    }

    private fun onRefreshPairing() {
        val deviceId = SecureStore.get(this, SecureStore.KEY_DEVICE_ID)?.toLongOrNull()
        val token = SecureStore.get(this, SecureStore.KEY_DEVICE_TOKEN)
        if (deviceId == null || token == null) {
            AppLog.log("尚未登录/注册设备")
            return
        }
        val server = serverInput.text.toString().trim()
        thread(name = "pairing") {
            try {
                val (valid, reason) = AccountClient(server).validate(deviceId, token)
                AppLog.log(
                    if (valid) "配对状态: 已批准 ✔" else "配对状态: $reason" +
                        if (reason == "not_approved") "（等待电脑端批准）" else ""
                )
            } catch (t: Throwable) {
                AppLog.log("刷新配对状态失败: ${t.message}")
            }
        }
    }

    private fun onLogout() {
        SecureStore.clearAccount(this)
        prefs().edit().remove("network_name").remove("public_node").apply()
        AppLog.log("已退出登录（本机设备令牌已清除，如需重新使用请再次登录注册）")
        updateAccountStatus()
    }

    // -------------------------------------------------------------- backup

    private fun onManualBackup() {
        persistSettings()
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

    // ------------------------------------------------------------------ UI

    private fun renderLog() {
        // The log lives at the bottom of the single scrolling page; we set the
        // text without yanking the scroll position while the user reads/edits.
        logView.text = AppLog.snapshot()
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
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(col)
        fun lp() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        fun field(hintText: String, password: Boolean = false): EditText =
            EditText(this).apply {
                hint = hintText
                inputType = InputType.TYPE_CLASS_TEXT or
                    (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else InputType.TYPE_TEXT_VARIATION_NORMAL)
                layoutParams = lp()
            }
        fun section(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(0, 24, 0, 8)
        }
        fun buttonRow(vararg buttons: Button): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                for (b in buttons) addView(b)
            }

        col.addView(TextView(this).apply { text = "牛备 NiuBackup"; textSize = 19f })

        // ---- account section
        col.addView(section("账号"))
        serverInput = field("账号服务地址（开发默认经自用组网访问）")
        col.addView(serverInput)
        col.addView(
            TextView(this).apply {
                text = "开发阶段说明：默认地址 10.200.0.1 只有在手机连上自用组网时可达；" +
                    "正式版将提供公网 HTTPS 地址。"
                textSize = 12f
            }
        )
        accountInput = field("账号")
        col.addView(accountInput)
        passwordInput = field("密码", password = true)
        col.addView(passwordInput)
        accountStatus = TextView(this)
        col.addView(accountStatus)
        col.addView(
            buttonRow(
                Button(this).apply {
                    text = "注册"
                    setOnClickListener { onRegisterOrLogin(register = true) }
                },
                Button(this).apply {
                    text = "登录"
                    setOnClickListener { onRegisterOrLogin(register = false) }
                },
                Button(this).apply {
                    text = "刷新配对状态"
                    setOnClickListener { onRefreshPairing() }
                },
                Button(this).apply {
                    text = "退出"
                    setOnClickListener { onLogout() }
                }
            )
        )

        // ---- mesh section
        col.addView(section("组网"))
        secretInput = field("开发者：自用组网密钥 machang-net（登录后可不填）", password = true)
        col.addView(secretInput)
        vpnButton = Button(this).apply { text = "启动组网" }
        col.addView(vpnButton)

        // ---- backup section
        col.addView(section("备份"))
        portInput = field("电脑备份端口（默认 28741）")
        col.addView(portInput)
        col.addView(
            buttonRow(
                Button(this).apply {
                    text = "立即备份 DCIM"
                    setOnClickListener { onManualBackup() }
                },
                Button(this).apply {
                    text = "文件库"
                    setOnClickListener {
                        persistSettings()
                        startActivity(Intent(this@MainActivity, LibraryActivity::class.java))
                    }
                },
                Button(this).apply {
                    text = "备份记录"
                    setOnClickListener {
                        persistSettings()
                        startActivity(Intent(this@MainActivity, RecordsActivity::class.java))
                    }
                }
            )
        )
        val autoBox = CheckBox(this).apply {
            text = "自动备份（充电 + WiFi + 空闲 + 直连时）"
            isChecked = prefs().getBoolean(KEY_AUTO, false)
            setOnCheckedChangeListener { _, checked ->
                prefs().edit().putBoolean(KEY_AUTO, checked).apply()
                AutoBackupWorker.setEnabled(this@MainActivity, checked)
                AppLog.log(if (checked) "自动备份已开启" else "自动备份已关闭")
            }
        }
        col.addView(autoBox)

        // ---- dev v2 identity (standalone seeded-receiver mode)
        col.addView(section("开发者：v2 独立模式身份"))
        userIdInput = field("账号 user_id（独立模式）")
        col.addView(userIdInput)
        deviceIdInput = field("设备 device_id（独立模式）")
        col.addView(deviceIdInput)
        tokenInput = field("设备令牌 device token（独立模式）", password = true)
        col.addView(tokenInput)

        // ---- log
        col.addView(section("日志"))
        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        col.addView(logView)
        logScroll = root

        setContentView(root)
    }
}
