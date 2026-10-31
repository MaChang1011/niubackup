//! 牛备 NiuBackup — Tauri desktop shell.
//!
//! The shell is deliberately thin: all backup logic lives in the Java
//! receiver engine (`BackupReceiver --account --control`), which this
//! process spawns as a managed child and talks to over its loopback
//! control API (127.0.0.1 + per-process token in <data-dir>/control.json,
//! see docs/tauri-shell.md). The frontend renders the API data; the shell
//! only owns the process lifecycle, the tray, autostart, and small
//! OS-level helpers (open path, config persistence).

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, State, WindowEvent};

/// Default account-service address (user decision 2026-10-31): the PUBLIC
/// endpoint — login must work before any mesh exists. The field stays
/// editable everywhere; a saved user-entered address wins over this.
pub const DEFAULT_SERVER: &str = "http://36.133.114.164:18090";
pub const DEFAULT_BACKUP_PORT: u16 = 28741;

// ------------------------------------------------------------------ config

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ShellConfig {
    /// Account-service base URL (prefills the login card; saved on login).
    pub server: String,
    /// Engine --target (where backups land).
    pub target_dir: String,
    /// Engine --data-dir (account.json, records.db, control.json).
    pub data_dir: String,
    /// Backup protocol port passed to the engine.
    pub backup_port: u16,
    /// Start the desktop app at login (XDG autostart on Linux).
    pub autostart: bool,
}

fn home_dir() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."))
}

impl Default for ShellConfig {
    fn default() -> Self {
        let home = home_dir();
        ShellConfig {
            server: DEFAULT_SERVER.to_string(),
            target_dir: home.join("NiuBackup").to_string_lossy().to_string(),
            data_dir: home.join(".niubackup").to_string_lossy().to_string(),
            backup_port: DEFAULT_BACKUP_PORT,
            autostart: false,
        }
    }
}

fn config_file(app: &AppHandle) -> Option<PathBuf> {
    // App-scoped config dir: ~/.config/cn.niubackup.desktop/ on Linux.
    app.path()
        .app_config_dir()
        .ok()
        .map(|d| d.join("shell-config.json"))
}

fn load_config(app: &AppHandle) -> ShellConfig {
    let mut cfg = ShellConfig::default();
    if let Some(f) = config_file(app) {
        if let Ok(text) = fs::read_to_string(&f) {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
                if let Some(s) = v.get("server").and_then(|x| x.as_str()) {
                    if !s.is_empty() {
                        cfg.server = s.to_string();
                    }
                }
                if let Some(s) = v.get("target_dir").and_then(|x| x.as_str()) {
                    if !s.is_empty() {
                        cfg.target_dir = s.to_string();
                    }
                }
                if let Some(s) = v.get("data_dir").and_then(|x| x.as_str()) {
                    if !s.is_empty() {
                        cfg.data_dir = s.to_string();
                    }
                }
                if let Some(n) = v.get("backup_port").and_then(|x| x.as_u64()) {
                    cfg.backup_port = n as u16;
                }
                if let Some(b) = v.get("autostart").and_then(|x| x.as_bool()) {
                    cfg.autostart = b;
                }
            }
        }
    }
    cfg
}

fn save_config(app: &AppHandle, cfg: &ShellConfig) -> Result<(), String> {
    let f = config_file(app).ok_or("no config dir")?;
    if let Some(parent) = f.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let text = serde_json::to_string_pretty(cfg).map_err(|e| e.to_string())?;
    fs::write(&f, text).map_err(|e| e.to_string())
}

// ------------------------------------------------------------------ engine

#[derive(Serialize, Clone, Debug)]
pub struct Endpoint {
    pub base: String,
    pub token: String,
    pub port: u16,
}

#[derive(Serialize, Clone, Debug)]
pub struct ShellInfo {
    pub engine_dir: Option<String>,
    pub java: Option<String>,
    pub config_path: Option<String>,
    pub exe: String,
}

pub struct ShellState {
    cfg: Mutex<ShellConfig>,
    child: Mutex<Option<Child>>,
    endpoint: Mutex<Option<Endpoint>>,
    quitting: Mutex<bool>,
}

