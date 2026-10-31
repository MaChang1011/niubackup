// 牛备 NiuBackup desktop frontend.
// All engine control-API traffic rides the Tauri command channel
// (invoke api_request → the Rust shell performs the loopback HTTP call):
// the WebView's own network stack cannot reach 127.0.0.1 on this
// platform ("Connection refused" from libsoup while the port is fine).
// Media bytes come back base64-wrapped and are shown via blob URLs.
"use strict";
const { invoke } = window.__TAURI__.core;

const $ = (id) => document.getElementById(id);
const DEFAULT_SERVER = "http://36.133.114.164:18090";

let cfg = null;          // shell config
let ep = null;           // {base, token, port}
let status = null;       // last /status payload
let page = "overview";
let blobUrls = [];
let recPage = 0;         // 0-based; design v1.3: 50/page + page jump
let recTotal = 0;
const REC_PAGE = 50;
let timers = [];
let bannerDismissed = false;

// ------------------------------------------------------------------ utils

function humanBytes(n) {
  if (n == null) return "—";
  if (n < 1024) return n + " B";
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + " KB";
  if (n < 1024 * 1024 * 1024) return (n / 1024 / 1024).toFixed(1) + " MB";
  return (n / 1024 / 1024 / 1024).toFixed(2) + " GB";
}
function fmtTime(ms) {
  if (!ms) return "—";
  return new Date(ms).toLocaleString("zh-CN", { hour12: false });
}
function fmtDay(ms) {
  if (!ms) return "—";
  return new Date(ms).toLocaleString("zh-CN", {
    hour12: false, month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit",
  });
}
function esc(s) {
  return String(s == null ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}
function toast(msg) {
  const t = $("toast");
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast._h);
  toast._h = setTimeout(() => { t.hidden = true; }, 3400);
}
function trackBlob(url) { blobUrls.push(url); return url; }
function revokeBlobs() { blobUrls.forEach((u) => URL.revokeObjectURL(u)); blobUrls = []; }

// ------------------------------------------------------------------ API

function b64Bytes(b64) {
  const bin = atob(b64 || "");
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}
function b64Text(b64) {
  return new TextDecoder("utf-8").decode(b64Bytes(b64));
}

async function api(path, opts = {}) {
  const r = await invoke("api_request", {
    method: opts.method || "GET",
    path,
    body: opts.body ? opts.body : null,
  });
  const text = b64Text(r.bodyBase64);
  let json = null;
  try { json = JSON.parse(text); } catch (e) { /* byte endpoints handled elsewhere */ }
  if (r.status < 200 || r.status >= 300) {
    const err = new Error((json && (json.error || json.detail)) || ("HTTP " + r.status));
    err.status = r.status;
    err.body = json;
    throw err;
  }
  return json;
}

async function apiBlob(path) {
  const r = await invoke("api_request", { method: "GET", path, body: null });
  if (r.status < 200 || r.status >= 300) throw new Error("HTTP " + r.status);
  const blob = new Blob([b64Bytes(r.bodyBase64)], {
    type: r.contentType || "application/octet-stream",
  });
  return trackBlob(URL.createObjectURL(blob));
}

// ------------------------------------------------------------------ views

function show(view) {
  $("bootView").hidden = view !== "boot";
  $("loginView").hidden = view !== "login";
  $("appView").hidden = view !== "app";
}

function stopTimers() { timers.forEach(clearInterval); timers = []; }

// ------------------------------------------------------------------ boot

async function boot() {
  show("boot");
  try {
    cfg = await invoke("get_config");
  } catch (e) {
    cfg = { server: DEFAULT_SERVER };
  }
  let lastErr = "";
  for (let i = 0; i < 40; i++) {
    try {
      ep = await invoke("engine_endpoint");
      break;
    } catch (e) {
      lastErr = String(e);
      $("bootMsg").textContent = "正在等待接收引擎启动…";
      await new Promise((r) => setTimeout(r, 1500));
    }
  }
  if (!ep) {
    $("bootMsg").textContent = "接收引擎没有响应";
    $("bootErr").textContent = lastErr;
    $("bootRetry").hidden = false;
    return;
  }
  await enterByStatus();
}

