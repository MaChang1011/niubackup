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

    /**
     * Manual-session pause state. [pauseRequested] is set the moment the
     * user taps 暂停备份 (the session is still winding down at its next
     * checkpoint); [paused] is set once that session has actually ended
     * via the pause, and stays until the next manual session starts —
     * MainActivity renders 已暂停 + 继续备份 from it, so the state
     * survives the Activity being recreated. Process-wide on purpose,
     * but ONLY the manual flow sets these: the auto-backup worker drives
     * its own BackupClient and never touches the pause flags.
     */
    @Volatile var pauseRequested: Boolean = false
    @Volatile var paused: Boolean = false

    fun requestPause() {
        pauseRequested = true
    }

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
