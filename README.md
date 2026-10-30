# 牛备 NiuBackup

手机文件自动备份到自己 PC 的多用户产品（开发中）：组网直连、不经云端、记录只存本地。
当前进度：M1 协议与接收端、M2 安卓客户端（内嵌 EasyTier 组网）、M3.5 哈希缓存均已实测通过。
产品与架构设计见 `docs/product-design.md`，早期方案见 `docs/route-b-design.md`。

---

以下为 M1 骨架说明（历史）：自研同步协议 + Java 接收端 + Java 测试发送端，零第三方依赖（只用 JDK 标准库）。

> **构建状态：已编译通过 ✅ / 运行时端到端待在真机验证 ⏳**
> 已在本机用 OpenJDK 21（javac 21.0.12.1）实际编译：两个文件零错误、零警告通过
> （2026-10-30）。但写这份骨架的沙箱环境禁止直连 TCP（权限策略 other_tcp=Deny），
> 发送端的 localhost 连接在到达接收端之前就被策略拦截，所以**协议的运行时端到端
> （全量/跳过/续传）还没实测过**。部署前请先在普通机器（笔记本/台式机）上按下面
> "本机自测"一节跑一遍再上组网。
> 笔记本上已有 OpenJDK 25 JRE、缺 `javac`：补装 JDK 后即可编译
> （或在台式机/任意有 JDK 的机器上编译，把 `out/` 里的 .class 拷过去用 JRE 跑）。

## 文件

| 文件 | 作用 |
|---|---|
| `receiver/BackupReceiver.java` | PC 接收端：监听 TCP、清单比对、断点续传接收、SHA-256 校验、原子落盘、回执 |
| `sender/BackupSender.java` | 测试发送端（CLI）：扫目录、算哈希、按接收端要求增量发送。M2 时这套逻辑搬进 Android 客户端 |

## 协议（一页版）

一条 TCP 连接 = 一次备份会话。控制行是 UTF-8 文本行，文件内容是原始字节：

```
C->S  HELLO <device> <protoVersion>            当前版本 = 1；device 为单个 token
S->C  OK 1                                     （或 ERR ...）
C->S  {"path":"DCIM/a.jpg","size":N,"mtime":N,"sha256":"..."}   每文件一行
C->S  MANIFEST-END
S->C  {"path":"...","offset":N}                只回需要传的文件；offset = 已有 .part 长度
S->C  READY
C->S  FILE {"path":"...","bytes":N} + N 字节原始数据    逐文件停等（stop-and-wait）
S->C  DONE {"path":"..."}  /  FAIL {"path":"...","error":"..."}
C->S  BYE
S->C  RECEIPT {"ok":[...],"skipped":[...],"failed":[...]}
```

接收端落盘规则：

- 落盘路径 `<目标目录>/<设备名>/<相对路径>`；拒绝绝对路径和 `..` 穿越。
- 先写 `<文件名>.part`，收齐后校验 SHA-256，通过才原子改名（`ATOMIC_MOVE`）为正式文件，
  并把正式文件的 mtime 恢复为源文件 mtime。
- 跳过判定：正式文件已存在且大小相同 **且哈希相同** → 跳过（M1 为求正确，每次都会
  对同大小的已存文件重算哈希；文件量大后应在 M3 前换成接收端本地清单索引）。
- 续传判定：`.part` 长度 < 文件大小 → 从该偏移续传；`.part` 恰好完整且哈希对 →
  直接转正、零传输；长度超标或完整但哈希错 → 删掉重传。
- 已知小毛病：源文件内容变了但接收端还留着旧内容的 `.part` 时，会先按旧偏移续传、
  哈希校验失败、删 `.part`，下一次会话自动全量重传（自愈，不丢数据）。

## 编译

```bash
cd ~/workspace/easytier-backup
javac -d out receiver/BackupReceiver.java
javac -d out sender/BackupSender.java
```

（两个文件各自带了一份相同的 Wire/Hash/MiniJson 辅助类，所以必须分开编译，
不要把两个文件写在同一条 javac 命令里——同包同名类会冲突。）

## 运行

接收端（家里笔记本，10.200.0.2；建议绑组网地址，只对组网内提供服务）：

```bash
java -cp out BackupReceiver --bind 10.200.0.2 --port 28741 --target ~/PhoneBackup
```

参数都有缺省：`--port 28741`、`--target ~/PhoneBackup`、`--bind` 缺省为全部网卡；
也可用环境变量 `BACKUP_PORT` / `BACKUP_DIR` / `BACKUP_BIND`（命令行优先）。

发送端（任意一台组网内的机器）：

```bash
java -cp out BackupSender --host 10.200.0.2 --port 28741 --device testpc --dir /path/to/DCIM
```

退出码：全部文件就位 = 0，有失败 = 1，用法错误 = 2。

## PC 间联调示例（M1 验收路径）

公司台式机（10.200.0.4）→ 家里笔记本（10.200.0.2），先在台式机上备一个测试目录：

```bash
mkdir -p /tmp/dcim-test/sub
echo "hello" > /tmp/dcim-test/a.txt
dd if=/dev/urandom of=/tmp/dcim-test/sub/b.bin bs=1024 count=300
```

1. 笔记本上启动接收端（绑 10.200.0.2，目标 ~/PhoneBackup）。
2. 台式机上跑发送端（`--device office-pc --dir /tmp/dcim-test`）→ 两个文件应全部传输、
   回执 `failed:[]`；笔记本上出现 `~/PhoneBackup/office-pc/{a.txt,sub/b.bin}`。
3. 再跑一次发送端 → 接收端应全部跳过（`receiver wants 0 files`）。
4. 验续传：删掉笔记本上的 `sub/b.bin`，再手动放一个半截的
   `head -c 100000 /tmp/dcim-test/sub/b.bin > ~/PhoneBackup/office-pc/sub/b.bin.part`，
   重跑发送端 → 日志应显示 b.bin 从 offset 100000 续传，收齐后 `cmp` 两边文件一致。

## 本机自测（部署前先在任意有 JDK 的机器上跑一遍）

```bash
javac -d out receiver/BackupReceiver.java
javac -d out sender/BackupSender.java
java -cp out BackupReceiver --port 28741 --target /tmp/PhoneBackup &   # 终端 A 日志
java -cp out BackupSender --host 127.0.0.1 --device testpc --dir /tmp/dcim-test
```

重复第 4 步的跳过/续传检查，确认无误后再上组网地址。

## 有意留到 M3 的事

- **应用层令牌**：v1 信任边界 = EasyTier 组网成员身份（私有网络 + 网络密钥），协议里只有
  设备名标识、没有鉴权。M3 在 HELLO 阶段加共享令牌校验。
- **TLS**：v1 明文传输，机密性靠组网隧道本身。M3 评估 TLS 或至少令牌 + HMAC。
- 其他 M3 项（见设计文档）：接收端服务化/托盘、省电调度、多目录、失败重试与通知、
  跳过判定的清单索引（替代每次重算哈希）。
