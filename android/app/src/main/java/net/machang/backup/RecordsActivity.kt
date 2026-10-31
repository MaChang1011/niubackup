package net.machang.backup

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Backup records page (design §6.2 + approved mockup v1.1): the PC's record
 * DB, fetched live via fs LIST with THUMB thumbnails (memory + disk LRU).
 *
 * Layout per the mockup: header title + connection pill, a persistent
 * freshness strip （记录截至 HH:MM when showing the offline cache), then ONE
 * card holding day-grouped rows — 42dp radius-9 thumb, filename + meta
 * (size · time · 回执已验证 ✓ when a verified receipt covers the path),
 * and inline ghost actions 播放 / 下载回来. Tapping an image row opens the
 * full-screen photo preview; tapping a video row plays it.
 *
 * Three display states, kept visually distinct: LIVE （实时记录， green
 * pill) — fetched from the PC just now; STALE （记录截至 HH:MM, amber
 * 离线缓存 pill) — the PC is unreachable and the last fetched copy is
 * shown; EMPTY — the PC answered (or not) and there are no records yet,
 * an explicit hint row in the card instead of a blank void. The page
 * reloads on every resume; overlapping loads are generation-guarded so
 * only the newest fetch reaches the UI. Since the receiver serves fs
 * sessions concurrently with backups, LIVE works even mid-backup.
 *
 * Pagination (design v1.3): 20 records per page with a bottom pager;
 * only the current page's rows are built and only its thumbnails are
 * prefetched. (The photo preview opened from a row still swipes the
 * full loaded record list — it is already in memory and costs nothing
 * to render lazily per swipe.)
 */
class RecordsActivity : Activity() {

    private sealed class Row {
        data class Day(val label: String) : Row()
        data class Entry(val record: FsClient.Record) : Row()
        data class Hint(val text: String) : Row()
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var connPill: LinearLayout
    private lateinit var stripText: TextView
    private lateinit var listView: ListView
    private lateinit var prevPageBtn: android.widget.Button
    private lateinit var nextPageBtn: android.widget.Button
    private lateinit var pageLabel: TextView
    private var records: List<FsClient.Record> = emptyList()
    private var rows: List<Row> = emptyList()
    private var receiptPaths: Set<String> = emptySet()

    /** Paging state (design v1.3: 20/page, explicit pager). The record
     *  list itself arrives whole over fs LIST — paging bounds what is
     *  RENDERED (rows + thumbnail prefetch), not what is fetched. */
    private var pageIndex = 0
    private var lastStaleAt: Long? = null
    private val io = Executors.newFixedThreadPool(3)
    private val memCache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = Ui.page(this, Ui.Page.RECORDS, "备份记录")
        val col = page.content
        connPill = Ui.pill(this, "连接中…", Ui.StatusKind.NONE)
        page.headerRight.addView(connPill)

        // Freshness strip (brand-50 card, always visible on this page).
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(this@RecordsActivity, Ui.BRAND_50, Ui.R_TILE, Ui.BRAND_100, 1)
            setPadding(
                Ui.dp(this@RecordsActivity, 11), Ui.dp(this@RecordsActivity, 8),
                Ui.dp(this@RecordsActivity, 11), Ui.dp(this@RecordsActivity, 8)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = Ui.dp(this@RecordsActivity, 16)
                rightMargin = Ui.dp(this@RecordsActivity, 16)
                topMargin = Ui.dp(this@RecordsActivity, Ui.PAGE_TOP)
            }
        }
        stripText = TextView(this).apply {
            text = "正在读取电脑上的备份记录…"
            textSize = 11.5f
            setTextColor(Ui.BRAND_800)
        }
        strip.addView(stripText)
        col.addView(strip)