async function enterByStatus() {
  try {
    status = await api("/status");
  } catch (e) {
    $("bootMsg").textContent = "控制接口无响应";
    $("bootErr").textContent = String(e.message || e);
    $("bootRetry").hidden = false;
    show("boot");
    return;
  }
  if (!status.account) {
    $("loginServer").value = (cfg && cfg.server) || DEFAULT_SERVER;
    $("loginErr").textContent = "";
    show("login");
    switchLoginTab("qr");
  } else {
    show("app");
    afterLogin();
  }
}

function afterLogin() {
  stopQr();
  $("sideAccount").textContent = status.account.account || "";
  goto("overview");
  stopTimers();
  timers.push(setInterval(async () => {
    if ($("appView").hidden) return;
    try {
      status = await api("/status");
      if (page === "overview") renderOverview();
      if (page === "storage") renderStorage();
    } catch (e) { /* transient */ }
  }, 5000));
}

// ------------------------------------------------------------------ login: QR tab (design v1.4, default)

let qrTicket = null;     // {id, secret, expiresAtMs}
let qrPollTimer = null;
let qrCountTimer = null;

function stopQr() {
  if (qrPollTimer) clearInterval(qrPollTimer);
  if (qrCountTimer) clearInterval(qrCountTimer);
  qrPollTimer = null;
  qrCountTimer = null;
  qrTicket = null;
}

function switchLoginTab(which) {
  const qr = which === "qr";
  $("tabQr").classList.toggle("active", qr);
  $("tabPwd").classList.toggle("active", !qr);
  $("qrPane").hidden = !qr;
  $("loginCard").hidden = qr;
  if (qr) {
    startQrLogin();
  } else {
    stopQr();
    $("loginAccount").focus();
  }
}

$("tabQr").addEventListener("click", () => switchLoginTab("qr"));
$("tabPwd").addEventListener("click", () => switchLoginTab("pwd"));
$("qrRefresh").addEventListener("click", () => startQrLogin());

async function startQrLogin() {
  stopQr();
  $("qrBox").innerHTML = '<span class="qr-hint">二维码生成中…</span>';
  $("qrStatus").textContent = "正在向账号服务申请二维码…";
  $("qrCount").textContent = "";
  $("qrRefresh").hidden = true;
  try {
    const r = await api("/account/qr-ticket", {
      method: "POST",
      body: { server: (cfg && cfg.server) || DEFAULT_SERVER },
    });
    qrTicket = { id: r.ticket_id, secret: r.secret, expiresAtMs: r.expires_at_ms };
    renderQr(r.qr_payload);
    $("qrStatus").textContent = "用手机牛备 App 扫码，在手机上确认";
    tickQrCountdown();
    qrCountTimer = setInterval(tickQrCountdown, 500);
    qrPollTimer = setInterval(pollQrTicket, 2000);
  } catch (e) {
    $("qrStatus").textContent = "二维码申请失败：" + (e.message || e);
    $("qrRefresh").hidden = false;
  }
}

function renderQr(payload) {
  try {
    // qrcode-generator (vendored qrcode.js, MIT): type 0 = auto version.
    const q = qrcode(0, "M");
    q.addData(payload);
    q.make();
    $("qrBox").innerHTML = q.createSvgTag(5, 4);
    const svg = $("qrBox").querySelector("svg");
    if (svg) { svg.style.width = "196px"; svg.style.height = "196px"; }
  } catch (e) {
    $("qrBox").innerHTML = '<span class="qr-hint">二维码渲染失败</span>';
  }
}

