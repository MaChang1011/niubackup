package net.machang.backup

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * 照片预览页 (approved mockup v1.1): full-screen photo viewer, opened from
 * an image tile in 文件库 or an image row in 记录.
 *
 * - Local photos decode from the DCIM file; PC-only photos are fetched
 *   full-size through the tunnel with the existing [FsClient] download
 *   (offset resume) into a preview cache, then decoded — no new protocol.
 * - Translucent top bar: filename + date + status badge (delete-safety
 *   reads at a glance). Tap the photo to hide all chrome.
 * - Swipe / arrows move within the caller's list; pager dots below.
 * - Pinch zoom (matrix) + double-tap zoom; the 双指放大 hint chip shows on
 *   first entry only.
 * - Bottom actions: 下载回来 (existing §6.3 publish flow), 释放空间 (the
 *   EXISTING §6.4 gate — verified receipt + live integrity cross-check +
 *   the same confirmation wording + MediaStore consent delete), 记录
 *   (jumps to the records tab).
 */
class PhotoPreviewActivity : Activity() {

    companion object {
        const val EXTRA_PATHS = "pv_paths"
        const val EXTRA_INDEX = "pv_index"
        const val EXTRA_SIZES = "pv_sizes"
        const val EXTRA_DATES = "pv_dates"
        const val EXTRA_LOCALS = "pv_locals"
        const val EXTRA_STATUSES = "pv_statuses"
        const val EXTRA_SHAS = "pv_shas"
        const val EXTRA_KIND = "pv_kind"
        const val KIND_LIBRARY = "library"
        const val KIND_RECORDS = "records"

        const val ST_BACKED = 0
        const val ST_PC_ONLY = 1
        const val ST_NOT_BACKED = 2
        const val ST_BACKING = 3
        const val ST_SUSPECT = 4

        private const val REQ_DELETE = 4711
        private const val PREF_HINT = "preview_hint_seen"
    }

    private lateinit var paths: List<String>
    private var sizes = LongArray(0)
    private var dates = LongArray(0)
    private var locals = BooleanArray(0)
    private var statuses = IntArray(0)
    private var shas: List<String> = emptyList()
    private var index = 0

    private lateinit var imageView: ZoomImageView
    private lateinit var loadingView: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var badgeSlot: LinearLayout
    private lateinit var actionsBar: LinearLayout
    private lateinit var pagerRow: LinearLayout
    private lateinit var hintChip: TextView
    private var chromeVisible = true
    private var loadGen = 0

    private val main = Handler(Looper.getMainLooper())

    /**
     * Decoded full-size (screen-sampled) bitmaps by path, ~32 MiB: paging
     * back to an already-viewed photo must not decode (or, for PC copies,
     * download) it again.
     */
    private val fullCache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** Decode target for the "full" image: the screen's long edge. */
    private fun screenTarget(): Int {
        val dm = resources.displayMetrics
        return maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
    }

    /** The shared 320px thumbnail file (same key rule as RecordsActivity). */
    private fun thumbFileFor(path: String, sha: String?): File {
        val dir = File(filesDir, "thumb-cache").apply { mkdirs() }
        val key = (if (!sha.isNullOrEmpty()) sha else path.hashCode().toString())
            .replace("/", "_")
        return File(dir, "$key.jpg")
    }

    private fun dcimDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: emptyList()
        if (paths.isEmpty()) {
            finish()
            return
        }
        sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: LongArray(paths.size)
        dates = intent.getLongArrayExtra(EXTRA_DATES) ?: LongArray(paths.size)
        locals = intent.getBooleanArrayExtra(EXTRA_LOCALS) ?: BooleanArray(paths.size)
        statuses = intent.getIntArrayExtra(EXTRA_STATUSES) ?: IntArray(paths.size)
        shas = intent.getStringArrayListExtra(EXTRA_SHAS) ?: List(paths.size) { "" }
        index = (intent.getIntExtra(EXTRA_INDEX, 0)).coerceIn(0, paths.size - 1)

