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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/**
 * Single-screen UI (built in code, no XML layouts):
 *  - mesh-secret settings field (persisted to SharedPreferences, never in code)
 *  - 启动/停止组网 toggle button (VPN permission flow -> BackupVpnService)
 *  - 立即备份 DCIM button (one manual BackupClient session on a worker thread)
 *  - scrolling status/log view fed by [AppLog]
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
    }

    private lateinit var secretInput: EditText
    private lateinit var userIdInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var vpnButton: Button
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    @Volatile
    private var meshRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        AppLog.listener = { _ ->
            runOnUiThread { renderLog() }
        }
        renderLog()

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        secretInput.setText(prefs.getString(KEY_SECRET, ""))
        userIdInput.setText(prefs.getString(KEY_USER_ID, ""))
        deviceIdInput.setText(prefs.getString(KEY_DEVICE_ID, ""))
        tokenInput.setText(prefs.getString(KEY_DEVICE_TOKEN, ""))

        vpnButton.setOnClickListener {
            if (meshRunning) {
                startService(
                    Intent(this, BackupVpnService::class.java).setAction(BackupVpnService.ACTION_STOP)
                )
                meshRunning = false
                vpnButton.text = "启动组网"
                AppLog.log("已请求停止组网")
            } else {
                val secret = secretInput.text.toString().trim()
                if (secret.isEmpty()) {
                    AppLog.log("请先填写组网密钥")
                    return@setOnClickListener
                }
                prefs.edit().putString(KEY_SECRET, secret).apply()
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

        findViewById<Button>(BACKUP_BTN_ID).setOnClickListener {
            val uid = userIdInput.text.toString().trim()
            val did = deviceIdInput.text.toString().trim()
            val tok = tokenInput.text.toString().trim()
            prefs.edit()
                .putString(KEY_USER_ID, uid)
                .putString(KEY_DEVICE_ID, did)
                .putString(KEY_DEVICE_TOKEN, tok)
                .apply()
            thread(name = "backup") {
                val dcim = File(Environment.getExternalStorageDirectory(), "DCIM")
                val logger = BackupClient.Logger { msg -> AppLog.log(msg) }
                val client = if (uid.isNotEmpty() && did.isNotEmpty() && tok.isNotEmpty()) {
                    BackupClient(logger, filesDir, uid, did, tok,
                        java.util.function.IntSupplier {
                            EasyTierBridge.peerRouteCost(BackupClient.DEFAULT_HOST)
                        })
                } else {
                    BackupClient(logger, filesDir)
                }
                client.runBackup(dcim)
            }
            AppLog.log("开始备份 DCIM…")
        }

        requestStoragePermissions()
        AppLog.log("应用就绪。先填组网密钥并启动组网，再点「立即备份 DCIM」。")
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

    private fun startMesh() {
        val secret = secretInput.text.toString().trim()
        val intent = Intent(this, BackupVpnService::class.java)
            .setAction(BackupVpnService.ACTION_START)
            .putExtra(BackupVpnService.EXTRA_SECRET, secret)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        meshRunning = true
        vpnButton.text = "停止组网"
        AppLog.log("组网启动中…")
    }

    private fun renderLog() {
        logView.text = AppLog.snapshot()
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = "组网备份 (machang-net)"
            textSize = 18f
        }
        root.addView(title)

        secretInput = EditText(this).apply {
            hint = "组网密钥（仅保存在本机设置里）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(secretInput)

        fun settingsField(hintText: String, password: Boolean = false): EditText {
            return EditText(this).apply {
                hint = hintText
                inputType = InputType.TYPE_CLASS_TEXT or
                    (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_NORMAL)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }
        userIdInput = settingsField("账号 user_id（协议 v2，三项填齐才启用）")
        root.addView(userIdInput)
        deviceIdInput = settingsField("设备 device_id（协议 v2）")
        root.addView(deviceIdInput)
        tokenInput = settingsField("设备令牌 device token（协议 v2）", password = true)
        root.addView(tokenInput)

        vpnButton = Button(this).apply { text = "启动组网" }
        root.addView(vpnButton)

        val backupBtn = Button(this).apply {
            id = BACKUP_BTN_ID
            text = "立即备份 DCIM"
        }
        root.addView(backupBtn)

        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addView(logView)
        }
        root.addView(logScroll)

        setContentView(root)
    }
}

private const val BACKUP_BTN_ID = 0x6d32 // "m2"
