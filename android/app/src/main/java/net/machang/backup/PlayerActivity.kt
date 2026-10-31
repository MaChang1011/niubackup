package net.machang.backup

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import java.io.File
import kotlin.concurrent.thread

/**
 * Playback screen (design §6.3 + approved mockup v1.1): streams a PC-side
 * copy through the direct tunnel via [FsDataSource] — ExoPlayer issues
 * ranged reads, the FsClient answers them with fs READ requests. The phone
 * never downloads the file for playback.
 *
 * Dark immersive layout per the mockup: top bar (back circle + title +
 * 已备份 badge), 16:9 stage, filename/meta, the persistent 直连流式 chip +
 * stream note card, accent seek bar with times, white-circle transport
 * (pause/play flanked by ±10s), and a bottom action bar whose only primary
 * action is the amber 下载回来 (label identical to the 记录 page) plus a
 * dark-ghost 详情.
 */
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null
    private lateinit var seekFill: View
    private lateinit var seekKnob: View
    private lateinit var seekArea: FrameLayout
    private lateinit var elapsedView: TextView
    private lateinit var totalView: TextView
    private lateinit var playPauseBtn: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: ""
        val size = intent.getLongExtra(EXTRA_SIZE, -1)
        val fileName = path.substringAfterLast('/')
        // Local playback (library tap on a backed-up video that is still on
        // the phone): play the on-device file directly instead of streaming
        // the PC copy. Falls back to streaming when the file has vanished.
        val localFile = intent.getStringExtra(EXTRA_LOCAL_FILE)
            ?.let { File(it) }?.takeIf { it.isFile }
        Ui.statusBar(this, Ui.VIEW_BG)
        // Same edge-to-edge opt-out as the tab scaffold (Ui.page): the
        // insets listener below then owns the top bar's status clearance.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.VIEW_BG)
        }
        setContentView(root)

        // ---- top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Ui.dp(this@PlayerActivity, 14), Ui.dp(this@PlayerActivity, 10),
                Ui.dp(this@PlayerActivity, 14), Ui.dp(this@PlayerActivity, 10)
            )
        }
        top.addView(backCircle { finish() })
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(this@PlayerActivity, 10), 0, 0, 0)
        }
        titleCol.addView(TextView(this).apply {
            text = "视频播放"
            textSize = 13f
            typeface = Ui.font(800)
            setTextColor(Ui.VIEW_TEXT)
        })
        titleCol.addView(TextView(this).apply {
            text = if (localFile != null) "来自这台手机 · 本地文件" else "来自你的电脑 · 备份记录"
            textSize = 11f
            setTextColor(Ui.VIEW_SUB)
        })
        top.addView(titleCol, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        top.addView(Ui.badge(this, "已备份", Ui.StatusKind.OK))
        root.addView(top)

        // Edge-to-edge placement (inset-aware, chosen over transient
        // immersive flags so the transport chrome stays put): the dark
        // stage still bleeds behind the system bars, but the top bar
        // clears the status bar and the bottom actions clear the
        // gesture bar. API 30+; below that the framework fits the
        // window and the base paddings apply.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top.setPadding(
                    Ui.dp(this, 14), Ui.dp(this, 10) + bars.top,
                    Ui.dp(this, 14), Ui.dp(this, 10)
                )
                root.setPadding(0, 0, 0, bars.bottom)
                insets
            }
        }

        // ---- 16:9 stage (PlayerView, custom transport below)
        val playerView = PlayerView(this).apply {
            useController = false
            setBackgroundColor(Color.BLACK)
        }
        root.post {
            val w = root.width
            playerView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, w * 9 / 16
            )
        }
        root.addView(playerView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0
        ))

        // ---- body
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Ui.dp(this@PlayerActivity, 16), Ui.dp(this@PlayerActivity, 14),
                Ui.dp(this@PlayerActivity, 16), 0
            )
        }
        root.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        body.addView(TextView(this).apply {
            text = fileName
            textSize = 15.5f
            typeface = Ui.font(900)
            setTextColor(Ui.VIEW_TEXT)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        body.addView(TextView(this).apply {
            text = if (localFile != null) {
                "${fmtSize(size)} · 手机里的原文件 · 本地播放"
            } else {
                "${fmtSize(size)} · 电脑上的备份副本 · 经组网直连播放"
            }
            textSize = 11.5f
            setTextColor(Ui.VIEW_SUB)
            setPadding(0, Ui.dp(this@PlayerActivity, 3), 0, 0)
        })

        // ---- stream note card (persistent, mockup)
        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(
                this@PlayerActivity,
                Color.argb(0x29, 0x12, 0x80, 0x9A), Ui.R_BTN,
                Color.argb(0x47, 0x7E, 0xC4, 0xD6), 1
            )
            setPadding(
                Ui.dp(this@PlayerActivity, 12), Ui.dp(this@PlayerActivity, 10),
                Ui.dp(this@PlayerActivity, 12), Ui.dp(this@PlayerActivity, 10)
            )
        }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.pillDrawable(this@PlayerActivity, Ui.BRAND_500)
            setPadding(
                Ui.dp(this@PlayerActivity, 9), Ui.dp(this@PlayerActivity, 5),
                Ui.dp(this@PlayerActivity, 9), Ui.dp(this@PlayerActivity, 5)
            )
        }
        chip.addView(
            View(this).apply {
                background = Ui.circleDrawable(this@PlayerActivity, Color.rgb(0x7E, 0xE2, 0xB0))
            },
            LinearLayout.LayoutParams(
                Ui.dp(this@PlayerActivity, 6), Ui.dp(this@PlayerActivity, 6)
            ).apply { rightMargin = Ui.dp(this@PlayerActivity, 5) }
        )
        chip.addView(TextView(this).apply {
            text = if (localFile != null) "本地播放" else "直连流式"
            textSize = 11f
            typeface = Ui.font(800)
            setTextColor(Color.WHITE)
        })
        chipRow.addView(chip)
        note.addView(chipRow)
        note.addView(TextView(this).apply {
            text = if (localFile != null) {
                "直接播放手机里的原文件 — 不耗流量、不经组网；这个文件也已经备份在你的电脑上。"
            } else {
                "经产品组网从你的电脑直连播放 — 不经服务器中转，不先下载完整文件；拖动进度时会短暂缓冲。"
            }
            textSize = 11.5f
            setTextColor(Color.rgb(0xC6, 0xDE, 0xE5))
            setLineSpacing(0f, 1.55f)
            setPadding(0, Ui.dp(this@PlayerActivity, 7), 0, 0)
        })
        body.addView(note, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Ui.dp(this@PlayerActivity, 13) })

        // ---- seek bar (accent fill + white knob, mockup)
        seekArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this@PlayerActivity, 22)
            ).apply { topMargin = Ui.dp(this@PlayerActivity, 10) }
        }
        val track = View(this).apply {
            background = Ui.pillDrawable(
                this@PlayerActivity, Color.argb(0x2E, 0xFF, 0xFF, 0xFF)
            )
        }
        seekArea.addView(track, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(this@PlayerActivity, 5)
        ).apply { gravity = Gravity.CENTER_VERTICAL })
        seekFill = View(this).apply {
            background = Ui.pillDrawable(this@PlayerActivity, Ui.ACCENT)
        }
        seekArea.addView(seekFill, FrameLayout.LayoutParams(
            0, Ui.dp(this@PlayerActivity, 5)
        ).apply { gravity = Gravity.CENTER_VERTICAL })
        seekKnob = View(this).apply {
            background = Ui.circleDrawable(this@PlayerActivity, Color.WHITE)
            elevation = Ui.dp(this@PlayerActivity, 2).toFloat()
        }
        seekArea.addView(seekKnob, FrameLayout.LayoutParams(
            Ui.dp(this@PlayerActivity, 15), Ui.dp(this@PlayerActivity, 15)
        ).apply { gravity = Gravity.CENTER_VERTICAL })
        seekArea.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                val frac = (ev.x / v.width).coerceIn(0f, 1f)
                val p = player
                if (p != null && p.duration > 0) {
                    p.seekTo((frac * p.duration).toLong())
                    updateSeekUi()
                }
                true
            } else {
                false
            }
        }
        body.addView(seekArea)

        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        elapsedView = TextView(this).apply {
            text = "已播 00:00"
            textSize = 11f
            setTextColor(Ui.VIEW_SUB)
        }
        times.addView(elapsedView, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        totalView = TextView(this).apply {
            text = "总时长 00:00"
            textSize = 11f
            setTextColor(Ui.VIEW_SUB)
            gravity = Gravity.END
        }
        times.addView(totalView, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))
        body.addView(times)

        // ---- transport: -10s / main circle / +10s
        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(this@PlayerActivity, 10), 0, 0)
        }
        transport.addView(TextView(this).apply {
            text = "−10s"
            textSize = 13f
            typeface = Ui.font(700)
            setTextColor(Color.rgb(0xD8, 0xE8, 0xEC))
            isClickable = true
            setPadding(
                Ui.dp(this@PlayerActivity, 14), Ui.dp(this@PlayerActivity, 10),
                Ui.dp(this@PlayerActivity, 14), Ui.dp(this@PlayerActivity, 10)
            )
            setOnClickListener {
                player?.let { it.seekTo(maxOf(0, it.currentPosition - 10_000)) }
            }
        })
        playPauseBtn = TextView(this).apply {
            text = "❚❚"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Ui.BRAND_800)
            background = Ui.circleDrawable(this@PlayerActivity, Color.WHITE)
            isClickable = true
            setOnClickListener {
                val p = player ?: return@setOnClickListener
                if (p.isPlaying) p.pause() else p.play()
                playPauseBtn.text = if (p.isPlaying) "▶" else "❚❚"
            }
        }
        transport.addView(playPauseBtn, LinearLayout.LayoutParams(
            Ui.dp(this@PlayerActivity, 52), Ui.dp(this@PlayerActivity, 52)
        ).apply {
            leftMargin = Ui.dp(this@PlayerActivity, 26)
            rightMargin = Ui.dp(this@PlayerActivity, 26)
        })
        transport.addView(TextView(this).apply {
            text = "+10s"
            textSize = 13f
            typeface = Ui.font(700)
            setTextColor(Color.rgb(0xD8, 0xE8, 0xEC))
            isClickable = true
            setPadding(
                Ui.dp(this@PlayerActivity, 14), Ui.dp(this@PlayerActivity, 10),
                Ui.dp(this@PlayerActivity, 14), Ui.dp(this@PlayerActivity, 10)
            )
            setOnClickListener {
                player?.let { p ->
                    val target = p.currentPosition + 10_000
                    p.seekTo(if (p.duration > 0) minOf(target, p.duration) else target)
                }
            }
        })
        body.addView(transport)

        // ---- bottom actions: amber 下载回来 (flex) + 详情 dark ghost
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(0x08, 0xFF, 0xFF, 0xFF))
            setPadding(
                Ui.dp(this@PlayerActivity, 16), Ui.dp(this@PlayerActivity, 12),
                Ui.dp(this@PlayerActivity, 16), Ui.dp(this@PlayerActivity, 20)
            )
        }
        if (localFile == null) {
            // 下载回来 only makes sense when playing the PC copy; the file
            // is already on the phone in local mode.
            val downloadBtn = Ui.accentButton(this, "下载回来").apply {
                textSize = 13.5f
                setOnClickListener { downloadBack(path, size) }
            }
            actions.addView(downloadBtn, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
        }
        val detailBtn = TextView(this).apply {
            text = "详情"
            textSize = 13f
            typeface = Ui.font(700)
            gravity = Gravity.CENTER
            setTextColor(Ui.VIEW_TEXT)
            background = Ui.rounded(
                this@PlayerActivity, Color.argb(0x17, 0xFF, 0xFF, 0xFF), Ui.R_BTN
            )
            isClickable = true
            setPadding(
                Ui.dp(this@PlayerActivity, 14), Ui.dp(this@PlayerActivity, 12),
                Ui.dp(this@PlayerActivity, 14), Ui.dp(this@PlayerActivity, 12)
            )
            setOnClickListener {
                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle(fileName)
                    .setMessage(
                        if (localFile != null) {
                            "路径: $path\n大小: ${fmtSize(size)}\n来源: 这台手机（${localFile.absolutePath}）\n方式: 本地播放"
                        } else {
                            "路径: $path\n大小: ${fmtSize(size)}\n来源: 你的电脑（${PcConn.pcHost(this@PlayerActivity)}）\n方式: 直连流式播放（fs READ 分段读取）"
                        }
                    )
                    .setPositiveButton("好", null)
                    .show()
            }
        }
        actions.addView(detailBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = Ui.dp(this@PlayerActivity, 10) })
        root.addView(actions)

        // ---- player: local file when the phone still holds it, else the
        // streaming stack (FsDataSource over the direct tunnel).
        val source = if (localFile != null) {
            ProgressiveMediaSource.Factory(
                DefaultDataSource.Factory(this)
            ).createMediaSource(MediaItem.fromUri(Uri.fromFile(localFile)))
        } else {
            val fsFactory: () -> FsClient = {
                PcConn.createFsClient(this)
                    ?: throw IllegalStateException("无设备身份，先登录或填写开发者令牌")
            }
            ProgressiveMediaSource.Factory(
                FsDataSource.Factory(fsFactory, path, size)
            ).createMediaSource(MediaItem.fromUri(Uri.parse("niubackup://fs")))
        }
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playPauseBtn.text = if (isPlaying) "❚❚" else "▶"
                }
            })
        }
        val tick = object : Runnable {
            override fun run() {
                updateSeekUi()
                handler.postDelayed(this, 500)
            }
        }
        ticker = tick
        handler.post(tick)
    }

    private fun updateSeekUi() {
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition
        elapsedView.text = "已播 ${fmtClock(pos)}"
        totalView.text = "总时长 ${if (dur > 0) fmtClock(dur) else "00:00"}"
        val frac = if (dur > 0) pos.toFloat() / dur else 0f
        seekArea.post {
            val w = seekArea.width
            val fillW = (w * frac).toInt()
            seekFill.layoutParams = (seekFill.layoutParams as FrameLayout.LayoutParams).apply {
                width = fillW
            }
            seekKnob.layoutParams = (seekKnob.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = (fillW - Ui.dp(this@PlayerActivity, 7)).coerceAtLeast(0)
            }
        }
    }

    private fun downloadBack(path: String, size: Long) {
        Toast.makeText(this, "开始下载 ${path.substringAfterLast('/')}", Toast.LENGTH_SHORT).show()
        thread(name = "player-download") {
            try {
                val fs = PcConn.createFsClient(this) ?: throw IllegalStateException("无设备身份")
                val tmp = fs.download(path, size, File(cacheDir, "restore-tmp"), null)
                fs.close()
                val uri = MediaStoreHelper.publishToRestoreFolder(
                    this, tmp, path.substringAfterLast('/')
                )
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (uri != null) "已保存到 DCIM/NiuBackupRestore" else "保存失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "下载失败: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun backCircle(onClick: () -> Unit): TextView = TextView(this).apply {
        text = "‹"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = Ui.circleDrawable(
            this@PlayerActivity, Color.argb(0x1A, 0xFF, 0xFF, 0xFF)
        )
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            Ui.dp(this@PlayerActivity, 32), Ui.dp(this@PlayerActivity, 32)
        )
        setOnClickListener { onClick() }
    }

    override fun onDestroy() {
        ticker?.let { handler.removeCallbacks(it) }
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun fmtClock(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes < 0 -> "未知大小"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        else -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    }

    companion object {
        const val EXTRA_PATH = "fs_path"
        const val EXTRA_SIZE = "fs_size"

        /** Absolute path of a local copy to play directly (optional). */
        const val EXTRA_LOCAL_FILE = "local_file"
    }
}