/// Resolve the engine directory: env override → next to the exe → bundle
/// resources. Layout inside: runtime/bin/java, app/classes, app/lib/*.jar,
/// app/easytier-core. See docs/tauri-shell.md.
fn engine_dir(app: &AppHandle) -> Option<PathBuf> {
    if let Ok(d) = std::env::var("NB_DESKTOP_ENGINE_DIR") {
        let p = PathBuf::from(d);
        if p.is_dir() {
            return Some(p);
        }
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let p = dir.join("engine");
            if p.is_dir() {
                return Some(p);
            }
        }
    }
    if let Ok(res) = app.path().resource_dir() {
        let p = res.join("engine");
        if p.is_dir() {
            return Some(p);
        }
    }
    None
}

fn java_bin(engine: &PathBuf) -> PathBuf {
    if let Ok(j) = std::env::var("NB_DESKTOP_JAVA") {
        return PathBuf::from(j);
    }
    let name = if cfg!(windows) { "java.exe" } else { "java" };
    engine.join("runtime").join("bin").join(name)
}

fn spawn_engine(app: &AppHandle, state: &State<ShellState>) -> Result<(), String> {
    let cfg = state.cfg.lock().unwrap().clone();
    let engine = engine_dir(app).ok_or_else(|| {
        "engine directory not found (set NB_DESKTOP_ENGINE_DIR or package the engine/ layout)"
            .to_string()
    })?;
    let java = java_bin(&engine);
    let appdir = engine.join("app");
    let sep = if cfg!(windows) { ";" } else { ":" };
    let cp = format!(
        "{}{}{}{}{}",
        appdir.join("classes").to_string_lossy(),
        sep,
        appdir
            .join("lib")
            .join("sqlite-jdbc-3.47.1.0.jar")
            .to_string_lossy(),
        sep,
        appdir
            .join("lib")
            .join("slf4j-api-2.0.16.jar")
            .to_string_lossy()
    );
    fs::create_dir_all(&cfg.data_dir).map_err(|e| e.to_string())?;
    fs::create_dir_all(&cfg.target_dir).map_err(|e| e.to_string())?;

    let log_path = PathBuf::from(&cfg.data_dir).join("engine-shell.log");
    let log_file = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&log_path)
        .map_err(|e| e.to_string())?;
    let log_file2 = log_file.try_clone().map_err(|e| e.to_string())?;

    let mut cmd = Command::new(&java);
    cmd.arg("-cp")
        .arg(&cp)
        .arg("BackupReceiver")
        .arg("--account")
        .arg("--control")
        .arg("--port")
        .arg(cfg.backup_port.to_string())
        .arg("--target")
        .arg(&cfg.target_dir)
        .arg("--data-dir")
        .arg(&cfg.data_dir);
    let et = appdir.join(if cfg!(windows) {
        "easytier-core.exe"
    } else {
        "easytier-core"
    });
    if et.is_file() {
        cmd.arg("--mesh-bin").arg(et.to_string_lossy().to_string());
    }
    // Dev/test hook: extra engine args (e.g. --no-mesh --bind 127.0.0.1).
    if let Ok(extra) = std::env::var("NB_DESKTOP_ENGINE_ARGS") {
        for a in extra.split_whitespace() {
            cmd.arg(a);
        }
    }
    cmd.stdin(Stdio::null())
        .stdout(Stdio::from(log_file))
        .stderr(Stdio::from(log_file2));
    let child = cmd
        .spawn()
        .map_err(|e| format!("failed to start engine ({}): {}", java.display(), e))?;
    *state.child.lock().unwrap() = Some(child);
    Ok(())
}

