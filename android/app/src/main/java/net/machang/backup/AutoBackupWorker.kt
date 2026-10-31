package net.machang.backup

import android.content.Context
import android.net.VpnService
import android.os.Environment
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.function.IntSupplier

/**
 * Scheduled auto-backup (design §6): runs when the device is charging, on
 * unmetered network (WiFi) and idle — and even then only proceeds when the
 * route to the PC is p2p-direct (§5.5 gate), checked after ensuring the mesh
 * is up. A count-level session report goes to the account service (§8).
 *
 * Dev-stage caveats: mesh cold-start needs a prior VPN consent; on
 * Android 12+ a background FGS start can be refused by the platform, in
 * which case the run is skipped (retried next window) instead of failing.
 */
class AutoBackupWorker(ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val identity = PcConn.identity(ctx) ?: return@withContext Result.success()
        val host = PcConn.pcHost(ctx)

        if (EasyTierBridge.peerRouteCost(host) < 0) {
            // Mesh not up in this process: try to start it (best effort).
            if (VpnService.prepare(ctx) != null) {
                AppLog.log("自动备份跳过: 未授予 VPN 权限")
                return@withContext Result.success()
            }
            try {
                PcConn.startMesh(ctx)
            } catch (t: Throwable) {
                AppLog.log("自动备份跳过: 组网启动失败 ${t.message}")
                return@withContext Result.success()
            }
            var waited = 0
            while (EasyTierBridge.peerRouteCost(host) < 0 && waited < 60_000) {
                delay(3_000)
                waited += 3_000
            }
        }
        val cost = EasyTierBridge.peerRouteCost(host)
        if (cost != 1) {
            AppLog.log("自动备份跳过: 到电脑的路由不是直连 (cost=$cost)")
            return@withContext Result.success()
        }
        val dcim = File(Environment.getExternalStorageDirectory(), "DCIM")
        val logger = BackupClient.Logger { msg -> AppLog.log(msg) }
        val client = BackupClient(
            logger, ctx.filesDir, identity.userId, identity.deviceId, identity.token,
            IntSupplier { EasyTierBridge.peerRouteCost(host) }
        )
        // Keep the library page's local file index fed from auto sessions too.
        client.setIndexSink(FileIndexDb.sink(ctx))
        BackupProgress.begin()
        val failures = try {
            client.runBackup(
                host, PcConn.pcPort(ctx), BackupClient.DEVICE_NAME, dcim
            ) { sent, total, path -> BackupProgress.update(sent, total, path) }
        } finally {
            BackupProgress.end()
        }
        if (PcConn.isAccountMode(ctx)) {
            try {
                AccountClient(PcConn.serverUrl(ctx)).postSessionLog(
                    identity.token, client.lastOkCount, client.lastBytesSent,
                    if (failures == 0) "ok" else "partial"
                )
            } catch (t: Throwable) {
                // Report is best effort; the backup itself already succeeded.
            }
        }
        if (failures == 0) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "niubackup-auto-backup"

        fun setEnabled(ctx: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(ctx)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresDeviceIdle(true)
                .build()
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }
    }
}