        Ui.statusBar(this, Ui.PHOTO_BG)
        // Same edge-to-edge opt-out as the tab scaffold (Ui.page): the
        // insets listener in buildUi then owns the top bar's clearance.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        buildUi()
        showCurrent()
        maybeShowHint()
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Ui.PHOTO_BG) }
        setContentView(root)

        imageView = ZoomImageView(this).apply {
            onSingleTap = { toggleChrome() }
            onSwipe = { dir -> move(dir) }
            onZoomed = { hideHint() }
        }
        root.addView(imageView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        loadingView = TextView(this).apply {
            text = "正在加载原图…"
            textSize = 13f
            setTextColor(Ui.VIEW_SUB)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        root.addView(loadingView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Translucent top bar (gradient scrim over the photo, mockup).
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(0xA8, 0x05, 0x11, 0x16),
                    Color.argb(0x47, 0x05, 0x11, 0x16),
                    Color.TRANSPARENT
                )
            )
            // Top offset matches the player page (14 sides, 10 top +
            // status inset, 32dp circle back button) so both viewer
            // screens place their close control identically; the taller
            // bottom padding is the scrim's fade tail below the controls.
            setPadding(
                Ui.dp(this@PhotoPreviewActivity, 14), Ui.dp(this@PhotoPreviewActivity, 10),
                Ui.dp(this@PhotoPreviewActivity, 14), Ui.dp(this@PhotoPreviewActivity, 18)
            )
        }
        topBar.addView(circleGlyph("‹") { finish() })
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@PhotoPreviewActivity, 10), 0, 0, 0)
        }
        titleView = TextView(this).apply {
            textSize = 13f
            typeface = Ui.font(800)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        titleCol.addView(titleView)
        subView = TextView(this).apply {
            textSize = 11f
            setTextColor(Ui.VIEW_SUB)
        }
        titleCol.addView(subView)
        topBar.addView(titleCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        badgeSlot = LinearLayout(this)
        topBar.addView(badgeSlot)
        root.addView(topBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))

        // Swipe arrows + pager dots (one row above the action bar).
        pagerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Ui.dp(this@PhotoPreviewActivity, 14), 0,
                Ui.dp(this@PhotoPreviewActivity, 14), 0
            )
        }
        pagerRow.addView(circleGlyph("‹") { move(-1) })
        val dotsHolder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            tag = "dots"
        }
        pagerRow.addView(dotsHolder, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        pagerRow.addView(circleGlyph("›") { move(1) })
        root.addView(pagerRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply { bottomMargin = Ui.dp(this@PhotoPreviewActivity, 86) })

        // Pinch hint chip (first entry only).
        hintChip = TextView(this).apply {
            text = "双指放大 · 捏合或双击查看细节"
            textSize = 11f
            typeface = Ui.font(700)
            setTextColor(Ui.VIEW_TEXT)
            background = Ui.pillDrawable(
                this@PhotoPreviewActivity, Color.argb(0x9E, 0x06, 0x18, 0x1F)
            )
            setPadding(
                Ui.dp(this@PhotoPreviewActivity, 11), Ui.dp(this@PhotoPreviewActivity, 6),
                Ui.dp(this@PhotoPreviewActivity, 11), Ui.dp(this@PhotoPreviewActivity, 6)
            )
            visibility = View.GONE
        }
        root.addView(hintChip, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = Ui.dp(this@PhotoPreviewActivity, 140) })

        // Bottom action bar: 3 equal columns （下载回来 / 释放空间 / 记录）.
        actionsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.PHOTO_BG)
            setPadding(0, Ui.dp(this@PhotoPreviewActivity, 10), 0, Ui.dp(this@PhotoPreviewActivity, 16))
        }
        actionsBar.addView(actionCell("↓", "下载回来", Ui.VIEW_TEXT) { onDownloadBack() })
        actionsBar.addView(actionCell("✕", "释放空间", Color.rgb(0xF5, 0xA5, 0x4B)) { onFreeUpSpace() })
        actionsBar.addView(actionCell("☰", "记录", Ui.VIEW_TEXT) {
            // Into a tab page: same instant, animation-free hop as the
            // bottom nav (see Ui.navBar).
            startActivity(
                Intent(this, RecordsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        })
        root.addView(actionsBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))

        // Edge-to-edge placement (inset-aware, chosen over transient
        // immersive flags so the chrome stays put): the photo and the
        // top scrim still bleed behind the system bars, but the top
        // bar's controls sit below the status bar and the action bar
        // clears the gesture bar. API 30+; below that the framework
        // fits the window and the base paddings apply.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                topBar.setPadding(
                    Ui.dp(this, 14), Ui.dp(this, 10) + bars.top,
                    Ui.dp(this, 14), Ui.dp(this, 18)
                )
                actionsBar.setPadding(
                    0, Ui.dp(this, 10), 0, Ui.dp(this, 16) + bars.bottom
                )
                insets
            }
        }
    }

    private fun circleGlyph(glyph: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = Ui.circleDrawable(
                this@PhotoPreviewActivity, Color.argb(0x6B, 0x06, 0x18, 0x1F)
            )
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                Ui.dp(this@PhotoPreviewActivity, 32), Ui.dp(this@PhotoPreviewActivity, 32)
            )
            setOnClickListener { onClick() }
        }

    private fun actionCell(
        glyph: String, label: String, color: Int, onClick: () -> Unit
    ): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onClick() }
        }
        cell.addView(TextView(this).apply {
            text = glyph
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(color)
        })
        cell.addView(TextView(this).apply {
            text = label
            textSize = 11f
            typeface = Ui.font(600)
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(0xC6, 0xDE, 0xE5))
            setPadding(0, Ui.dp(this@PhotoPreviewActivity, 3), 0, 0)
        })
        cell.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        return cell
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        val vis = if (chromeVisible) View.VISIBLE else View.GONE
        topBar.visibility = vis
        actionsBar.visibility = vis
        pagerRow.visibility = vis
    }

    // --------------------------------------------------------------- paging

    private fun move(dir: Int) {
        val next = index + dir
        if (next !in paths.indices) return
        index = next
        showCurrent()
    }

    private fun showCurrent() {
        val path = paths[index]
        titleView.text = path.substringAfterLast('/')
        val dateText = if (dates[index] > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(dates[index]))
        } else {
            ""
        }
        subView.text = listOf(
            dateText, fmtSize(sizes[index]), "${index + 1} / ${paths.size}"
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        badgeSlot.removeAllViews()
        val (label, kind) = when (statuses[index]) {
            ST_BACKED -> "已备份" to Ui.StatusKind.OK
            ST_PC_ONLY -> "仅PC有" to Ui.StatusKind.PC
            ST_BACKING -> "备份中" to Ui.StatusKind.RUN
            ST_SUSPECT -> "校验存疑" to Ui.StatusKind.WAIT
            else -> "未备份" to Ui.StatusKind.NONE
        }
        badgeSlot.addView(Ui.badge(this, label, kind))
        rebuildDots()
        loadImage(path)
    }

    private fun rebuildDots() {
        val holder = pagerRow.findViewWithTag<LinearLayout>("dots") ?: return
        holder.removeAllViews()
        if (paths.size > 20) return // top bar already shows n / N
        for (i in paths.indices) {
            val active = i == index
            val dot = View(this).apply {
                background = if (active) {
                    Ui.pillDrawable(this@PhotoPreviewActivity, Color.WHITE)
                } else {
                    Ui.circleDrawable(
                        this@PhotoPreviewActivity, Color.argb(0x6B, 0xFF, 0xFF, 0xFF)
                    )
                }
            }
            holder.addView(dot, LinearLayout.LayoutParams(
                Ui.dp(this, if (active) 16 else 6), Ui.dp(this, 6)
            ).apply {
                leftMargin = Ui.dp(this@PhotoPreviewActivity, 3)
                rightMargin = Ui.dp(this@PhotoPreviewActivity, 3)
            })
        }
    }

    // -------------------------------------------------------------- loading

    private fun previewCacheFile(path: String): File {
        val dir = File(cacheDir, "preview").apply { mkdirs() }
        val safe = path.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, safe)
    }

    private fun loadImage(path: String) {
        val gen = ++loadGen
        imageView.resetZoom()
        // Already decoded this session: show it at once, no re-decode,
        // no re-download, no placeholder flicker.
        fullCache.get(path)?.let { bmp ->
            loadingView.visibility = View.GONE
            imageView.setImageBitmap(bmp)
            return
        }
        imageView.setImageBitmap(null)
        val localFile = File(dcimDir(), path)
        val isLocal = locals[index] && localFile.isFile
        val target = screenTarget()
        if (isLocal) {
            loadingView.visibility = View.GONE
            thread(name = "preview-local") {
                // Paint a 320px stand-in first so the page never sits
                // blank while the full decode runs.
                val thumb = decodeSampled(localFile, 320)
                if (thumb != null) {
                    main.post {
                        if (gen == loadGen && fullCache.get(path) == null) {
                            imageView.setImageBitmap(thumb)
                        }
                    }
                }
                val bmp = decodeSampled(localFile, target)
                if (bmp != null) fullCache.put(path, bmp)
                main.post {
                    if (gen == loadGen && bmp != null) imageView.setImageBitmap(bmp)
                }
            }
            return
        }
        // PC copy already downloaded earlier: decode from the preview cache.
        val cached = previewCacheFile(path)
        if (cached.isFile && (sizes[index] <= 0 || cached.length() == sizes[index])) {
            loadingView.visibility = View.GONE
            thread(name = "preview-cache") {
                val bmp = decodeSampled(cached, target)
                if (bmp != null) fullCache.put(path, bmp)
                main.post {
                    if (gen == loadGen && bmp != null) imageView.setImageBitmap(bmp)
                }
            }
            return
        }
        // PC copy: the PC-generated 320px thumbnail (already fetched by the
        // 记录 page into thumb-cache, or one THUMB request away) paints
        // immediately; the full-size fetch continues behind it.
        val thumbFile = thumbFileFor(path, shas.getOrNull(index))
        val thumbOnDisk = thumbFile.isFile
        if (thumbOnDisk) {
            loadingView.visibility = View.GONE
            thread(name = "preview-thumb") {
                val tb = try {
                    BitmapFactory.decodeFile(thumbFile.absolutePath)
                } catch (t: Throwable) {
                    null
                }
                if (tb != null) {
                    main.post {
                        if (gen == loadGen && fullCache.get(path) == null) {
                            imageView.setImageBitmap(tb)
                        }
                    }
                }
            }
        } else {
            loadingView.text = "正在从电脑读取原图…"
            loadingView.visibility = View.VISIBLE
        }
        thread(name = "preview-fetch") {
            try {
                val fs = PcConn.createFsClient(this) ?: throw IllegalStateException("无设备身份")
                if (!thumbOnDisk) {
                    // Fetch the tiny thumbnail first — one round trip and
                    // the page has something to show.
                    try {
                        val bytes = fs.thumb(path)
                        if (bytes != null) {
                            try {
                                thumbFile.writeBytes(bytes)
                            } catch (t: Throwable) {
                                // Cache write is best effort.
                            }
                            val tb = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (tb != null) {
                                main.post {
                                    if (gen == loadGen && fullCache.get(path) == null) {
                                        loadingView.visibility = View.GONE
                                        imageView.setImageBitmap(tb)
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        // Placeholder is best effort; the fetch goes on.
                    }
                }
                val size = if (sizes[index] > 0) sizes[index] else {
                    fs.connect()
                    fs.list().firstOrNull { it.path == path }?.size ?: -1
                }
                val tmp = fs.download(path, size, File(cacheDir, "preview-dl"), null)
                fs.close()
                tmp.copyTo(cached, overwrite = true)
                val bmp = decodeSampled(cached, target)
                if (bmp != null) fullCache.put(path, bmp)
                main.post {
                    if (gen != loadGen) return@post
                    loadingView.visibility = View.GONE
                    if (bmp != null) {
                        imageView.setImageBitmap(bmp)
                    } else {
                        Toast.makeText(this, "原图读取失败", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (t: Throwable) {
                main.post {
                    if (gen != loadGen) return@post
                    loadingView.visibility = View.GONE
                    Toast.makeText(this, "原图读取失败: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
        if (!file.isFile) return null
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0) return null
            var sample = 1
            while (opts.outWidth / sample > targetPx || opts.outHeight / sample > targetPx) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------------------------------------------------------- hint

    private fun maybeShowHint() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean(PREF_HINT, false)) return
        hintChip.visibility = View.VISIBLE
        main.postDelayed({ hideHint() }, 5000)
    }

    private fun hideHint() {
        if (hintChip.visibility != View.VISIBLE) return
        hintChip.visibility = View.GONE
        getSharedPreferences("settings", MODE_PRIVATE)
            .edit().putBoolean(PREF_HINT, true).apply()
    }

    // -------------------------------------------------------------- actions

    private fun onDownloadBack() {
        val path = paths[index]
        val localFile = File(dcimDir(), path)
        if (locals[index] && localFile.isFile) {
            Toast.makeText(this, "原图已在本机", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "开始下载 ${path.substringAfterLast('/')}", Toast.LENGTH_SHORT).show()
        thread(name = "preview-download") {
            try {
                val cached = previewCacheFile(path)
                val src = if (cached.isFile &&
                    (sizes[index] <= 0 || cached.length() == sizes[index])
                ) {
                    cached
                } else {
                    val fs = PcConn.createFsClient(this)
                        ?: throw IllegalStateException("无设备身份")
                    val tmp = fs.download(path, sizes[index], File(cacheDir, "restore-tmp"), null)
                    fs.close()
                    tmp
                }
                val uri = MediaStoreHelper.publishToRestoreFolder(
                    this, src, path.substringAfterLast('/')
                )
                main.post {
                    Toast.makeText(
                        this,
                        if (uri != null) "已保存到 DCIM/NiuBackupRestore" else "保存失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (t: Throwable) {
                main.post {
                    Toast.makeText(this, "下载失败: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * §6.4 free-up-space for this one photo — same gates and same
     * confirmation wording as the 文件库 flow: verified receipt first,
     * live integrity cross-check when the PC is reachable, explicit
     * confirmation, MediaStore consent delete.
     */
    private fun onFreeUpSpace() {
        val path = paths[index]
        val file = File(dcimDir(), path)
        if (!file.isFile) {
            Toast.makeText(this, "本机没有这张照片，无需释放", Toast.LENGTH_SHORT).show()
            return
        }
        if (statuses[index] != ST_BACKED && statuses[index] != ST_SUSPECT) {
            Toast.makeText(this, "该文件尚未确认备份，不能释放", Toast.LENGTH_SHORT).show()
            return
        }
        val sha = shas.getOrNull(index)?.ifEmpty { null }
        thread(name = "preview-freeup") {
            val receipts = ReceiptStore.loadMerged(filesDir)
            val rf = receipts[path]
            val receiptOk = rf != null && (sha == null || rf.sha256 == sha)
            if (!receiptOk) {
                main.post {
                    Toast.makeText(this, "没有通过校验的可删除文件", Toast.LENGTH_LONG).show()
                }
                return@thread
            }
            var checkedLive = false
            var liveOk = true
            try {
                val fs = PcConn.createFsClient(this)
                if (fs != null) {
                    fs.connect()
                    val live = fs.list()
                    fs.close()
                    val r = live.firstOrNull { it.path == path }
                    liveOk = r != null && "ok" == r.integrity &&
                        (sha == null || r.sha256 == sha)
                    checkedLive = true
                }
            } catch (t: Throwable) {
                checkedLive = false
            }
            if (checkedLive && !liveOk) {
                main.post {
                    Toast.makeText(this, "没有通过校验的可删除文件", Toast.LENGTH_LONG).show()
                }
                return@thread
            }
            val size = if (sizes[index] > 0) sizes[index] else file.length()
            main.post { confirmFreeUpSpace(file, size, checkedLive) }
        }
    }

    private fun confirmFreeUpSpace(file: File, totalBytes: Long, checkedLive: Boolean) {
        val sb = StringBuilder()
        sb.append("将删除本机上的 1 个文件（共 ${fmtSize(totalBytes)}），")
        sb.append("它已完整备份到你的电脑（${PcConn.pcHost(this)} 的备份目录）。")
        sb.append("删除后可随时从电脑下载回手机。")
        if (!checkedLive) {
            sb.append("\n\n注意：本次未连接电脑做实时核对，依据的是手机保存的备份回执。")
        }
        AlertDialog.Builder(this)
            .setTitle("释放空间")
            .setMessage(sb.toString())
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> executeDelete(file) }
            .show()
    }

    private fun executeDelete(file: File) {
        val pending = MediaStoreHelper.createDeleteRequest(this, listOf(file))
        if (pending != null) {
            try {
                startIntentSenderForResult(pending.intentSender, REQ_DELETE, null, 0, 0, 0)
            } catch (t: Throwable) {
                Toast.makeText(this, "调起系统确认失败: ${t.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            reportDeleteResult(file)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) {
            reportDeleteResult(File(dcimDir(), paths[index]))
        }
    }

    private fun reportDeleteResult(file: File) {
        if (!file.exists()) {
            Toast.makeText(
                this,
                "已删除 1 个文件，空间已释放；它仍在电脑上（仅PC有）",
                Toast.LENGTH_LONG
            ).show()
            locals[index] = false
            statuses[index] = ST_PC_ONLY
            showCurrent()
        } else {
            Toast.makeText(this, "未删除（系统确认未通过或文件仍在）", Toast.LENGTH_LONG).show()
        }
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    }
}

/**
 * ImageView with a matrix transform: pinch to zoom (1x–4x around the focal
 * point), drag to pan while zoomed, double-tap toggles 1x ↔ 2.5x, single
 * tap toggles chrome, horizontal fling at 1x pages to the next photo.
 */
class ZoomImageView(ctx: Context) : ImageView(ctx) {

    var onSingleTap: (() -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null
    var onZoomed: (() -> Unit)? = null

    private val imgMatrix = Matrix()
    private var baseScale = 1f
    private var scale = 1f

    private val scaleDetector = ScaleGestureDetector(
        ctx,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val newScale = (scale * factor).coerceIn(1f, 4f)
                val applied = newScale / scale
                imgMatrix.postScale(applied, applied, detector.focusX, detector.focusY)
                scale = newScale
                clampTranslation()
                imageMatrix = imgMatrix
                if (scale > 1.05f) onZoomed?.invoke()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        ctx,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > 1.05f) {
                    resetZoom()
                } else {
                    val target = 2.5f
                    imgMatrix.postScale(target, target, e.x, e.y)
                    scale = target
                    clampTranslation()
                    imageMatrix = imgMatrix
                    onZoomed?.invoke()
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                if (scale > 1.05f) {
                    imgMatrix.postTranslate(-dx, -dy)
                    clampTranslation()
                    imageMatrix = imgMatrix
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
            ): Boolean {
                if (scale <= 1.05f && abs(vx) > 480 && abs(vx) > abs(vy) * 1.6f) {
                    onSwipe?.invoke(if (vx < 0) 1 else -1)
                    return true
                }
                return false
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        resetZoom()
        if (bm != null && width > 0 && height > 0) fitToView(bm)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = drawable ?: return
        if (scale <= 1.05f) fitBitmapSize(d.intrinsicWidth, d.intrinsicHeight)
    }

    fun resetZoom() {
        imgMatrix.reset()
        scale = 1f
        val d = drawable
        if (d != null && width > 0 && height > 0) {
            fitBitmapSize(d.intrinsicWidth, d.intrinsicHeight)
        }
        imageMatrix = imgMatrix
    }

    private fun fitToView(bm: android.graphics.Bitmap) = fitBitmapSize(bm.width, bm.height)

    private fun fitBitmapSize(bw: Int, bh: Int) {
        if (bw <= 0 || bh <= 0 || width == 0 || height == 0) return
        baseScale = minOf(width.toFloat() / bw, height.toFloat() / bh)
        imgMatrix.reset()
        imgMatrix.postScale(baseScale, baseScale)
        val dx = (width - bw * baseScale) / 2f
        val dy = (height - bh * baseScale) / 2f
        imgMatrix.postTranslate(dx, dy)
        scale = 1f
        imageMatrix = imgMatrix
    }

    private fun clampTranslation() {
        val d = drawable ?: return
        val values = FloatArray(9)
        imgMatrix.getValues(values)
        val totalScale = baseScale * scale
        val w = d.intrinsicWidth * totalScale
        val h = d.intrinsicHeight * totalScale
        var tx = values[Matrix.MTRANS_X]
        var ty = values[Matrix.MTRANS_Y]
        val minTx = if (w > width) width - w else (width - w) / 2f
        val maxTx = if (w > width) 0f else (width - w) / 2f
        val minTy = if (h > height) height - h else (height - h) / 2f
        val maxTy = if (h > height) 0f else (height - h) / 2f
        tx = tx.coerceIn(minTx, maxTx)
        ty = ty.coerceIn(minTy, maxTy)
        values[Matrix.MTRANS_X] = tx
        values[Matrix.MTRANS_Y] = ty
        imgMatrix.setValues(values)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (scale < 1f) resetZoom()
        }
        return true
    }
}
