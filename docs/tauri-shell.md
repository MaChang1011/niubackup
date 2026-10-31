# Tauri desktop shell + loopback control API (2026-10-31)

User decision (2026-10-31): the PC desktop client is a **Tauri v2 shell**
driving the existing Java receiver engine. The Swing app (`NiuPcApp`)
stays in the tree untouched and retires once the shell reaches parity.

```
┌──────────────────────────── NiuBackup.app / niubackup-desktop ─┐
│  Tauri shell (Rust, desktop/src-tauri)                          │
│   - spawns & supervises the engine child process                │
│   - tray icon, autostart, open-path, shell config               │
│  Web frontend (desktop/src, vanilla HTML/CSS/JS)                │
│   - renders the design v1.1 PC frames                           │
└──────────────┬─────────────────────────────────────────────────┘
               │ HTTP 127.0.0.1:<port>  +  X-Control-Token
               ▼
┌──────────────────────────── Java engine ───────────────────────┐
│  BackupReceiver --account --control                             │
│   - backup protocol v2 listener (unchanged)                     │
│   - account sync, mesh sidecar, receipts (unchanged)            │
│   - loopback control API (new, this doc)                        │
└────────────────────────────────────────────────────────────────┘
```

## Default account-service address

**Every login surface defaults to the PUBLIC endpoint
`http://36.133.114.164:18090`** — the Tauri login card's prefilled
服务地址， the control API's login when no server is given, and the
receiver's `--login` (constant `BackupReceiver.DEFAULT_SERVER`, used
when neither `--server` nor `NB_SERVER` is set). Rationale (user,
2026-10-31): login must work *before any mesh exists*, so a mesh address
can never be the default. The field stays editable everywhere, and an
explicitly entered + saved address wins over the default (the shell
persists it into its config on login; `account.json` does the same for
the engine).

> Temporary state: the account service is currently bound to 0.0.0.0
> with a public iptables ACCEPT for the phone test (plain HTTP,
> test-period only). Before launch this default must move to the
> domain + HTTPS endpoint — one constant + one config default.

## Loopback control API

Started by `BackupReceiver` when `--control` is passed (account mode).
Implemented with the JDK's built-in `com.sun.net.httpserver` — no new
dependencies. The backup protocol socket is untouched.

### Discovery & auth

- Binds **127.0.0.1 only**. First free port in **28799..28808**.
- On start it writes `<data-dir>/control.json` (mode 600):
  `{"port":28799,"token":"<64 hex>","pid":<engine pid>,"started_at_ms":…,"data_dir":"…"}`
  The token is random per engine process. The file is deleted by a
  shutdown hook; a stale file must not be trusted — the shell matches
  `pid` against its own child before using an endpoint.
- **Every request** requires header `X-Control-Token: <token>`
  (401 `{"ok":false,"error":"bad control token"}` otherwise).
- Responses are JSON (`application/json; charset=utf-8`). CORS is
  permissive (`Access-Control-Allow-Origin: *` + OPTIONS preflight)
  because the caller is the local webview; the token is the real gate
  and the socket never leaves loopback.
- Media bytes are returned raw; the frontend fetches them with the
  token header and displays them via blob URLs (`<img>` cannot set
  headers).

### Modes

- **Full mode**: account state exists → the receiver serves backups as
  usual and the control API alongside.
- **Control-only mode**: `--account --control` with **no saved session**
  parks the process serving *only* the control API (no backup listener,
  no sidecar) so the shell can drive the first login. This replaces the
  old "run --login first" hard exit for the shell path only; the CLI
  without `--control` still exits with the usage hint.

### Endpoints