function tickQrCountdown() {
  if (!qrTicket) return;
  const left = Math.max(0, Math.round((qrTicket.expiresAtMs - Date.now()) / 1000));
  const mm = Math.floor(left / 60);
  const ss = String(left % 60).padStart(2, "0");
  $("qrCount").textContent = left > 0 ? `有效期还剩 ${mm}:${ss}` : "";
  if (left <= 0) {
    stopQr();
    $("qrStatus").textContent = "二维码已过期，正在刷新…";
    setTimeout(() => { if (!$("loginView").hidden) startQrLogin(); }, 600);
  }
}

async function pollQrTicket() {
  if (!qrTicket) return;
  const t = qrTicket;
  let r;
  try {
    r = await api(`/account/qr-ticket/${encodeURIComponent(t.id)}?secret=${encodeURIComponent(t.secret)}`);
  } catch (e) {
    return; // transient — the next 2 s tick retries
  }
  if (!qrTicket || qrTicket.id !== t.id) return; // refreshed meanwhile
  if (r.status === "approved") {
    stopQr();
    $("qrStatus").textContent = "手机已确认，正在登录…";
    toast("扫码登录成功，正在以新身份重启引擎…");
    try {
      ep = await invoke("restart_engine");
    } catch (e) { /* waitForAccount surfaces the failure */ }
    await waitForAccount(true);
  } else if (r.status === "scanned") {
    $("qrStatus").textContent = "已扫码，请在手机上确认";
  } else if (r.status === "expired" || r.status === "consumed" || r.status === "cancelled") {
    stopQr();
    $("qrStatus").textContent = "二维码已失效，正在刷新…";
    setTimeout(() => { if (!$("loginView").hidden) startQrLogin(); }, 600);
  }
}

// ------------------------------------------------------------------ login: password tab / logout

$("loginCard").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const server = $("loginServer").value.trim() || DEFAULT_SERVER;
  const account = $("loginAccount").value.trim();
  const password = $("loginPassword").value;
  if (!account || !password) {
    $("loginErr").textContent = "账号和密码都要填";
    return;
  }
  const btn = $("loginBtn");
  btn.disabled = true;
  btn.textContent = "登录中…";
  $("loginErr").textContent = "";
  try {
    const r = await api("/account/login", {
      method: "POST",
      body: { server, account, password },
    });
    // A saved, explicitly entered address wins over the default from now on.
    cfg.server = server;
    try { await invoke("save_shell_config", { config: cfg }); } catch (e) { /* non-fatal */ }
    toast("登录成功，正在以新身份重启引擎…");
    ep = await invoke("restart_engine");
    $("loginPassword").value = "";
    await waitForAccount(false);
  } catch (e) {
    $("loginErr").textContent = loginErrorText(e);
  } finally {
    btn.disabled = false;
    btn.textContent = "登录";
  }
});

function loginErrorText(e) {
  const b = e && e.body;
  if (b && b.error) {
    if (String(b.error).includes("bad_credentials")) return "账号或密码不正确";
    if (String(b.error).includes("user_disabled")) return "该账号已被停用";
    return "登录失败：" + b.error;
  }
  return "登录失败：" + (e.message || e);
}

async function waitForAccount(fromQr) {
  for (let i = 0; i < 40; i++) {
    try {
      status = await api("/status");
      if (status.account) {
        show("app");
        afterLogin();
        return;
      }
    } catch (e) { /* engine still restarting */ }
    await new Promise((r) => setTimeout(r, 1200));
  }
  show("login");
  if (fromQr) {
    switchLoginTab("qr");
    $("qrStatus").textContent = "引擎已重启，但仍未读到登录状态，请刷新二维码重试";
  } else {
    // Back to the password tab so the error below is actually visible.
    switchLoginTab("pwd");
    $("loginErr").textContent = "引擎已重启，但仍未读到登录状态，请再试一次";
  }
}

async function doLogout() {
  try {
    await api("/account/logout", { method: "POST", body: {} });
  } catch (e) { /* wipe happens regardless on restart */ }
  stopTimers();
  stopQr();
  toast("已退出登录，正在重启引擎…");
  try { ep = await invoke("restart_engine"); } catch (e) { /* boot loop will recover */ }
  status = null;
  show("login");
  $("loginServer").value = (cfg && cfg.server) || DEFAULT_SERVER;
  $("loginErr").textContent = "";
  switchLoginTab("qr");
}

