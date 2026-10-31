# M4c Notes — Account Integration, Android UX, PC Packaging

Status: 2026-10-31. Builds on M4a (protocol v2 receiver, docs/m4a-notes.md) and
M4b (account service, docs/m4b-notes.md). Design: docs/product-design.md (§5.5,
§6, §7, §8, §11 M4c, §14.2/14.3).

## P1 — Receiver account mode (PC side)

`BackupReceiver` gained an account mode that binds the standalone v2 receiver
to the M4b account service. Standalone `--seed` mode is unchanged.

### Login (the only password moment)

```
BackupReceiver --login --server http://10.200.0.1:18090 \
    --account-user <account> --account-pass <password> \   # or NB_ACCOUNT/NB_PASSWORD
    --data-dir <dir>
```

Flow (`AccountLink.loginFlow`): password login → `GET /api/license` for the
numeric `user_id` → PC device registration with the receiver's ECDSA public
key → self-approve via the account session → `GET /api/provisioning` →
everything the receiver needs is persisted to `<dataDir>/account.json`
(mode 600): server, account, user_id, pc_device_id, pc_device_token,
refresh_token, network_name/secret, public_node, pc_ip (default 10.99.0.2).

PC-slot handling: the license caps PCs at 1. If a previous registration from
the same data dir is still live it is reused; if registration hits
`device_limit` because a *stale* registration holds the slot (reinstall case),
the flow revokes the stale pc devices of this user — legitimate because the
caller just authenticated with the account password — and retries once.
Verified live: a fresh-data-dir login against an account whose PC slot was
occupied succeeded and received a new pc_device_id.

### Serving + sync

```
NB_SYNC_SECONDS=300 BackupReceiver --account --port 28741 \
    --target ~/NiuBackup --data-dir ~/.niubackup
```

* On start and then every `NB_SYNC_SECONDS` (default 300), `syncOnce` calls
  `GET /api/devices` + `GET /api/license` + `GET /api/provisioning` with the PC
  device token and merges the result into the **same local SQLite tables** the
  M4a seed mode used (devices approved/revoked, license expiry + caps in
  `licenses`, quota/expiry mirrored to `meta`, provisioning rotation picked up
  into account.json).
* HELLO validation is cache-first (offline grace, design §14.2): the server
  API never returns token hashes, so hashes are *learned* — the first time a
  device presents a token, the receiver validates it online
  (`POST /api/license/validate`) and caches `sha256(token)`. Afterwards:
  revoked-in-cache → reject; approved + hash match → accept from cache (works
  with the server down, staleness logged); approved + hash *mismatch* → reject
  (never falls back to online — a rotated token must not resurrect access);
  unknown device → online validation, or reject when the server is
  unreachable. License expiry is enforced from the cached row.
* Account mode also rejects HELLOs whose `user_id` differs from the account's.

### Operator commands (PC device-token auth, M4b §device approve path)

```
BackupReceiver --devices            # server-side device list for this account
BackupReceiver --approve <id>       # approve a pending phone (this PC's token)
BackupReceiver --sync               # one syncOnce against the local cache
```

### Mesh sidecar

In account mode the receiver supervises an `easytier-core` child
(`MeshSidecar`): it renders `<dataDir>/easytier.toml` from the provisioning
(instance `niubackup-pc`, ipv4 `<pcIp>/24` in the product subnet
**10.99.0.0/24** — PC `.2`, phone `.3`, peer = issued public node, RPC
127.0.0.1:15891), launches it, and relaunches on exit with backoff (5 s → 60 s).
Binary resolution: `--mesh-bin` → `NB_EASYTIER_BIN` → sibling of the code
source (the jpackage `lib/app/` layout) → `<dataDir>/sidecar/` → PATH.
Stale instances from a previous receiver run are pkilled at launch, and a JVM
shutdown hook stops the sidecar with the receiver.

