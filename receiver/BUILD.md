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