fn terminate_engine(state: &State<ShellState>) {
    let mut slot = state.child.lock().unwrap();
    if let Some(mut child) = slot.take() {
        let pid = child.id();
        #[cfg(unix)]
        {
            // SIGTERM first: the JVM runs its shutdown hooks (sidecar stop,
            // control.json cleanup) on TERM; SIGKILL would skip them.
            let _ = Command::new("kill").arg("-TERM").arg(pid.to_string()).status();
            let deadline = Instant::now() + Duration::from_secs(4);
            loop {
                match child.try_wait() {
                    Ok(Some(_)) => break,
                    Ok(None) => {
                        if Instant::now() > deadline {
                            let _ = child.kill();
                            let _ = child.wait();
                            break;
                        }
                        std::thread::sleep(Duration::from_millis(150));
                    }
                    Err(_) => {
                        let _ = child.kill();
                        break;
                    }
                }
            }
        }
        #[cfg(not(unix))]
        {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

/// Poll control.json until it names OUR child pid (a stale file from a
/// previous engine must not be trusted), then cache the endpoint.
fn wait_endpoint(state: &State<ShellState>, timeout: Duration) -> Result<Endpoint, String> {
    let cfg = state.cfg.lock().unwrap().clone();
    let control = PathBuf::from(&cfg.data_dir).join("control.json");
    let want_pid = state
        .child
        .lock()
        .unwrap()
        .as_ref()
        .map(|c| c.id())
        .ok_or("engine not running")?;
    let deadline = Instant::now() + timeout;
    loop {
        if let Ok(text) = fs::read_to_string(&control) {
            if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
                let pid = v.get("pid").and_then(|x| x.as_u64()).unwrap_or(0) as u32;
                let port = v.get("port").and_then(|x| x.as_u64()).unwrap_or(0) as u16;
                let token = v
                    .get("token")
                    .and_then(|x| x.as_str())
                    .unwrap_or("")
                    .to_string();
                if pid == want_pid && port > 0 && !token.is_empty() {
                    let ep = Endpoint {
                        base: format!("http://127.0.0.1:{}", port),
                        token,
                        port,
                    };
                    *state.endpoint.lock().unwrap() = Some(ep.clone());
                    return Ok(ep);
                }
            }
        }
        if Instant::now() > deadline {
            return Err("engine control API did not come up in time".to_string());
        }
        std::thread::sleep(Duration::from_millis(250));
    }
}

// ------------------------------------------------------------------ commands

#[tauri::command]
fn get_config(state: State<ShellState>) -> ShellConfig {
    state.cfg.lock().unwrap().clone()
}

#[tauri::command]
fn save_shell_config(
    app: AppHandle,
    state: State<ShellState>,
    config: ShellConfig,
) -> Result<(), String> {
    save_config(&app, &config)?;
    *state.cfg.lock().unwrap() = config;
    Ok(())
}

#[tauri::command]
fn shell_info(app: AppHandle) -> ShellInfo {
    let engine = engine_dir(&app);
    ShellInfo {
        java: engine.as_ref().map(|e| java_bin(e).to_string_lossy().to_string()),
        engine_dir: engine.map(|e| e.to_string_lossy().to_string()),
        config_path: config_file(&app).map(|p| p.to_string_lossy().to_string()),
        exe: std::env::current_exe()
            .map(|p| p.to_string_lossy().to_string())
            .unwrap_or_default(),
    }
}

#[tauri::command]
fn engine_endpoint(state: State<ShellState>) -> Result<Endpoint, String> {
    if let Some(ep) = state.endpoint.lock().unwrap().clone() {
        // Re-validate the pid binding cheaply: if the child died, drop it.
        let alive = state
            .child
            .lock()
            .unwrap()
            .as_mut()
            .map(|c| c.try_wait().ok().flatten().is_none())
            .unwrap_or(false);
        if alive {
            return Ok(ep);
        }
        *state.endpoint.lock().unwrap() = None;
    }
    wait_endpoint(&state, Duration::from_secs(3))
}

#[tauri::command]
fn restart_engine(app: AppHandle, state: State<ShellState>) -> Result<Endpoint, String> {
    terminate_engine(&state);
    *state.endpoint.lock().unwrap() = None;
    spawn_engine(&app, &state)?;
    wait_endpoint(&state, Duration::from_secs(25))
}

#[tauri::command]
fn open_path(path: String) -> Result<(), String> {
    #[cfg(target_os = "linux")]
    let mut cmd = {
        let mut c = Command::new("xdg-open");
        c.arg(&path);
        c
    };
    #[cfg(target_os = "macos")]
    let mut cmd = {
        let mut c = Command::new("open");
        c.arg(&path);
        c
    };
    #[cfg(target_os = "windows")]
    let mut cmd = {
        let mut c = Command::new("explorer");
        c.arg(&path);
        c
    };
    cmd.stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| e.to_string())?;
    Ok(())
}

/// Linux autostart = XDG autostart entry running this shell (which in turn
/// spawns the engine). Windows/macOS mechanisms are noted in
/// docs/tauri-shell.md; on those platforms this reports unsupported for now.
#[tauri::command]
fn set_autostart(app: AppHandle, state: State<ShellState>, enabled: bool) -> Result<(), String> {
    #[cfg(target_os = "linux")]
    {
        let dir = home_dir().join(".config").join("autostart");
        let file = dir.join("niubackup-desktop.desktop");
        if enabled {
            fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
            let exe = std::env::current_exe().map_err(|e| e.to_string())?;
            let content = format!(
                "[Desktop Entry]\nType=Application\nName=NiuBackup\nComment=牛备 NiuBackup 电脑端\nExec={}\nIcon=niubackup\nTerminal=false\nX-GNOME-Autostart-enabled=true\n",
                exe.to_string_lossy()
            );
            fs::write(&file, content).map_err(|e| e.to_string())?;
        } else if file.exists() {
            fs::remove_file(&file).map_err(|e| e.to_string())?;
        }
        let mut cfg = state.cfg.lock().unwrap();
        cfg.autostart = enabled;
        save_config(&app, &cfg)?;
        Ok(())
    }
    #[cfg(not(target_os = "linux"))]
    {
        let _ = (app, state, enabled);
        Err("autostart toggle is implemented for Linux only in this build".to_string())
    }
}

#[tauri::command]
fn quit_app(app: AppHandle, state: State<ShellState>) {
    *state.quitting.lock().unwrap() = true;
    terminate_engine(&state);
    app.exit(0);
}

// ------------------------------------------------------------------ app

fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.set_focus();
    }
}

