package net.machang.backup

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide log bus: the service and background backup threads log here;
 * MainActivity registers a listener to render lines into its scrolling TextView.
 * Lines are also mirrored to logcat under the "MachangBackup" tag.
 */
object AppLog {
    private const val TAG = "MachangBackup"
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val buffer = StringBuilder()
    private const val MAX_CHARS = 60_000

    /** In-app cap for engine error dumps (see [logEngineError]). */
    private const val UI_MAX_LINES = 6

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun log(msg: String) {
        emit(logcatText = msg, uiText = msg)
    }

    /**
     * Core/engine error text (e.g. the EasyTier core's TOML parse dump,
     * which runs to hundreds of lines): the FULL text still goes to
     * logcat, but the in-app log only shows the first [UI_MAX_LINES]
     * lines plus a truncation marker — a raw core dump must never flood
     * the UI log.
     */
    fun logEngineError(msg: String) {
        val lines = msg.split('\n')
        val uiText = if (lines.size > UI_MAX_LINES) {
            lines.take(UI_MAX_LINES).joinToString("\n") +
                "\n…（错误过长已截断，完整内容见 logcat）"
        } else {
            msg
        }
        emit(logcatText = msg, uiText = uiText)
    }

    private fun emit(logcatText: String, uiText: String) {
        val line = "[${fmt.format(Date())}] $uiText"
        Log.i(TAG, logcatText)
        synchronized(buffer) {
            buffer.append(line).append('\n')
            if (buffer.length > MAX_CHARS) {
                buffer.delete(0, buffer.length - MAX_CHARS)
            }
        }
        listener?.invoke(line)
    }

    fun snapshot(): String = synchronized(buffer) { buffer.toString() }
}
