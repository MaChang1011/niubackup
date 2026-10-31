package net.machang.backup

import android.content.Context
import java.util.UUID

/**
 * This phone's persistent device identity (design §3.2) — deliberately
 * separate from the account session:
 *
 *  - [uuid] is generated once and kept in its OWN SharedPreferences file
 *    ("device_identity"), which [SecureStore.clearAccount] never touches:
 *    logging out drops the session, never the identity, so the next login
 *    presents the same uuid and the service upserts the SAME device row —
 *    the phone keeps its device number across logins. It dies only with
 *    the app data (clear data / reinstall), exactly like a fresh phone.
 *  - [bound] remembers that the uuid has been registered with the service
 *    at least once. SessionRestore uses it to run the one-time identity
 *    heal (re-register with the uuid right after a session restore) only
 *    until the binding exists, instead of rotating the device token on
 *    every cold start.
 *
 * The uuid is an identifier, not a credential (the device token is the
 * credential and stays in [SecureStore]), so plain prefs are fine here.
 */
object DeviceIdentity {
    private const val PREFS = "device_identity"
    private const val KEY_UUID = "device_uuid"
    private const val KEY_BOUND = "uuid_bound"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun uuid(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(KEY_UUID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = UUID.randomUUID().toString()
        p.edit().putString(KEY_UUID, fresh).apply()
        return fresh
    }

    fun isBound(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BOUND, false)

    fun setBound(ctx: Context, bound: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_BOUND, bound).apply()
    }
}
