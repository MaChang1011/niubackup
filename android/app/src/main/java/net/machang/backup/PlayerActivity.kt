package net.machang.backup

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView

/**
 * Playback screen (design §6.3): streams a PC-side copy through the direct
 * tunnel via [FsDataSource] — ExoPlayer issues ranged reads, the FsClient
 * answers them with fs READ requests. The phone never downloads the file.
 */
class PlayerActivity : Activity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: ""
        val size = intent.getLongExtra(EXTRA_SIZE, -1)
        val title = TextView(this).apply {
            text = "播放（PC 流式）: ${path.substringAfterLast('/')}"
            setPadding(24, 24, 24, 8)
        }
        val playerView = PlayerView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(
                playerView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
        }
        setContentView(root)

        val fsFactory: () -> FsClient = {
            PcConn.createFsClient(this) ?: throw IllegalStateException("无设备身份，先登录或填写开发者令牌")
        }
        val mediaItem = MediaItem.fromUri(Uri.parse("niubackup://fs"))
        val source = ProgressiveMediaSource.Factory(
            FsDataSource.Factory(fsFactory, path, size)
        ).createMediaSource(mediaItem)
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.setMediaSource(source)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "fs_path"
        const val EXTRA_SIZE = "fs_size"
    }
}
