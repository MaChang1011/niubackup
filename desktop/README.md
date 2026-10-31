# 牛备 NiuBackup — desktop (Tauri shell)

Tauri v2 shell + vanilla HTML/CSS/JS frontend for the PC client. The
shell supervises the Java receiver engine (`BackupReceiver --account
--control`) and renders its loopback control API. Full design,
endpoint spec, engine layout, and parity notes:
[docs/tauri-shell.md](../docs/tauri-shell.md).

Layout:

- `src/` — frontend (served from `frontendDist`; no build step)
- `src-tauri/` — Rust shell (config, engine lifecycle, tray, autostart)
- `src-tauri/engine/` — packaging-time engine bundle (README only in git)

Dev build on the laptop (no Node required):

```bash
export PATH=$HOME/.cargo/bin:$PATH
cd src-tauri && cargo build --release
NB_DESKTOP_ENGINE_DIR=<engine layout> ./target/release/niubackup-desktop
```