pub fn run() {
    let state = ShellState {
        cfg: Mutex::new(ShellConfig::default()),
        child: Mutex::new(None),
        endpoint: Mutex::new(None),
        quitting: Mutex::new(false),
    };
    tauri::Builder::default()
        .manage(state)
        .invoke_handler(tauri::generate_handler![
            get_config,
            save_shell_config,
            shell_info,
            engine_endpoint,
            restart_engine,
            open_path,
            set_autostart,
            quit_app
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            {
                let st = handle.state::<ShellState>();
                *st.cfg.lock().unwrap() = load_config(&handle);
            }
            // Spawn the engine in the background so the window opens
            // immediately; the frontend polls engine_endpoint().
            let h2 = handle.clone();
            std::thread::spawn(move || {
                let st2 = h2.state::<ShellState>();
                if let Err(e) = spawn_engine(&h2, &st2) {
                    eprintln!("engine spawn failed: {}", e);
                }
            });

            // Tray: status + 打开/退出.
            let open_item = MenuItem::with_id(app, "open", "打开牛备", true, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit", "退出牛备", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open_item, &quit_item])?;
            let mut tray = TrayIconBuilder::with_id("niubackup-tray")
                .tooltip("牛备 NiuBackup")
                .menu(&menu)
                .show_menu_on_left_click(false);
            if let Some(icon) = app.default_window_icon() {
                tray = tray.icon(icon.clone());
            }
            tray.on_menu_event(|app, event| match event.id.as_ref() {
                "open" => show_main(app),
                "quit" => {
                    let st = app.state::<ShellState>();
                    *st.quitting.lock().unwrap() = true;
                    terminate_engine(&st);
                    app.exit(0);
                }
                _ => {}
            })
            .on_tray_icon_event(|tray, event| {
                if let TrayIconEvent::Click {
                    button: MouseButton::Left,
                    button_state: MouseButtonState::Up,
                    ..
                } = event
                {
                    show_main(tray.app_handle());
                }
            })
            .build(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            // Closing the window parks the app in the tray; the engine
            // keeps receiving. Real exit goes through the tray / 退出.
            if let WindowEvent::CloseRequested { api, .. } = event {
                let app = window.app_handle();
                let st = app.state::<ShellState>();
                if !*st.quitting.lock().unwrap() {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running the NiuBackup desktop shell");
}
