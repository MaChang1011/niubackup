package net.machang.backup

/**
 * Process-wide view of a running backup session, so the file library can
 * render its 「备份中 x%」 badge (§6.1) while MainActivity / the auto-backup
 * worker drives the actual transfer.
 */
object BackupProgress {
    @Volatile var active: Boolean = false
    @Volatile var currentPath: String? = null
    @Volatile var percent: Int = 0

    fun begin() {
        active = true
        currentPath = null
        percent = 0
    }

    fun update(sent: Long, total: Long, path: String) {
        currentPath = path
        percent = if (total > 0) (sent * 100 / total).toInt() else 0
    }

    fun end() {
        active = false
        currentPath = null
    }
}
