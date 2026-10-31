package net.machang.backup

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * MediaStore operations for the M4c library screen: locating a DCIM file's
 * media URI (scoped storage makes raw deletes unreliable on Android 11+),
 * building a user-consented batch delete request (§6.4 free-up-space), and
 * publishing a downloaded-back file into DCIM/NiuBackupRestore (§6.3).
 */
object MediaStoreHelper {

    private fun collections(): List<Uri> = listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    )

    fun findMediaUri(ctx: Context, file: File): Uri? {
        val path = file.absolutePath
        for (base in collections()) {
            try {
                ctx.contentResolver.query(
                    base, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        return Uri.withAppendedPath(base, c.getLong(0).toString())
                    }
                }
            } catch (t: Throwable) {
                // Collection not applicable for this file type; try the next.
            }
        }
        return null
    }

    /**
     * Batch delete for Android 11+: returns a PendingIntent the Activity
     * launches for the system consent dialog, or null when nothing deletable
     * was found. On older versions performs the deletes inline and returns an
     * already-"handled" null after reporting via [onDone].
     */
    fun createDeleteRequest(ctx: Context, files: List<File>): PendingIntent? {
        val uris = files.mapNotNull { findMediaUri(ctx, it) }
        if (uris.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return MediaStore.createDeleteRequest(ctx.contentResolver, uris)
        }
        for (u in uris) {
            try {
                ctx.contentResolver.delete(u, null, null)
            } catch (t: Throwable) {
                // RecoverableSecurityException pre-30 needs an Activity loop;
                // the caller re-checks existence afterwards either way.
            }
        }
        return null
    }

    /** Insert a downloaded file into DCIM/NiuBackupRestore (§6.3). */
    fun publishToRestoreFolder(ctx: Context, src: File, displayName: String): Uri? {
        val isVideo = isVideoName(displayName)
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/NiuBackupRestore")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(collection, values) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        ctx.contentResolver.update(uri, values, null, null)
        return uri
    }

    fun isVideoName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") ||
            n.endsWith(".webm") || n.endsWith(".3gp") || n.endsWith(".avi")
    }

    fun isAudioName(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") ||
            n.endsWith(".wav") || n.endsWith(".ogg")
    }

    fun isPlayableName(name: String): Boolean = isVideoName(name) || isAudioName(name)
}
