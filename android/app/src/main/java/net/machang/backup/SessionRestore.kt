package net.machang.backup

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Account-session persistence (2026-10-31 — user requirement: 用户登录以后
 * 要保存会话）. One password login must survive process death and app
 * restarts; the password is asked again only after an explicit 退出登录 or
 * a server-side invalidation.
 *
 * Server token model (unchanged): login issues an access token (2 h) plus
 * a refresh token (30 days, ROTATING — every /api/auth/refresh revokes the
 * presented refresh token and mints a fresh pair). Both halves live in
 * [SecureStore] (Keystore-wrapped AES/GCM); the password is never stored.
 *
 * Cold-start restore (once per process, kicked off by MainActivity):
 *  - no stored refresh token -> NO_SESSION (login form).
 *  - access token still fresh -> ALIVE, no network needed.
 *  - access expired -> silently rotate via /api/auth/refresh and persist
 *    the new pair -> ALIVE. Because each use re-arms the 30-day window, a
 *    phone that reaches the account service at least once a month stays
 *    signed in indefinitely.
 *  - server answers 401/403 -> EXPIRED: the dead session (access + refresh
 *    + stored account name) is dropped and a one-shot hint flag is set so
 *    the 我的 page shows 登录已过期，请重新登录 above the form. The DEVICE
 *    identity (device token/id, user id, network secret) is deliberately
 *    kept: it is an independent, non-expiring credential, and after the
 *    user re-enters the password the same device is reused — no fresh
 *    registration, no second PC approval.
 *  - network failure -> UNKNOWN: everything is kept. The dev account
 *    endpoint is mesh-only, so "unreachable" is the common cold-start
 *    case and must never log the user out.
 *
 * Pairing state is refreshed separately by the existing validate call
 * (MainActivity status card, 我的 page) using the device token.
 */
object SessionRestore {

    enum class Outcome { NO_SESSION, ALIVE, EXPIRED, UNKNOWN }

    private const val PREFS = "settings"
    private const val KEY_EXPIRED_HINT = "session_expired_hint"

    private val started = AtomicBoolean(false)

    @Volatile
    var lastOutcome: Outcome? = null
        private set

    /** True while a stored account session exists (regardless of freshness). */
    fun hasStoredSession(ctx: Context): Boolean =
        SecureStore.get(ctx, SecureStore.KEY_REFRESH_TOKEN) != null

    /** Persist a fresh login/refresh pair (never the password). */
    fun storeSession(ctx: Context, session: AccountClient.Session) {
        SecureStore.put(ctx, SecureStore.KEY_ACCESS_TOKEN, session.accessToken)
        SecureStore.put(ctx, SecureStore.KEY_REFRESH_TOKEN, session.refreshToken)
        SecureStore.put(
            ctx, SecureStore.KEY_ACCESS_EXPIRES_AT, session.expiresAtMs.toString()
        )
    }

    fun expiredHintPending(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_EXPIRED_HINT, false)

    fun clearExpiredHint(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_EXPIRED_HINT).apply()
    }

    /**
     * Run the restore at most once per process. Later callers (e.g. the
     * 我的 page opening after MainActivity already restored) just get the
     * recorded outcome. [onDone] fires on a worker thread.
     */
    fun restoreAsync(ctx: Context, onDone: (Outcome) -> Unit = {}) {
        if (!started.compareAndSet(false, true)) {
            lastOutcome?.let { onDone(it) }
            return
        }
        val appCtx = ctx.applicationContext
        thread(name = "session-restore") {
            val outcome = restore(appCtx)
            lastOutcome = outcome
            onDone(outcome)
        }
    }

    fun restore(ctx: Context): Outcome {
        val refresh = SecureStore.get(ctx, SecureStore.KEY_REFRESH_TOKEN)
            ?: return Outcome.NO_SESSION
        val expiresAt = SecureStore.get(ctx, SecureStore.KEY_ACCESS_EXPIRES_AT)
            ?.toLongOrNull() ?: 0L
        if (SecureStore.get(ctx, SecureStore.KEY_ACCESS_TOKEN) != null &&
            System.currentTimeMillis() < expiresAt - 60_000
        ) {
            healDeviceIdentity(ctx)
            return Outcome.ALIVE
        }
        return try {
            val session = AccountClient(PcConn.serverUrl(ctx)).refresh(refresh)
            storeSession(ctx, session)
            healDeviceIdentity(ctx)
            Outcome.ALIVE
        } catch (e: HttpStatusException) {
            if (e.code == 401 || e.code == 403) {
                SecureStore.put(ctx, SecureStore.KEY_ACCESS_TOKEN, null)
                SecureStore.put(ctx, SecureStore.KEY_REFRESH_TOKEN, null)
                SecureStore.put(ctx, SecureStore.KEY_ACCESS_EXPIRES_AT, null)
                SecureStore.put(ctx, SecureStore.KEY_ACCOUNT, null)
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_EXPIRED_HINT, true).apply()
                Outcome.EXPIRED
            } else {
                Outcome.UNKNOWN
            }
        } catch (t: Throwable) {
            Outcome.UNKNOWN
        }
    }

    /**
     * One-time device-identity heal (design §3.2). The first cold start
     * after the uuid rollout re-registers this phone WITH its persistent
     * uuid while a session is alive: the service binds the uuid to the
     * phone's existing device row and hands back that row's id plus a
     * fresh token. This is what walks a phone whose stored id drifted
     * during the pre-uuid era (registered as #28 on a re-login) back to
     * its data-bearing row (#7) — no password, no new number. Best
     * effort: any failure keeps the stored pair and retries on a later
     * cold start; [DeviceIdentity.bound] stops the retry loop after the
     * first success so the device token is not rotated on every start.
     */
    private fun healDeviceIdentity(ctx: Context) {
        if (DeviceIdentity.isBound(ctx)) return
        // Only after a real account login (dev/standalone mode has no
        // server-side device row to bind to).
        if (SecureStore.get(ctx, SecureStore.KEY_ACCOUNT) == null) return
        val access = SecureStore.get(ctx, SecureStore.KEY_ACCESS_TOKEN) ?: return
        val storedRefresh = SecureStore.get(ctx, SecureStore.KEY_REFRESH_TOKEN) ?: return
        val expiresAt = SecureStore.get(ctx, SecureStore.KEY_ACCESS_EXPIRES_AT)
            ?.toLongOrNull() ?: 0L
        try {
            val devName = (android.os.Build.MANUFACTURER + " " +
                android.os.Build.MODEL).trim()
            val (id, tok) = AccountClient(PcConn.serverUrl(ctx)).registerDevice(
                AccountClient.Session(access, storedRefresh, expiresAt),
                "phone", devName, DeviceIdentity.uuid(ctx)
            )
            val prev = SecureStore.get(ctx, SecureStore.KEY_DEVICE_ID)
            SecureStore.put(ctx, SecureStore.KEY_DEVICE_ID, id.toString())
            SecureStore.put(ctx, SecureStore.KEY_DEVICE_TOKEN, tok)
            DeviceIdentity.setBound(ctx, true)
            AppLog.log(
                if (prev != null && prev != id.toString()) {
                    "设备身份已归并：#$prev → #$id（稳定身份绑定完成）"
                } else {
                    "设备稳定身份绑定完成：#$id"
                }
            )
        } catch (t: Throwable) {
            AppLog.log("设备身份绑定暂失败，下次启动重试：${t.message}")
        }
    }
}