$("logoutBtn").addEventListener("click", doLogout);
$("bootRetry").addEventListener("click", async () => {
  $("bootErr").textContent = "";
  $("bootRetry").hidden = true;
  $("bootMsg").textContent = "正在重启接收引擎…";
  try {
    ep = await invoke("restart_engine");
    await enterByStatus();
  } catch (e) {
    $("bootErr").textContent = String(e);
    $("bootRetry").hidden = false;
  }
});

// ------------------------------------------------------------------ nav

$("nav").addEventListener("click", (ev) => {
  const b = ev.target.closest("[data-page]");
  if (b) goto(b.dataset.page);
});
$("bannerClose").addEventListener("click", () => {
  bannerDismissed = true;
  $("autoJoinBanner").hidden = true;
});

function goto(p) {
  page = p;
  document.querySelectorAll(".nav-item").forEach((n) =>
    n.classList.toggle("active", n.dataset.page === p));
  ["overview", "devices", "storage", "records", "logs", "settings"].forEach((x) => {
    $("page-" + x).hidden = x !== p;
  });
  if (p === "overview") renderOverview();
  if (p === "devices") renderDevices();
  if (p === "storage") renderStorage();
  if (p === "records") loadRecords(true);
  if (p === "logs") renderLogs();
  if (p === "settings") renderSettings();
}

// ------------------------------------------------------------------ 概览

async function renderOverview() {
  if (!status) return;
  const r = status.receiver || {};
  $("ovService").innerHTML =
    (r.serving ? '<span class="dot green"></span>运行中' : '<span class="dot red"></span>未运行');
  $("ovServiceSub").textContent =
    `备份端口 ${r.backup_port} · 绑定 ${r.bind || "全部网卡"}\n存储目录 ${r.target_dir || ""}`;
  const m = status.mesh || { state: "off" };
  if (m.state === "up") {
    const peers = m.peers || [];
    const p2p = peers.filter((x) => x.route === "p2p").length;
    $("ovMesh").innerHTML = '<span class="dot green"></span>已连接';
    $("ovMeshSub").textContent =
      `安全模式 ${m.secure_mode ? "开" : "关"} · 对端 ${peers.length} 个（直连 ${p2p}）` +
      (status.account ? `\n网络 ${status.account.network} · 本机 ${status.account.pc_ip}` : "");
  } else if (m.state === "down") {
    $("ovMesh").innerHTML = '<span class="dot red"></span>组网未运行';
    $("ovMeshSub").textContent = "easytier sidecar 未在运行，请看日志页";
  } else {
    $("ovMesh").innerHTML = '<span class="dot slate"></span>未启用';
    $("ovMeshSub").textContent = "本机未启动组网 sidecar";
  }
  if (status.account) {
    $("ovAccount").textContent = status.account.account;
    // v1.5: no license/expiry copy anywhere — one flat statement.
    $("ovAccountSub").textContent =
      "开源免费 · 永久可用" +
      (status.last_sync_ok_ms ? `\n上次同步 ${fmtDay(status.last_sync_ok_ms)}` : "");
  }
  const st = status.storage || {};
  const pct = st.quota_bytes ? Math.min(100, (st.used_bytes / st.quota_bytes) * 100) : 0;
  $("ovBarFill").style.width = pct.toFixed(1) + "%";
  $("ovBarFill").classList.toggle("hot", pct >= 90);
  $("ovStorageSub").textContent =
    `已用 ${humanBytes(st.used_bytes)}${st.quota_bytes ? " / 配额 " + humanBytes(st.quota_bytes) : ""} · 共 ${st.files || 0} 个文件`;
  // Auto-join notice (design v1.4): informational only, dismissible for
  // the session — there is no approval queue anymore.
  $("autoJoinBanner").hidden = bannerDismissed;
  try {
    const ss = await api("/sessions?limit=6");
    const box = $("ovSessions");
    if (!ss.sessions.length) {
      box.innerHTML = '<div class="empty">暂无备份会话</div>';
    } else {
      box.innerHTML = ss.sessions.map((s) => `
        <div class="sess-row">
          <span class="t">${fmtDay(s.ts)}</span>
          <span>设备 ${esc(s.device_id)} · ${s.files_ok} 个文件 · ${humanBytes(s.bytes)}</span>
          <span class="r">${resultChip(s.result)}</span>
        </div>`).join("");
    }
  } catch (e) { /* keep old */ }
}

