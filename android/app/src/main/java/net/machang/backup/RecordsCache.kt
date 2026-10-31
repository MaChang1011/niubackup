package net.machang.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Last-known copy of the PC's backup records (design §6.2): written whenever
 * an fs LIST succeeds, read when the PC is unreachable — the UI labels it
 * 「记录截至 HH:MM」. Stored as JSON in the app files dir.
 */
object RecordsCache {
    private const val FILE_NAME = "records-cache.json"

    data class Cached(val records: List<FsClient.Record>, val fetchedAt: Long)

    fun save(ctx: Context, records: List<FsClient.Record>) {
        try {
            val arr = JSONArray()
            for (r in records) {
                arr.put(
                    JSONObject()
                        .put("path", r.path)
                        .put("size", r.size)
                        .put("sha256", r.sha256 ?: JSONObject.NULL)
                        .put("backed_at", r.backedAt)
                        .put("integrity", r.integrity ?: JSONObject.NULL)
                        .put("thumb", r.thumb)
                )
            }
            val root = JSONObject()
                .put("fetched_at", System.currentTimeMillis())
                .put("files", arr)
            File(ctx.filesDir, FILE_NAME).writeText(root.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            // Cache write failure must not break the caller.
        }
    }

    fun load(ctx: Context): Cached? {
        val f = File(ctx.filesDir, FILE_NAME)
        if (!f.isFile) return null
        return try {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            val arr = root.getJSONArray("files")
            val out = ArrayList<FsClient.Record>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += FsClient.Record().apply {
                    path = o.getString("path")
                    size = o.optLong("size", -1)
                    sha256 = if (o.isNull("sha256")) null else o.optString("sha256")
                    backedAt = o.optLong("backed_at", 0)
                    integrity = if (o.isNull("integrity")) null else o.optString("integrity")
                    thumb = o.optBoolean("thumb")
                }
            }
            Cached(out, root.optLong("fetched_at", 0))
        } catch (t: Throwable) {
            null
        }
    }
}
