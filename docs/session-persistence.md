# Login-session persistence （登录后保存会话） — 2026-10-31

User requirement: after logging in once, reopening either client must come
back logged in. The password is asked again only after an explicit 退出登录
or a server-side invalidation. **No password is ever stored, anywhere.**

## Server token model (unchanged — read from `server/`)

| Credential | TTL | Notes |
|---|---|---|
| Account access token (`auth_tokens`, kind=access) | **2 h** | Bearer for session-level API calls (device register, device list via session, provisioning via session). |
| Account refresh token (kind=refresh) | **30 days, rotating** | `POST /api/auth/refresh` revokes the presented token and issues a fresh pair. Each successful refresh re-arms the 30-day window. |
| Device token (`devices.token_hash`) | **no expiry** | Revocable only (admin / account session / approved-PC paths). Shown once at registration; only its SHA-256 hash is stored server-side. |

`POST /api/auth/logout` revokes **whatever bearer token it is given**
(the update is by token hash, kind-agnostic) — both clients use this to
kill their stored refresh token server-side at logout.

Consequence: indefinite persistence needs no server change. A client that
reaches the account service at least once per 30 days and rotates its
refresh token stays signed in forever; the long-lived device token covers
all runtime traffic in between.

## PC (receiver / NiuPcApp)

Storage: `<data-dir>/account.json` (mode 600, unchanged format) — server
URL, account, user_id, pc_device_id, pc_device_token, **refresh_token**,
network name/secret, public node, pc_ip. The PC deliberately does **not**
keep an access token: every runtime call (sync, approve, provisioning,
license) already runs on the device token.

Behavior:
- `--login` (CLI or GUI button) is still the only password moment; it
  persists the state above via the existing `AccountLink.loginFlow`.
- GUI launch with saved state restores **straight into the logged-in
  card** (account summary + 退出登录） — the password form is not shown.
  A background check (`AccountLink.refreshSession`) then proves the
  session and rolls its window:
  - `OK` — refresh token rotated and re-saved to account.json.
  - `REJECTED` (HTTP 401/403 — refresh revoked/expired, user disabled) —
    state wiped, back to the login form with 「登录已过期，请重新登录」.
  - `UNREACHABLE` (offline / service down) — saved login kept untouched.
- 退出登录 (GUI button, or the new one-shot CLI
  `BackupReceiver --logout --data-dir <dir>`): stops the embedded
  receiver, best-effort `POST /api/auth/logout` with the refresh token
  (revokes it server-side), deletes account.json. Headless `--account`
  serve and all pre-existing CLI flags are unchanged.

Verified live (laptop, M4c test account user 3, scratch data dir):
`--login` → refresh rotated + persisted → refresh again after reload OK →
garbage token REJECTED → dead-port UNREACHABLE → `logoutSession` →
post-logout refresh against the real service returns **401** → `--logout`
round trip + no-op when already logged out.

## Android app

Audit finding (before this change): login stored access+refresh tokens in
`SecureStore`, but nothing ever read them again — `AccountClient.refresh()`
was dead code, the access token silently rotted after 2 h, the login form
was always visible, and no server-side invalidation was ever detected.
Device-token features (backup, validate, provisioning) kept working
because device tokens do not expire, which masked the rot.

Now (`SessionRestore.kt`, new):
- Stored in `SecureStore` (Keystore-wrapped AES/GCM, as before):
  access token, refresh token, **access_expires_at** (new key, from the
  server's `expires_in`). Device token/id, user id, network secret —
  unchanged.
- Cold start (`MainActivity.onCreate`, once per process): no refresh
  token → login form; access token fresh → restored, no network; access
  expired → silent rotation via `/api/auth/refresh`, new pair persisted.
  Pairing state keeps refreshing through the pre-existing `validate`
  call on the status card / 我的 page.
- Server rejection (401/403 during restore) → **EXPIRED**: access +
  refresh + stored account name are dropped, a one-shot hint flag makes
  the 我的 page show 「登录已过期，请重新登录」 above the form (the 备份
  page status line mirrors it). The **device identity is kept on
  purpose**: after re-login the same device is reused — no new
  registration slot, no second PC approval. Network failure → session
  kept (the dev endpoint is mesh-only; offline cold starts are normal).
- 我的 page: with a stored session, the password field and 注册/登录
  buttons are hidden (server address + account stay visible as
  settings). 退出登录 now also fires the best-effort server logout
  (refresh token as bearer) before wiping local state.

Verified headlessly: `assembleDebug` BUILD SUCCESSFUL on the laptop,
APK 21,750,978 B; `SessionRestore` and the new keys confirmed present in
the APK dex. **Not installed** — first on-device pass should check:
login → force-stop → reopen （直接已登录） → 退出登录 → reopen （表单出现）.

## Remaining limits (precise)

1. **30 days of total unreachability ends the session.** If neither
   client manages one successful `/api/auth/refresh` within 30 days of
   the last one (app never opened, account service down, mesh never up),
   the refresh token lapses and the password is required once more. The
   device binding survives (Android keeps it explicitly; the PC reuses /
   reclaims its single PC slot at the next `--login`).
2. **Rotation is single-shot.** If a refresh response is lost in transit
   after the server processed it, the client still holds the now-revoked
   old token and will see one "expired" prompt at the next restore. The
   clients only rotate when needed (Android: access token lapsed; PC:
   once per GUI launch) to keep this window small.
3. A server-side **user disable** surfaces as 403 at the next restore —
   both clients drop to the login form; re-login then reports
   `user_disabled` until an admin re-enables the account. Correct, but
   the hint text says 登录已过期 in that case too.
