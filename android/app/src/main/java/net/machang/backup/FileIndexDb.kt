package net.machang.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * FileIndexDb — the phone-local index of DCIM file state (user-requested
 * replacement for scan-on-open in the library page).
 *
 * One row per DCIM-relative path:
 *   path TEXT PK, size, mtime — last known local metadata;
 *   sha256 TEXT (nullable) — known content hash, when any source has one
 *     (hashed-mode scan, a stream-mode DONE verdict, a verified receipt,
 *     or the PC's fs LIST);
 *   status — STATUS_BACKED once any trusted source confirms the PC holds
 *     this exact (path, size, mtime);
 *   receipt_verified — a signature-verified receipt vouches for this row's
 *     (path, size, sha256); the §6.4 free-up-space gate reads this;
 *   pc_integrity — the PC's latest fs LIST integrity verdict for the path.
 *
 * Writers: the backup engine (via [FileIndexSink], fed from BackupClient's
 * session hooks) and the library page's metadata-only MediaStore diff.
 * Readers: the library page (renders from this index in milliseconds —
 * it never hashes or reads file content).
 *
 * Every method is failure-isolated: any SQLite problem degrades to a
 * no-op / empty result. The index is a cache of state that receipts and
 * the PC can always re-derive, so losing it must never break a backup
 * or a page.
 */
class FileIndexDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class Row(
        val path: String,
        val size: Long,
        val mtime: Long,
        val sha256: String?,
        val status: Int,
        val receiptVerified: Boolean,
        val pcIntegrity: String?
    )

    /** Metadata event for batch upserts (sha256 null = unknown). */
    data class Meta(val path: String, val size: Long, val mtime: Long, val sha256: String?)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS file_index (" +
                "path TEXT PRIMARY KEY, " +
                "size INTEGER NOT NULL, " +
                "mtime INTEGER NOT NULL, " +
                "sha256 TEXT, " +
                "status INTEGER NOT NULL DEFAULT 0, " +
                "receipt_verified INTEGER NOT NULL DEFAULT 0, " +
                "pc_integrity TEXT, " +
                "updated_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 only so far; the index is a rebuildable cache, so a future
        // schema bump may simply drop and recreate.
        db.execSQL("DROP TABLE IF EXISTS file_index")
        onCreate(db)
    }

    // ------------------------------------------------------------ reading

    /** All rows keyed by path (path order). Empty on any failure. */
    fun loadAll(): Map<String, Row> {
        val out = LinkedHashMap<String, Row>()
        try {
            val db = readableDatabase
            db.query(
                "file_index",
                arrayOf("path", "size", "mtime", "sha256", "status", "receipt_verified", "pc_integrity"),
                null, null, null, null, "path"
            )?.use { c ->
                while (c.moveToNext()) {
                    val row = Row(
                        path = c.getString(0),
                        size = c.getLong(1),
                        mtime = c.getLong(2),
                        sha256 = if (c.isNull(3)) null else c.getString(3),
                        status = c.getInt(4),
                        receiptVerified = c.getInt(5) != 0,
                        pcIntegrity = if (c.isNull(6)) null else c.getString(6)
                    )
                    out[row.path] = row
                }
            }
        } catch (t: Throwable) {
            // Degrade to "no index": callers fall back to receipts/fs only.
        }
        return out
    }

    // ------------------------------------------------------------ writing

    /**
     * Metadata upserts in one transaction. A row whose (size, mtime) is
     * unchanged only gains a newly learned sha256; a row whose size or
     * mtime changed is a different file version, so its backup evidence
     * (sha, status, receipt flag, PC verdict) is reset.
     */
    fun upsertMetaBatch(metas: List<Meta>) {
        if (metas.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                for (m in metas) upsertMetaRow(db, m, now)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            // Index writes are best effort (see class doc).
        }
    }

    private fun upsertMetaRow(db: SQLiteDatabase, m: Meta, now: Long) {
        val existing = queryRow(db, m.path)
        if (existing == null) {
            db.insert("file_index", null, ContentValues().apply {
                put("path", m.path)
                put("size", m.size)
                put("mtime", m.mtime)
                put("sha256", m.sha256)
                put("status", STATUS_NOT_BACKED)
                put("receipt_verified", 0)
                putNull("pc_integrity")
                put("updated_at", now)
            })
            return
        }
        if (existing.size == m.size && existing.mtime == m.mtime) {
            if (m.sha256 != null && m.sha256 != existing.sha256) {
                db.update("file_index", ContentValues().apply {
                    put("sha256", m.sha256)
                    put("updated_at", now)
                }, "path=?", arrayOf(m.path))
            }
            return
        }
        // Changed on disk: previous evidence describes another version.
        db.update("file_index", ContentValues().apply {
            put("size", m.size)
            put("mtime", m.mtime)
            put("sha256", m.sha256)
            put("status", STATUS_NOT_BACKED)
            put("receipt_verified", 0)
            putNull("pc_integrity")
            put("updated_at", now)
        }, "path=?", arrayOf(m.path))
    }

    /**
     * The PC confirmed it holds this exact (path, size, mtime): a DONE
     * verdict, or a receiver skip (its stored copy matched size+mtime
     * with integrity ok). [sha256] is the confirmed content hash when the
     * session knows it (hashed mode / stream DONE), null for stream-mode
     * skips.
     */
    fun markBacked(path: String, size: Long, mtime: Long, sha256: String?) {
        try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val existing = queryRow(db, path)
            when {
                existing == null -> db.insert("file_index", null, ContentValues().apply {
                    put("path", path)
                    put("size", size)
                    put("mtime", mtime)
                    put("sha256", sha256)
                    put("status", STATUS_BACKED)
                    put("receipt_verified", 0)
                    putNull("pc_integrity")
                    put("updated_at", now)
                })
                existing.size == size && existing.mtime == mtime ->
                    db.update("file_index", ContentValues().apply {
                        put("status", STATUS_BACKED)
                        put("sha256", sha256 ?: existing.sha256)
                        put("updated_at", now)
                    }, "path=?", arrayOf(path))
                else -> db.update("file_index", ContentValues().apply {
                    put("size", size)
                    put("mtime", mtime)
                    put("sha256", sha256)
                    put("status", STATUS_BACKED)
                    put("receipt_verified", 0)
                    putNull("pc_integrity")
                    put("updated_at", now)
                }, "path=?", arrayOf(path))
            }
        } catch (t: Throwable) {
            // Best effort.
        }
    }

    /**
     * A signature-verified receipt lists this file: pin the receipt's
     * sha to the row and set the §6.4 evidence flag — but only when the
     * row still describes the same version (size match, and no
     * conflicting known sha). Rows first seen via a receipt get mtime 0;
     * the library diff repairs it from MediaStore without dropping the
     * evidence (see [refreshMtime]).
     */
    fun applyReceiptFile(path: String, size: Long, sha256: String) {
        try {
            val db = writableDatabase
            val now = System.currentTimeMillis()
            val existing = queryRow(db, path)
            when {
                existing == null -> db.insert("file_index", null, ContentValues().apply {
                    put("path", path)
                    put("size", size)
                    put("mtime", 0L)
                    put("sha256", sha256)
                    put("status", STATUS_BACKED)
                    put("receipt_verified", 1)
                    putNull("pc_integrity")
                    put("updated_at", now)
                })
                existing.size == size &&
                    (existing.sha256 == null || existing.sha256 == sha256) ->
                    db.update("file_index", ContentValues().apply {
                        put("sha256", sha256)
                        put("status", STATUS_BACKED)
                        put("receipt_verified", 1)
                        put("updated_at", now)
                    }, "path=?", arrayOf(path))
                else -> Unit // receipt covers an older version; leave the row
            }
        } catch (t: Throwable) {
            // Best effort.
        }
    }

    /**
     * Merge the PC's fs LIST verdicts: pc_integrity always refreshes;
     * status/sha are promoted only when the record describes the same
     * version the row knows (size match, no conflicting sha). Records for
     * paths with no row are PC-only files — not inserted (the library
     * derives those from receipts + the live LIST directly).
     */
    fun applyPcRecords(records: List<FsClient.Record>) {
        if (records.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                for (r in records) {
                    val existing = queryRow(db, r.path) ?: continue
                    val values = ContentValues().apply {
                        put("pc_integrity", r.integrity)
                        put("updated_at", now)
                        if ("ok" == r.integrity && existing.size == r.size &&
                            (existing.sha256 == null || existing.sha256 == r.sha256)
                        ) {
                            put("status", STATUS_BACKED)
                            if (existing.sha256 == null) put("sha256", r.sha256)
                        }
                    }
                    db.update("file_index", values, "path=?", arrayOf(r.path))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            // Best effort.
        }
    }

    /**
     * Fill in the real mtime for a receipt-created placeholder row
     * (mtime 0) without touching its evidence. Only fires while the row
     * is still a placeholder.
     */
    fun refreshMtime(path: String, mtime: Long) {
        try {
            writableDatabase.update("file_index", ContentValues().apply {
                put("mtime", mtime)
                put("updated_at", System.currentTimeMillis())
            }, "path=? AND mtime=0", arrayOf(path))
        } catch (t: Throwable) {
            // Best effort.
        }
    }

    /** Drop rows (files verified gone from the device). */
    fun deletePaths(paths: Collection<String>) {
        if (paths.isEmpty()) return
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (p in paths) db.delete("file_index", "path=?", arrayOf(p))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (t: Throwable) {
            // Best effort.
        }
    }

    private fun queryRow(db: SQLiteDatabase, path: String): Row? {
        db.query(
            "file_index",
            arrayOf("size", "mtime", "sha256", "status", "receipt_verified", "pc_integrity"),
            "path=?", arrayOf(path), null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return null
            return Row(
                path = path,
                size = c.getLong(0),
                mtime = c.getLong(1),
                sha256 = if (c.isNull(2)) null else c.getString(2),
                status = c.getInt(3),
                receiptVerified = c.getInt(4) != 0,
                pcIntegrity = if (c.isNull(5)) null else c.getString(5)
            )
        }
        return null
    }

    companion object {
        private const val DB_NAME = "file-index.db"
        private const val DB_VERSION = 1

        const val STATUS_NOT_BACKED = 0
        const val STATUS_BACKED = 1

        @Volatile
        private var instance: FileIndexDb? = null

        fun get(context: Context): FileIndexDb = instance ?: synchronized(this) {
            instance ?: FileIndexDb(context).also { instance = it }
        }

        /** Engine feed: attach to a BackupClient via setIndexSink. */
        fun sink(context: Context): FileIndexSink = FileIndexSink(get(context))
    }
}

