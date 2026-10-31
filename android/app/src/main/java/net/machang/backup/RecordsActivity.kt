package net.machang.backup

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Backup records page (design §6.2): the PC's record DB, fetched live via
 * fs LIST with THUMB thumbnails (memory + disk LRU). When the PC is
 * unreachable, the last fetched copy is shown and labeled 「记录截至 HH:MM」.
 * Tapping a record plays it (video/audio) or downloads it back (§6.3).
 */
class RecordsActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var listView: ListView
    private var records: List<FsClient.Record> = emptyList()
    private val io = Executors.newFixedThreadPool(3)
    private val memCache = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = Ui.page(this, Ui.Page.RECORDS)
        val col = page.content
        col.addView(Ui.header(this, "记录", "电脑上的备份记录"))
        statusView = TextView(this).apply {
            text = "正在读取电脑上的备份记录…"
            textSize = 13.5f
            setTextColor(Ui.TEXT_SUB)
            setPadding(Ui.dp(this@RecordsActivity, 16), 4, Ui.dp(this@RecordsActivity, 16), 8)
        }
        listView = ListView(this).apply {
            adapter = RecordAdapter()
            setOnItemClickListener { _, _, pos, _ -> onRecordTap(records[pos]) }
        }
        col.addView(statusView)
        col.addView(
            listView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        createdOnce = true
        loadRecords()
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

    private fun loadRecords() {
        thread(name = "records-load") {
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
                main.post { show(list, null) }
            } else {
                val cached = RecordsCache.load(this)
                main.post {
                    if (cached != null) {
                        show(cached.records, cached.fetchedAt)
                    } else {
                        statusView.text = "电脑不可达，且暂无缓存记录。先组网并完成一次备份。"
                    }
                }
            }
        }
    }

    private fun show(list: List<FsClient.Record>, staleAt: Long?) {
        records = list
        val label = when {
            list.isEmpty() -> "电脑上暂无备份记录。先在「备份」页完成一次备份。"
            staleAt == null -> "实时记录 · 共 ${list.size} 个文件"
            else -> "电脑离线 · 记录截至 ${fmtTime(staleAt)} · 共 ${list.size} 个文件"
        }
        statusView.text = label
        (listView.adapter as BaseAdapter).notifyDataSetChanged()
        for (r in list) if (r.thumb) prefetchThumb(r)
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
        val options = mutableListOf<String>()
        if (MediaStoreHelper.isPlayableName(name)) options += "播放（流式）"
        options += "下载回手机"
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options.toTypedArray()) { _, which ->
                if (options[which].startsWith("播放")) {
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_PATH, r.path)
                            .putExtra(PlayerActivity.EXTRA_SIZE, r.size)
                    )
                } else {
                    downloadBack(r)
                }
            }
            .show()
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
        override fun getCount(): Int = records.size
        override fun getItem(position: Int): Any = records[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val r = records[position]
            val root = (convertView as? LinearLayout) ?: LinearLayout(this@RecordsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 10, 16, 10)
                addView(
                    ImageView(this@RecordsActivity),
                    LinearLayout.LayoutParams(140, 140)
                )
                addView(
                    TextView(this@RecordsActivity).apply {
                        setPadding(20, 0, 0, 0)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
            }
            val img = root.getChildAt(0) as ImageView
            val txt = root.getChildAt(1) as TextView
            val bmp = if (r.thumb) thumbBitmap(r) else null
            if (bmp != null) {
                img.setImageBitmap(bmp)
            } else {
                img.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            val integrity = when (r.integrity) {
                "ok" -> "完整"
                "suspect" -> "存疑"
                else -> r.integrity ?: ""
            }
            txt.text = "${r.path}\n${fmtSize(r.size)} · ${fmtDate(r.backedAt)} · $integrity"
            return root
        }
    }

    private fun fmtTime(ms: Long): String =
        SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

    private fun fmtDate(ms: Long): String =
        if (ms <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

    private fun fmtSize(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    }
}
