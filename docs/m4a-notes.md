# M4a 落地记录（2026-10-31）

协议 v2 + 多用户接收端已实现并在笔记本实测通过。精确的构建/运行命令见
`receiver/BUILD.md`；本文件记录数据模型、协议要点与验收结果。

## 1. PC 本地记录库（SQLite，`<dataDir>/records.db`）

| 表 | 列 | 说明 |
|---|---|---|
| users | id (PK), created_at | 账号（本里程碑由 seed 建立） |
| devices | id (PK), user_id, name, kind, token_hash, pc_pubkey, approved, revoked | 令牌**只存 SHA-256 哈希**；校验时对来令牌哈希后恒定时间比较 |
| licenses | user_id (PK), expires_at（毫秒）, max_phones=2, max_pcs=1 | 过期/无行 → 会话拒绝 |
| files | id, user_id, device_id, rel_path, size, sha256, backed_at, integrity（ok/suspect/missing）, thumb | 唯一键 (user_id, device_id, rel_path)；文件级记录**只存在于 PC**（设计三版） |
| sessions | ts, device_id, files_ok, bytes, result（ok/partial/failed） | 计数级会话流水 |

`--seed` 工具：建用户+设备、生成 32 字节随机令牌**只打印一次**（库存哈希）、
授权默认 +365 天（`--days -1` 可种已过期授权做测试）。

## 2. 协议 v2 摘要

- 握手：`HELLO {"version":2,"user_id":..,"device_id":..,"device_token":..,"route_cost":N,"mode":"backup"|"fs"}`
  - route_cost：1=p2p 直连，>=2=中继，-1=未知
  - 校验顺序：设备存在+已批准+未吊销+令牌哈希匹配 → 授权未过期 → 直连门槛
  - 失败：`HELLO_ERR {"code":"AUTH"|"LICENSE_EXPIRED"|"NOT_DIRECT"|"VERSION"|"MODE"}` 后关连接
  - 成功：`HELLO_ACK {"ok":true}`
- 直连门槛（§5.5）：客户端 route_cost>=2 拒绝；接收端另用 `easytier-cli peer`
  查自己一侧到客户端 IP 的 cost，>=2 同样拒绝；任一侧未知 = 放行并记日志；
  环境变量 `NB_ALLOW_RELAY=1` 把拒绝降级为日志警告（开发开关）。
- 备份：v1 的清单/决策/传输流程不变，但
  - 落盘目录 `users/<userId>/devices/<deviceId>/`
  - 决策先查 files 表（size+sha256+integrity=ok+副本在位 → 跳过），不再重哈希已存副本
  - 续传在 v1 `.part` 偏移基础上加 `.part.meta` 边车（源 size+mtime）：源变了 →
    旧 `.part` 作废从 0 重发，杜绝拼凑文件（§5.3）；>256 MB 分块续传是 v2.1，未做
- 回执：会话结束 RECEIPT 内嵌签名块
  `{"receipt":"<回执JSON字符串>","signature":"<base64>","pubkey":"<base64 X.509>"}`
  回执 JSON = {user_id, device_id, ts, files[{path,size,sha256}]}（本会话终检通过的文件），
  签名 = ECDSA P-256 / SHA256withECDSA，密钥对在 dataDir 生成并持久化（私钥 600）。
  发送端与安卓端均验签后保存回执。
- 文件服务（mode:"fs"，同一套校验）：JSON 行命令
  - `{"op":"LIST"}` → 该用户/设备的 files 表记录
  - `{"op":"READ","path":..,"offset":..,"len":..}` → base64 分块（len 上限 1 MB）
  - `{"op":"THUMB","path":..}` → base64 缩略图或 `{"thumb":null}`
- 缩略图（落盘时生成，失败不影响备份）：jpg/png 走 ImageIO 缩放至长边 320px；
  视频走 ffmpeg（第 1 秒帧，需在 PATH）；HEIC 走 ImageMagick `convert`（需在 PATH），
  存 `<dataDir>/thumbs/<sha256>.jpg`。
- `--verify`：全量重哈希 files 表记录并比對，打印 MISMATCH/MISSING、回写 integrity，
  退出码 0=全对。
- `--allow-v1`（默认关）：兼容旧 v1 HELLO（无校验）落 `<target>/<device>/`。