/**
 * BackupClient.IndexSink backed by [FileIndexDb]. Metadata events (one
 * per scanned file, thousands per session) are buffered and flushed in
 * a single transaction every 200 entries or on demand; backed/receipt
 * events (rare) write through immediately after flushing the buffer, so
 * ordering is preserved. Every entry point swallows failures — the
 * index must never disturb a backup session.
 */
class FileIndexSink(private val db: FileIndexDb) : BackupClient.IndexSink {

    private val lock = Any()
    private val metaBuf = ArrayList<FileIndexDb.Meta>(256)

    override fun onMeta(path: String, size: Long, mtime: Long, sha256: String?) {
        try {
            synchronized(lock) {
                metaBuf += FileIndexDb.Meta(path, size, mtime, sha256)
                if (metaBuf.size >= 200) flushLocked()
            }
        } catch (t: Throwable) {
            // Never disturb the session.
        }
    }

    override fun onBacked(path: String, size: Long, mtime: Long, sha256: String?) {
        try {
            synchronized(lock) {
                flushLocked()
                db.markBacked(path, size, mtime, sha256)
            }
        } catch (t: Throwable) {
            // Never disturb the session.
        }
    }

    override fun onReceiptFile(path: String, size: Long, sha256: String) {
        try {
            synchronized(lock) {
                flushLocked()
                db.applyReceiptFile(path, size, sha256)
            }
        } catch (t: Throwable) {
            // Never disturb the session.
        }
    }

    override fun flush() {
        try {
            synchronized(lock) { flushLocked() }
        } catch (t: Throwable) {
            // Never disturb the session.
        }
    }

    private fun flushLocked() {
        if (metaBuf.isEmpty()) return
        val batch = ArrayList(metaBuf)
        metaBuf.clear()
        db.upsertMetaBatch(batch)
    }
}