function resultChip(result) {
  if (result === "ok") return '<span class="chip green">完成</span>';
  if (result === "failed") return '<span class="chip red">失败</span>';
  return '<span class="chip amber">' + esc(result) + "</span>";
}

// ------------------------------------------------------------------ 设备（只读同网清单，账号即网络）

async function renderDevices() {
  let dv;
  try {
    dv = await api("/devices");
  } catch (e) {
    toast("设备列表加载失败：" + e.message);
    return;
  }
  // Same-account devices auto-join the per-user network at login; there
  // is no approval queue. Any legacy "pending" rows are shown as 新设备
  // (they join on their next sync) — still read-only.
  const rows = [...(dv.bound || []),
    ...(dv.pending || []).map((d) => ({ ...d, legacyPending: true }))];
  const tb = $("devTable").querySelector("tbody");
  tb.innerHTML = rows.length ? rows.map((d) => `
    <tr>
      <td><span class="dev-name">${esc(d.name || d.id)}</span>${d.is_this_pc ? ' <span class="chip slate">本机</span>' : ""}</td>
      <td>${esc(d.kind)}</td>
      <td>${esc(d.id)}</td>
      <td>${d.revoked
        ? '<span class="chip red">已吊销</span>'
        : d.legacyPending
          ? '<span class="chip amber">新设备 · 同步中</span>'
          : '<span class="chip green">已同网 · 同账号</span>'}</td>
    </tr>`).join("")
    : '<tr><td colspan="4"><div class="empty">暂无设备</div></td></tr>';
}

$("devRefresh").addEventListener("click", renderDevices);

// ------------------------------------------------------------------ 存储

function renderStorage() {
  if (!status) return;
  const st = status.storage || {};
  const pct = st.quota_bytes ? Math.min(100, (st.used_bytes / st.quota_bytes) * 100) : 0;
  $("stBarFill").style.width = pct.toFixed(1) + "%";
  $("stBarFill").classList.toggle("hot", pct >= 90);
  $("stKv").innerHTML = `
    <dt>已用空间</dt><dd>${humanBytes(st.used_bytes)}</dd>
    <dt>配额</dt><dd>${st.quota_bytes ? humanBytes(st.quota_bytes) : "不限（开源免费）"}</dd>
    <dt>已备份文件</dt><dd>${st.files || 0} 个</dd>
    <dt>存储目录</dt><dd>${esc(status.receiver.target_dir || "")}</dd>
    <dt>数据目录</dt><dd>${esc(status.receiver.data_dir || "")}</dd>`;
}
$("stOpenTarget").addEventListener("click", () =>
  status && invoke("open_path", { path: status.receiver.target_dir }).catch((e) => toast(String(e))));
$("stOpenData").addEventListener("click", () =>
  status && invoke("open_path", { path: status.receiver.data_dir }).catch((e) => toast(String(e))));

// ------------------------------------------------------------------ 备份记录

async function loadRecords(reset) {
  if (reset) {
    recPage = 0;
    revokeBlobs();
    $("previewPane").innerHTML = '<div class="empty">从左侧选择一条记录查看预览</div>';
  }
  await loadRecordPage();
}

/** One page of records (design v1.3: 50/page, explicit pager + jump).
 *  Turning a page re-renders ONLY the list; the preview pane keeps its
 *  current selection until a row on the new page is clicked. */
