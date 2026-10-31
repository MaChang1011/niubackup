# M2 build notes (2026-10-30, built on machang-laptop)

## Native libs (real build, not stubs)
Recipe (vendor build.sh equivalent, run manually because the tree came from a tarball, not git):
- Rust via rsproxy.cn mirror (static.rust-lang.org bulk downloads crawl on this broadband):
  RUSTUP_DIST_SERVER=https://rsproxy.cn, RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup,
  toolchain 1.95 (repo pin), cargo-ndk 4.1.2, ~/.cargo/config.toml replaces crates-io with rsproxy-sparse.
- GitHub is unreachable from this laptop, but EasyTier has 6 git deps in Cargo.lock plus a
  transitive one (WinDivert, pulled by windivert-rust). They were cloned via another machine
  into ~/src/git-mirrors/ and cargo (git-fetch-with-cli=true) is redirected with:
  git config --global url."file:///home/machang/src/git-mirrors/".insteadOf "https://github.com/EasyTier/"
- protoc: the build needs a host protoc WITH well-known-type includes. Maven Central
  protoc-3.20.1-linux-x86_64.exe + protobuf-java-3.20.1.jar (extract google/protobuf/*.proto)
  assembled as ~/tools/bin/protoc + ~/tools/include/. Export PROTOC=$HOME/tools/bin/protoc.
- ANDROID_NDK_ROOT=$HOME/android-sdk/ndk/28.2.13676358
- cd easytier-contrib/easytier-ffi && cargo ndk -t arm64-v8a build --release
- cd easytier-contrib/easytier-android-jni && cargo ndk -t arm64-v8a build --release
- Artifacts: target/aarch64-linux-android/release/libeasytier_ffi.so (16,787,368 B),
  libeasytier_android_jni.so (17,015,272 B) -> app/src/main/jniLibs/arm64-v8a/.

## APK
- Gradle 8.10.2 distribution came from mirrors.cloud.tencent.com (services.gradle.org is
  slow here); the wrapper dist in ~/.gradle was seeded from that zip, and
  gradle-wrapper.properties now points at the Tencent mirror too.
- JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64; ./gradlew assembleDebug
  -> app/build/outputs/apk/debug/app-debug.apk (16,774,835 B), BUILD SUCCESSFUL.
- AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 35, buildTools 36.0.0, minSdk 26.

## Open points for the first phone test
- The VpnService starts the EasyTier instance first, then establishes the TUN and calls
  setTunFd. If the core expects a different order, the UI log + getLastError will show it.
- DCIM walking needs the media permissions; on Android 11+ scoped storage the walk may
  see 0 files until "all files access" is granted (the app logs a hint in that case).

## M4c addendum (2026-10-31)

- New dependencies (Maven Central / Google via the existing mirrors):
  androidx.media3 1.4.1 (exoplayer + ui) for tunnel-streamed playback and
  androidx.work:work-runtime-ktx 2.9.1 for constrained auto-backup.
  `gradle.properties`: android.useAndroidX=true (was false in the pure-
  framework M2/M4a app).
- Manifest: usesCleartextTraffic=true (dev account-service endpoint is
  plain HTTP over the mesh; production gets HTTPS), label 牛备， and the
  Library/Records/Player activities.
- Verified: `./gradlew assembleDebug` BUILD SUCCESSFUL;
  app-debug.apk 21,650,801 bytes. Not installed on a device this round.
- Feature map and dev limitations: docs/m4c-notes.md (P3).

## Addendum (2026-10-31) — FGS crash fix + 4-page restructure

1. **Mesh-start crash fixed** (`ForegroundServiceDidNotStartInTimeException`):
   account-mode startup (provisioning fetch + JNI init + establish) can exceed
   the 5 s startForeground contract, and the M2 pattern (promote after
   establish with SYSTEM_EXEMPTED type) cannot promote earlier because that
   type throws SecurityException before the VPN appop exists. Two-step
   promotion in `BackupVpnService`:
   - Step 1 (top of onStartCommand, every non-STOP intent): promote
     immediately with FOREGROUND_SERVICE_TYPE_DATA_SYNC (manifest:
     `systemExempted|dataSync` + FOREGROUND_SERVICE_DATA_SYNC permission) —
     its only prerequisite is the permission, so it cannot throw pre-VPN.
   - Step 2 (after establish()): existing retry-loop promotion switches the
     type to SYSTEM_EXEMPTED (also avoids Android 15 dataSync time limits).
   Error paths promote first, then log + stopSelf (`demoteAndStop()`); the
   service can no longer crash on config/network failure.
2. **UI restructured into 4 pages** (bottom nav, separate singleTop
   activities; engine/protocol classes untouched): MainActivity=备份
   (status/mesh/manual backup/last session/auto-backup conditions/log),
   LibraryActivity=文件库 (thumbnail grid + badges + §6.4 free-up-space),
   RecordsActivity=记录 (fs records, download-back, playback),
   AccountActivity=我的 (login/register, server address, pc_port, device
   info, dev overrides, about). Shared structural helpers in `Ui.kt`.
   STYLING IS INTENTIONALLY PLAIN (system defaults, no brand skin) pending
   the user's design pass — `Ui.kt` helpers are the reskin seams.

## Addendum (2026-10-31) — login-session persistence

User requirement: 登录后保存会话 (log in once; reopening restores the
session; password only after explicit logout or server-side invalidation).

- New `SessionRestore.kt`: cold-start restore once per process (from
  MainActivity). Stored session = access + refresh + access_expires_at
  in SecureStore (new key `access_expires_at`; Session now carries
  expiresAtMs from the server's `expires_in`). Access token fresh ->
  restored silently; lapsed -> rotate via POST /api/auth/refresh (the
  previously dead-code call) and persist the new pair. Refresh tokens
  are 30-day rotating, so monthly contact keeps the session alive
  indefinitely. Server 401/403 -> session dropped + one-shot
  `session_expired_hint` flag -> form shows 登录已过期，请重新登录;
  the DEVICE identity is kept (re-login reuses the device, no second
  PC approval). Network failure -> session kept (dev endpoint is
  mesh-only; offline cold starts are the norm).
- AccountActivity: with a stored session the password field and
  注册/登录 buttons are hidden; 退出登录 also calls the new
  AccountClient.logout(refreshToken) (best effort — the endpoint
  revokes whatever bearer it is given) before the local wipe.
- AccountClient: non-2xx now throws HttpStatusException (message format
  unchanged) so restore can tell rejection (401/403) from offline.
- Verified: ./gradlew assembleDebug BUILD SUCCESSFUL on the laptop;
  app-debug.apk 21,750,978 bytes; SessionRestore + new keys confirmed
  in the dex. NOT installed this round — see docs/session-persistence.md
  for the on-device checklist and the remaining limits (30-day refresh
  window, lost-rotation-response edge).

## Addendum (2026-10-31) — approved-design reskin + photo preview

- The four pages, the player, and the new photo preview are skinned to the
  user-approved design mockup (artifact `niubackup` v1.1; tokens extracted
  verbatim via artifact inspection): brand teal scale (primary #0E6B7E),
  scarce 牛橙 accent #F07F13, status pairs （已备份 #1E9E66 / 备份中 #2470D8 /
  等待直连 #C77400 / 仅PC有 slate), radii 16/12/10/6, cards on #F3F7F8.
  All skin lives in Ui.kt (tokens, badges/pills, filter chips, kv rows,
  progress bar, brand tile, line-icon bottom nav with accent dot).
- 备份： header mesh pill （直连/等待直连/未连接）; 账户与配对 kv card with the
  mesh control; 立即备份 big button transforms IN PLACE into the progress
  card while a session runs; conditions as colored dots （充电/WiFi/空闲/直连）;
  log card shows the latest 6 lines only.
- 文件库： filter chips （全部/照片/视频/未备份）; selection mode (long-press)
  replaces the header （取消/已选 N 项/释放空间 orange) over the mandatory
  释放安全 card; video duration chips; 仅PC有 dim + PC overlay; image tap
  opens the preview. §6.4 flow unchanged.
- 记录： connection pill + persistent freshness strip; ONE card with
  day-grouped rows, inline 播放/下载回来； meta shows 回执已验证 ✓ when a
  locally verified receipt covers the path; image rows open the preview.
- 我的： profile card (gradient 牛 avatar, session/pairing pills), red-tinted
  standalone logout. Stats card from the mockup NOT reproduced (no honest
  data source for 已释放空间）; license-expiry date chip likewise omitted
  (expiry is not exposed by existing client calls).
- PlayerActivity: dark immersive skin, custom transport (accent seek bar,
  ±10s, white main circle), persistent 直连流式 chip + note, amber 下载回来
  + 详情； streaming stack (FsDataSource/ExoPlayer) untouched.
- NEW PhotoPreviewActivity: full-screen viewer; local decode, PC copies
  fetched full-size via the existing FsClient download into a preview
  cache; translucent top bar (name/date/badge), swipe + arrows + dots,
  pinch/double-tap zoom (ZoomImageView matrix), first-entry 双指放大 hint;
  bottom 下载回来 (existing publish flow) / 释放空间 (the existing §6.4
  gates + identical confirmation wording, single-file) / 记录.
- Engine/protocol/session classes untouched (BackupClient, FsClient,
  FsDataSource, PcConn, AccountClient, SessionRestore, SecureStore,
  BackupVpnService, AutoBackupWorker).

## Addendum (2026-10-31) — four-defect fix round (header / tab transitions / secure-mode config / insets)

1. **Fixed app bar** (user: "手机app的头部不在"): `Ui.page` now builds a
   FIXED app bar above the page content — brand tile + 牛备 wordmark +
   page title + a right slot for the page's status element （备份 mesh
   pill, 记录 connection pill, 文件库 刷新 action, 我的 none). Before,
   each page's title row was the first child of its scrolling column
   (Main/Account) or a plain in-flow row, so no persistent branded
   header existed. 文件库 selection mode hides the app bar while its
   selection bar is up (mockup behavior kept). The old `Ui.header`
   helper is removed; the four pages were its only callers.
2. **Instant tab switches** (user: "切换页面是滑动效果，太难看了"):
   bottom-nav hops add FLAG_ACTIVITY_NO_ANIMATION and call
   overridePendingTransition(0, 0); all four tab activities override
   finish() with the same zero transition (back-press path); the photo
   preview's 记录 action (into RecordsActivity) hops the same way.
   singleTop + REORDER_TO_FRONT semantics unchanged.
3. **Secure-mode config spelling** (the user's "异常" screenshot):
   buildProductConfig emitted `[flags] secure_mode = true` — accepted
   by the PC's easytier-core 2.6.4 sidecar, but the bundled 2.7.0 JNI
   core rejects it at TOML parse ("unknown field `secure_mode`"), so
   the mesh instance never started. In 2.7.0 secure mode is a top-level
   `[secure_mode]` section, `enabled = true` (source tree ~/src/EasyTier:
   easytier-core/src/config/toml.rs `Config.secure_mode:
   Option<SecureModeConfig>`; easytier-proto/proto/common.proto
   `SecureModeConfig { enabled, local_private_key, local_public_key }`).
   normalize_secure_mode_config generates the node X25519 keypair when
   none is configured, and with network_secret set the node stays in
   shared-secret mode (not credential mode) — the same secure mode the
   2.6.4 sidecar runs (the live receiver's serve log shows no
   secure-mode fallback), so phone ↔ PC interop is unchanged.
   Validated headlessly on the laptop with a temporary integration
   test (since removed) feeding both spellings to the real loader —
   TomlConfigLoader::new_from_str, the entry the FFI parseConfig uses
   (easytier-contrib/easytier-ffi/src/instance_api.rs): the old
   spelling is rejected with the field named; the new spelling parses
   with secure mode enabled and the keypair generated (2 passed).
   Two environment notes for host builds in that tree: its
   .cargo/config.toml pins -fuse-ld=mold but mold is not installed
   (override RUSTFLAGS=-C link-arg=-fuse-ld=bfd) and build scripts
   need PROTOC=~/tools/bin/protoc.
4. **Log cap**: core/engine errors go through the new
   AppLog.logEngineError — full text to logcat, first 6 lines +
   truncation marker in the app UI. Used by both BackupVpnService
   engine-error paths (startInstance, setTunFd); AppLog.log unchanged.
5. **Edge-to-edge insets** (user screenshot: 上次备份 card under the
   clock): targetSdk 35 draws behind the system bars. Tab scaffold
   (API 30+): setDecorFitsSystemWindows(false), app bar padded by the
   status-bar inset (the BRAND_800 band extends through the status
   area, white status icons), nav bar padded by the navigation-bar
   inset. Player/photo preview keep their bleeding dark backgrounds
   but their chrome is inset-aware (chosen over transient immersive
   flags): player top bar + root bottom; preview top bar (replacing a
   hardcoded 36dp guess) + bottom action bar.
- Verified: ./gradlew assembleDebug BUILD SUCCESSFUL on the laptop;
  app-debug.apk 21,805,901 bytes; the new TOML string is present in
  the packaged dex. NOT installed this round.
