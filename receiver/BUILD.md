# PC receiver / sender — build & run (M4a, protocol v2)

JDK 21. The only third-party dependency is sqlite-jdbc (receiver record
store); everything else is JDK stdlib.

## Fetch the dependency jars (once)

```bash
mkdir -p lib
curl -o lib/sqlite-jdbc-3.47.1.0.jar \
  https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.47.1.0/sqlite-jdbc-3.47.1.0.jar
curl -o lib/slf4j-api-2.0.16.jar \
  https://repo1.maven.org/maven2/org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar
```

(`lib/*.jar` is git-ignored; the jars are build inputs, not source.)

## Compile (from the repo root; the two files are compiled separately
because each carries its own copy of the Wire/Hash/MiniJson helpers)

```bash
CP=lib/sqlite-jdbc-3.47.1.0.jar:lib/slf4j-api-2.0.16.jar
javac -cp $CP -d out receiver/BackupReceiver.java
javac -d out sender/BackupSender.java
```

## Seed a user + device (prints the device token ONCE; only its hash is stored)

```bash
java -cp out:$CP BackupReceiver --seed \
  --target ~/PhoneBackup --data-dir ~/PhoneBackup/.niubackup \
  --user u1 --device phone1 --days 365
# --days -1 seeds an already-expired license (testing)
```

## Run the v2 receiver

```bash
java -cp out:$CP BackupReceiver \
  --port 28742 --bind 10.200.0.2 \
  --target ~/PhoneBackup --data-dir ~/PhoneBackup/.niubackup
# --allow-v1   also accept the legacy v1 HELLO into <target>/<device>/
# env NB_ALLOW_RELAY=1   downgrade the direct-only gate to a logged warning (dev)
```

Data dir contents: `records.db` (SQLite), `receiver-key.pkcs8` (ECDSA P-256
receipt-signing key, mode 600), `receiver-key.pub`, `thumbs/<sha256>.jpg`.

## Verify stored files against the record store

```bash
java -cp out:$CP BackupReceiver --verify \
  --target ~/PhoneBackup --data-dir ~/PhoneBackup/.niubackup
# exit 0 = all ok; prints MISMATCH/MISSING lines and updates integrity flags
```

## Sender (test client)

```bash
# backup (protocol v2; route cost from --route-cost or env NB_ROUTE_COST, default 1)
java -cp out BackupSender --host 10.200.0.2 --port 28742 --dir <sourceDir> \
  --user u1 --device-id phone1 --token <hex> [--receipt-out receipt.json]

# file service
java -cp out BackupSender --host 10.200.0.2 --port 28742 \
  --user u1 --device-id phone1 --token <hex> --fs-list
java -cp out BackupSender --host ... --fs-read photo.jpg 10 100 [--out chunk.bin]
java -cp out BackupSender --host ... --fs-thumb photo.jpg [--out thumb.jpg]

# legacy v1 mode: omit --user/--device-id/--token
```

## Account mode (M4c) — receiver bound to the account service

```bash
# one-time login (password moment; writes <data-dir>/account.json, mode 600)
NB_ACCOUNT=<account> NB_PASSWORD=<password> \
java -cp classes:$CP BackupReceiver --login \
  --server http://10.200.0.1:18090 --data-dir ~/.niubackup
# (or --account-user/--account-pass instead of the env vars)

# serve in account mode: syncs allowlist+license from the service every
# NB_SYNC_SECONDS (default 300; flag --sync-seconds), validates HELLOs from
# the local cache with offline grace, and supervises the easytier sidecar
# for the issued nb-* network (product subnet 10.99.0.0/24, PC = .2).
# Linux: the sidecar needs CAP_NET_ADMIN (setcap the easytier-core binary)
# or root; see docs/m4c-notes.md.
java -cp classes:$CP BackupReceiver --account --port 28741 \
  --target ~/NiuBackup --data-dir ~/.niubackup [--mesh-bin <easytier-core>]

# operator commands (PC device-token auth; need a prior --login)
java -cp classes:$CP BackupReceiver --devices  --data-dir ~/.niubackup
java -cp classes:$CP BackupReceiver --approve <deviceId> --data-dir ~/.niubackup
java -cp classes:$CP BackupReceiver --sync     --data-dir ~/.niubackup

# sign out: revoke the stored refresh token server-side (best effort) and
# delete account.json; the GUI's 退出登录 does the same. The session in
# account.json otherwise persists across restarts (see
# docs/session-persistence.md)
java -cp classes:$CP BackupReceiver --logout   --data-dir ~/.niubackup

# PC operator UI / packaging entry point (same classes)
java -cp classes:$CP NiuPcApp                 # Swing window + tray
java -cp classes:$CP NiuPcApp --serve --account ...   # headless passthrough
```

See docs/m4c-notes.md for the account-mode design (hash learning, offline
grace) and the E2E evidence.
