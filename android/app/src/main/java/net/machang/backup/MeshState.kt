package net.machang.backup

import android.app.Activity
import android.content.Context
import android.net.VpnService

/**
 * MeshState — the one shared view of the product mesh's lifecycle
 * (design v1.2: 组网全自动）. BackupVpnService writes; the pages only
 * read and render the status pill / 网络状态 row. Before this holder the
 * "is the mesh running" fact lived as a local flag inside MainActivity,
 * which is why auto-start (app launch / login / before backup) could not
 * exist: nothing outside the home page could know or affect it.
 *
 * FAILED carries a human reason — most importantly the FlClash case:
 * Android allows exactly one VPN, so when another VPN app takes the
 * slot the system revokes ours (BackupVpnService.onRevoke) and the UI
 * must say WHY the mesh is down instead of spinning forever.
 */
object MeshState {

    enum class Phase { OFF, STARTING, UP, FAILED }

    @Volatile
    var phase: Phase = Phase.OFF
        private set

    @Volatile
    var failureReason: String? = null
        private set

    fun markStarting() {
        phase = Phase.STARTING
        failureReason = null
    }

    fun markUp() {
        phase = Phase.UP
        failureReason = null
    }

    fun markFailed(reason: String) {
        phase = Phase.FAILED
        failureReason = reason
    }

    fun markOff() {
        phase = Phase.OFF
        failureReason = null
    }

    /** A start was requested and has not failed (yet). */
    val active: Boolean
        get() = phase == Phase.STARTING || phase == Phase.UP
}

/**
 * MeshAuto — the single "make sure the mesh is coming up" entry point,
 * called at the three design-mandated moments: app launch, login
 * success, and before a manual backup. The first-ever call still hits
 * the system VPN consent dialog (Android forces it; there is no way
 * around it) — [ensure] routes that through the caller's activity.
 */
object MeshAuto {

    /**
     * Returns true when the mesh is up, starting, or a start was just
     * requested (including "consent dialog now showing"). False when no
     * mesh configuration exists yet (not logged in / no secret).
     */
    fun ensure(act: Activity, vpnRequestCode: Int): Boolean {
        if (MeshState.active) return true
        if (PcConn.meshStartIntent(act) == null) return false
        val prepare = VpnService.prepare(act)
        if (prepare != null) {
            @Suppress("DEPRECATION")
            act.startActivityForResult(prepare, vpnRequestCode)
            return true
        }
        start(act)
        return true
    }

    /** Start (or restart) the mesh service now; consent already granted. */
    fun start(ctx: Context) {
        MeshState.markStarting()
        PcConn.startMesh(ctx)
    }
}
