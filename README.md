# 牛备 NiuBackup

手机照片/文件自动备份到你自己的 PC：组网直连、不经云端、文件级记录只存 PC 本地。
多用户产品，开源免费（自有代码 MIT；组网组件 EasyTier 为 LGPL-3.0，见 `NOTICE`）。

## 当前状态（2026-10-31）

- M4 全线落地：账户服务 + 管理后台、协议 v2（边传边算 + 逐文件签名回执）、安卓客户端
  （内嵌 EasyTier 组网）、Tauri 桌面端（本机控制 API 驱动 Java 接收引擎）。
- 产品规则定版：**登录即入网**（无人工批准）、**扫码登录**（手机扫 PC 一次性票据）、
  **稳定设备编码**（device_uuid，重复登录不换号）、**开源免费、无授权期限**、
  存储呈现按磁盘真实容量（不用配额口径）。
- 2026-10-31 晚完成一次彻底从零重置：旧账号与全部测试数据注销清空、双端重装，
  已以真实账号重新注册启用，处于单用户实机运行阶段；下一步 M4d 小范围试运行。
- **接手开发先读 `docs/DEVELOPMENT.md`**（环境地图、构建命令、已知陷阱、待办清单）。

## 仓库结构

| 目录 | 内容 |
|---|---|
| `android/` | 安卓客户端（Kotlin + EasyTier JNI），构建说明见 `android/BUILD-NOTES.md` |
| `receiver/` | PC 接收端引擎（Java 21，零框架），构建纪律见 `receiver/BUILD.md` |
| `desktop/` | 桌面壳（Tauri v2：`src/` 前端 + `src-tauri/` Rust 壳） |
| `server/` | 账户服务 + 管理后台（Spring Boot 3.4.5 + PostgreSQL） |
| `sender/` | CLI 测试发送端（协议联调/验收 harness 用） |
| `lib/` | 接收端运行时 jar（sqlite-jdbc、slf4j-api） |
| `deploy/` | 服务端部署物料（systemd unit、`deploy.sh`、Maven 设置） |
| `packaging/` | PC 端打包与自启脚本 |
| `brand/` | 应用图标与品牌素材 |
| `docs/` | 设计与交接文档（见下方索引） |

## 文档索引

- `docs/product-design.md` — 产品与架构设计（定版口径，含协议 v2、数据模型、里程碑）
- `docs/DEVELOPMENT.md` — **开发交接手册**：环境地图、构建/部署命令、已知陷阱、当前生产状态、待办
- `docs/prototype/index.html` — 界面设计稿 v1.5（原型基准，浏览器直接打开；实现已对齐）
- `docs/session-persistence.md` — 双端登录会话保持的设计与实现
- `docs/tauri-shell.md` — 桌面壳与本机控制 API 说明
- `docs/m4a-notes.md`、`docs/m4b-notes.md`、`docs/m4c-notes.md` — 各里程碑实现与验收记录
- `docs/route-b-design.md` — 早期单用户方案（历史）

## 协议一页版（v2 要点）

一条 TCP 连接 = 一次备份会话，跑在 EasyTier 组网直连隧道上（中继路径不传文件）：

```
HELLO（user_id + device_id + 设备令牌）→ 身份/状态校验 + 直连门（对端路由 cost==1）
批清单（每轮 200 文件 / 256 MiB，纯元数据）→ 接收端逐文件决策（跳过 / 从偏移续传）
边传边算：流式传输中双方同步算 SHA-256 → DONE 校验一致才原子落盘
逐文件签名回执（ECDSA P-256，落盘即签发）+ 整场回执 → BYE 收尾
断点续传：.part 长度即进度（断线/被杀/重启可续）；源文件 size/mtime 变化即失效重传
fs 模式：LIST / ranged READ / THUMB —— 支撑记录查看、下载回传与在线播放
```

细节见 `docs/product-design.md` §5、§14.7 与 `docs/m4a-notes.md`。

## 许可

自有代码 MIT（见 `LICENSE`）；EasyTier 以动态链接方式使用，LGPL-3.0 义务见 `NOTICE`。
