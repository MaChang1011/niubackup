# 牛备开发交接手册（DEVELOPMENT）

> 给接手开发者的自包含手册：环境在哪、怎么构建、哪些坑踩过、现在跑着什么、还剩什么。
> 产品与协议的定版口径以 `docs/product-design.md` 为准；本文只讲「怎么干活」。
> 最后更新：2026-10-31 夜（目录统一迁移 + 彻底从零重置之后）。

## 1. 环境地图

### 1.1 云服务器 `36.133.114.164`（root，Ubuntu 24.04）

| 服务 | 说明 |
|---|---|
| `niubackup-server` | 账户服务 + 管理后台（Spring Boot 3.4.5）。目录 `/opt/niubackup`：fat jar、私有 JDK 21（`/opt/niubackup/jdk21`）、`server.env`（0600，含 DB 口令等）、构建树 `/opt/niubackup/build`。端口 **18090** |
| PostgreSQL（宿主机） | 库 `niubackup` + 同名角色，口令只在 `server.env`。表：users / auth_tokens / devices / networks / licenses / session_logs / admins / admin_tokens / admin_audit / pc_login_tickets。**服务端永不存文件级数据**，session_logs 只有计数 |
| `easytier-public` | 产品公共节点：EasyTier **2.7.0**（`/opt/easytier-2.7.0`），TCP+UDP **11011**，`--secure-mode`、`--relay-network-whitelist nb-*`、`--no-tun`，RPC 127.0.0.1:15889 |
| `easytier-node` | 用户**自用**组网 machang-net（2.6.4，11010）——与产品无关，别动 |
| 管理后台 | `http://36.133.114.164:18090/admin/`，账号 admin，口令在用户本人手里（不在任何文档/代码中） |

- ⚠️ **18090 是测试期临时公网暴露**：`server.env` 里 `SERVER_ADDRESS=0.0.0.0` + iptables 放行，明文 HTTP。上线前必须二选一：收回（改回仅本机+组网绑定）或换域名 + HTTPS。这是挂账事项，别忘了。
- 同机还跑着用户其他生产业务（Spring Cloud、英语学习平台、n8n 等），一律不碰。
- 服务器出网走本机 mihomo 代理（127.0.0.1:7890，fake-IP DNS）：在服务器上测公网端口别用 127.0.0.1 直连（会被 TUN 绕晕），用公网 IP 经代理测，或从笔记本测。
- 账户服务**在服务器上构建**：Maven + 阿里云镜像（`deploy/maven-settings.xml`），且必须 `JAVA_HOME=/opt/niubackup/jdk21`（宿主默认 JDK 跑不了 release 21，第一次构建必栽）。

### 1.2 笔记本（用户家里的 Ubuntu；组网 10.200.0.2 / 局域网 192.168.3.32）

统一目录 **`~/niubackup/`**（2026-10-31 迁移定版，旧路径已全部废弃）：

| 路径 | 用途 |
|---|---|
| `~/niubackup/pc/` | PC 端源码与运行树。现行代码在 `pc/v2/`（接收端/发送端源码 + classes + lib）；`pc/` 根下有早期副本与 BUILD 索引，**以 v2 为准** |
| `~/niubackup/android/` | 安卓工程构建树（gradle 在此跑） |
| `~/niubackup/desktop/` | 桌面端构建树；壳二进制在 `desktop/src-tauri/target/release/niubackup-desktop` |
| `~/niubackup/run/` | 运行物料：`run/engine/` 是桌面正式引擎包（classes + easytier-core 2.7.0 + runtime）；`run/formal-config/` 是正式启动配置（xdg、shell-config.json、SWITCH-STEPS.md） |
| `~/niubackup/archive/` | 2026-10-31 彻底重置前的归档（records.db 快照、465 文件清单 CSV、回执副本、截图），只读留档 |

运行时数据**不在**统一目录、不要搬：

