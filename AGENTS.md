# AGENTS.md — 牛备 / niubackup 开发须知

手机照片/文件经 EasyTier 点对点直连隧道自动备份到用户自己的 PC 的多用户产品。
备份数据只存在用户 PC 上，平台服务器永不接触文件内容。

**先读**：`docs/DEVELOPMENT.md`（环境地图、构建命令、全部已知陷阱的权威来源）、
`docs/product-design.md`（产品规格）、`docs/prototype/`（UI 设计基准稿 v1.5）。
本文件与 DEVELOPMENT.md 冲突时以 DEVELOPMENT.md 为准，并把差异修回本文件。

## 仓库结构

| 目录 | 内容 |
|---|---|
| `android/` | 安卓客户端（Kotlin/Java + EasyTier JNI，applicationId `net.machang.backup`） |
| `receiver/` | PC 接收端（Java，桌面引擎本体：BackupReceiver + NiuPcApp） |
| `sender/` | PC 发送端（Java，BackupSender） |
| `desktop/` | Tauri 桌面壳（`src/` 前端 + `src-tauri/` Rust） |
| `server/` | Spring Boot 账户服务（含 `static/admin` 管理后台） |
| `docs/` | 产品设计、开发交接、原型 |
| `lib/` | 收发两端共用的 jar |

## 硬性规则

- **UI 改动先改原型**：先更新 `docs/prototype/` 经用户批准，再写实现。这是用户定下的规矩（design first, code second）。
- **编译纪律**：接收端（BackupReceiver.java + NiuPcApp.java）一次 javac 编译；发送端 BackupSender.java **单独**编译。两者有同名辅助类（MiniJson 等），混编必炸。细节见 `receiver/BUILD.md`。
- **传输只许直连**：备份流量只走打洞成功的 p2p 直连（EasyTier 路由 cost=1），永不走中继。不要擅自加 relay 兜底——这是产品定位，需用户拍板才可改。
- **记录只在 PC**：文件级记录只存 PC 本地 SQLite；服务器只存账号、组网开通、计数级会话日志。禁止给服务器加任何含文件名/哈希的表。
- **回执不可弱化**：手机删本地文件的依据是 PC 签名的 ECDSA P-256 逐文件回执；不要绕过或降级这套校验。
- **动数据库先备份**：任何 schema/数据变更前先 pg_dump 留档（先例：`/opt/niubackup/db-backup-*.sql`）。
- **秘密纪律**：密钥、令牌、密码不进仓库、不进日志、不进提交信息；只存于各机器 chmod 600 的配置文件。服务器只存设备令牌的哈希。

## 构建速查（完整版在 DEVELOPMENT.md）

- 笔记本 gradle 构建必须 `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`（默认 Java 25 会失败）。
- Tauri 必须带 `custom-protocol` feature 构建；裸 `cargo build` 产出的是连 localhost:1420 的坏包。
- 引擎包必须内置 EasyTier core **2.7.0** 且 `setcap cap_net_admin,cap_net_raw=ep`（2.6.4 与产品组网的 secure mode 握不上手）。
- 服务端在云服务器上构建：`JAVA_HOME=/opt/niubackup/jdk21` + Maven（阿里云镜像）。

## 环境速查

- 云服务器 36.133.114.164：账户服务 systemd `niubackup-server`（/opt/niubackup，端口 18090）；产品公共组网节点 systemd `easytier-public`（11011，仅 nb-* 网络，secure mode）。
  ⚠️ 18090 目前是测试期临时公网明文 HTTP，上线前必须收回或换域名 + HTTPS。
- 笔记本 `/home/machang/niubackup` 与本仓库同构；`.run/` 是运行时目录（引擎包 + formal-config），`archive/` 是历史归档，两者都不在仓库里。
- `~/.niubackup-shouji`（接收端数据）与 `~/NiuBackupTest`（备份落地）是数据不是代码，不要随手移动/删除。
- 笔记本连不上 GitHub：它的 origin 是本地镜像 `~/niubackup.git`，提交先落镜像，之后由同步流程带回 GitHub。提交信息沿用现有风格（简短祈使句，可带 `docs:` / `fix:` 前缀）。

## 当前状态（2026-10-31）

- 已彻底重置并以正式账号重新上线：user 15（业主本人），网络 `nb-15-*`，PC/手机设备均已入网在用。
- 授权期限模型已取消：开源免费、注册永久有效。设备审批已取消：账号即网络、登录即入网（设备吊销与账号停用仍保留）。
- 桌面存储口径是真实磁盘可用空间，不是配额。
- 待办详单见 DEVELOPMENT.md；其中一条：f450938 记录页分页修复的 APK 已构建但用户决定暂不安装，手机现运行 c878e98 版——不要擅自装机。