| Method & path | Needs account | Behavior |
|---|---|---|
| `GET /status` | no | `{receiver:{serving, backup_port, bind, target_dir, data_dir, account_mode, control_only}, mesh:{state: off\|up\|down, secure_mode, peers:[{ipv4,cost,route}] \| null}, account:{account, user_id, server, network, pc_ip, pc_device_id} \| null, license_expires_at, storage:{used_bytes, quota_bytes, files}, last_sync_ok_ms, server_time_ms}`. `peers` is best-effort from the sidecar's easytier-cli RPC portal (15891); `null` when unavailable. |
| `GET /devices` | yes | `{bound:[…], pending:[…]}` from the local synced store. Bound = approved or revoked (flagged); pending = neither. Rows: `{id, name, kind, approved, revoked, is_this_pc}`. |
| `POST /devices/{id}/approve` | yes | PC-approves-device via the PC device token (`AccountLink.approveWithDevice`), then a sync; `{ok, result}` (+ `sync_warning` if the follow-up sync failed). 502 on service error. |
| `POST /devices/{id}/revoke` | yes | The service accepts only an **account session** here (device token → 401), so the engine mints one from the stored refresh token (`AccountLink.accessViaRefresh`, rotating + re-saving) and revokes with it. A dead session answers **401 `{error:"session_expired"}`** so the shell drops back to the login card. |
| `GET /sessions?limit=` | no | Count-level session rows, newest first: `{sessions:[{ts, device_id, files_ok, bytes, result}]}` (limit default 50, max 200). |
| `GET /records?limit&offset` | yes | Page of the files table, newest backup first: `{total, records:[{id, device_id, path, size, sha256, backed_at, integrity, thumb, kind, mime}]}`. `kind` ∈ image/video/other by extension. |
| `GET /records/{id}/thumb` | no | Stored thumbnail bytes (`image/jpeg`); 404 JSON when absent. |
| `GET /records/{id}/preview` | no | Full bytes for web-renderable images (png/jpg/gif/bmp/webp); the JPEG thumbnail stands in for HEIC and for videos (poster frame, header `X-Preview-Fallback: thumb`); 404 JSON when nothing is available. Videos are *played* by opening the stored file in the system player from the UI (the preview pane shows the poster + ▶). |
| `GET /logs?tail=` | no | `{lines:[…]}` — tail of the receiver's in-memory log ring (400 lines; `log()` mirrors into it; same lines the process prints). |
| `POST /account/login` | no | Body `{server?, account, password}` (`server` defaults to the public endpoint). Drives `AccountLink.loginFlow` and persists `account.json`. Answers `{ok, restart_required:true, user_id, account, network}`; 401/403 on bad credentials/disabled, 502 otherwise. |
| `POST /account/logout` | no | `AccountLink.logoutSession` (best-effort server revocation + wipe). `{ok, restart_required:true}`. |
| `POST /sync` | yes | Forces `AccountLink.syncOnce`; `{ok, summary}` or 502. |

Errors: 400 bad input · 401 bad token or `session_expired` · 404 no
such endpoint/record · **409 `{error:"no_account"}`** when an endpoint
needs a session the engine doesn't have · 500/502 upstream failures.

**`restart_required` semantics**: login/logout change the engine's
identity (mesh network, bind address, sidecar config), so the shell
restarts the engine child after a successful call and the fresh process
comes up exactly as a normal launch would. Session persistence rules
are unchanged — see [session-persistence.md](session-persistence.md).

Also fixed on the way: `Store.listSessions` ordered by a nonexistent
`id` column (sessions has no PK) → `ORDER BY rowid DESC`. The Swing
session view was silently broken by the same bug.

## Shell (desktop/)

- `src-tauri/` — Rust: config persistence, engine lifecycle
  (spawn → wait for `control.json` pid → endpoint; SIGTERM-then-kill
  shutdown so JVM hooks run), tray （打开/退出； window close parks in
  the tray), `open_path`, autostart, and the `engine_endpoint` /
  `restart_engine` / `get_config` / `save_shell_config` / `shell_info`
  / `set_autostart` / `quit_app` commands. No Tauri plugins.
- `src/` — the frontend (vanilla): boot wait → login card (design
  frame) → sidebar shell with 概览 / 设备 / 存储 / 备份记录 / 日志 /
  设置， matching design v1.1 (palette, pending-approval banner +
  inline approve/reject, storage bar, records preview pane).
