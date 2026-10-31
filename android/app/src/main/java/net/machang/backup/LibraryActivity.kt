package net.machang.backup

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 文件库 page (design §6.1 + approved mockup v1.1): DCIM as a thumbnail grid,
 * merged with backup state.
 *
 * State comes from the local SQLite file index ([FileIndexDb]), which the
 * backup engine feeds as sessions run; this page renders straight from the
 * index (milliseconds) and refreshes it in the background with a
 * metadata-only MediaStore diff — it NEVER scans or hashes DCIM content
 * (the old scan-on-open hashed all ~6,300 files and wedged the page).
 *
 * Every cell carries one of four badges, merged from three sources:
 *   未备份 — local file with no matching verified receipt / PC record;
 *   备份中 x% — the file a running session is currently sending;
 *   已备份 — the index confirms the PC holds this (path, size, mtime), or a
 *             signature-verified receipt / the PC's live LIST vouches for
 *             it with matching size (and matching sha whenever a sha is
 *             known — stream-hash sessions may never learn one);
 *   仅PC有 — in the receipts / PC records but no longer on the phone
 *             (restorable via §6.3 download-back, streamable via playback).
 *
 * Mockup behaviors: filter chips （全部/照片/视频/未备份）, video tiles show a
 * duration chip, 仅PC有 tiles are dimmed with a PC overlay, tapping an image
 * tile opens the full-screen photo preview, and SELECTION MODE (long-press,
 * or tapping a backed video) shows an action bar at the top of the content
 * area — 取消 / 已选 N 项 / 释放空间 (the scarce orange CTA) — under the app
 * bar, which itself never moves or changes, with the mandatory 释放安全
 * card above the nav bar. The §6.4 free-up-space flow behind it is unchanged:
 * receipt-gated, live integrity cross-check when the PC is reachable,
 * explicit confirmation, then a MediaStore delete with system consent.
 */
class LibraryActivity : Activity() {

    enum class Status(val label: String, val kind: Ui.StatusKind) {
        NOT_BACKED("未备份", Ui.StatusKind.NONE),
        BACKING_UP("备份中", Ui.StatusKind.RUN),
        BACKED("已备份", Ui.StatusKind.OK),
        PC_ONLY("仅PC有", Ui.StatusKind.PC)
    }

    data class Item(
        val path: String,
        val size: Long,
        val sha: String?,
        val local: Boolean,
        var status: Status,
        val receiptVerified: Boolean,
        val liveIntegrity: String?
    )

    private enum class Filter(val label: String) {
        ALL("全部"), PHOTO("照片"), VIDEO("视频"), NOT_BACKED("未备份")
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var countView: TextView
    private lateinit var chipsRow: LinearLayout
    private lateinit var selBar: LinearLayout
    private lateinit var selCount: TextView
    private lateinit var safetyCard: LinearLayout
    private lateinit var safetyText: TextView
    private lateinit var gridView: GridView
    private var items: List<Item> = emptyList()

    /** Latest snapshot of the local file index (see [FileIndexDb]). */
    @Volatile
    private var indexRows: Map<String, FileIndexDb.Row> = emptyMap()
    private var receipts: Map<String, ReceiptStore.ReceiptFile> = emptyMap()
    private val selected = HashSet<String>()
    private val downloadPct = HashMap<String, Int>()
    private val durations = HashMap<String, Long>()
    private var liveOk: Boolean = false
    private var liveByPath: Map<String, FsClient.Record> = emptyMap()
    private var filterMode = Filter.ALL
    private var poller: Runnable? = null
    private var createdOnce = false
    private var wasPaused = false

    private val thumbIo = Executors.newFixedThreadPool(3)
    private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = Ui.page(this, Ui.Page.LIBRARY, "文件库")
        val col = page.content