## 3. 安卓端 v2

- 设置页新增 user_id / device_id / device token 三个字段（SharedPreferences 持久化，
  仿原有组网密钥字段）；三项填齐才启用 v2，否则回退 v1（线上 v1 接收端不受影响）。
- route cost：备份开始时经 EasyTierBridge.peerRouteCost 解析
  collectNetworkInfos 中对端（10.200.0.2）附近的 "cost"，取不到为 -1。
- 收到签名回执后在机内验签（java.security，ECDSA P-256），结果写入日志，
  回执存 app files 目录 `last-receipt.json`。
- APK 已重编（见下），**未安装到任何手机**。

## 4. 验收实测（2026-10-31，笔记本；v2 接收端在 28742 + 独立 scratch 目录，
线上 v1 接收端 28741 全程未动）

| 用例 | 结果 |
|---|---|
| (a) 正常会话 | 4 文件落 `users/u1/devices/phone1/` 且字节一致；files 表 4 行 integrity=ok、图片生成缩略图；sessions 行 (4 文件, 1300349 B, ok)；**回执验签 VALID**；重跑全跳过（查表决策，接收端 wants 0） |
| (b1) 错令牌 | `HELLO_ERR {"code":"AUTH"}`，sender exit 1 |
| (b2) 过期授权 | `HELLO_ERR {"code":"LICENSE_EXPIRED"}`，exit 1 |
| (b3) route_cost=2 | `HELLO_ERR {"code":"NOT_DIRECT"}`，exit 1；接收端加 `NB_ALLOW_RELAY=1` 重启后同请求放行（日志明示降级），会话成功 |
| (c) 传 300 MB 杀 sender | .part 停在 152,371,200 B；重跑 `sent big.bin (162201600 bytes from offset 152371200)`，终检 SHA 与源一致，回执 VALID |
| (d) 传 300 MB 杀接收端（SIGKILL） | .part 停在 44,498,944 B；接收端重启后重跑自 offset 44,498,944 续传，SHA 一致 |
| (e) 续传前改源（追加 1 MB） | 接收端日志 `discarding stale .part (source changed)`，从 offset 0 全量重发，落盘 SHA = 新源 SHA，无拼凑 |
| (f) 文件服务 | LIST 返回全部记录；READ photo.jpg[10,100) 与源 dd 切片 cmp 一致；THUMB 返回可解码 JPEG（320x240，长边 320） |
| (g) --verify | 初始 `verify: 8 files, 8 ok, 0 mismatch, 0 missing`（exit 0）；改坏存副本 1 字节后精确报 `MISMATCH u1/phone1/small.txt`（exit 1，integrity→suspect）；重发该文件后自愈，再 verify 全 ok |
| 附：v1 兼容 | v2 接收端默认拒绝 v1 HELLO；加 `--allow-v1` 后 v1 会话正常落 legacy 目录 |
| 未测 | §5.3 用例③「组网开关后续传」无法在此自动化（需真机组网切换），**deferred**，未伪造 |

安卓 APK：`~/niubackup-android/app/build/outputs/apk/debug/app-debug.apk`，
16,780,495 B，BUILD SUCCESSFUL；dex 校验含 v2 符号（HELLO_ACK / device_token /
peerRouteCost / last-receipt）。

笔记本运行树：`~/niubackup-pc/v2/`（源码 + lib + classes）；仓库根的两份 PC
源码与 `~/niubackup-pc/` 根目录副本均已同步。测试 scratch 在 `~/m4a-test/`
（大数据文件已清理，保留日志/seed/回执/记录库）。

## 5. 已知偏差与后续

- 同机测试时接收端一侧 cost 恒为未知（easytier-cli 的 peer 表里没有本机自己），
  按设计「未知=放行+日志」处理；真机双端时两侧判定才完整。
- 笔记本未装 ImageMagick，HEIC 缩略图在该机为 null 回退（代码路径在 convert
  存在时启用）；ffmpeg 在位，视频封面可用。
- 回执只含本会话终检通过的文件；「已跳过」文件的凭证来自其原会话的回执
  （手机端在 M4c 做多回执合并展示）。
- 文件分块续传（>256 MB，v2.1）、接收端托盘/打包、账户服务属 M4b/M4c，未在本里程碑。