- Engine spawn:
  `java -cp app/classes:app/lib/sqlite-jdbc-3.47.1.0.jar:app/lib/slf4j-api-2.0.16.jar BackupReceiver --account --control --port <cfg> --target <cfg> --data-dir <cfg> [--mesh-bin app/easytier-core]`,
  stdout/stderr appended to `<data-dir>/engine-shell.log`.

### Engine layout & resolution

```
<engine>/
  runtime/bin/java            bundled JRE (java.exe on Windows)
  app/classes/                compiled receiver classes
  app/lib/sqlite-jdbc-3.47.1.0.jar, slf4j-api-2.0.16.jar
  app/easytier-core           sidecar for the target OS
```

Resolution order: `$NB_DESKTOP_ENGINE_DIR` → `<exe dir>/engine` →
Tauri resource dir `engine/` (bundled from `desktop/src-tauri/engine/`,
assembled by packaging/CI — the repo carries only a README there).
Dev overrides: `$NB_DESKTOP_JAVA` (java binary), `$NB_DESKTOP_ENGINE_ARGS`
(extra engine args, e.g. `--no-mesh --bind 127.0.0.1` for safe tests).

Shell config: `<config dir>/cn.niubackup.desktop/shell-config.json`
(`server`, `target_dir`, `data_dir`, `backup_port`, `autostart`).
Defaults: server = public endpoint (above), target `~/NiuBackup`,
data dir `~/.niubackup` (same as the Swing app, so accounts carry over),
port 28741.

### Autostart

- **Linux (implemented)**: XDG autostart entry
  `~/.config/autostart/niubackup-desktop.desktop` running the shell
  (which spawns the engine). The headless systemd-user pattern in
  `packaging/` remains available for the engine alone.
- Windows (Run key) / macOS (LaunchAgent): not yet implemented in the
  shell — `set_autostart` returns an explicit error there.

### Linux privilege note (unchanged)

The easytier sidecar needs `CAP_NET_ADMIN` to create its TUN:
`setcap cap_net_admin,cap_net_raw+ep <engine>/app/easytier-core` at
install time (same as the jpackage flow's install script).

## Build

Dev (this laptop): no Node needed —

```bash
# deps (once): libwebkit2gtk-4.1-dev libgtk-3-dev libsoup-3.0-dev
#   libayatana-appindicator3-dev librsvg2-dev libssl-dev (via apt)
export PATH=$HOME/.cargo/bin:$PATH        # crates via rsproxy.cn mirror
cd desktop/src-tauri && cargo build --release
# binary: target/release/niubackup-desktop
```

Packaging (deb/AppImage/msi/dmg) goes through the Tauri CLI in
`.github/workflows/desktop-packages.yml`, which assembles the engine
layout (JDK 21 → `engine/runtime`, compiled classes + jars →
`engine/app`, easytier-core release → `engine/app`) before
`npx tauri build`. The old jpackage workflow
(`pc-packages.yml`) is kept until parity is confirmed.

## Verification (2026-10-31, laptop)

- Control API smoke (scratch data dir, disposable test account, engine
  bound to loopback with `--no-mesh` so the live test receiver in
  `~/.niubackup-shouji` was never touched): 401 without token →
  control-only `/status` → `POST /account/login` **via the public URL**
  → restart → `/status` live (license/storage) → `/devices` →
  **approve of a pending phone succeeded** → real sender backup (2
  files, receipt signature VALID) → `/sessions`, `/records`,
  thumb bytes (JPEG), image preview (PNG bytes), video preview
  (poster), 404 JSON, `/logs` — all pass. Logout live-verified too
  (`account.json` wiped, status back to `account: null`), and revoke
  via the refresh-minted session returned 200.
- Tauri shell: `cargo build --release` on the laptop; launched under
  the user's graphical session against the scratch data dir — see the
  delivery report for the process/HTTP evidence and what visual check
  was possible.
