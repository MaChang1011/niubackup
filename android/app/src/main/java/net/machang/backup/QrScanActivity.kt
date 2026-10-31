package net.machang.backup

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 扫码登录电脑 (design v1.4): scan the desktop's one-time login QR —
 * payload {"t","s","srv"} only, no credentials — show the confirm card
 * （电脑名称 / 账号 / 专属网络）, and only on an explicit tap POST the
 * approval. The approval is the login: the service registers that PC
 * under this account and it joins the same per-user network on its own.
 *
 * Scanner route (lightest reliable): ZXing core + the legacy Camera API
 * on a SurfaceView; frames are decoded on one worker thread, trying the
 * raw frame and both 90° rotations of the luma plane (the sensor buffer
 * is landscape while the phone is held portrait).
 */
class QrScanActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var camera: Camera? = null
    private var previewRunning = false
    private var surfaceReady = false
    private var surfaceView: SurfaceView? = null
    private lateinit var statusText: TextView
    private lateinit var scanLayer: FrameLayout
    private lateinit var confirmLayer: LinearLayout
    private val decodePool = Executors.newSingleThreadExecutor()
    private val decoding = AtomicBoolean(false)
    private val handled = AtomicBoolean(false)
    private val reader = MultiFormatReader().apply {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.TRY_HARDER] = true
        setHints(hints)
    }

    private var ticketId: String = ""
    private var ticketSecret: String = ""
    private var ticketServer: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (!handled.get()) ensureCamera()
        } else {
            statusText.text = "没有相机权限，无法扫码。请在系统设置中允许牛备使用相机。"
        }
    }

    // ------------------------------------------------------------ camera

    override fun onResume() {
        super.onResume()
        if (!handled.get()) ensureCamera()
    }

    override fun onPause() {
        stopCamera()
        super.onPause()
    }

    /**
     * Surface-driven camera start. The preview may only be bound once the
     * SurfaceView surface actually exists: onResume regularly wins the race
     * against surfaceCreated, and binding then hands the camera a NULL
     * surface ("app passed NULL surface" in logcat) - the preview stays
     * black forever after, because the surfaceCreated retry used to bail
     * out on camera != null. So: open lazily, bind only when the surface
     * is valid, and re-bind on surfaceCreated if the camera is already
     * open. Callable from onResume, surfaceCreated and the retry tap.
     */
    private fun ensureCamera() {
        val sv = surfaceView ?: return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "需要相机权限才能扫码"
            return
        }
        if (!surfaceReady || !sv.holder.surface.isValid) {
            // Not yet - surfaceCreated calls us again when the surface lands.
            return
        }
        try {
            var cam = camera
            if (cam == null) {
                cam = Camera.open()
                camera = cam
                val params = cam.parameters
                params.previewFormat = ImageFormat.NV21
                // Prefer a ~720p preview: big enough for QR decode, cheap to copy.
                val size = params.supportedPreviewSizes
                    ?.filter { it.width in 640..1600 }
                    ?.minByOrNull { kotlin.math.abs(it.width - 1280) }
                if (size != null) params.setPreviewSize(size.width, size.height)
                if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in
                    (params.supportedFocusModes ?: emptyList())
                ) {
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                }
                cam.parameters = params
                cam.setDisplayOrientation(90)
                cam.setPreviewCallback { data, c -> onPreviewFrame(data, c) }
            }
            if (!previewRunning) {
                cam.setPreviewDisplay(sv.holder)
                cam.startPreview()
                previewRunning = true
                statusText.text = ""
            }
        } catch (t: Throwable) {
            stopCamera()
            statusText.text = "相机打开失败：${t.message}。轻点屏幕重试。"
        }
    }

    private fun stopCamera() {
        previewRunning = false
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
        } catch (t: Throwable) {
            // already stopped
        }
        camera?.release()
        camera = null
    }

    private fun onPreviewFrame(data: ByteArray?, cam: Camera) {
        if (data == null || handled.get() || !decoding.compareAndSet(false, true)) return
        val w = cam.parameters.previewSize.width
        val h = cam.parameters.previewSize.height
        val frame = data.copyOf()
        decodePool.execute {
            try {
                val text = decodeAnyOrientation(frame, w, h)
                if (text != null) main.post { onQrText(text) }
            } finally {
                decoding.set(false)
            }
        }
    }

    private fun decodeAnyOrientation(data: ByteArray, w: Int, h: Int): String? {
        decodeOnce(data, w, h)?.let { return it }
        // Sensor buffers are landscape; the QR the user sees is rotated.
        val cw = rotateY(data, w, h, clockwise = true)
        decodeOnce(cw, h, w)?.let { return it }
        val ccw = rotateY(data, w, h, clockwise = false)
        decodeOnce(ccw, h, w)?.let { return it }
        return null
    }

    private fun decodeOnce(data: ByteArray, w: Int, h: Int): String? {
        return try {
            val src = PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)
            reader.decode(BinaryBitmap(HybridBinarizer(src))).text
        } catch (t: Throwable) {
            null
        } finally {
            reader.reset()
        }
    }

    /** Rotate just the luma plane (first w*h bytes of NV21) by 90°. */
    private fun rotateY(data: ByteArray, w: Int, h: Int, clockwise: Boolean): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = data[y * w + x]
                if (clockwise) out[x * h + (h - 1 - y)] = v
                else out[(w - 1 - x) * h + y] = v
            }
        }
        return out
    }

    // -------------------------------------------------------------- flow

    private fun onQrText(text: String) {
        val obj = try {
            JSONObject(text)
        } catch (t: Throwable) {
            null
        }
        val t = obj?.optString("t", "") ?: ""
        val s = obj?.optString("s", "") ?: ""
        val srv = obj?.optString("srv", "") ?: ""
        if (t.isEmpty() || s.isEmpty() || srv.isEmpty()) {
            Toast.makeText(this, "这不是牛备的登录二维码", Toast.LENGTH_SHORT).show()
            return // keep scanning
        }
        if (!handled.compareAndSet(false, true)) return
        ticketId = t
        ticketSecret = s
        ticketServer = srv.trimEnd('/')
        stopCamera()
        statusText.text = "已识别，正在读取这台电脑的信息…"
        thread(name = "qr-ticket-info") {
            val info = try {
                AccountClient(ticketServer).pcLoginTicket(ticketId, ticketSecret)
            } catch (e: HttpStatusException) {
                main.post {
                    if (isFinishing) return@post
                    if (e.code == 404 || e.code == 410) {
                        showMessage("二维码已过期", "请回到电脑端，登录页会自动刷新出新的二维码。")
                    } else {
                        showMessage("读取失败", "账号服务返回 HTTP ${e.code}，请稍后重试。")
                    }
                }
                return@thread
            } catch (t: Throwable) {
                main.post {
                    if (!isFinishing) {
                        showMessage("连接失败", "无法连接账号服务：${t.message}")
                    }
                }
                return@thread
            }
            main.post {
                if (isFinishing) return@post
                when {
                    info.status == "expired" || info.status == "consumed" ->
                        showMessage("二维码已过期", "请回到电脑端，登录页会自动刷新出新的二维码。")
                    info.status == "approved" ->
                        showMessage("已登录", "这台电脑已经登录过了，无需重复确认。")
                    else -> showConfirm(info)
                }
            }
        }
    }

    private fun showConfirm(info: AccountClient.PcLoginTicket) {
        scanLayer.visibility = View.GONE
        confirmLayer.visibility = View.VISIBLE
        confirmLayer.removeAllViews()
        val account = SecureStore.get(this, SecureStore.KEY_ACCOUNT) ?: info.account ?: "—"
        val network = prefsNetwork() ?: info.networkName ?: "—"
        confirmLayer.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@QrScanActivity, "确认登录这台电脑"))
            addView(kvText("电脑名称", info.deviceName ?: "未命名电脑"))
            if (!info.createdAt.isNullOrEmpty()) {
                addView(kvText("申请时间", fmtCreatedAt(info.createdAt)))
            }
            addView(kvText("登录账号", account))
            addView(kvText("专属网络", network))
            addView(
                Ui.subText(
                    this@QrScanActivity,
                    "确认后，这台电脑将以你的账号登录，并自动加入你的专属网络，" +
                        "现在即可发起备份，无需批准。二维码为一次性票据，确认后立即失效。"
                ),
                Ui.spaced(Ui.matchWrap(), 8, this@QrScanActivity)
            )
        })
        confirmLayer.addView(Ui.primaryButton(this, "确认登录这台电脑").apply {
            setOnClickListener { onApprove(this) }
        }, Ui.spaced(Ui.matchWrap(), 4, this))
        confirmLayer.addView(Ui.ghostButton(this, "不是我操作的").apply {
            // No cancel call exists in the contract — walking away is the
            // safe action: the ticket expires on its own within 2 minutes
            // and can never be used after that.
            setOnClickListener { finish() }
        }, Ui.spaced(Ui.matchWrap(), 8, this))
    }

    private fun onApprove(btn: TextView) {
        btn.isEnabled = false
        (btn as? android.widget.Button)?.text = "正在确认…"
        thread(name = "qr-approve") {
            val access = freshAccessToken()
            if (access == null) {
                main.post {
                    if (!isFinishing) {
                        showMessage("登录已过期", "手机上的账号会话已失效，请先到「我的」页重新登录，再扫码。")
                    }
                }
                return@thread
            }
            try {
                AccountClient(ticketServer).approvePcLogin(ticketId, ticketSecret, access)
                main.post { if (!isFinishing) showSuccess() }
            } catch (e: HttpStatusException) {
                main.post {
                    if (isFinishing) return@post
                    if (e.code == 404 || e.code == 410) {
                        showMessage("二维码已过期", "请回到电脑端，登录页会自动刷新出新的二维码。")
                    } else {
                        showMessage("确认失败", "账号服务返回 HTTP ${e.code}（${e.serverError}），请稍后重试。")
                    }
                }
            } catch (t: Throwable) {
                main.post {
                    if (!isFinishing) {
                        showMessage("确认失败", "无法连接账号服务：${t.message}")
                    }
                }
            }
        }
    }

    /** The stored session's access token, silently refreshed if lapsed. */
    private fun freshAccessToken(): String? {
        val access = SecureStore.get(this, SecureStore.KEY_ACCESS_TOKEN)
        val exp = SecureStore.get(this, SecureStore.KEY_ACCESS_EXPIRES_AT)?.toLongOrNull() ?: 0L
        if (access != null && System.currentTimeMillis() < exp - 60_000) return access
        val refresh = SecureStore.get(this, SecureStore.KEY_REFRESH_TOKEN) ?: return null
        return try {
            val s = AccountClient(PcConn.serverUrl(this)).refresh(refresh)
            SessionRestore.storeSession(this, s)
            s.accessToken
        } catch (t: Throwable) {
            null
        }
    }

    private fun showSuccess() {
        scanLayer.visibility = View.GONE
        confirmLayer.visibility = View.VISIBLE
        confirmLayer.removeAllViews()
        confirmLayer.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@QrScanActivity, "✓ 已确认"))
            addView(
                Ui.bodyText(
                    this@QrScanActivity,
                    "这台电脑已以你的账号登录并自动入网。回到电脑端即可开始使用，手机这边可以放下了。"
                )
            )
        })
        confirmLayer.addView(Ui.primaryButton(this, "完成").apply {
            setOnClickListener { finish() }
        }, Ui.spaced(Ui.matchWrap(), 4, this))
    }

    private fun showMessage(title: String, body: String) {
        scanLayer.visibility = View.GONE
        confirmLayer.visibility = View.VISIBLE
        confirmLayer.removeAllViews()
        confirmLayer.addView(Ui.cardBlock(this) {
            addView(Ui.sectionTitle(this@QrScanActivity, title))
            addView(Ui.bodyText(this@QrScanActivity, body))
        })
        confirmLayer.addView(Ui.primaryButton(this, "返回").apply {
            setOnClickListener { finish() }
        }, Ui.spaced(Ui.matchWrap(), 4, this))
    }

    private fun kvText(key: String, value: String): View =
        Ui.kvRow(this, key, Ui.kvValue(this, value))

    private fun prefsNetwork(): String? =
        getSharedPreferences(PcConn.PREFS, MODE_PRIVATE).getString("network_name", null)

    private fun fmtCreatedAt(raw: String): String {
        // ISO-8601 → HH:mm; anything else is shown as delivered.
        return try {
            val ms = java.time.Instant.parse(raw).toEpochMilli()
            SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
        } catch (t: Throwable) {
            raw
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.SCREEN_BG)
        scanLayer = FrameLayout(this)
        scanLayer.setBackgroundColor(android.graphics.Color.BLACK)
        surfaceView = SurfaceView(this)
        scanLayer.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // Viewfinder: centered bordered box (the preview runs full-screen
        // behind it; decoding uses the whole frame, the box is a guide).
        val boxSize = Ui.dp(this, 248)
        val finder = View(this).apply {
            background = Ui.rounded(
                this@QrScanActivity, android.graphics.Color.TRANSPARENT,
                Ui.R_BIG, android.graphics.Color.WHITE, 2
            )
        }
        scanLayer.addView(
            finder,
            FrameLayout.LayoutParams(boxSize, boxSize, Gravity.CENTER).apply {
                topMargin = -Ui.dp(this@QrScanActivity, 30)
            }
        )
        val hint = TextView(this).apply {
            text = "将电脑端二维码放入框内"
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
        }
        scanLayer.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply { topMargin = Ui.dp(this@QrScanActivity, 140) }
        )
        val title = TextView(this).apply {
            text = "扫码登录电脑"
            textSize = 16f
            typeface = Ui.font(800)
            setTextColor(android.graphics.Color.WHITE)
            setPadding(Ui.dp(this@QrScanActivity, 16), Ui.dp(this@QrScanActivity, 14), 0, 0)
        }
        scanLayer.addView(title)
        statusText = TextView(this).apply {
            text = "正在打开相机…"
            textSize = 12.5f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(
                Ui.dp(this@QrScanActivity, 24), 0,
                Ui.dp(this@QrScanActivity, 24), Ui.dp(this@QrScanActivity, 26)
            )
        }
        scanLayer.addView(
            statusText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        scanLayer.setOnClickListener {
            if (!handled.get() && camera == null) ensureCamera()
        }
        root.addView(
            scanLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        confirmLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(
                Ui.dp(this@QrScanActivity, 16), Ui.dp(this@QrScanActivity, 24),
                Ui.dp(this@QrScanActivity, 16), 0
            )
        }
        root.addView(
            confirmLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        surfaceView?.holder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                if (!handled.get()) ensureCamera()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                stopCamera()
            }
        })
    }

    companion object {
        private const val REQ_CAMERA = 2001
    }
}