async function loadRecordPage() {
  let data;
  try {
    data = await api(`/records?limit=${REC_PAGE}&offset=${recPage * REC_PAGE}`);
  } catch (e) {
    toast("记录加载失败：" + e.message);
    return;
  }
  recTotal = data.total;
  const pages = Math.max(1, Math.ceil(recTotal / REC_PAGE));
  if (recPage >= pages) { // records shrank under us — step back a page
    recPage = pages - 1;
    return loadRecordPage();
  }
  $("recTotal").textContent = `共 ${recTotal} 条`;
  const list = $("recList");
  list.innerHTML = "";
  for (const rec of data.records) {
    const row = document.createElement("div");
    row.className = "rec-row";
    const icon = rec.kind === "video" ? "🎬" : rec.kind === "image" ? "🖼" : "📄";
    row.innerHTML = `
      ${rec.thumb ? '<img class="rec-thumb" alt="">' : `<div class="rec-thumb-ph">${icon}</div>`}
      <div class="rec-meta">
        <div class="rec-name" title="${esc(rec.path)}">${esc(baseName(rec.path))}</div>
        <div class="rec-sub"><span>${humanBytes(rec.size)}</span><span>${fmtDay(rec.backed_at)}</span>${integrityChip(rec.integrity)}</div>
      </div>`;
    if (rec.thumb) {
      const img = row.querySelector("img");
      apiBlob(`/records/${rec.id}/thumb`).then((u) => { img.src = u; }).catch(() => {});
    }
    row.addEventListener("click", () => selectRecord(rec, row));
    list.appendChild(row);
  }
  $("recPageLabel").textContent = `第 ${recPage + 1} / ${pages} 页`;
  $("recPrev").disabled = recPage <= 0;
  $("recNext").disabled = recPage >= pages - 1;
  $("recJump").max = pages;
  if (document.activeElement !== $("recJump")) $("recJump").value = recPage + 1;
}

function baseName(p) {
  const parts = String(p).split(/[\\/]/);
  return parts[parts.length - 1] || p;
}
function integrityChip(v) {
  if (v === "ok") return '<span class="chip green">已备份</span>';
  if (v === "suspect") return '<span class="chip red">完整性异常</span>';
  return `<span class="chip slate">${esc(v)}</span>`;
}

async function selectRecord(rec, row) {
  document.querySelectorAll(".rec-row").forEach((x) => x.classList.remove("selected"));
  row.classList.add("selected");
  const pane = $("previewPane");
  pane.innerHTML = '<div class="empty">加载预览…</div>';
  let imgUrl = null;
  try {
    imgUrl = await apiBlob(`/records/${rec.id}/preview`);
  } catch (e) { /* no preview available */ }
  const abs = recordAbsPath(rec);
  pane.innerHTML = `
    ${imgUrl ? `<div class="pv-stage"><img class="pv-img" src="${imgUrl}" alt="">
      ${rec.kind === "video" ? '<div class="pv-play">▶</div>' : ""}</div>`
      : '<div class="empty">该文件没有可用的预览图</div>'}
    <div class="pv-name">${esc(rec.path)}</div>
    <dl class="kv">
      <dt>大小</dt><dd>${humanBytes(rec.size)}</dd>
      <dt>备份时间</dt><dd>${fmtTime(rec.backed_at)}</dd>
      <dt>完整性</dt><dd>${rec.integrity === "ok" ? "校验 OK · SHA-256 一致" : esc(rec.integrity)}</dd>
      <dt>SHA-256</dt><dd>${esc(String(rec.sha256).slice(0, 24))}…</dd>
      <dt>来源设备</dt><dd>${esc(rec.device_id)}</dd>
    </dl>
    <div class="row-gap">
      <button class="btn btn-primary btn-sm" id="pvOpen">${rec.kind === "video" ? "打开文件播放" : "打开文件"}</button>
      <button class="btn btn-ghost btn-sm" id="pvFolder">打开所在文件夹</button>
    </div>
    ${rec.kind === "video" ? '<p class="hint">视频在电脑上用系统播放器打开播放；这里显示的是封面帧。</p>' : ""}`;
  $("pvOpen").addEventListener("click", () =>
    invoke("open_path", { path: abs }).catch((e) => toast(String(e))));
  $("pvFolder").addEventListener("click", () =>
    invoke("open_path", { path: abs.replace(/[\\/][^\\/]*$/, "") }).catch((e) => toast(String(e))));
}

