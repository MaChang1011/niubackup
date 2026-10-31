package net.machang.backup

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * 我的 page: account + settings.
 *
 *  - 账号区： account-service URL + register/login; on login this phone is
 *    registered as a device, provisioning is fetched, and the product mesh is
 *    auto-configured (moved here from the old single-screen MainActivity).
 *  - 设备信息： account, device id, per-user network, pairing state.
 *  - 设置： PC backup port; developer overrides (self-use mesh secret and
 *    the M4a standalone v2 identity fields).
 *  - 关于： app name / version.
 *
 * Secrets live in [SecureStore] (Keystore-wrapped); only non-secret settings
 * go to the shared "settings" preferences, exactly as before.
 */
class AccountActivity : Activity() {

    companion object {
        private const val PREFS = "settings"
        private const val KEY_SECRET = "mesh_secret"
        private const val KEY_USER_ID = "v2_user_id"
        private const val KEY_DEVICE_ID = "v2_device_id"
        private const val KEY_DEVICE_TOKEN = "v2_device_token"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ACCOUNT = "account_name"
        private const val KEY_PC_PORT = "pc_port"
    }

    private lateinit var serverInput: EditText
    private lateinit var accountInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButtons: LinearLayout
    private lateinit var accountStatus: TextView
    private lateinit var deviceInfo: TextView
    private lateinit var secretInput: EditText
    private lateinit var userIdInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var portInput: EditText

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        val p = prefs()
        serverInput.setText(p.getString(KEY_SERVER_URL, "http://10.200.0.1:18090"))
        accountInput.setText(p.getString(KEY_ACCOUNT, ""))
        secretInput.setText(p.getString(KEY_SECRET, ""))
        userIdInput.setText(p.getString(KEY_USER_ID, ""))
        deviceIdInput.setText(p.getString(KEY_DEVICE_ID, ""))
        tokenInput.setText(p.getString(KEY_DEVICE_TOKEN, ""))
        portInput.setText(p.getInt(KEY_PC_PORT, PcConn.DEFAULT_PC_PORT).toString())
        updateAccountStatus()
        // If the cold-start restore (MainActivity) is still running, reflect
        // its outcome here when it lands (expired session -> form + hint).
        SessionRestore.restoreAsync(this) { runOnUiThread { updateAccountStatus() } }
    }

    override fun onPause() {
        persistSettings()
        super.onPause()
    }

    private fun persistSettings() {
        if (!::serverInput.isInitialized) return
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

    // ------------------------------------------------------------- account

    private fun updateAccountStatus() {
        val account = SecureStore.get(this, SecureStore.KEY_ACCOUNT)
            ?: prefs().getString(KEY_ACCOUNT, "")?.ifEmpty { null }
        val deviceId = SecureStore.get(this, SecureStore.KEY_DEVICE_ID)
        val userId = SecureStore.get(this, SecureStore.KEY_USER_ID)
        val network = prefs().getString("network_name", null)
        val node = prefs().getString("public_node", null)
        // Session persistence: a stored session (refresh token in
        // SecureStore) means "logged in" — the password form stays hidden.
        // It reappears only with no session, after 退出登录, or when the
        // restore found the session server-rejected (expired hint).
        val loggedIn = SessionRestore.hasStoredSession(this)
        accountStatus.text = when {
            loggedIn -> "已登录: ${account ?: ""}"
            SessionRestore.expiredHintPending(this) -> "登录已过期，请重新登录"
            else -> "未登录（填写账号密码后点注册或登录）"
        }
        if (::passwordInput.isInitialized && ::loginButtons.isInitialized) {
            val vis = if (loggedIn) View.GONE else View.VISIBLE
            passwordInput.visibility = vis
            loginButtons.visibility = vis
        }
        deviceInfo.text = buildString {
            append("账号: ${account ?: "—"}\n")
            append("用户 ID: ${userId ?: "—"}\n")
            append("本机设备: ${deviceId?.let { "#$it" } ?: "—"}（${Build.MANUFACTURER} ${Build.MODEL}）\n")
            append("专属组网: ${network ?: "—"}\n")
            append("公共节点: ${node ?: "—"}\n")
            append("模式: ${if (PcConn.isAccountMode(this@AccountActivity)) "账号模式" else "开发者模式"}")
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
                SessionRestore.storeSession(this, session)
                SecureStore.put(this, SecureStore.KEY_ACCOUNT, account)
                SessionRestore.clearExpiredHint(this)

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
        // Server-side revocation first (best effort, off-thread): presenting
        // the refresh token to /api/auth/logout kills the stored session
        // pair on the server too, not just on this phone.
        val refresh = SecureStore.get(this, SecureStore.KEY_REFRESH_TOKEN)
        val server = serverInput.text.toString().trim()
        if (refresh != null) {
            thread(name = "logout") { AccountClient(server).logout(refresh) }
        }
        SecureStore.clearAccount(this)
        SessionRestore.clearExpiredHint(this)
        prefs().edit().remove("network_name").remove("public_node").apply()
        AppLog.log("已退出登录（本机设备令牌已清除，如需重新使用请再次登录注册）")
        updateAccountStatus()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val page = Ui.page(this, Ui.Page.ACCOUNT)
        val scroll = android.widget.ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col)
        page.content.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        col.addView(Ui.header(this, "我的", "账号、设备与设置"))

        // ---- account card
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@AccountActivity, "账号"))
            serverInput = Ui.field(this@AccountActivity, "账号服务地址")
            addView(serverInput, Ui.spaced(Ui.matchWrap(), 2, this@AccountActivity))
            addView(
                Ui.subText(
                    this@AccountActivity,
                    "开发阶段说明：默认地址 10.200.0.1 只有在手机连上自用组网时可达；" +
                        "正式版将提供公网 HTTPS 地址。"
                ),
                Ui.spaced(Ui.matchWrap(), 4, this@AccountActivity)
            )
            accountInput = Ui.field(this@AccountActivity, "账号")
            addView(accountInput, Ui.spaced(Ui.matchWrap(), 8, this@AccountActivity))
            passwordInput = Ui.field(this@AccountActivity, "密码", password = true)
            addView(passwordInput, Ui.spaced(Ui.matchWrap(), 8, this@AccountActivity))
            accountStatus = Ui.bodyText(this@AccountActivity)
            addView(accountStatus, Ui.spaced(Ui.matchWrap(), 8, this@AccountActivity))
            loginButtons = LinearLayout(this@AccountActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            loginButtons.addView(Ui.primaryButton(this@AccountActivity, "注册").apply {
                setOnClickListener { onRegisterOrLogin(register = true) }
            })
            loginButtons.addView(Ui.primaryButton(this@AccountActivity, "登录").apply {
                setOnClickListener { onRegisterOrLogin(register = false) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Ui.dp(this@AccountActivity, 8) })
            addView(loginButtons, Ui.spaced(Ui.matchWrap(), 10, this@AccountActivity))
            val row2 = LinearLayout(this@AccountActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row2.addView(Ui.ghostButton(this@AccountActivity, "刷新配对状态").apply {
                setOnClickListener { onRefreshPairing() }
            })
            row2.addView(Ui.ghostButton(this@AccountActivity, "退出登录").apply {
                setOnClickListener { onLogout() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Ui.dp(this@AccountActivity, 8) })
            addView(row2, Ui.spaced(Ui.matchWrap(), 8, this@AccountActivity))
        })

        // ---- device info card
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@AccountActivity, "设备信息"))
            deviceInfo = Ui.bodyText(this@AccountActivity)
            addView(deviceInfo)
        })

        // ---- settings card
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@AccountActivity, "设置"))
            portInput = Ui.field(this@AccountActivity, "电脑备份端口（默认 28741）")
            addView(portInput, Ui.spaced(Ui.matchWrap(), 2, this@AccountActivity))
        })

        // ---- developer overrides card
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@AccountActivity, "开发者选项"))
            secretInput = Ui.field(
                this@AccountActivity, "自用组网密钥 machang-net（登录后可不填）", password = true
            )
            addView(secretInput, Ui.spaced(Ui.matchWrap(), 2, this@AccountActivity))
            addView(
                Ui.subText(this@AccountActivity, "v2 独立模式身份（连接 --seed 的接收端时使用）"),
                Ui.spaced(Ui.matchWrap(), 10, this@AccountActivity)
            )
            userIdInput = Ui.field(this@AccountActivity, "账号 user_id（独立模式）")
            addView(userIdInput, Ui.spaced(Ui.matchWrap(), 6, this@AccountActivity))
            deviceIdInput = Ui.field(this@AccountActivity, "设备 device_id（独立模式）")
            addView(deviceIdInput, Ui.spaced(Ui.matchWrap(), 6, this@AccountActivity))
            tokenInput = Ui.field(this@AccountActivity, "设备令牌 device token（独立模式）", password = true)
            addView(tokenInput, Ui.spaced(Ui.matchWrap(), 6, this@AccountActivity))
            addView(
                Button(this@AccountActivity).apply {
                    text = "保存设置"
                    setAllCaps(false)
                    setOnClickListener {
                        persistSettings()
                        AppLog.log("设置已保存")
                    }
                },
                Ui.spaced(Ui.matchWrap(), 8, this@AccountActivity)
            )
        })

        // ---- about card
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (t: Throwable) {
            "?"
        }
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@AccountActivity, "关于"))
            addView(Ui.bodyText(this@AccountActivity, "牛备 NiuBackup v$version"))
            addView(
                Ui.subText(
                    this@AccountActivity,
                    "把手机照片和文件自动备份到你自己的电脑——不经云端，记录只存本地。"
                ),
                Ui.spaced(Ui.matchWrap(), 4, this@AccountActivity)
            )
        })
    }
}