- `~/.niubackup-shouji/` — 接收端数据目录：account.json、records.db（+wal）、回执签名密钥、device-identity.json、control.json、easytier.toml、日志（engine-shell.log、easytier.log）。
- `~/NiuBackupTest/` — 备份目标：`users/<userId>/devices/<deviceId>/<相对路径>`。

正式单元：用户级 **transient 单元** `niubackup-desktop-formal`（systemd-run 拉起，停掉定义即消失）。重拉参数必须带全：

```bash
systemd-run --user --unit=niubackup-desktop-formal \
  -p Environment="XDG_CONFIG_HOME=$HOME/niubackup/run/formal-config/xdg" \
  -p Environment="XDG_DATA_HOME=$HOME/niubackup/run/formal-config/xdg" \
  -p Environment="NB_DESKTOP_ENGINE_DIR=$HOME/niubackup/run/engine" \
  $HOME/niubackup/desktop/src-tauri/target/release/niubackup-desktop
```

工具链注意：

- JDK 21 在 `/usr/lib/jvm/java-21-openjdk-amd64`；系统默认 java 是 25，**gradle 必须显式 `JAVA_HOME` 指到 21**，否则构建失败。
- Android SDK 在 `~/android-sdk`（adb 在 `platform-tools/`）。
- cargo 在 `~/.cargo/bin`：非登录 SSH shell 的 PATH 里可能没有，桌面构建用登录 shell 或先补 PATH（曾出现「构建静默没跑、二进制没更新」的假成功，务必核对产物时间戳）。
- 笔记本直连不上 github.com：需要 GitHub 资源时在别处拉取再 scp 进来。本机代理是 Clash Verge `127.0.0.1:7897`。
- 笔记本上还有自用 easytier 服务（machang-net）与 Syncthing 残留服务，与产品无关。
- 同机 10.200.0.2:28741 还跑着一个 v1 自用接收端（`~/PhoneBackup`），不是产品，别停别改。

### 1.3 手机（OnePlus PJA110，Android 16 / ColorOS）

- **无线调试会被 ColorOS 自行关闭**：adb 掉线是常态。恢复：让用户重开「设置 → 开发者选项 → 无线调试」并停在该页；笔记本侧 adb 卡死时 `adb kill-server && adb start-server` 后等 mDNS 重新发现（配对关系保留，一般不必重配）。
- adb 命令一律加 `</dev/null` 并用 `timeout` 包裹（stdin 喂脚本时 adb 会吞脚本内容）；`run-as … cat` 拉**二进制**文件会损坏，文本文件可以；大文件走 cp 到 /sdcard 再 pull。
- 大 APK 装机走分片脚本 **`~/nbpush-install2.sh`**：2 MiB 分片 → 推到 `/data/local/tmp` 拼合 → sha256 与源文件比对 → `pm install -r`，硬查输出含 `Success` 才算成。注意：①别用 /sdcard 路径拼装（ColorOS 的 pm 读不了 fuse 路径）；②脚本没有 +x 位，用 `bash ~/nbpush-install2.sh` 显式启动（sed -i 改文件会重置权限位）；③脚本日志在 `~/nbpush-install2.log`，分片源在 `/tmp/nbpush/`（由 `~/nbpush-install.sh` 的 split 步骤或手动 `split -b 2M <apk> /tmp/nbpush/part-` 生成，APK 重构建后必须重新分片）。

## 2. 仓库与构建纪律

