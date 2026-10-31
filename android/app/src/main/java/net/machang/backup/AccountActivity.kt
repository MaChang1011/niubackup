package net.machang.backup

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * 我的 page (approved mockup v1.1): profile card (gradient avatar with 牛，
 * account, network/device sub-line, session + pairing pills) → 账号 form
 * card → 设备与配对 card → 设置 / 开发者选项 cards → 关于 card (wordmark) →
 * standalone logout button (low-saturation red ghost).
 *
 * Behavior is unchanged from the functional build: on login this phone is
 * registered as a device, provisioning is fetched, and the product mesh is
 * auto-configured; a stored session keeps the password form hidden
 * (session persistence). Secrets live in [SecureStore] (Keystore-wrapped);
 * only non-secret settings go to the shared "settings" preferences.
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
    private lateinit var profileAccount: TextView
    private lateinit var profileSub: TextView
    private lateinit var sessionPillSlot: LinearLayout
    private lateinit var pairingPillSlot: LinearLayout
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
        serverInput.setText(p.getString(KEY_SERVER_URL, "http://36.133.114.164:18090"))
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

    private fun setSlotPill(slot: LinearLayout, text: String, kind: Ui.StatusKind) {
        slot.removeAllViews()
        slot.addView(Ui.pill(this, text, kind))
    }

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
        val expired = !loggedIn && SessionRestore.expiredHintPending(this)
        accountStatus.text = when {
            loggedIn -> "已登录，会话已保存 — 下次打开自动恢复"
            expired -> "登录已过期，请重新登录"
            else -> "未登录（填写账号密码后点注册或登录）"
        }
        if (::passwordInput.isInitialized && ::loginButtons.isInitialized) {
            val vis = if (loggedIn) View.GONE else View.VISIBLE
            passwordInput.visibility = vis
            loginButtons.visibility = vis
        }
        // Profile card (mockup): account + network/device sub + pills.
        profileAccount.text = account ?: "未登录"
        profileSub.text = buildString {
            append("网络 ${network ?: "—"}")
            append(" · ")
            append(deviceId?.let { "设备 #$it" } ?: "本机未注册")
            append(" · ")
            append(if (PcConn.isAccountMode(this@AccountActivity)) "账号模式" else "开发者模式")
        }
        setSlotPill(
            sessionPillSlot,
            when {
                loggedIn -> "会话已保存"
                expired -> "登录已过期"
                else -> "未登录"
            },
            when {
                loggedIn -> Ui.StatusKind.OK
                expired -> Ui.StatusKind.WAIT
                else -> Ui.StatusKind.NONE
            }
        )
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
            setSlotPill(pairingPillSlot, "配对: 未注册", Ui.StatusKind.NONE)
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
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (valid) {
                        setSlotPill(pairingPillSlot, "已批准", Ui.StatusKind.OK)
                    } else {
                        setSlotPill(
                            pairingPillSlot,
                            if (reason == "not_approved") "等待电脑批准" else reason,
                            Ui.StatusKind.WAIT
                        )
                    }
                }
            } catch (t: Throwable) {
                AppLog.log("刷新配对状态失败: ${t.message}")
                runOnUiThread {
                    if (!isFinishing) {
                        setSlotPill(pairingPillSlot, "配对: 未知（服务不可达）", Ui.StatusKind.NONE)
                    }
                }
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
        col.addView(Ui.header(this, "我的"))

        // ---- profile card (mockup): avatar + account + pills
        col.addView(Ui.cardBlock(this) {
            val row = LinearLayout(this@AccountActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(Ui.brandTile(this@AccountActivity, 44))
            val info = LinearLayout(this@AccountActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@AccountActivity, 12), 0, 0, 0)
            }
            profileAccount = TextView(this@AccountActivity).apply {
                textSize = 12.5f
                typeface = Ui.font(800)
                setTextColor(Ui.INK)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            info.addView(profileAccount)
            profileSub = TextView(this@AccountActivity).apply {
                textSize = 11f
                setTextColor(Ui.SLATE)
                setPadding(0, Ui.dp(this@AccountActivity, 2), 0, 0)
            }
            info.addView(profileSub)
            row.addView(
                info,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(row)
            val pills = LinearLayout(this@AccountActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, Ui.dp(this@AccountActivity, 10), 0, 0)
            }
            sessionPillSlot = LinearLayout(this@AccountActivity)
            pairingPillSlot = LinearLayout(this@AccountActivity)
            pills.addView(sessionPillSlot)
            pills.addView(
                pairingPillSlot,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = Ui.dp(this@AccountActivity, 8) }
            )
            addView(pills)
        })

        // ---- account form card
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@AccountActivity, "账号"))
            serverInput = Ui.field(this@AccountActivity, "账号服务地址")
            addView(serverInput, Ui.spaced(Ui.matchWrap(), 2, this@AccountActivity))
            addView(
                Ui.subText(
                    this@AccountActivity,
                    "说明：默认是公网服务地址，未组网也能登录（测试期为明文 HTTP，" +
                        "正式版将换成域名 + HTTPS）。"
                ),
                Ui.spaced(Ui.matchWrap(), 4, this@AccountActivity)
            )
            accountInput = Ui.field(this@AccountActivity, "账号")
            addView(accountInput, Ui.spaced(Ui.matchWrap(), 8, this@AccountActivity))
            passwordInput = Ui.field(this@AccountActivity, "密码", password = true)
            addView(passwordInput, Ui.spaced(Ui.matchWrap(), 8, this@AccountActivity))
            accountStatus = Ui.bodyText(this@AccountActivity)
            accountStatus.textSize = 13f
            addView(accountStatus, Ui.spaced(Ui.matchWrap(), 8, this@AccountActivity))
            loginButtons = LinearLayout(this@AccountActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            loginButtons.addView(Ui.ghostButton(this@AccountActivity, "注册").apply {
                setOnClickListener { onRegisterOrLogin(register = true) }
            })
            loginButtons.addView(Ui.primaryButton(this@AccountActivity, "登录").apply {
                setOnClickListener { onRegisterOrLogin(register = false) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Ui.dp(this@AccountActivity, 8) })
            addView(loginButtons, Ui.spaced(Ui.matchWrap(), 10, this@AccountActivity))
        })

        // ---- device & pairing card
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@AccountActivity, "设备与配对"))
            deviceInfo = Ui.bodyText(this@AccountActivity)
            deviceInfo.textSize = 13f
            addView(deviceInfo)
            addView(Ui.ghostButton(this@AccountActivity, "刷新配对状态").apply {
                setOnClickListener { onRefreshPairing() }
            }, Ui.spaced(Ui.matchWrap(), 10, this@AccountActivity))
        })

        // ---- settings card
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@AccountActivity, "设置"))
            addView(
                Ui.subText(
                    this@AccountActivity,
                    "自动备份条件：充电 + WiFi + 空闲 + 直连时（开关在「备份」页）"
                )
            )
            portInput = Ui.field(this@AccountActivity, "电脑备份端口（默认 28741）")
            addView(portInput, Ui.spaced(Ui.matchWrap(), 8, this@AccountActivity))
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
                    typeface = Ui.font(700)
                    setTextColor(Ui.BRAND_700)
                    background = Ui.rounded(this@AccountActivity, Ui.MIST, Ui.R_BTN)
                    setOnClickListener {
                        persistSettings()
                        AppLog.log("设置已保存")
                    }
                },
                Ui.spaced(Ui.matchWrap(), 10, this@AccountActivity)
            )
        })

        // ---- about card (wordmark lockup)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (t: Throwable) {
            "?"
        }
        col.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@AccountActivity, "关于"))
            val row = LinearLayout(this@AccountActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(Ui.brandTile(this@AccountActivity, 42))
            val info = LinearLayout(this@AccountActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@AccountActivity, 12), 0, 0, 0)
            }
            info.addView(TextView(this@AccountActivity).apply {
                text = "牛备"
                textSize = 17f
                typeface = Ui.font(900)
                setTextColor(Ui.INK)
            })
            info.addView(TextView(this@AccountActivity).apply {
                text = "NIUBACKUP · v$version"
                textSize = 10.5f
                setTextColor(Ui.BRAND_600)
                typeface = Ui.font(800)
                letterSpacing = 0.18f
            })
            row.addView(info)
            addView(row)
            addView(
                Ui.subText(
                    this@AccountActivity,
                    "把手机照片和文件自动备份到你自己的电脑——不经云端，记录只存本地。"
                ),
                Ui.spaced(Ui.matchWrap(), 8, this@AccountActivity)
            )
        })

        // ---- logout (standalone, low-saturation red — mockup)
        val logout = Button(this).apply {
            text = "退出登录"
            textSize = 13f
            setAllCaps(false)
            typeface = Ui.font(700)
            setTextColor(Ui.LOGOUT_TEXT)
            background = Ui.rounded(this@AccountActivity, Ui.LOGOUT_BG, Ui.R_BTN)
            stateListAnimator = null
            setPadding(0, Ui.dp(this@AccountActivity, 10), 0, Ui.dp(this@AccountActivity, 10))
            setOnClickListener { onLogout() }
        }
        col.addView(logout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = Ui.dp(this@AccountActivity, 16)
            rightMargin = Ui.dp(this@AccountActivity, 16)
            topMargin = Ui.dp(this@AccountActivity, 11)
            bottomMargin = Ui.dp(this@AccountActivity, 16)
        })
    }
}
