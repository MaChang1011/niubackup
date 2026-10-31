package net.machang.backup

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/**
 * File library (design §6.1): DCIM merged with the backup state.
 *
 * Every row carries one of four badges, merged from three sources:
 *   未备份 — local file with no matching verified receipt / PC record;
 *   备份中 x% — the file a running session is currently sending;
 *   已备份 — local file whose sha256 appears in a signature-verified stored
 *             receipt or in the PC's live LIST with integrity ok;
 *   仅PC有 — in the receipts / PC records but no longer on the phone
 *             (restorable via §6.3 download-back, streamable via playback).
 *
 * Selecting 已备份 rows and tapping 「释放空间」 runs the §6.4 free-up-space
 * flow: receipt-gated, live integrity cross-check when the PC is reachable,
 * explicit confirmation, then a MediaStore delete with system consent.
 */
class LibraryActivity : Activity() {

    enum class Status(val label: String, val color: Int) {
        NOT_BACKED("未备份", Color.rgb(0xB0, 0x30, 0x30)),
        BACKING_UP("备份中", Color.rgb(0xB0, 0x70, 0x00)),
        BACKED("已备份", Color.rgb(0x1E, 0x7A, 0x34)),
        PC_ONLY("仅PC有", Color.rgb(0x44, 0x55, 0xAA))
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

    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var freeButton: Button
    private lateinit var listView: ListView
    private var items: List<Item> = emptyList()
    private val selected = HashSet<String>()
    private val downloadPct = HashMap<String, Int>()
    private var liveOk: Boolean = false
    private var liveByPath: Map<String, FsClient.Record> = emptyMap()
    private var poller: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this).apply {
            text = "正在扫描 DCIM 并合并备份状态…"
            setPadding(24, 24, 24, 12)
        }
        val refreshBtn = Button(this).apply {
            text = "刷新"
            setOnClickListener { refresh() }
        }
        freeButton = Button(this).apply {
            text = "释放空间"
            setOnClickListener { startFreeUpSpace() }
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(refreshBtn)
            addView(freeButton)
        }
        listView = ListView(this).apply {
            adapter = LibraryAdapter()
            setOnItemClickListener { _, _, pos, _ -> onItemTap(items[pos]) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
            addView(btnRow)
            addView(
                listView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
        }
        setContentView(root)
        refresh()
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

    override fun onDestroy() {
        poller?.let { main.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun dcimDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)

    private fun refresh() {
        statusView.text = "正在扫描 DCIM 并合并备份状态…"
        thread(name = "library-refresh") {
            val scanner = BackupClient(BackupClient.Logger { }, filesDir)
            val localEntries = try {
                scanner.scan(dcimDir())
            } catch (t: Throwable) {
                emptyList()
            }
            val receipts = ReceiptStore.loadMerged(filesDir)
            val backedShas = HashSet<String>()
            for (rf in receipts.values) backedShas += rf.sha256

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
            }

            val merged = ArrayList<Item>()
            val localPaths = HashSet<String>()
            for (e in localEntries) {
                localPaths += e.path
                val receiptHit = receipts[e.path]?.sha256 == e.sha
                val liveHit = liveMap[e.path]?.let {
                    it.sha256 == e.sha && "ok" == it.integrity
                } == true
                val shaHit = backedShas.contains(e.sha)
                merged += Item(
                    e.path, e.size, e.sha, local = true,
                    status = if (receiptHit || liveHit || shaHit) {
                        Status.BACKED
                    } else {
                        Status.NOT_BACKED
                    },
                    receiptVerified = receiptHit,
                    liveIntegrity = liveMap[e.path]?.integrity
                )
            }
            val pcOnlyPaths = (receipts.keys + liveMap.keys).filter { it !in localPaths }
            for (p in pcOnlyPaths.sorted()) {
                val rec = liveMap[p]
                val rf = receipts[p]
                merged += Item(
                    p, rec?.size ?: rf?.size ?: -1, rec?.sha256 ?: rf?.sha256,
                    local = false, status = Status.PC_ONLY,
                    receiptVerified = rf != null, liveIntegrity = rec?.integrity
                )
            }
            main.post {
                items = merged
                liveOk = live != null
                liveByPath = liveMap
                selected.retainAll(merged.filter { it.status == Status.BACKED }.map { it.path })
                statusView.text =
                    "共 ${merged.size} 项（本机 ${localEntries.size}，仅PC ${merged.size - localEntries.size}）· " +
                        if (liveOk) "电脑记录为实时" else "电脑离线（按回执判断）"
                (listView.adapter as BaseAdapter).notifyDataSetChanged()
                updateFreeButton()
            }
        }
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
        if (changed) (listView.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun updateFreeButton() {
        freeButton.text = if (selected.isEmpty()) "释放空间" else "释放空间（已选 ${selected.size}）"
    }

    private fun onItemTap(item: Item) {
        when (item.status) {
            Status.BACKED -> {
                if (!selected.add(item.path)) selected.remove(item.path)
                (listView.adapter as BaseAdapter).notifyDataSetChanged()
                updateFreeButton()
            }
            Status.PC_ONLY -> {
                val name = item.path.substringAfterLast('/')
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
            else -> {
                Toast.makeText(this, "该文件尚未备份，去主页点「立即备份」", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadBack(item: Item) {
        thread(name = "library-download") {
            try {
                val fs = PcConn.createFsClient(this) ?: throw IllegalStateException("无设备身份")
                val tmp = fs.download(item.path, item.size, File(cacheDir, "restore-tmp")) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (downloadPct[item.path] != pct) {
                        downloadPct[item.path] = pct
                        main.post { (listView.adapter as BaseAdapter).notifyDataSetChanged() }
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
                    (listView.adapter as BaseAdapter).notifyDataSetChanged()
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
            // Gate 1 (always): a signature-verified receipt must vouch for the
            // file with the same sha256 as the current local copy.
            val receipts = ReceiptStore.loadMerged(filesDir)
            val receiptOk = candidates.filter { c ->
                val rf = receipts[c.path]
                rf != null && rf.sha256 == c.sha
            }
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
                        r != null && "ok" == r.integrity && r.sha256 == c.sha
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

    // --------------------------------------------------------------- adapter

    private inner class LibraryAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val root = (convertView as? LinearLayout) ?: LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 10, 16, 10)
                addView(CheckBox(this@LibraryActivity))
                addView(
                    TextView(this@LibraryActivity),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(TextView(this@LibraryActivity))
            }
            val check = root.getChildAt(0) as CheckBox
            val main = root.getChildAt(1) as TextView
            val badge = root.getChildAt(2) as TextView
            check.visibility = if (item.status == Status.BACKED) View.VISIBLE else View.INVISIBLE
            check.isChecked = item.path in selected
            main.text = "${item.path}\n${fmtSize(item.size)}"
            val pct = downloadPct[item.path]
            badge.text = when {
                pct != null -> "下载中 $pct%"
                item.status == Status.BACKING_UP -> "备份中 ${BackupProgress.percent}%"
                else -> item.status.label
            }
            badge.setTextColor(
                if (pct != null) Status.BACKING_UP.color else item.status.color
            )
            return root
        }
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

