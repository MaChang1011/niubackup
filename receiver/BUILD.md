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
javac -cp $CP -d out receiver/BackupReceiver.java receiver/NiuPcApp.java
javac -d out sender/BackupSender.java
```

⚠️ Do NOT throw all three files into one `javac` invocation:
`BackupSender.java` is a standalone single-file tool carrying its own
copies of the `Wire`/`Hash`/`MiniJson` helpers, so a combined compile
fails with "duplicate class: Wire/Hash" (by design since M1 — that is
also why the laptop build tree keeps the sender's classes in a separate
output dir). A clean build = the two commands above, each from an empty
output dir; both were re-verified from clean on 2026-10-31 in the repo
and in the laptop tree (`~/niubackup-pc/v2`).

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

## Stream-hash sessions (protocol v2 extension)

When the HELLO_ACK advertises `"streamhash":1` alongside `"batch":1`,
a batch session needs no hashing pass: the client's scan is
metadata-only and manifest entries carry `"sha256":""` (unknown).
Decisions for such an entry key on the files table: a row with the
same size AND mtime, integrity `ok`, and a present stored copy of
matching size is skipped (the row's stored hash stands); a `.part`
whose `.part.meta` matches size+mtime resumes as usual; anything else
is received from offset 0. The receiver SHA-256s each ASSEMBLED file
itself; that hash is stored in the files table (which gained an
`mtime` column for this; pre-existing rows have mtime 0 and are
re-received once), goes into the signed receipt, and is returned in
`DONE {"path":...,"sha256":...}`. The client, which hashed the file
while streaming (hashing a resumed file's local prefix first),
compares: on mismatch it sends `HASH-MISMATCH {"path":...}` in the
slot where the next FILE header would go (or as the first line before
the next round / BYE); the receiver deletes the stored copy, marks
the row `bad`, and counts the file failed. Non-stream-hash rounds are
unchanged in every detail.

## Batch sessions (protocol v2 extension)

When the receiver answers HELLO with `HELLO_ACK {"ok":true,"batch":1}`,
a v2 backup session runs in rounds: the client enumerates once, then per
batch hashes up to the batch limit (default 200 files / 256 MiB; sender
override `-Dnb.batch.files` / `-Dnb.batch.bytes` or env `NB_BATCH_FILES` /
`NB_BATCH_BYTES`), sends the batch manifest terminated by `BATCH-END`,
reads that batch's decisions + `READY`, and transfers its files. `BYE`
ends the session; the receiver writes ONE session row and returns ONE
signed receipt over all rounds. A wanted file the client cannot open at
its turn is reported as `FILE-ERROR {"path":...,"error":...}` instead of
the FILE header — it counts failed and the batch continues (in legacy
single-manifest rounds the same line is a protocol violation). Clients
that don't see the token (and receivers that don't send it) run the
pre-batch single-manifest flow unchanged; v1 is untouched. The Android
client probes capability on a throwaway fs-mode HELLO so non-batch
receivers keep seeing the full scan before the backup connection.

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