**Secure mode works**: the config sets `[flags] secure_mode = true`;
2.6.4 clients (desktop sidecar + a second 2.6.4 client) joined the nb- network
through the public node and reached p2p-direct with secure mode on (E2E
below). Node-pubkey pinning remains impossible on 2.6.4 (M4b finding).

**Linux privilege finding**: an unprivileged `easytier-core` cannot create its
TUN (`tun device error: Operation not permitted`). The shipped install script
therefore setcaps the bundled binary (`cap_net_admin,cap_net_bind_service`);
alternatives are a system-level service or a root helper. This is called out
in `packaging/install-autostart.sh`.

## P2 — Headless E2E evidence (2026-10-31, live infrastructure)

Scratch setup: account `test-m4c-*` (user_id 3, network `nb-3-…`, trial to
2026-11-14) created via the live API; receiver on the laptop, scratch port
28743, scratch target/data under `~/m4c-test/`; a temp easytier client +
the Java sender on the cloud server played the "phone" (10.99.0.3). The live
v1 receiver (28741) and all server services were untouched; temp state was
cleaned up afterwards (the clearly-named test account remains in the server
DB for reference).

* Login + sidecar: account.json written (600); sidecar up with
  `secure_mode=true`, `tun1 = 10.99.0.2/24`, peer `PublicServer_et-public` p2p.
* Pairing: phone device registered via session; approved through the
  receiver's PC path — `BackupReceiver --approve 4` → server `approved=true`,
  next sync showed it approved in the local cache.
* Mesh: after ~25 s the PC↔phone route became **p2p direct UDP (~44–58 ms,
  0 % loss)** in both peer tables.
* Backup over the product network: 4 files (incl. a 200,000-byte blob) sent
  as user 3 / device 4, `route_cost=1`; **receipt signature VALID**; files
  landed under `target/users/3/devices/4/`; blob sha256 matched the receipt;
  `files` table rows `integrity=ok` (photo got a thumbnail); receiver log:
  `auth ok via online validate (hash learned): device=4`.
* Negative 1: second phone device left unapproved →
  `HELLO_ERR {"code":"AUTH"}`.
* Revocation: `POST /api/devices/4/revoke` → one sync cycle later the cached
  row was `revoked=1` → backup attempt rejected:
  `auth fail (device revoked, cached) device=4`.
* Offline grace: receiver restarted with the server URL pointed at a dead
  port — initial sync failed and was logged, serving continued from cache; a
  backup as device 4 succeeded from cache (`auth ok from cache: device=4`),
  receipt VALID, file landed.

## P3 — Android app M4c

Evolved in place (plain Views, code-built UI, same package
`net.machang.backup`). APK built, **not installed** (no phone this round):
`app/build/outputs/apk/debug/app-debug.apk`, 21,650,801 bytes (Media3 +
WorkManager added ≈5 MB over M4a).

New pieces:

* `AccountClient` — account-service client (register/login/refresh, device
  registration, provisioning, validate, count-level session-log POST).
* `SecureStore` — AES-256 key in AndroidKeyStore, AES/GCM ciphertext in
  SharedPreferences: access/refresh tokens, device token, network secret.
* Main screen account section: server URL (configurable, dev default
  `http://10.200.0.1:18090`), register/login → phone device registration
  (token shown once, straight into SecureStore) → provisioning →
  `EasyTierBridge.buildProductConfig` (per-user nb- network, public-node
  peer, `secure_mode = true`, phone 10.99.0.3/24) → pairing state via
  validate polling （等待 PC 批准 / 已批准）. The manual-secret field remains
  as the dev override (self-use machang-net); the v2 standalone identity
  fields remain for seeded receivers.
* `BackupVpnService` generalized: full-config start (TOML + TUN address +
  route via intent extras) alongside the unchanged legacy path.
* `ReceiptStore` — generalizes M4a's `last-receipt.json`: every signature-
  *verified
...[truncated 5626 chars]