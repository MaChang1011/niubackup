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

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun log(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        Log.i(TAG, msg)
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