- 本仓库是 canonical；笔记本 `pc/`、`android/`、`desktop/` 三棵树与仓库对应目录（`receiver/`+`sender/` ↔ `pc/v2/`、`android/`、`desktop/`）必须保持字节一致，改完用 md5 抽查。漂移出过事故，别省这步。
- **接收端/发送端分开编译**（血泪纪律）：`BackupReceiver.java`（+`NiuPcApp.java`）一套、`BackupSender.java` 一套，各自一条 javac。两边各带一份同名辅助类（MiniJson/Wire/Hash），混编必冲突；且两份 MiniJson 有过细微差异——817341c 的扫码登录 NPE 就是引擎 classpath 装错了发送端变体（esc() 缺 null 守卫）。命令见 `receiver/BUILD.md`。
- 安卓构建：`cd ~/niubackup/android && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug`。JNI 库（libeasytier_android_jni.so，EasyTier 2.7.0）的构建配方见 `android/BUILD-NOTES.md`（host 构建需 `RUSTFLAGS="-C link-arg=-fuse-ld=bfd"` 与 `PROTOC=~/tools/bin/protoc`）。
- 桌面构建：`desktop/` 下 `cargo build --release`，Tauri 的 `custom-protocol` feature 必须开（已在 Cargo.toml 固定；裸构建出 dev 模式二进制会去连 localhost:1420，d4996df 的教训）。前端是原生 HTML/CSS/JS，二维码库 qrcode-generator 1.4.4 已 vendored 在 `desktop/src/qrcode.js`（MIT），不引 CDN。
- 账户服务构建/部署见 §1.1；换 jar 前把旧 jar 备份为 `niubackup-server.jar.bak-<日期>-<事由>`，重启后验活（注册探针号或查 /admin/ 200）。
- **引擎包刷新纪律**：`run/engine/app/classes` 必须与 `pc/v2/classes` 一致（md5 核对）；`run/engine/` 内的 easytier-core 必须是 **2.7.0** 且 `setcap cap_net_admin,cap_net_raw=ep`（`getcap` 核对）——2.6.4 与 2.7.0 的 secure mode 握手不兼容，无 cap 时 sidecar 建不了 TUN。

## 3. 协议与接口摘要

细节以 `docs/product-design.md` §5/§14.7 与 `docs/m4a-notes.md` 为准，这里只给定位用的骨架。

### 3.1 备份协议 v2（手机 ↔ PC 接收端，TCP 28741，跑在组网直连上）

- `HELLO {user_id, device_id, device_token}` → 接收端验令牌（本地缓存的令牌哈希清单，由账户服务每 5 分钟同步；服务端不可达时凭缓存离线宽限）+ **直连门**：双方经 JNI `collectNetworkInfos` 读对端路由 cost，`cost==1`（p2p）才开传，中途降级即暂停；中继永不传文件（产品红线）。
- 批清单：HELLO_ACK 广告 `batch:1` 后按轮发送，每轮 200 文件 / 256 MiB；清单只有元数据（path/size/mtime），**边传边算**：双方流式计算 SHA-256，DONE 回带哈希交叉核对；不一致 → `HASH-MISMATCH`（接收端删副本、记录标 bad、会话继续）。跳过判定 = 记录中 size+mtime 一致且 integrity ok 且副本在盘。
- 断点续传：`.part` 长度即进度，杀进程/断网/重启后续传；源文件 size 或 mtime 变化 → `.part` 失效重传。>256 MB 分块续传是 v2.1 的事，现在没有。
- 回执：文件落盘即由 PC 用 ECDSA P-256 签**逐文件回执**（流式模式要等该文件的哈希交叉核对窗口关闭才下发，在下一文件头/轮次/会话结束时冲刷）；BYE 后另有整场回执。文档形如 `{receipt:{user_id, device_id, ts, files:[{path,size,sha256}]}, signature, pubkey}`；手机验签后存 `files/receipts/receipts.jsonl`，是「释放空间」删本地的门禁凭证（无回执的文件永不出现在可删列表）。
- 能力探测三态（c878e98 定版）：备份前 fs 探测分 HELLO_ACK（带能力）/ 明确 HELLO_ERR / **无应答**。无应答只许重试 3 次后快速失败并给出明确日志——**绝不能**把无应答当成「老式接收端」静默降级到全量哈希路径（那会让 6 千多个文件先算几分钟哈希、零字节发出，是已修掉的坑）。
- fs 模式（同一协议）：`LIST` 全表返回（SQL `ORDER BY rel_path`，无分页参数，分页在客户端渲染侧切）、`READ` 支持任意偏移 ranged 读、`THUMB` 取 PC 生成的缩略图。支撑记录页、文件库、下载回来与 Media3 在线播放。