        // The record card wraps the grouped list (mockup: single card).
        val card = Ui.cardBlock(this) { }
        card.setPadding(
            Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 10)
        )
        listView = ListView(this).apply {
            adapter = RecordAdapter()
            divider = null
            setOnItemClickListener { _, _, pos, _ ->
                (rows.getOrNull(pos) as? Row.Entry)?.let { onRecordTap(it.record) }
            }
        }
        card.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        col.addView(card)

        // ---- pager (design v1.3: 上一页 / 页码 / 下一页 under the card)
        val pager = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(
                Ui.dp(this@RecordsActivity, 16), Ui.dp(this@RecordsActivity, 4),
                Ui.dp(this@RecordsActivity, 16), Ui.dp(this@RecordsActivity, 8)
            )
        }
        prevPageBtn = Ui.ghostButton(this, "上一页").apply {
            setOnClickListener { turnPage(-1) }
        }
        pager.addView(prevPageBtn)
        pageLabel = TextView(this).apply {
            text = "第 1 / 1 页"
            textSize = 12f
            setTextColor(Ui.SLATE)
            gravity = Gravity.CENTER
        }
        pager.addView(pageLabel, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        nextPageBtn = Ui.ghostButton(this, "下一页").apply {
            setOnClickListener { turnPage(1) }
        }
        pager.addView(nextPageBtn)
        col.addView(pager)
        createdOnce = true
        loadRecords()
    }

    private fun totalPages(): Int =
        if (records.isEmpty()) 1 else (records.size + PAGE_SIZE - 1) / PAGE_SIZE

    private fun turnPage(delta: Int) {
        val target = (pageIndex + delta).coerceIn(0, totalPages() - 1)
        if (target == pageIndex) return
        pageIndex = target
        renderPage()
    }

    private fun renderPager() {
        pageLabel.text = "第 ${pageIndex + 1} / ${totalPages()} 页"
        prevPageBtn.isEnabled = pageIndex > 0
        nextPageBtn.isEnabled = pageIndex < totalPages() - 1
    }

    private var createdOnce = false
    private var wasPaused = false

    override fun onResume() {
        super.onResume()
        // singleTop tab: reload when returning to this page.
        if (createdOnce && wasPaused) loadRecords()
    }

    override fun onPause() {
        wasPaused = true
        super.onPause()
    }

    /** Tab switches are instant in both directions (see [Ui.navBar]). */
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    @Volatile
    private var loadGen = 0

    private fun loadRecords() {
        // Loads overlap by design (onCreate + every onResume): only the
        // newest load's result may reach the UI — a slow stale fetch must
        // never overwrite fresher records.
        val gen = ++loadGen
        thread(name = "records-load") {
            // Receipt coverage powers the 回执已验证 meta (verified locally).
            val receipts = try {
                ReceiptStore.loadMerged(filesDir).keys
            } catch (t: Throwable) {
                emptySet()
            }
            var live: List<FsClient.Record>? = null
            try {
                val fs = PcConn.createFsClient(this)
                if (fs != null) {
                    fs.connect()
                    live = fs.list()
                    fs.close()
                }
            } catch (t: Throwable) {
                live = null
            }
            if (live != null) {
                val list = live
                RecordsCache.save(this, list)
                main.post { if (gen == loadGen) show(list, null, receipts) }
            } else {
                val cached = RecordsCache.load(this)
                main.post {
                    if (gen != loadGen) return@post
                    if (cached != null) {
                        show(cached.records, cached.fetchedAt, receipts)
                    } else {
                        records = emptyList()
                        rows = listOf(Row.Hint("暂无备份记录 · 连上电脑后回到本页会自动刷新"))
                        receiptPaths = receipts
                        (listView.adapter as BaseAdapter).notifyDataSetChanged()
                        renderPager()
                        Ui.setPill(connPill, "电脑离线", Ui.StatusKind.PC)
                        stripText.text = "电脑不可达，且暂无缓存记录。先组网并完成一次备份。"
                    }
                }
            }
        }
    }

    private fun show(list: List<FsClient.Record>, staleAt: Long?, receipts: Set<String>) {
        records = list
        receiptPaths = receipts
        lastStaleAt = staleAt
        pageIndex = 0
        renderPage()
    }

    /** Render the current page of [records] (20/page). Day-group headers
     *  repeat at a page boundary — buildRows runs per page slice, so a
     *  group never starts headerless mid-page (design v1.3). */
    private fun renderPage() {
        val list = records
        val staleAt = lastStaleAt
        val pages = totalPages()
        if (pageIndex > pages - 1) pageIndex = pages - 1
        if (pageIndex < 0) pageIndex = 0
        val start = pageIndex * PAGE_SIZE
        val slice = if (start >= list.size) emptyList()
            else list.subList(start, minOf(list.size, start + PAGE_SIZE))
        // Empty is a state of its own — an explicit hint row, never a
        // blank card that looks like a loading failure.
        rows = if (list.isEmpty()) {
            listOf(
                Row.Hint(
                    if (staleAt == null) "暂无备份记录 · 完成一次备份后会显示在这里"
                    else "暂无备份记录"
                )
            )
        } else {
            buildRows(slice)
        }
        if (staleAt == null) {
            Ui.setPill(connPill, "直连中", Ui.StatusKind.OK)
            stripText.text = if (list.isEmpty()) {
                "电脑上暂无备份记录。先在「备份」页完成一次备份。"
            } else {
                "实时记录 · 共 ${list.size} 个文件 · 刚刚从电脑更新"
            }
        } else {
            // Amber pill: this is the offline cache, NOT live data.
            Ui.setPill(connPill, "离线缓存", Ui.StatusKind.WAIT)
            stripText.text = "记录截至 ${fmtTime(staleAt)} · 电脑暂时不可达 · 共 ${list.size} 个文件"
        }
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
        renderPager()
        // Prefetch only the visible page's thumbnails (design v1.3).
        for (r in slice) if (r.thumb) prefetchThumb(r)
    }

    private fun buildRows(list: List<FsClient.Record>): List<Row> {
        val out = ArrayList<Row>()
        var lastDay: String? = null
        for (r in list) {
            val day = dayLabel(r.backedAt)
            if (day != lastDay) {
                out += Row.Day(day)
                lastDay = day
            }
            out += Row.Entry(r)
        }
        return out
    }

    private fun dayLabel(ms: Long): String {
        if (ms <= 0) return "更早"
        val cal = Calendar.getInstance().apply { timeInMillis = ms }
        val now = Calendar.getInstance()
        val yday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val md = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(ms))
        return when {
            sameDay(cal, now) -> "今天 · $md"
            sameDay(cal, yday) -> "昨天 · $md"
            else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(ms))
        }
    }

    private fun thumbFile(r: FsClient.Record): File {
        val dir = File(filesDir, "thumb-cache").apply { mkdirs() }
        val key = (r.sha256 ?: r.path.hashCode().toString()).replace("/", "_")
        return File(dir, "$key.jpg")
    }

    private fun prefetchThumb(r: FsClient.Record) {
        val key = r.sha256 ?: r.path
        if (memCache.get(key) != null || thumbFile(r).isFile) return
        io.execute {
            try {
                val fs = PcConn.createFsClient(this) ?: return@execute
                fs.connect()
                val bytes = fs.thumb(r.path)
                fs.close()
                if (bytes != null) {
                    thumbFile(r).writeBytes(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let {
                        memCache.put(key, it)
                    }
                    main.post { (listView.adapter as BaseAdapter).notifyDataSetChanged() }
                }
            } catch (t: Throwable) {
                // Thumbnail fetch failure just leaves the placeholder.
            }
        }
    }

    private fun thumbBitmap(r: FsClient.Record): Bitmap? {
        val key = r.sha256 ?: r.path
        memCache.get(key)?.let { return it }
        val f = thumbFile(r)
        if (f.isFile) {
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            if (bmp != null) memCache.put(key, bmp)
            return bmp
        }
        return null
    }

    private fun onRecordTap(r: FsClient.Record) {
        val name = r.path.substringAfterLast('/')
        when {
            MediaStoreHelper.isImageName(name) -> openPreview(r)
            MediaStoreHelper.isPlayableName(name) -> play(r)
            else -> downloadBack(r)
        }
    }

    private fun play(r: FsClient.Record) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PATH, r.path)
                .putExtra(PlayerActivity.EXTRA_SIZE, r.size)
        )
    }

    private fun openPreview(tapped: FsClient.Record) {
        val images = records.filter {
            MediaStoreHelper.isImageName(it.path.substringAfterLast('/'))
        }
        val idx = images.indexOfFirst { it.path == tapped.path }
        if (idx < 0) return
        val paths = ArrayList<String>()
        val sizes = LongArray(images.size)
        val dates = LongArray(images.size)
        val locals = BooleanArray(images.size)
        val statuses = IntArray(images.size)
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        images.forEachIndexed { i, r ->
            paths += r.path
            sizes[i] = r.size
            dates[i] = r.backedAt
            locals[i] = File(dcim, r.path).isFile
            statuses[i] = if ("ok" == r.integrity) {
                PhotoPreviewActivity.ST_BACKED
            } else {
                PhotoPreviewActivity.ST_SUSPECT
            }
        }
        startActivity(
            Intent(this, PhotoPreviewActivity::class.java)
                .putStringArrayListExtra(PhotoPreviewActivity.EXTRA_PATHS, paths)
                .putExtra(PhotoPreviewActivity.EXTRA_INDEX, idx)
                .putExtra(PhotoPreviewActivity.EXTRA_SIZES, sizes)
                .putExtra(PhotoPreviewActivity.EXTRA_DATES, dates)
                .putExtra(PhotoPreviewActivity.EXTRA_LOCALS, locals)
                .putExtra(PhotoPreviewActivity.EXTRA_STATUSES, statuses)
                .putExtra(PhotoPreviewActivity.EXTRA_KIND, PhotoPreviewActivity.KIND_RECORDS)
                .putExtra(PhotoPreviewActivity.EXTRA_SHAS,
                    ArrayList(images.map { it.sha256 ?: "" }))
        )
    }

    private fun downloadBack(r: FsClient.Record) {
        Toast.makeText(this, "开始下载 ${r.path.substringAfterLast('/')}", Toast.LENGTH_SHORT).show()
        thread(name = "record-download") {
            try {
                val fs = PcConn.createFsClient(this) ?: throw IllegalStateException("无设备身份")
                val tmpDir = File(cacheDir, "restore-tmp")
                val tmp = fs.download(r.path, r.size, tmpDir, null)
                fs.close()
                val uri = MediaStoreHelper.publishToRestoreFolder(
                    this, tmp, r.path.substringAfterLast('/')
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

    private inner class RecordAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 3
        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Day -> 0
            is Row.Entry -> 1
            is Row.Hint -> 2
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = rows[position]) {
                is Row.Day -> dayView(row.label, convertView)
                is Row.Entry -> entryView(row.record, convertView, position)
                is Row.Hint -> hintView(row.text, convertView)
            }
        }

        private fun hintView(text: String, convertView: View?): View {
            val tv = (convertView as? TextView) ?: TextView(this@RecordsActivity).apply {
                textSize = 12f
                setTextColor(Ui.SLATE)
                gravity = Gravity.CENTER
                setPadding(
                    Ui.dp(this@RecordsActivity, 8), Ui.dp(this@RecordsActivity, 26),
                    Ui.dp(this@RecordsActivity, 8), Ui.dp(this@RecordsActivity, 26)
                )
            }
            tv.text = text
            return tv
        }

        private fun dayView(label: String, convertView: View?): View {
            val tv = (convertView as? TextView) ?: TextView(this@RecordsActivity).apply {
                textSize = 11.5f
                setTextColor(Ui.SLATE)
                typeface = Ui.font(900)
                letterSpacing = 0.06f
                setPadding(0, Ui.dp(this@RecordsActivity, 12), 0, Ui.dp(this@RecordsActivity, 2))
            }
            tv.text = label
            return tv
        }

        private fun entryView(r: FsClient.Record, convertView: View?, position: Int): View {
            val root = (convertView as? LinearLayout) ?: buildRow()
            val img = root.getChildAt(0) as ImageView
            val info = root.getChildAt(1) as LinearLayout
            val name = info.getChildAt(0) as TextView
            val meta = info.getChildAt(1) as TextView
            val actions = root.getChildAt(2) as LinearLayout
            val playBtn = actions.getChildAt(0) as TextView
            val dlBtn = actions.getChildAt(1) as TextView

            val bmp = if (r.thumb) thumbBitmap(r) else null
            if (bmp != null) {
                img.setImageBitmap(bmp)
            } else {
                img.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            val fname = r.path.substringAfterLast('/')
            name.text = fname
            val integrity = when (r.integrity) {
                "ok" -> "完整"
                "suspect" -> "存疑"
                else -> r.integrity ?: ""
            }
            val receipt = if (r.path in receiptPaths) " · 回执已验证 ✓" else ""
            meta.text = "${fmtSize(r.size)} · ${fmtTimeHM(r.backedAt)} · $integrity$receipt"
            val playable = MediaStoreHelper.isPlayableName(fname)
            playBtn.visibility = if (playable) View.VISIBLE else View.GONE
            playBtn.setOnClickListener { play(r) }
            dlBtn.setOnClickListener { downloadBack(r) }
            return root
        }

        private fun buildRow(): LinearLayout {
            val root = LinearLayout(this@RecordsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Ui.dp(this@RecordsActivity, 8), 0, Ui.dp(this@RecordsActivity, 8))
            }
            val img = ImageView(this@RecordsActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(this@RecordsActivity, Ui.SLATE_BG, Ui.R_THUMB)
                clipToOutline = true
            }
            root.addView(img, LinearLayout.LayoutParams(
                Ui.dp(this@RecordsActivity, 42), Ui.dp(this@RecordsActivity, 42)
            ))
            val info = LinearLayout(this@RecordsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(this@RecordsActivity, 10), 0, Ui.dp(this@RecordsActivity, 8), 0)
            }
            info.addView(TextView(this@RecordsActivity).apply {
                textSize = 12.5f
                typeface = Ui.font(700)
                setTextColor(Ui.INK)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            })
            info.addView(TextView(this@RecordsActivity).apply {
                textSize = 11f
                setTextColor(Ui.SLATE)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
            })
            root.addView(info, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            val actions = LinearLayout(this@RecordsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actions.addView(Ui.xsButton(this@RecordsActivity, "播放"))
            actions.addView(Ui.xsButton(this@RecordsActivity, "下载回来").apply {
                (layoutParams as? LinearLayout.LayoutParams)?.leftMargin =
                    Ui.dp(this@RecordsActivity, 6)
            })
            root.addView(actions)
            return root
        }
    }

    private fun fmtTime(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    private fun fmtTimeHM(ms: Long): String =
        if (ms <= 0) "" else SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    private fun fmtSize(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    }

    companion object {
        /** Design v1.3: records render 20 per page (5,412 ≈ 271 pages). */
        const val PAGE_SIZE = 20
    }
}
