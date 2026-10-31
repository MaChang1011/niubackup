# M4b 落地记录：账户服务 + 管理后台 + 产品公共节点（2026-10-31）

## 部署拓扑（云服务器 36.133.114.164，全部新增、与既有业务隔离）

| 组件 | 位置 | 说明 |
|---|---|---|
| 账户服务 | systemd `niubackup-server`，`/opt/niubackup/niubackup-server.jar` | Spring Boot 3.4 / JDK 21（私有安装 `/opt/niubackup/jdk21`，服务器自带只有 11/17） |
| 监听 | `127.0.0.1:18090` + 组网地址 `10.200.0.1:18090` | **不绑公网口**；iptables 仅放行 `-i tun0 --dport 18090`；域名+HTTPS 是上线前置（设计 §8） |
| 数据库 | PostgreSQL 独立库 `niubackup` + 独立角色 | 密码只存 `/opt/niubackup/server.env`（chmod 600），不在任何文档/日志中 |
| 产品公共节点 | systemd `easytier-public` | easytier-core 2.6.4 裸启动（无网络名/密钥、不入任何网）+ `--relay-network-whitelist nb-*` + `--no-tun`，监听 11011 tcp/udp，RPC 127.0.0.1:15889；iptables 已追加 11011 放行并持久化。与自用 `easytier-node`（11010）完全独立 |

## 数据模型（服务端，隐私不变量）

表：`users`、`auth_tokens`、`devices`、`networks`（一户一网，name=`nb-<userId>-<rand8>`）、`licenses`、`session_logs`、`admins`、`admin_tokens`、`admin_audit`。
**没有任何文件级表、没有文件名/文件哈希列**——`session_logs` 只有计数列（files_count/bytes/result）；带 hash 的列只有 `password_hash` 与 `token_hash`（凭证哈希，按设计 §13.7 只存哈希）。

## API 摘要（JSON，Bearer 令牌）

- 账号：`POST /api/auth/register`（建号即发 14 天试用授权 + 生成本户组网）/ `login` / `refresh`（轮换）/ `logout`
- 设备：`POST /api/devices/register`（令牌只显示一次、库存哈希；受授权设备数上限约束）/ `GET /api/devices` / `POST /api/devices/{id}/approve`（账号会话，或已批准的同户 PC 设备——PC 批准手机走此路）/ `POST /api/devices/{id}/revoke`
- 客户端：`GET /api/provisioning`（用户或设备令牌；未批准设备也可取配置以便先入网，停用用户/已吊销设备 403）/ `GET /api/license` / `POST /api/license/validate {device_id, device_token}` → `{valid, reason}`（reason: ok/bad_token/not_approved/device_revoked/user_disabled/license_not_started/license_expired）/ `POST /api/session-logs`（设备令牌，计数级）
- 后台：`POST /api/admin/login` → `/api/admin/users?search=`、`users/{id}/devices`、`users/{id}/license {days}`（从 max(现到期， now) 起延）、`users/{id}/disable|enable`（disable 同时吊销该用户全部令牌）、`devices/{id}/revoke`、`overview`、`audit`；**每次后台变更都写 admin_audit**
- 管理页面：`/admin/`（静态单页：登录、总览、用户搜索、续期、停用/恢复、设备吊销、审计列表）

## 构建说明（为什么在服务器上构建）

构建 VM 的出口代理会破坏 Java 的 TLS 握手（JDK/Maven 均无法经它拉取 Maven Central，curl 正常），故按既有部署惯例在服务器上构建：服务器 Maven 经本地 mihomo 代理 + 阿里云镜像（`deploy/maven-settings.xml`），依赖缓存在服务器 `/root/.m2`。源码正本仍在本仓库 `server/`。

构建中修掉一个真 bug：pgjdbc 在 `RETURN_GENERATED_KEYS` 时返回整行，`GeneratedKeyHolder.getKey()` 直接抛错——注册/设备登记改用 `INSERT ... RETURNING id`，注册加 `@Transactional`（初版曾留下半成品用户行，已清理）。

## 验收结果（2026-10-31，全部实测）

- 全链路：注册 → 登录/刷新 → 登记 PC（带公钥）+ 手机 → **未批准前 provisioning 可达、validate=not_approved** → 账号会话批准 PC、PC 令牌批准手机 → validate=valid → session-log 落库（仅计数）→ 后台续期 +30 天（2026-11-14 → 2026-12-14）→ 停用后 validate=user_disabled、登录 403、provisioning 403 → 恢复后 valid → 审计表含 license_renew/user_disable/user_enable 三条。验收测试账号已删除，库内只剩 admin 与审计历史
- 笔记本经组网 `10.200.0.1:18090` 调 health/license 成功（valid:true）
- 公共节点实证：服务器与笔记本各起一个临时 `nb-proof9x` 网络节点、只经公共节点发现——双方 peer 列表互见，且两节点间最终走 **p2p 直连**（UDP，约 41–47 ms，0% 丢包）；公共节点在双方列表中为 `PublicServer_et-public`。临时实例已全部清理
- 无扰动确认：`easytier-node`、n8n、english-backend/frontend、PostgreSQL 均 active；11010/11011 双监听正常；组网 tun 只有原有的 tun0；18090 只绑 127.0.0.1 与 10.200.0.1

## Secure Mode 公钥固定 —— 状态：未定版，列 M4c

简查结论：easytier-core 2.6.4 中 `--secure-mode true` 可启用，但 `easytier-cli node` 输出与启动日志都**不暴露节点公钥**（peer id 还逐次变化），裸公共节点本身也非网络成员、无从固定。因此「客户端固定公共节点公钥」在当前版本没有现成抓手，列入 M4c 集成项（届时评估：客户端 secure mode 的对端公钥配置方式、或等版本升级）。本次未在此深挖。产品客户端先按 legacy 加密（一户一网独立密钥）运行，风险面与设计 §13.2 的说明一致。

## 管理员使用

- 入口：`http://10.200.0.1:18090/admin/`（组网内）或服务器本地 `http://127.0.0.1:18090/admin/`
- 账号：`admin`，密码为初始化时生成的一次性交接口令（不入库明文、不写文档；只存 BCrypt 哈希）
- 忘记密码的恢复方式：服务器上用 spring-security-crypto 重新生成哈希并 UPDATE admins 表（deploy.sh 第 5 步注释同法）

## 已知待办（归 M4c）

- 接收端从本服务同步用户/设备/令牌哈希清单（M4a 的接收端仍用本地种子库）
- 安卓端接入：登录 → provisioning 拉配置自动入网 → validate 门
- 域名 + HTTPS 后再考虑公网监听
- secure mode 公钥固定（见上）
