package net.machang.backup

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * PcConn (M4c) — resolves "how do we talk to my PC right now" in one place:
 *
 *  - Account mode (logged in): identity = the server-issued user/device ids
 *    and the Keystore-stored device token; the PC lives at 10.99.0.2 on the
 *    per-user product network.
 *  - Dev mode (M4a standalone): identity from the manual settings fields
 *    (seeded receiver); the PC lives at the self-use mesh address.
 *
 * Used by the library/records/player screens, manual backup and the
 * auto-backup worker so they all agree.
 */
object PcConn {
    const val PREFS = "settings"
    const val PRODUCT_PC_IP = "10.99.0.2"
    const val PRODUCT_PHONE_IP = "10.99.0.3"
    const val PRODUCT_SUBNET = "10.99.0.0"
    const val DEV_PC_IP = "10.200.0.2"
    const val DEFAULT_PC_PORT = 28741

    data class Identity(val userId: String, val deviceId: String, val token: String)

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAccountMode(ctx: Context): Boolean =
        SecureStore.get(ctx, SecureStore.KEY_DEVICE_TOKEN) != null

    fun identity(ctx: Context): Identity? {
        val token = SecureStore.get(ctx, SecureStore.KEY_DEVICE_TOKEN)
        val user = SecureStore.get(ctx, SecureStore.KEY_USER_ID)
        val device = SecureStore.get(ctx, SecureStore.KEY_DEVICE_ID)
        if (token != null && user != null && device != null) {
            return Identity(user, device, token)
        }
        // Dev mode (M4a standalone): the manual settings fields.
        val p = prefs(ctx)
        val devToken = p.getString("v2_device_token", "") ?: ""
        if (devToken.isBlank()) return null
        return Identity(
            p.getString("v2_user_id", "1") ?: "1",
            p.getString("v2_device_id", "phone") ?: "phone",
            devToken
        )
    }

    fun pcHost(ctx: Context): String =
        if (isAccountMode(ctx)) PRODUCT_PC_IP else DEV_PC_IP

    fun pcPort(ctx: Context): Int =
        prefs(ctx).getInt("pc_port", DEFAULT_PC_PORT)

    fun createFsClient(ctx: Context): FsClient? {
        val id = identity(ctx) ?: return null
        val host = pcHost(ctx)
        return FsClient(host, pcPort(ctx), id.userId, id.deviceId, id.token) {
            EasyTierBridge.peerRouteCost(host)
        }
    }

    /** Account-service base URL (dev default: reachable only via the mesh). */
    fun serverUrl(ctx: Context): String =
        prefs(ctx).getString("server_url", "http://10.200.0.1:18090")
            ?: "http://10.200.0.1:18090"

    /**
     * Build the BackupVpnService start intent for the current mode: product
     * config (per-user nb- network) when logged in, legacy self-use config
     * otherwise. Null when the needed material (secret) is missing.
     */
    fun meshStartIntent(ctx: Context): Intent? {
        val p = prefs(ctx)
        if (isAccountMode(ctx)) {
            val secret = SecureStore.get(ctx, SecureStore.KEY_NETWORK_SECRET) ?: return null
            val name = p.getString("network_name", null) ?: return null
            val node = p.getString("public_node", "tcp://36.133.114.164:11011")
                ?: "tcp://36.133.114.164:11011"
            val config = EasyTierBridge.buildProductConfig(name, secret, node)
            return Intent(ctx, BackupVpnService::class.java).apply {
                action = BackupVpnService.ACTION_START
                putExtra(BackupVpnService.EXTRA_CONFIG, config)
                putExtra(
                    BackupVpnService.EXTRA_INSTANCE_NAME,
                    EasyTierBridge.PRODUCT_INSTANCE_NAME
                )
                putExtra(BackupVpnService.EXTRA_TUN_IP, PRODUCT_PHONE_IP)
                putExtra(BackupVpnService.EXTRA_TUN_PREFIX, 24)
                putExtra(BackupVpnService.EXTRA_ROUTE_NET, PRODUCT_SUBNET)
                putExtra(BackupVpnService.EXTRA_ROUTE_PREFIX, 24)
                putExtra(BackupVpnService.EXTRA_SESSION, "niubackup")
            }
        }
        val secret = p.getString("mesh_secret", "") ?: ""
        if (secret.isBlank()) return null
        return Intent(ctx, BackupVpnService::class.java).apply {
            action = BackupVpnService.ACTION_START
            putExtra(BackupVpnService.EXTRA_SECRET, secret)
        }
    }

    /** Start the mesh service honoring the API-26+ foreground-service rule. */
    fun startMesh(ctx: Context) {
        val intent = meshStartIntent(ctx) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
    }
}