        // ---- selection-mode action bar: lives at the top of the CONTENT
        // area; the scaffold app bar above it stays fixed and untouched.
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@LibraryActivity, 16), Ui.dp(this@LibraryActivity, Ui.PAGE_TOP),
                Ui.dp(this@LibraryActivity, 16), Ui.dp(this@LibraryActivity, 8))
            visibility = View.GONE
            val cancel = TextView(this@LibraryActivity).apply {
                text = "取消"
                textSize = 12.5f
                setTextColor(Ui.BRAND_600)
                typeface = Ui.font(600)
                isClickable = true
                setOnClickListener {
                    selected.clear()
                    (gridView.adapter as BaseAdapter).notifyDataSetChanged()
                    updateSelectionUi()
                }
            }
            addView(cancel)
            selCount = TextView(this@LibraryActivity).apply {
                textSize = 14f
                setTextColor(Ui.INK)
                typeface = Ui.font(900)
                gravity = Gravity.CENTER
            }
            addView(selCount, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(Ui.accentButton(this@LibraryActivity, "释放空间").apply {
                textSize = 11.5f
                setPadding(Ui.dp(this@LibraryActivity, 12), Ui.dp(this@LibraryActivity, 7),
                    Ui.dp(this@LibraryActivity, 12), Ui.dp(this@LibraryActivity, 7))
                setOnClickListener { startFreeUpSpace() }
            })
        }
        col.addView(selBar)
        page.headerRight.addView(Ui.xsButton(this, "刷新").apply {
            setOnClickListener { refresh() }
        })

        // ---- filter chips + count
        val chipsOuter = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(this@LibraryActivity, 16), Ui.dp(this@LibraryActivity, Ui.PAGE_TOP),
                Ui.dp(this@LibraryActivity, 16), Ui.dp(this@LibraryActivity, 2))
        }
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipsOuter.addView(chipsRow, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        countView = TextView(this).apply {
            textSize = 11f
            setTextColor(Ui.SLATE)
        }
        chipsOuter.addView(countView)
        col.addView(chipsOuter)
        buildChips()

        statusView = TextView(this).apply {
            text = "正在读取本地文件索引…"
            textSize = 12f
            setTextColor(Ui.SLATE)
            setPadding(Ui.dp(this@LibraryActivity, 16), 2, Ui.dp(this@LibraryActivity, 16), 4)
        }
        col.addView(statusView)

        gridView = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = Ui.dp(this@LibraryActivity, 110)
            horizontalSpacing = Ui.dp(this@LibraryActivity, 6)
            verticalSpacing = Ui.dp(this@LibraryActivity, 6)
            setPadding(
                Ui.dp(this@LibraryActivity, 16), Ui.dp(this@LibraryActivity, 6),
                Ui.dp(this@LibraryActivity, 16), Ui.dp(this@LibraryActivity, 8)
            )
            adapter = GridAdapter()
            setOnItemClickListener { _, _, pos, _ -> onItemTap(visibleItems()[pos]) }
            setOnItemLongClickListener { _, _, pos, _ ->
                val item = visibleItems()[pos]
                if (item.status == Status.BACKED) {
                    selected += item.path
                    (adapter as BaseAdapter).notifyDataSetChanged()
                    updateSelectionUi()
                    true
                } else {
                    false
                }
            }
        }
        // Grid area: the grid plus a centered empty state over it (the
        // same centered-hint treatment as the records page's empty card).
        val gridWrap = FrameLayout(this)
        gridWrap.addView(
            gridView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        emptyView = TextView(this).apply {
            text = "没有可显示的文件。\n完成一次备份后，这里会显示每个文件的备份状态。"
            textSize = 12f
            setTextColor(Ui.SLATE)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.5f)
            setPadding(
                Ui.dp(this@LibraryActivity, 40), 0,
                Ui.dp(this@LibraryActivity, 40), 0
            )
            visibility = View.GONE
        }
        gridWrap.addView(
            emptyView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        col.addView(
            gridWrap,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // ---- 释放安全 card (mandatory before delete; mockup)
        safetyCard = Ui.cardBlock(this) {
            val row = LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(Ui.badge(this@LibraryActivity, "可安全释放", Ui.StatusKind.OK))
            safetyText = TextView(this@LibraryActivity).apply {
                textSize = 11.5f
                setTextColor(Ui.INK_2)
                setLineSpacing(0f, 1.45f)
                setPadding(Ui.dp(this@LibraryActivity, 10), 0, 0, 0)
            }
            row.addView(safetyText, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(row)
        }
        safetyCard.visibility = View.GONE
        col.addView(safetyCard)

        refresh()
        createdOnce = true
        // Poll while visible so 备份中 badges tick with the running session.
        val poll = object : Runnable {
            override fun run() {
                applyProgressBadges()
                main.postDelayed(this, 700)
            }
        }
        poller = poll
        main.post(poll)
    }

    override fun onResume() {
        super.onResume()
        // singleTop tab: returning to this page re-reads state (the activity
        // is reused, not recreated, when navigated to via the bottom bar).
        if (createdOnce && wasPaused) refresh()
    }

    override fun onPause() {
        wasPaused = true
        super.onPause()
    }

    override fun onDestroy() {
        poller?.let { main.removeCallbacks(it) }
        super.onDestroy()
    }

    /** Tab switches are instant in both directions (see [Ui.navBar]). */
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    // -------------------------------------------------------------- filters

    private fun buildChips() {
        chipsRow.removeAllViews()
        for (f in Filter.values()) {
            val chip = Ui.filterChip(this, f.label, f == filterMode)
            chip.setOnClickListener {
                filterMode = f
                buildChips()
                (gridView.adapter as BaseAdapter).notifyDataSetChanged()
                updateCount()
            }
            chipsRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = Ui.dp(this@LibraryActivity, 6) })
        }
    }

    private fun visibleItems(): List<Item> = when (filterMode) {
        Filter.ALL -> items
        Filter.PHOTO -> items.filter { MediaStoreHelper.isImageName(nameOf(it)) }
        Filter.VIDEO -> items.filter { MediaStoreHelper.isVideoName(nameOf(it)) }
        Filter.NOT_BACKED -> items.filter { it.status == Status.NOT_BACKED }
    }

    private fun nameOf(item: Item): String = item.path.substringAfterLast('/')

    private fun updateCount() {
        countView.text = "${visibleItems().size} 项"
    }

    // ---------------------------------------------------------------- data

    private fun dcimDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)

    // Refresh coalescing: overlapping triggers (onCreate, onResume,
    // post-download, post-delete) collapse into at most one extra pass.
    private val refreshLock = Object()
    private var refreshActive = false
    private var refreshPending = false

    /**
     * Two-phase refresh, index-first:
     *  1. Render immediately from the local file index + stored receipts
     *     (a SQLite read — milliseconds, no DCIM walk at all).
     *  2. In the background, freshen the index with a metadata-only
     *     MediaStore diff, merge the live PC LIST when reachable, and
     *     re-render. No content is read and no hash is computed anywhere
     *     on this page.
     */
    private fun refresh() {
        synchronized(refreshLock) {
            if (refreshActive) {
                refreshPending = true
                return
            }
            refreshActive = true
        }
        thread(name = "library-refresh") {
            var rerun = false
            try {
                val db = FileIndexDb.get(this)
                val rows0 = db.loadAll()
                val receipts0 = ReceiptStore.loadMerged(filesDir)
                val first = buildItems(rows0, receipts0, emptyMap())
                main.post {
                    indexRows = rows0
                    receipts = receipts0
                    items = first
                    emptyView.visibility = if (first.isEmpty()) View.VISIBLE else View.GONE
                    (gridView.adapter as BaseAdapter).notifyDataSetChanged()
                    updateCount()
                }
                refreshDiff(db)
            } finally {
                synchronized(refreshLock) {
                    rerun = refreshPending
                    refreshPending = false
                    refreshActive = false
                }
                if (rerun) refresh()
            }
        }
    }

    /** Phase 2 of [refresh]: metadata diff + live merge + final render. */
    private fun refreshDiff(db: FileIndexDb) {
        // Freshness diff: MediaStore metadata (path/size/mtime) vs the
        // index. mtime compares at SECOND granularity because MediaStore's
        // DATE_MODIFIED is seconds while the index also carries the
        // engine's millisecond mtimes.
        try {
            val metas = MediaStoreHelper.listDcimMetadata(this, dcimDir())
            val current = db.loadAll()
            val changed = ArrayList<FileIndexDb.Meta>()
            val seen = HashSet<String>()
            for (m in metas) {
                seen += m.relPath
                val row = current[m.relPath]
                when {
                    row == null || row.size != m.size ->
                        changed += FileIndexDb.Meta(m.relPath, m.size, m.mtimeMs, null)
                    row.mtime == 0L ->
                        // Receipt-created placeholder: adopt the real
                        // mtime, keep its backup evidence.
                        db.refreshMtime(m.relPath, m.mtimeMs)
                    row.mtime / 1000 != m.mtimeMs / 1000 ->
                        changed += FileIndexDb.Meta(m.relPath, m.size, m.mtimeMs, null)
                }
            }
            if (changed.isNotEmpty()) db.upsertMetaBatch(changed)
            // Vanished locals: MediaStore alone can lag the filesystem, so
            // a row is dropped only when the file is actually gone (a stat,
            // not a read). If it later shows up in receipts/LIST it renders
            // as 仅PC有, as before.
            val gone = current.keys.filter { it !in seen && !File(dcimDir(), it).exists() }
            if (gone.isNotEmpty()) db.deletePaths(gone)
        } catch (t: Throwable) {
            // Freshness is best effort; the index render stands.
        }

        val mergedReceipts = ReceiptStore.loadMerged(filesDir)
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
        val liveMap = HashMap<String, FsClient.Record>()
        if (live != null) {
            RecordsCache.save(this, live)
            for (r in live) liveMap[r.path] = r
            db.applyPcRecords(live)
        }
        val rows = db.loadAll()
        val merged = buildItems(rows, mergedReceipts, liveMap)
        main.post {
            items = merged
            receipts = mergedReceipts
            indexRows = rows
            liveOk = live != null
            liveByPath = liveMap
            selected.retainAll(merged.filter { it.status == Status.BACKED }.map { it.path })
            // The status line reports only the connection state; the
            // empty case is the centered overlay over the grid area.
            emptyView.visibility = if (merged.isEmpty()) View.VISIBLE else View.GONE
            statusView.text = if (liveOk) "电脑记录为实时" else "电脑离线（按回执判断）"
            (gridView.adapter as BaseAdapter).notifyDataSetChanged()
            updateCount()
            updateSelectionUi()
        }
    }

    /**
     * Merge the three state sources into grid items. Local rows come from
     * the index; a local file counts as 已备份 when the index already
     * confirms the PC holds its (path, size, mtime), or a verified receipt
     * vouches for it (see [receiptVouches]), or the live LIST holds an
     * integrity-ok record with matching size (and matching sha whenever
     * the index knows one), or a known sha appears in any verified
     * receipt (renamed-file fallback, as before).
     */
    private fun buildItems(
        rows: Map<String, FileIndexDb.Row>,
        receiptsMap: Map<String, ReceiptStore.ReceiptFile>,
        liveMap: Map<String, FsClient.Record>
    ): List<Item> {
        val backedShas = HashSet<String>()
        for (rf in receiptsMap.values) backedShas += rf.sha256
        val merged = ArrayList<Item>()
        val localPaths = HashSet<String>()
        for ((path, row) in rows) {
            localPaths += path
            val rf = receiptsMap[path]
            val live = liveMap[path]
            val receiptHit = row.receiptVerified && rf != null && rf.size == row.size &&
                (row.sha256 == null || rf.sha256 == row.sha256)
            val liveHit = live != null && "ok" == live.integrity && live.size == row.size &&
                (row.sha256 == null || live.sha256 == row.sha256)
            val shaHit = row.sha256 != null && backedShas.contains(row.sha256)
            val backed = row.status == FileIndexDb.STATUS_BACKED || receiptHit || liveHit || shaHit
            merged += Item(
                path, row.size,
                row.sha256 ?: live?.sha256 ?: rf?.sha256,
                local = true,
                status = if (backed) Status.BACKED else Status.NOT_BACKED,
                receiptVerified = receiptHit,
                liveIntegrity = live?.integrity ?: row.pcIntegrity
            )
        }
        val pcOnlyPaths = (receiptsMap.keys + liveMap.keys).filter { it !in localPaths }
        for (p in pcOnlyPaths.sorted()) {
            val rec = liveMap[p]
            val rf = receiptsMap[p]
            merged += Item(
                p, rec?.size ?: rf?.size ?: -1, rec?.sha256 ?: rf?.sha256,
                local = false, status = Status.PC_ONLY,
                receiptVerified = rf != null, liveIntegrity = rec?.integrity
            )
        }
        return merged
    }

    /**
     * §6.4 evidence check, aligned with stream-hash semantics: a verified
     * receipt vouches for [item] when path+size match and either the sha
     * is known to match, or no local sha is known and the index recorded
     * this receipt against the item's exact (size, mtime) during the
     * session (receipt_verified — the index clears it whenever the file's
     * size or mtime later changes).
     */
    private fun receiptVouches(item: Item): Boolean {
        val rf = receipts[item.path] ?: return false
        if (rf.size != item.size) return false
        val sha = item.sha
        if (sha != null) return rf.sha256 == sha
        val row = indexRows[item.path] ?: return false
        return row.receiptVerified && row.size == item.size
    }

    private fun applyProgressBadges() {
        if (!BackupProgress.active) return
        var changed = false
        for (it in items) {
            if (it.local && it.path == BackupProgress.currentPath &&
                it.status != Status.BACKED
            ) {
                it.status = Status.BACKING_UP
                changed = true
            }
        }
        if (changed) (gridView.adapter as BaseAdapter).notifyDataSetChanged()
    }

    // ------------------------------------------------------------ selection

    private fun updateSelectionUi() {
        val selecting = selected.isNotEmpty()
        // Only the in-content action bar appears; the app bar above it
        // stays fixed and visible, so entering/leaving selection mode
        // never shifts the page's top chrome.
        selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        safetyCard.visibility = if (selecting) View.VISIBLE else View.GONE
        if (!selecting) return
        val chosen = items.filter { it.path in selected }
        val verified = chosen.count { c -> receiptVouches(c) }
        val bytes = chosen.sumOf { it.size }
        selCount.text = "已选 ${chosen.size} 项"
        safetyText.text = "所选 ${chosen.size} 项中 $verified 项持有已验证回执，预计释放 " +
            "${fmtSize(bytes)}。点「释放空间」时将逐项复核回执与电脑记录，未通过校验的文件会自动排除、不会删除。"
    }

    private fun onItemTap(item: Item) {
        if (selected.isNotEmpty()) {
            // Selection mode (entered by long-press): a tap only toggles
            // releasable (backed-up) items — it never opens the preview or
            // the player. Leaving selection mode stays as it was: clear
            // the selection via 取消 in the selection bar.
            if (item.status == Status.BACKED) {
                if (!selected.add(item.path)) selected.remove(item.path)
                (gridView.adapter as BaseAdapter).notifyDataSetChanged()
                updateSelectionUi()
            }
            return
        }
        // Images open the full-screen preview (mockup 照片预览页）.
        if (MediaStoreHelper.isImageName(nameOf(item))) {
            openPreview(item)
            return
        }
        // Videos open the player (design v1.1): the local file when the
        // phone still holds a backed-up copy, otherwise the PC copy
        // streamed over the tunnel. A plain tap never selects — selection
        // is long-press only.
        if (MediaStoreHelper.isPlayableName(nameOf(item))) {
            val localFile = File(dcimDir(), item.path)
            when {
                item.local && localFile.isFile && item.status == Status.BACKED ->
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_PATH, item.path)
                            .putExtra(PlayerActivity.EXTRA_SIZE, item.size)
                            .putExtra(
                                PlayerActivity.EXTRA_LOCAL_FILE,
                                localFile.absolutePath
                            )
                    )
                item.status == Status.BACKED ->
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_PATH, item.path)
                            .putExtra(PlayerActivity.EXTRA_SIZE, item.size)
                    )
                item.status == Status.PC_ONLY -> showPcOnlyActions(item)
                else -> Toast.makeText(
                    this, "该文件尚未备份，去「备份」页点「立即备份」", Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        when (item.status) {
            Status.BACKED -> Toast.makeText(
                this, "已备份到电脑 · 长按可多选并释放空间", Toast.LENGTH_SHORT
            ).show()
            Status.PC_ONLY -> showPcOnlyActions(item)
            else -> Toast.makeText(
                this, "该文件尚未备份，去「备份」页点「立即备份」", Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** PC-only item actions: stream-play (videos) / download back. */
    private fun showPcOnlyActions(item: Item) {
        val name = nameOf(item)
        val options = mutableListOf<String>()
        if (MediaStoreHelper.isPlayableName(name)) options += "播放（流式）"
        options += "下载回手机"
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options.toTypedArray()) { _, which ->
                if (options[which].startsWith("播放")) {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_PATH, item.path)
                            .putExtra(PlayerActivity.EXTRA_SIZE, item.size)
                    )
                } else {
                    downloadBack(item)
                }
            }
            .show()
    }

    private fun openPreview(tapped: Item) {
        val images = visibleItems().filter { MediaStoreHelper.isImageName(nameOf(it)) }
        val idx = images.indexOfFirst { it.path == tapped.path }
        if (idx < 0) return
        val paths = ArrayList<String>()
        val sizes = LongArray(images.size)
        val dates = LongArray(images.size)
        val locals = BooleanArray(images.size)
        val statuses = IntArray(images.size)
        images.forEachIndexed { i, it ->
            paths += it.path
            sizes[i] = it.size
            locals[i] = it.local
            statuses[i] = when (it.status) {
                Status.BACKED -> PhotoPreviewActivity.ST_BACKED
                Status.PC_ONLY -> PhotoPreviewActivity.ST_PC_ONLY
                Status.BACKING_UP -> PhotoPreviewActivity.ST_BACKING
                Status.NOT_BACKED -> PhotoPreviewActivity.ST_NOT_BACKED
            }
            dates[i] = when {
                it.local -> File(dcimDir(), it.path).lastModified()
                liveByPath[it.path] != null -> liveByPath[it.path]!!.backedAt
                else -> receipts[it.path]?.ts ?: 0L
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
                .putExtra(PhotoPreviewActivity.EXTRA_KIND, PhotoPreviewActivity.KIND_LIBRARY)
                .putExtra(PhotoPreviewActivity.EXTRA_SHAS,
                    ArrayList(images.map { it.sha ?: "" }))
        )
    }

    private fun downloadBack(item: Item) {
        thread(name = "library-download") {
            try {
                val fs = PcConn.createFsClient(this) ?: throw IllegalStateException("无设备身份")
                val tmp = fs.download(item.path, item.size, File(cacheDir, "restore-tmp")) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (downloadPct[item.path] != pct) {
                        downloadPct[item.path] = pct
                        main.post { (gridView.adapter as BaseAdapter).notifyDataSetChanged() }
                    }
                }
                fs.close()
                val uri = MediaStoreHelper.publishToRestoreFolder(
                    this, tmp, item.path.substringAfterLast('/')
                )
                main.post {
                    downloadPct.remove(item.path)
                    Toast.makeText(
                        this,
                        if (uri != null) "已下载到 DCIM/NiuBackupRestore" else "保存到相册失败",
                        Toast.LENGTH_LONG
                    ).show()
                    refresh()
                }
            } catch (t: Throwable) {
                main.post {
                    downloadPct.remove(item.path)
                    Toast.makeText(this, "下载失败: ${t.message}", Toast.LENGTH_LONG).show()
                    (gridView.adapter as BaseAdapter).notifyDataSetChanged()
                }
            }
        }
    }

    // ------------------------------------------------------------ §6.4 flow

    private fun startFreeUpSpace() {
        val candidates = items.filter { it.status == Status.BACKED && it.path in selected }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "先点选标有「已备份」的文件", Toast.LENGTH_SHORT).show()
            return
        }
        thread(name = "freeup-precheck") {
            // Gate 1 (always): a signature-verified receipt must vouch for
            // the file — same sha256 as the current local copy, or, when no
            // local sha is known (stream-hash), the index's receipt flag
            // for this exact (size, mtime). See receiptVouches.
            receipts = ReceiptStore.loadMerged(filesDir)
            try {
                indexRows = FileIndexDb.get(this).loadAll()
            } catch (t: Throwable) {
                // Keep the previous snapshot; the gate degrades to sha-only.
            }
            val receiptOk = candidates.filter { c -> receiptVouches(c) }
            // Gate 2 (when the PC is reachable): live integrity cross-check —
            // suspect or missing records are excluded, never silently deleted.
            var excluded = candidates.size - receiptOk.size
            var finalList = receiptOk
            var checkedLive = false
            try {
                val fs = PcConn.createFsClient(this)
                if (fs != null) {
                    fs.connect()
                    val live = fs.list()
                    fs.close()
                    val byPath = live.associateBy { it.path }
                    val before = finalList.size
                    finalList = finalList.filter { c ->
                        val r = byPath[c.path]
                        r != null && "ok" == r.integrity && r.size == c.size &&
                            (c.sha == null || r.sha256 == c.sha)
                    }
                    excluded += before - finalList.size
                    checkedLive = true
                }
            } catch (t: Throwable) {
                checkedLive = false
            }
            val totalBytes = finalList.sumOf { it.size }
            main.post { confirmFreeUpSpace(finalList, totalBytes, excluded, checkedLive) }
        }
    }

    private fun confirmFreeUpSpace(
        list: List<Item>, totalBytes: Long, excluded: Int, checkedLive: Boolean
    ) {
        if (list.isEmpty()) {
            Toast.makeText(this, "没有通过校验的可删除文件", Toast.LENGTH_LONG).show()
            return
        }
        val sb = StringBuilder()
        sb.append("将删除本机上的 ${list.size} 个文件（共 ${fmtSize(totalBytes)}），")
        sb.append("它们已完整备份到你的电脑（${PcConn.pcHost(this)} 的备份目录）。")
        sb.append("删除后可随时从电脑下载回手机。")
        if (!checkedLive) {
            sb.append("\n\n注意：本次未连接电脑做实时核对，依据的是手机保存的备份回执。")
        }
        if (excluded > 0) {
            sb.append("\n\n另有 $excluded 个文件未通过校验，已自动排除。")
        }
        AlertDialog.Builder(this)
            .setTitle("释放空间")
            .setMessage(sb.toString())
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> executeDelete(list) }
            .show()
    }

    private fun executeDelete(list: List<Item>) {
        val files = list.map { File(dcimDir(), it.path) }
        val pending = MediaStoreHelper.createDeleteRequest(this, files)
        if (pending != null) {
            try {
                startIntentSenderForResult(
                    pending.intentSender, REQ_DELETE, null, 0, 0, 0
                )
            } catch (t: Throwable) {
                Toast.makeText(this, "调起系统确认失败: ${t.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            // Pre-API-30 inline path (or nothing found): verify by existence.
            reportDeleteResult(files)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) {
            val files = items.filter { it.path in selected && it.status == Status.BACKED }
                .map { File(dcimDir(), it.path) }
            reportDeleteResult(files)
        }
    }

    private fun reportDeleteResult(files: List<File>) {
        val gone = files.count { !it.exists() }
        Toast.makeText(
            this,
            "已删除 $gone 个文件，空间已释放；它们仍在电脑上（仅PC有）",
            Toast.LENGTH_LONG
        ).show()
        selected.clear()
        refresh()
    }

    // ------------------------------------------------------------ thumbnails

    private fun bindThumbnail(item: Item, image: ImageView) {
        val key = if (item.local) "f:" + File(dcimDir(), item.path).absolutePath else "pc:" + item.path
        image.tag = key
        thumbCache.get(key)?.let {
            image.setImageBitmap(it)
            return
        }
        image.setImageResource(android.R.drawable.ic_menu_gallery)
        if (!item.local) return // PC-only: no local bytes to decode; placeholder stays.
        val file = File(dcimDir(), item.path)
        thumbIo.execute {
            val bmp = decodeThumb(file, item.path)
            if (bmp != null) {
                thumbCache.put(key, bmp)
                main.post {
                    if (image.tag == key) image.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun decodeThumb(file: File, relPath: String): Bitmap? {
        if (!file.isFile) return null
        val name = file.name.lowercase()
        return try {
            if (MediaStoreHelper.isVideoName(name)) {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(file.absolutePath)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.let { durations[relPath] = it }
                    mmr.getFrameAtTime(0)?.let { scaleToWidth(it, 256) }
                } finally {
                    try {
                        mmr.release()
                    } catch (t: Throwable) {
                        // ignore
                    }
                }
            } else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                if (opts.outWidth <= 0) return null
                var sample = 1
                while (opts.outWidth / sample > 256 || opts.outHeight / sample > 256) sample *= 2
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun scaleToWidth(bmp: Bitmap, width: Int): Bitmap {
        if (bmp.width <= width) return bmp
        val h = (bmp.height * width.toFloat() / bmp.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, width, h, true)
    }

    // --------------------------------------------------------------- adapter

    private inner class GridAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleItems().size
        override fun getItem(position: Int): Any = visibleItems()[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = visibleItems()[position]
            val cellSize = Ui.dp(this@LibraryActivity, 110)
            val cell = (convertView as? LinearLayout) ?: buildCell(cellSize)
            val frame = cell.getChildAt(0) as FrameLayout
            val image = frame.getChildAt(0) as ImageView
            val badge = frame.getChildAt(1) as TextView
            val check = frame.getChildAt(2) as TextView
            val duration = frame.getChildAt(3) as TextView
            val pcOverlay = frame.getChildAt(4) as View
            val name = cell.getChildAt(1) as TextView

            // Selection outline: 2.5dp brand stroke inset (mockup).
            frame.background = if (item.path in selected) {
                Ui.rounded(this@LibraryActivity, Ui.SLATE_BG, Ui.R_TILE, Ui.BRAND_600, 3)
            } else {
                Ui.rounded(this@LibraryActivity, Ui.SLATE_BG, Ui.R_TILE)
            }
            bindThumbnail(item, image)
            name.text = item.path.substringAfterLast('/')
            val pct = downloadPct[item.path]
            badge.text = when {
                pct != null -> "下载中 $pct%"
                item.status == Status.BACKING_UP -> "备份中 ${BackupProgress.percent}%"
                else -> item.status.label
            }
            val kind = if (pct != null) Ui.StatusKind.RUN else item.status.kind
            badge.setTextColor(kind.text)
            badge.background = Ui.rounded(this@LibraryActivity, kind.bg, Ui.R_BADGE)
            check.visibility = if (item.path in selected) View.VISIBLE else View.GONE
            // Duration chip for local videos; ▶ marker when duration unknown.
            val durMs = durations[item.path]
            duration.visibility = when {
                !MediaStoreHelper.isVideoName(nameOf(item)) -> View.GONE
                durMs != null -> {
                    duration.text = fmtDuration(durMs)
                    View.VISIBLE
                }
                else -> {
                    duration.text = "▶"
                    View.VISIBLE
                }
            }
            pcOverlay.visibility = if (item.local) View.GONE else View.VISIBLE
            return cell
        }

        private fun buildCell(cellSize: Int): LinearLayout {
            val cell = LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(1, 1, 1, 1)
            }
            val frame = FrameLayout(this@LibraryActivity).apply {
                layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
                background = Ui.rounded(this@LibraryActivity, Ui.SLATE_BG, Ui.R_TILE)
                clipToOutline = true
            }
            frame.addView(
                ImageView(this@LibraryActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            // Status badge, pinned bottom-left with a 5dp inset (mockup).
            frame.addView(
                TextView(this@LibraryActivity).apply {
                    textSize = 10.5f
                    typeface = Ui.font(700)
                    setPadding(
                        Ui.dp(this@LibraryActivity, 7), Ui.dp(this@LibraryActivity, 4),
                        Ui.dp(this@LibraryActivity, 7), Ui.dp(this@LibraryActivity, 4)
                    )
                    elevation = Ui.dp(this@LibraryActivity, 2).toFloat()
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                ).apply {
                    leftMargin = Ui.dp(this@LibraryActivity, 5)
                    bottomMargin = Ui.dp(this@LibraryActivity, 5)
                }
            )
            // Selection tick: brand circle with white ✓, top-right.
            frame.addView(
                TextView(this@LibraryActivity).apply {
                    text = "✓"
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = Ui.circleDrawable(this@LibraryActivity, Ui.BRAND_600)
                    visibility = View.GONE
                },
                FrameLayout.LayoutParams(
                    Ui.dp(this@LibraryActivity, 19), Ui.dp(this@LibraryActivity, 19),
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = Ui.dp(this@LibraryActivity, 5)
                    rightMargin = Ui.dp(this@LibraryActivity, 5)
                }
            )
            // Duration chip, bottom-right (mockup: dark scrim, radius 5).
            frame.addView(
                TextView(this@LibraryActivity).apply {
                    textSize = 9.5f
                    typeface = Ui.font(700)
                    setTextColor(Color.WHITE)
                    background = Ui.rounded(
                        this@LibraryActivity,
                        Color.argb(0x8C, 0x08, 0x18, 0x1E), 5
                    )
                    setPadding(
                        Ui.dp(this@LibraryActivity, 5), Ui.dp(this@LibraryActivity, 2),
                        Ui.dp(this@LibraryActivity, 5), Ui.dp(this@LibraryActivity, 2)
                    )
                    visibility = View.GONE
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.END
                ).apply {
                    rightMargin = Ui.dp(this@LibraryActivity, 5)
                    bottomMargin = Ui.dp(this@LibraryActivity, 5)
                }
            )
            // 仅PC有 dim + centered PC marker (mockup).
            val pcOverlay = LinearLayout(this@LibraryActivity).apply {
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(0x57, 0x26, 0x36, 0x42))
                visibility = View.GONE
                addView(TextView(this@LibraryActivity).apply {
                    text = "PC"
                    textSize = 12f
                    typeface = Ui.font(800)
                    setTextColor(Color.WHITE)
                    background = Ui.rounded(
                        this@LibraryActivity, Color.TRANSPARENT, 999, Color.WHITE, 1
                    )
                    setPadding(
                        Ui.dp(this@LibraryActivity, 8), Ui.dp(this@LibraryActivity, 4),
                        Ui.dp(this@LibraryActivity, 8), Ui.dp(this@LibraryActivity, 4)
                    )
                })
            }
            frame.addView(
                pcOverlay,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            cell.addView(frame)
            cell.addView(
                TextView(this@LibraryActivity).apply {
                    textSize = 11f
                    setTextColor(Ui.INK_2)
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(2, Ui.dp(this@LibraryActivity, 3), 2, 0)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            return cell
        }
    }

    private fun fmtDuration(ms: Long): String {
        val totalSec = ms / 1000
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    }

    companion object {
        private const val REQ_DELETE = 4711
    }
}