function recordAbsPath(rec) {
  const t = (status && status.receiver && status.receiver.target_dir) || "";
  const uid = (status && status.account && status.account.user_id) || "";
  return `${t}/users/${uid}/devices/${rec.device_id}/${rec.path}`;
}

$("recPrev").addEventListener("click", () => {
  if (recPage > 0) { recPage--; loadRecordPage(); }
});
$("recNext").addEventListener("click", () => {
  recPage++;
  loadRecordPage();
});
$("recGo").addEventListener("click", () => {
  const pages = Math.max(1, Math.ceil(recTotal / REC_PAGE));
  const n = parseInt($("recJump").value, 10);
  if (!Number.isFinite(n)) return;
  recPage = Math.min(pages, Math.max(1, n)) - 1;
  loadRecordPage();
});
$("recRefresh").addEventListener("click", () => loadRecords(true));

// ------------------------------------------------------------------ 日志

async function renderLogs() {
  try {
    const lg = await api("/logs?tail=200");
    const box = $("logBox");
    box.textContent = lg.lines.join("\n");
    box.scrollTop = box.scrollHeight;
  } catch (e) {
    $("logBox").textContent = "日志加载失败：" + e.message;
  }
}
$("logRefresh").addEventListener("click", renderLogs);

// ------------------------------------------------------------------ 设置

async function renderSettings() {
  if (!cfg) cfg = await invoke("get_config");
  $("setServer").value = cfg.server || DEFAULT_SERVER;
  $("setTarget").value = cfg.target_dir || "";
  $("setPort").value = cfg.backup_port || 28741;
  $("setAutostart").checked = !!cfg.autostart;
  try {
    const info = await invoke("shell_info");
    $("aboutKv").innerHTML = `
      <dt>版本</dt><dd>牛备桌面端 0.1.0（Tauri 壳 + Java 接收引擎）</dd>
      <dt>引擎目录</dt><dd>${esc(info.engine_dir || "未找到")}</dd>
      <dt>Java</dt><dd>${esc(info.java || "—")}</dd>
      <dt>外壳配置</dt><dd>${esc(info.config_path || "—")}</dd>
      <dt>控制接口</dt><dd>${ep ? esc(ep.base) : "—"}（仅本机回环，令牌鉴权）</dd>`;
  } catch (e) { /* ignore */ }
}

$("settingsForm").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const next = {
    ...cfg,
    server: $("setServer").value.trim() || DEFAULT_SERVER,
    target_dir: $("setTarget").value.trim(),
    backup_port: parseInt($("setPort").value, 10) || 28741,
    autostart: $("setAutostart").checked,
  };
  const engineAffecting =
    next.target_dir !== cfg.target_dir || next.backup_port !== cfg.backup_port;
  try {
    if (next.autostart !== cfg.autostart) {
      await invoke("set_autostart", { enabled: next.autostart });
    }
    cfg = next;
    await invoke("save_shell_config", { config: cfg });
    if (engineAffecting) {
      toast("设置已保存，正在重启引擎…");
      ep = await invoke("restart_engine");
      status = await api("/status");
    } else {
      toast("设置已保存");
    }
    renderSettings();
  } catch (e) {
    toast("保存失败：" + (e.message || e));
  }
});
$("setRestart").addEventListener("click", async () => {
  try {
    toast("正在重启接收引擎…");
    ep = await invoke("restart_engine");
    status = await api("/status");
    toast("引擎已重启");
    if (page === "overview") renderOverview();
  } catch (e) {
    toast("重启失败：" + (e.message || e));
  }
});
$("setLogout").addEventListener("click", doLogout);

// ------------------------------------------------------------------ go

boot();