### 3.2 控制 API（桌面壳 ↔ 本机引擎）

- 绑 `127.0.0.1`，端口 28799–28808 首个空闲；`<数据目录>/control.json`（0600）写 port + 每进程随机 token + pid；请求头 `X-Control-Token`。
- `GET /status`（账号/组网/对端/存储——storage 含按目标卷 FileStore 取的 `disk_total_bytes`/`disk_free_bytes`，桌面端按磁盘呈现，**不用配额**）、`/devices`、`/sessions`、`/records?limit&offset`、`/records/{id}/thumb`、`/records/{id}/preview`、`/logs`。
- `POST /account/login`、`/account/logout`（都答 `restart_required`，壳负责重启引擎换身份）、`POST /account/qr-ticket` + `GET /account/qr-ticket/{id}?secret=`（扫码登录的引擎侧代理，见 §3.3）、`POST /devices/{id}/approve|revoke`（approve 现为兼容性空操作）、`POST /sync`。

### 3.3 账户服务 API（:18090）

- 认证：注册/登录发 access token（2 h）+ refresh token（30 天，**轮换式**，每次刷新吊销所呈 token；刷新响应丢失会把客户端晾住，需重新登录）；登出吊销所呈 token。设备令牌独立、不过期、只可吊销，服务端只存哈希。
- 设备：`POST /api/devices` 注册（带 `device_uuid` 走 upsert，见下）、`validate`（失败原因仅剩 `bad_token` / `device_revoked` / `user_disabled`，外加设备数量上限拦截；**不再有 `not_approved` 与 `license_expired`**）、`provisioning`（下发该账号专属网络名/secret 与公共节点地址）。
- 授权：注册即永久，`licenses.expires_at` 统一写哨兵 `9999-12-31T00:00:00Z`（不要写 NULL——旧版接收端会把 NULL 误判为无授权）；admin 的 renew 接口保留为空操作（返回成功 + `perpetual:true`，仍写审计）。
- 扫码登录票据：PC 未登录 `POST /api/pc-login/tickets {device_name, device_uuid}` → `{ticket_id, secret, expires_at}`（2 分钟，secret 只存 SHA-256）；二维码内容由客户端拼 `{"t":ticket_id,"s":secret,"srv":服务地址}`；手机带 Bearer `POST /api/pc-login/tickets/{id}/approve {secret}` → 以手机账号登记/复用 PC 设备并封存凭证；PC `GET /api/pc-login/tickets/{id}?secret=` 轮询，approved 后**一次性**取回 `{account, user_id, pc_device_id, device_token, refresh_token, device_name, created_at}`，取走即作废。
- **device_uuid upsert 语义**（40276f9）：按 (user_id, device_uuid) 命中未吊销行 → 返回原 device_id 并重签设备令牌（旧令牌即刻失效），不建行、不占名额、不触发槽位回收；命中已吊销行 → 明确拒绝（不许靠重登录复活）；未命中且同 kind 恰有一台未吊销的无-uuid 存量行 → 一次性归并绑 uuid（审计 `device_uuid_bind`）；否则新建。PC 的 uuid 存数据目录 `device-identity.json`（退出登录不删），安卓存独立 `device_identity` 存储（退出登录不清）。不带 uuid 的旧请求保持原行为。

## 4. 当前生产状态（2026-10-31 夜）

- 账号：`machang1011@qq.com` = **user 15**（彻底从零重置后以真实邮箱重新注册），专属网络 **nb-15-9rmvatsl**；手机设备 **40**、PC 设备 **41**，均已绑 device_uuid。
- 桌面正式单元运行中：引擎 serving、组网 up + secure mode、手机对端 p2p cost 1 直连；新一轮备份自 19:37 起持续落地（条数以引擎 /status 实时为准）。
- 账户服务、公共节点运行正常；18090 仍是临时公网明文状态（§1.1 的挂账）。
- 服务端数据库里除 user 15 外只有管理员；重置前的归档在服务器 `/opt/niubackup/db-backup-20261031-pre-reset.sql` 与笔记本 `~/niubackup/archive/`。
- **凭证纪律**：任何密码、令牌、网络 secret 都不在仓库与本文档中；排障只许用哈希前缀比对，原文只许存在于各端受保护存储与 `server.env`。

## 5. 待办清单

- [ ] **f450938 分页修复 APK 已构建，暂不安装（用户 2026-10-31 决定）**：记录页分页器布局修复的 APK 已构建完成（sha256 `880a6fd0…`，含 c878e98 的相机/hash 修复），安装分片已传至手机 `/data/local/tmp`；用户决定暂不安装，**手机现仍是 c878e98 版**。日后恢复安装时用 §1.3 的 `~/nbpush-install2.sh` 拼合安装，装后打开 App 记录页截图验证：分页器常驻可见、翻第 2 页条目与第 1 页不重不缺（记录总数以 PC 实时为准，20 条/页）。
- [ ] 相机扭曲修复（c878e98，已装机）待用户下次打开扫码页目视确认；选档日志打在 logcat 的 `MachangBackup` tag，可复核。
- [ ] **HEIC 缩略图**：PC 端缺 ImageMagick + libheif，HEIC 当前回退为类型图标；按设计 §13.6 定版随安装包捆绑或在目标机预装。
- [ ] **§5.3 验收缺口**：备份中途「组网断开 → 恢复」的续传用例仍未实测（杀进程/杀接收端/源变更等其余续传用例均已过）。
- [ ] 一次完整全量备份（手机 DCIM 约 306 GB / 6 千余文件）尚未完整跑完过；逐文件回执使删本地不依赖整场完成，但 M4d 前建议完整跑一轮，并抽查一次「释放空间」真实删除闭环。
- [ ] **上线前清单**：① 18090 收回公网或换域名 + HTTPS，并把双端默认服务地址从裸 IP 改为域名；② 「牛备」商标正式检索（第 9/42 类）；③ 代码签名（安卓 release 签名、桌面安装包签名）；④ 用户协议 + 隐私政策定稿；⑤ `.github/workflows` 的 Win/macOS 打包实跑一遍；⑥ 历史口令轮换（服务器 root、管理后台、笔记本 sudo——均曾在聊天里出现过，用户已被告知）。
- [ ] M4d 小范围试运行：3–5 名真实用户连续一周。

## 6. 协作约定（用户定版，必须遵守）

- **UI 改动先改原型**：先更新 `docs/prototype/`（v1.5 是当前基准），经用户批准后再改实现；不许先码后补设计。
- **动数据库先备份**：pg_dump 到 `/opt/niubackup/db-backup-<日期>-<事由>.sql` 再操作；删除线上数据这类操作必须有用户明确拍板。
- **commit 纪律**：小步提交、信息写清事由；push 后核验远程 HEAD；仓库 ↔ 笔记本树同步后 md5 抽查。
- **秘密纪律**：密码/令牌/secret 永不进仓库、文档、日志与聊天明文，只许哈希前缀比对；用户凭证只由用户本人输入，代操作不代填密码。
- **产品红线**（改口径要用户拍板）：文件只走 p2p 直连、永不中继；服务端永不存文件级数据；同账号即同网、登录即入网；开源免费、无授权期限。

## 附：原型（设计稿）

`docs/prototype/index.html` 是设计稿 v1.5 的静态产物，浏览器直接打开（字体走 Google Fonts CDN，离线时降级显示）。原型是 UI 设计基准；当前实现已对齐 v1.5，并包含其后三项修复：存储按磁盘（f79a5d0）、记录页分页器布局（f450938）、扫码黑屏/扭曲与哈希阻塞（817341c、c878e98）。`docs/prototype/icon.webp` 是该稿的应用图标图。
