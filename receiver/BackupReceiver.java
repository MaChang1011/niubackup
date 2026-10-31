import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.*;
import java.sql.*;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

/**
 * BackupReceiver — PC-side receiver for NiuBackup （牛备）, protocol v2 (M4a).
 *
 * Extends the M1 v1 receiver with:
 *  - Identity: SQLite record store (sqlite-jdbc) holding users / devices
 *    (token stored as SHA-256 hash only) / licenses / files / sessions.
 *  - Protocol v2 HELLO (JSON): auth (device exists + approved + not revoked +
 *    token hash match) -&gt; license not expired -&gt; direct-only gate (§5.5:
 *    client route_cost and, when easytier-cli is available, the receiver's own
 *    peer cost; relay rejected unless env NB_ALLOW_RELAY=1 downgrades it to a
 *    warning; unknown = allow + log).
 *  - Multi-user layout: files land in &lt;target&gt;/users/&lt;userId&gt;/devices/&lt;deviceId&gt;/.
 *  - Manifest decisions consult the files table (receiver-side index) instead
 *    of re-hashing stored copies.
 *  - Resume (§5.3): .part offset resume as v1, plus a .part.meta sidecar
 *    (source size+mtime); if the source changed since the partial transfer,
 *    the stale .part is discarded so versions are never spliced.
 *  - Signed completion receipts: after each session the receiver signs a
 *    receipt (user_id, device_id, ts, verified files[] with sha256) with its
 *    ECDSA P-256 keypair (SHA256withECDSA; keys persisted under the data dir)
 *    and returns {receipt, signature, pubkey} inside the RECEIPT message.
 *  - File-service mode (HELLO mode="fs"): LIST / READ (base64 ranged chunk,
 *    1 MB cap) / THUMB over the same authenticated connection.
 *  - Thumbnails at ingest (§7.1): jpg/png via ImageIO, videos via ffmpeg,
 *    HEIC via ImageMagick convert — 320px long edge into &lt;dataDir&gt;/thumbs/.
 *    Thumbnail failures never fail a backup.
 *  - --verify mode: re-hash stored files against the files table, report
 *    mismatches, update integrity flags.
 *  - --seed mode: create user + device, print a fresh device token ONCE
 *    (only its hash is stored), license expiring in --days days (negative =
 *    already expired, for tests).
 *  - --allow-v1: also accept the legacy v1 HELLO (no auth) into
 *    &lt;target&gt;/&lt;device&gt;/ (transition compatibility; default OFF).
 *
 * Data dir (default &lt;target&gt;/.niubackup): records.db, receiver-key.pkcs8 (600),
 * receiver-key.pub, thumbs/.
 *
 * Build/run: see receiver/BUILD.md.
 */
public class BackupReceiver {

    static final int PROTO_V2 = 2;
    static final int DEFAULT_PORT = 28741;
    static final long MAX_READ_CHUNK = 1024 * 1024;
    static final int THUMB_EDGE = 320;

    final int port;
    final Path targetRoot;
    final String bind;
    final Path dataDir;
    final boolean allowV1;
    final boolean allowRelay;

    Store store;
    PrivateKey signKey;
    PublicKey signPub;
    boolean ffmpegPresent;
    boolean convertPresent;

    public BackupReceiver(int port, Path targetRoot, String bind, Path dataDir, boolean allowV1) {
        this.port = port;
        this.targetRoot = targetRoot;
        this.bind = bind;
        this.dataDir = dataDir;
        this.allowV1 = allowV1;
        this.allowRelay = "1".equals(System.getenv("NB_ALLOW_RELAY"));
    }

    // ------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int port = DEFAULT_PORT;
        String envPort = System.getenv("BACKUP_PORT");
        if (envPort != null) port = Integer.parseInt(envPort.trim());
        Path target = Paths.get(System.getProperty("user.home"), "PhoneBackup");
        String envDir = System.getenv("BACKUP_DIR");
        if (envDir != null && !envDir.isEmpty()) target = Paths.get(envDir);
        String bind = System.getenv("BACKUP_BIND");
        Path dataDir = null;
        boolean allowV1 = false, seed = false, verify = false;
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--allow-v1": allowV1 = true; break;
                case "--seed": seed = true; break;
                case "--verify": verify = true; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--target": target = Paths.get(args[++i]); break;
                case "--bind": bind = args[++i]; break;
                case "--data-dir": dataDir = Paths.get(args[++i]); break;
                default: opt.put(args[i], i + 1 < args.length ? args[i + 1] : ""); break;
            }
        }
        if (dataDir == null) dataDir = target.resolve(".niubackup");
        BackupReceiver r = new BackupReceiver(port, target, bind, dataDir, allowV1);
        r.openStore();
        if (seed) { r.seed(opt); return; }
        if (verify) { System.exit(r.verifyAll() ? 0 : 1); }
        r.loadOrCreateKeys();
        r.detectTools();
        r.serve();
    }

    void openStore() throws Exception {
        Files.createDirectories(dataDir);
        store = new Store(dataDir.resolve("records.db"));
    }

    void detectTools() {
        ffmpegPresent = onPath("ffmpeg");
        convertPresent = onPath("convert");
        log("tools: ffmpeg=" + ffmpegPresent + ", imagemagick-convert=" + convertPresent
                + ", easytier-cli=" + onPath("easytier-cli"));
    }

    static boolean onPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            if (Files.isExecutable(Paths.get(dir, exe))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ seed

    /** Create (or re-issue) a user + device. Prints the token exactly once. */
    void seed(Map<String, String> opt) throws Exception {
        String user = opt.get("--user");
        String device = opt.get("--device");
        if (user == null || device == null) {
            System.err.println("usage: --seed --user <id> --device <id> [--name <label>] "
                    + "[--kind phone|pc] [--days 365]");
            System.exit(2);
        }
        if (!user.equals(sanitizeDevice(user)) || !device.equals(sanitizeDevice(device))) {
            System.err.println("user/device ids may only contain [A-Za-z0-9._-]");
            System.exit(2);
        }
        String name = opt.getOrDefault("--name", device);
        String kind = opt.getOrDefault("--kind", "phone");
        long days = Long.parseLong(opt.getOrDefault("--days", "365"));
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String token = Hash.hex(raw);
        long expires = System.currentTimeMillis() + days * 86_400_000L;
        store.seedUserDevice(user, device, name, kind, Hash.hex(sha256(token)), expires);
        System.out.println("user_id: " + user);
        System.out.println("device_id: " + device);
        System.out.println("device_token: " + token + "   (shown once; only its SHA-256 hash is stored)");
        System.out.println("license_expires_at: " + expires + " (" + new java.util.Date(expires) + ")");
    }

    // ------------------------------------------------------------------ verify

    /** Re-hash every recorded file; returns true when everything checks out. */
    boolean verifyAll() throws Exception {
        List<FileRow> rows = store.allFiles();
        int ok = 0, mismatch = 0, missing = 0;
        for (FileRow f : rows) {
            Path p = targetRoot.resolve("users").resolve(f.userId)
                    .resolve("devices").resolve(f.deviceId).resolve(f.relPath);
            if (!Files.isRegularFile(p)) {
                missing++;
                store.setIntegrity(f.id, "missing");
                System.out.println("MISSING  " + f.userId + "/" + f.deviceId + "/" + f.relPath);
            } else if (Files.size(p) == f.size && Hash.sha256(p).equals(f.sha256)) {
                ok++;
                if (!"ok".equals(f.integrity)) store.setIntegrity(f.id, "ok");
            } else {
                mismatch++;
                store.setIntegrity(f.id, "suspect");
                System.out.println("MISMATCH " + f.userId + "/" + f.deviceId + "/" + f.relPath);
            }
        }
        System.out.println("verify: " + rows.size() + " files, " + ok + " ok, "
                + mismatch + " mismatch, " + missing + " missing");
        return mismatch == 0 && missing == 0;
    }

    // ------------------------------------------------------------------ keys

    void loadOrCreateKeys() throws Exception {
        Path priv = dataDir.resolve("receiver-key.pkcs8");
        Path pub = dataDir.resolve("receiver-key.pub");
        KeyFactory kf = KeyFactory.getInstance("EC");
        if (Files.isRegularFile(priv) && Files.isRegularFile(pub)) {
            signKey = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv)));
            signPub = kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub)));
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            signKey = kp.getPrivate();
            signPub = kp.getPublic();
            Files.write(priv, signKey.getEncoded());
            Files.write(pub, signPub.getEncoded());
            try {
                Files.setPosixFilePermissions(priv,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignore) { /* non-POSIX fs */ }
        }
        log("receipt signing key: ECDSA P-256, pubkey=" + pubkeyB64());
    }

    String pubkeyB64() {
        return Base64.getEncoder().encodeToString(signPub.getEncoded());
    }

    String sign(String payload) throws Exception {
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(signKey);
        s.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    // ------------------------------------------------------------------ serve

    void serve() throws IOException {
        Files.createDirectories(targetRoot);
        InetAddress addr = (bind == null || bind.isEmpty()) ? null : InetAddress.getByName(bind);
        try (ServerSocket ss = new ServerSocket(port, 50, addr)) {
            log("listening on " + (addr == null ? "*" : addr.getHostAddress()) + ":" + port
                    + ", target dir " + targetRoot + ", data dir " + dataDir
                    + ", allowV1=" + allowV1 + ", allowRelay=" + allowRelay);
            while (true) {
                Socket s = ss.accept();
                log("connection from " + s.getRemoteSocketAddress());
                try {
                    handle(s);
                } catch (Exception e) {
                    log("session error: " + e);
                } finally {
                    try { s.close(); } catch (IOException ignore) { }
                }
            }
        }
    }

    void handle(Socket s) throws Exception {
        s.setSoTimeout(120_000);
        InputStream in = s.getInputStream();
        OutputStream out = new BufferedOutputStream(s.getOutputStream());

        String hello = Wire.readLine(in);
        if (hello == null || !hello.startsWith("HELLO ")) {
            Wire.writeLine(out, "ERR expected HELLO");
            return;
        }
        String rest = hello.substring(6).trim();
        if (rest.startsWith("{")) {
            handleV2(s, in, out, rest);
        } else if (allowV1) {
            handleV1(in, out, rest);
        } else {
            Wire.writeLine(out, "ERR legacy v1 protocol disabled (start receiver with --allow-v1 to accept)");
            log("rejected legacy v1 HELLO (allowV1 off)");
        }
    }

    // ------------------------------------------------------------------ v2 auth + gate

    static final class Auth {
        String userId, deviceId, mode;
        int routeCost;
        DeviceRow device;
    }

    /** Returns null on success (auth filled), or the HELLO_ERR code to send. */
    String authenticate(String json, String clientIp, Auth auth) throws Exception {
        long version = MiniJson.num(json, "version", 0);
        if (version != PROTO_V2) return "VERSION";
        auth.userId = MiniJson.str(json, "user_id");
        auth.deviceId = MiniJson.str(json, "device_id");
        String token = MiniJson.str(json, "device_token");
        auth.mode = MiniJson.str(json, "mode");
        if (auth.mode == null) auth.mode = "backup";
        auth.routeCost = (int) MiniJson.num(json, "route_cost", -1);
        if (auth.userId == null || auth.deviceId == null || token == null) return "AUTH";

        DeviceRow dev = store.findDevice(auth.userId, auth.deviceId);
        if (dev == null || !dev.approved || dev.revoked) {
            log("auth fail (unknown/unapproved/revoked device) user=" + auth.userId + " device=" + auth.deviceId);
            return "AUTH";
        }
        byte[] presented = sha256(token);
        byte[] expected = hexToBytes(dev.tokenHash);
        if (!MessageDigest.isEqual(presented, expected)) {
            log("auth fail (token mismatch) user=" + auth.userId + " device=" + auth.deviceId);
            return "AUTH";
        }
        auth.device = dev;

        long expires = store.licenseExpiresAt(auth.userId);
        if (expires < 0 || expires < System.currentTimeMillis()) {
            log("license expired/absent for user=" + auth.userId);
            return "LICENSE_EXPIRED";
        }

        // Direct-only gate (§5.5): client declaration first, then our own view.
        String relayWhy = null;
        if (auth.routeCost >= 2) relayWhy = "client route_cost=" + auth.routeCost;
        Integer ownCost = receiverSideCost(clientIp);
        if (ownCost != null && ownCost >= 2) relayWhy = "receiver-side peer cost=" + ownCost;
        if (auth.routeCost == -1 && ownCost == null) {
            log("direct check: route unknown (client -1, easytier-cli unavailable/no peer) — allowing, logged");
        }
        if (relayWhy != null) {
            if (allowRelay) {
                log("direct check: " + relayWhy + " — RELAY ALLOWED by NB_ALLOW_RELAY=1 (dev override)");
            } else {
                log("direct check: " + relayWhy + " — rejecting (NOT_DIRECT)");
                return "NOT_DIRECT";
            }
        } else {
            log("direct check: ok (client route_cost=" + auth.routeCost + ", receiver cost=" + ownCost + ")");
        }
        if (!auth.mode.equals("backup") && !auth.mode.equals("fs")) return "MODE";
        return null;
    }

    /**
     * Our own view of the tunnel to the client: parse `easytier-cli peer` for the
     * row of the client IP. Cost 1 = p2p, >=2 = relay(n). Null = unknown
     * (CLI missing, timed out, or the peer is not listed — e.g. same-host tests).
     */
    static Integer receiverSideCost(String clientIp) {
        if (clientIp == null || !onPath("easytier-cli")) return null;
        Path tmp = null;
        try {
            tmp = Files.createTempFile("etpeer", ".txt");
            Process p = new ProcessBuilder("easytier-cli", "peer")
                    .redirectOutput(tmp.toFile())
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            Pattern relay = Pattern.compile("relay\\((\\d+)\\)");
            for (String line : Files.readAllLines(tmp, StandardCharsets.UTF_8)) {
                if (!line.contains(clientIp)) continue;
                Matcher m = relay.matcher(line);
                if (m.find()) return Integer.parseInt(m.group(1));
                if (line.contains("p2p")) return 1;
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignore) { }
        }
    }

    void handleV2(Socket s, InputStream in, OutputStream out, String json) throws Exception {
        String clientIp = s.getInetAddress() == null ? null : s.getInetAddress().getHostAddress();
        Auth auth = new Auth();
        String err = authenticate(json, clientIp, auth);
        if (err != null) {
            Wire.writeLine(out, "HELLO_ERR {\"code\":\"" + err + "\"}");
            return;
        }
        Wire.writeLine(out, "HELLO_ACK {\"ok\":true}");
        log("v2 session start: user=" + auth.userId + " device=" + auth.deviceId + " mode=" + auth.mode);
        if (auth.mode.equals("fs")) {
            handleFs(in, out, auth);
        } else {
            Path deviceDir = targetRoot.resolve("users").resolve(auth.userId)
                    .resolve("devices").resolve(auth.deviceId);
            Files.createDirectories(deviceDir);
            runBackupSession(in, out, deviceDir, auth);
        }
    }

    // ------------------------------------------------------------------ v1 legacy

    void handleV1(InputStream in, OutputStream out, String rest) throws Exception {
        String[] hp = rest.split(" ");
        String device = sanitizeDevice(hp.length > 0 ? hp[0] : "unknown");
        int clientProto = hp.length > 1 ? parseInt(hp[1], 0) : 0;
        if (clientProto != 1) {
            Wire.writeLine(out, "ERR protocol version mismatch, server speaks 2 (v1 via --allow-v1)");
            return;
        }
        Wire.writeLine(out, "OK 1");
        Path deviceDir = targetRoot.resolve(device);
        Files.createDirectories(deviceDir);
        log("legacy v1 session, device=" + device);
        runBackupSession(in, out, deviceDir, null);
    }

    // ------------------------------------------------------------------ backup session (v1 flow, v2-aware)

    /**
     * The v1 manifest/decision/transfer flow. When {@code auth} is non-null
     * (v2), decisions consult the files table, verified files are recorded,
     * thumbnails are generated, a session row is written and the RECEIPT
     * carries a signed completion receipt. Legacy v1 (auth == null) behaves
     * exactly like the M1 receiver.
     */
    void runBackupSession(InputStream in, OutputStream out, Path deviceDir, Auth auth) throws Exception {
        // ---- manifest ----
        List<Entry> manifest = new ArrayList<>();
        String line;
        while ((line = Wire.readLine(in)) != null) {
            if (line.equals("MANIFEST-END")) break;
            Entry e = new Entry();
            e.path = MiniJson.str(line, "path");
            e.size = MiniJson.num(line, "size", -1);
            e.mtime = MiniJson.num(line, "mtime", 0);
            e.sha = MiniJson.str(line, "sha256");
            if (e.path != null && e.size >= 0 && e.sha != null) manifest.add(e);
        }

        // ---- decide ----
        List<Entry> toSend = new ArrayList<>();
        List<String> ok = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, Entry> byPath = new HashMap<>();
        long sessionBytes = 0;
        for (Entry e : manifest) {
            Path dest = safeResolve(deviceDir, e.path);
            if (dest == null) {
                log("rejecting unsafe path: " + e.path);
                failed.add(e.path);
                continue;
            }
            e.dest = dest;
            e.part = dest.resolveSibling(dest.getFileName().toString() + ".part");
            e.meta = dest.resolveSibling(dest.getFileName().toString() + ".part.meta");
            byPath.put(e.path, e);

            // Receiver-side index: a matching files-table row + present copy = skip,
            // no re-hash of the stored file (the M3.5 receiver bottleneck fix).
            if (auth != null) {
                FileRow row = store.findFile(auth.userId, auth.deviceId, e.path);
                if (row != null && row.size == e.size && row.sha256.equals(e.sha)
                        && "ok".equals(row.integrity)
                        && Files.isRegularFile(dest) && Files.size(dest) == e.size) {
                    skipped.add(e.path);
                    continue;
                }
            }
            if (Files.isRegularFile(dest) && Files.size(dest) == e.size
                    && Hash.sha256(dest).equals(e.sha)) {
                skipped.add(e.path);
                if (auth != null) recordVerified(auth, e, dest);
                continue;
            }
            long offset = 0;
            if (Files.isRegularFile(e.part)) {
                long plen = Files.size(e.part);
                Boolean metaMatch = partMetaMatches(e);
                if (Boolean.FALSE.equals(metaMatch)) {
                    // Source size/mtime changed since the partial transfer:
                    // the old progress belongs to another version — discard (§5.3).
                    log("discarding stale .part (source changed): " + e.path);
                    Files.deleteIfExists(e.part);
                    Files.deleteIfExists(e.meta);
                } else if (plen == e.size) {
                    if (Hash.sha256(e.part).equals(e.sha)) {
                        promote(e);
                        ok.add(e.path);
                        if (auth != null) recordVerified(auth, e, dest);
                        log("completed from leftover .part: " + e.path);
                        continue;
                    }
                    Files.deleteIfExists(e.part);
                    Files.deleteIfExists(e.meta);
                } else if (plen < e.size) {
                    offset = plen;
                } else {
                    Files.deleteIfExists(e.part);
                    Files.deleteIfExists(e.meta);
                }
            }
            e.offset = offset;
            toSend.add(e);
            Wire.writeLine(out, "{\"path\":\"" + MiniJson.esc(e.path) + "\",\"offset\":" + offset + "}");
        }
        Wire.writeLine(out, "READY");
        log("manifest: " + manifest.size() + " files, " + skipped.size() + " skipped, "
                + toSend.size() + " to receive");

        // ---- transfer, stop-and-wait per file ----
        for (int i = 0; i < toSend.size(); i++) {
            String hdr = Wire.readLine(in);
            if (hdr == null || !hdr.startsWith("FILE ")) {
                for (int j = i; j < toSend.size(); j++) failed.add(toSend.get(j).path);
                log("sender stopped early");
                break;
            }
            String fjson = hdr.substring(5);
            String fpath = MiniJson.str(fjson, "path");
            long bytes = MiniJson.num(fjson, "bytes", -1);
            Entry e = byPath.get(fpath);
            if (e == null || bytes < 0 || bytes != e.size - e.offset) {
                for (int j = i; j < toSend.size(); j++) failed.add(toSend.get(j).path);
                log("protocol violation in FILE header: " + hdr);
                break;
            }
            Files.createDirectories(e.dest.getParent());
            if (e.offset == 0) {
                Files.deleteIfExists(e.part);
                Files.deleteIfExists(e.meta);
            }
            writePartMeta(e); // records source size+mtime for cross-session invalidation
            try (OutputStream fos = Files.newOutputStream(e.part,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                copyN(in, fos, bytes);
            }
            sessionBytes += bytes;
            if (Hash.sha256(e.part).equals(e.sha)) {
                promote(e);
                Files.deleteIfExists(e.meta);
                ok.add(e.path);
                if (auth != null) recordVerified(auth, e, e.dest);
                Wire.writeLine(out, "DONE {\"path\":\"" + MiniJson.esc(e.path) + "\"}");
                log("received " + e.path + " (" + e.size + " bytes, resumed at " + e.offset + ")");
            } else {
                Files.deleteIfExists(e.part);
                Files.deleteIfExists(e.meta);
                failed.add(e.path);
                Wire.writeLine(out, "FAIL {\"path\":\"" + MiniJson.esc(e.path)
                        + "\",\"error\":\"sha256 mismatch\"}");
                log("FAILED (sha256 mismatch): " + e.path);
            }
        }

        Wire.readLine(in); // BYE (best effort)
        String receipt = receiptJson(ok, skipped, failed);
        if (auth != null) {
            String signedDoc = buildSignedReceipt(auth, manifest, ok);
            String signed = ",\"signed\":{\"receipt\":\"" + MiniJson.esc(signedDoc)
                    + "\",\"signature\":\"" + sign(signedDoc)
                    + "\",\"pubkey\":\"" + pubkeyB64() + "\"}";
            receipt = receipt.substring(0, receipt.length() - 1) + signed + "}";
            String result = failed.isEmpty() ? "ok" : (ok.isEmpty() && skipped.isEmpty() ? "failed" : "partial");
            store.insertSession(auth.deviceId, ok.size(), sessionBytes, result);
        }
        try {
            Wire.writeLine(out, "RECEIPT " + receipt);
        } catch (IOException ignore) { }
        log("session done: ok=" + ok.size() + " skipped=" + skipped.size() + " failed=" + failed.size());
    }

    /** Files-table write for a file whose stored copy is verified (transferred or already present). */
    void recordVerified(Auth auth, Entry e, Path stored) {
        String thumb = null;
        try {
            thumb = makeThumbnail(stored, e.sha);
        } catch (Exception ex) {
            log("thumbnail failed for " + e.path + ": " + ex);
        }
        try {
            store.upsertFile(auth.userId, auth.deviceId, e.path, e.size, e.sha,
                    System.currentTimeMillis(), "ok", thumb);
        } catch (Exception ex) {
            log("record write failed for " + e.path + ": " + ex);
        }
    }

    /** The signed completion receipt document (exact string that gets signed). */
    String buildSignedReceipt(Auth auth, List<Entry> manifest, List<String> okPaths) {
        Map<String, Entry> byPath = new HashMap<>();
        for (Entry e : manifest) byPath.put(e.path, e);
        StringBuilder b = new StringBuilder();
        b.append("{\"user_id\":\"").append(MiniJson.esc(auth.userId))
         .append("\",\"device_id\":\"").append(MiniJson.esc(auth.deviceId))
         .append("\",\"ts\":").append(System.currentTimeMillis())
         .append(",\"files\":[");
        boolean first = true;
        for (String p : okPaths) {
            Entry e = byPath.get(p);
            if (e == null) continue;
            if (!first) b.append(',');
            first = false;
            b.append("{\"path\":\"").append(MiniJson.esc(e.path))
             .append("\",\"size\":").append(e.size)
             .append(",\"sha256\":\"").append(e.sha).append("\"}");
        }
        return b.append("]}").toString();
    }

    /** True = meta matches manifest, False = stale, null = no meta (v1 leftovers). */
    Boolean partMetaMatches(Entry e) {
        if (!Files.isRegularFile(e.meta)) return null;
        try {
            String json = Files.readString(e.meta, StandardCharsets.UTF_8);
            return MiniJson.num(json, "size", -1) == e.size
                    && MiniJson.num(json, "mtime", -1) == e.mtime;
        } catch (IOException ex) {
            return null;
        }
    }

    void writePartMeta(Entry e) {
        try {
            Files.writeString(e.meta, "{\"size\":" + e.size + ",\"mtime\":" + e.mtime + "}",
                    StandardCharsets.UTF_8);
        } catch (IOException ignore) { }
    }

    // ------------------------------------------------------------------ file service (mode=fs)

    void handleFs(InputStream in, OutputStream out, Auth auth) throws Exception {
        Path deviceDir = targetRoot.resolve("users").resolve(auth.userId)
                .resolve("devices").resolve(auth.deviceId);
        String line;
        while ((line = Wire.readLine(in)) != null) {
            String op = MiniJson.str(line, "op");
            if (op == null) {
                Wire.writeLine(out, "{\"ok\":false,\"error\":\"missing op\"}");
                continue;
            }
            switch (op) {
                case "LIST": {
                    List<FileRow> rows = store.listFiles(auth.userId, auth.deviceId);
                    StringBuilder b = new StringBuilder("{\"ok\":true,\"files\":[");
                    boolean first = true;
                    for (FileRow f : rows) {
                        if (!first) b.append(',');
                        first = false;
                        b.append("{\"path\":\"").append(MiniJson.esc(f.relPath))
                         .append("\",\"size\":").append(f.size)
                         .append(",\"sha256\":\"").append(f.sha256)
                         .append("\",\"backed_at\":").append(f.backedAt)
                         .append(",\"integrity\":\"").append(f.integrity)
                         .append("\",\"thumb\":").append(f.thumb != null).append('}');
                    }
                    Wire.writeLine(out, b.append("]}").toString());
                    break;
                }
                case "READ": {
                    String path = MiniJson.str(line, "path");
                    long offset = MiniJson.num(line, "offset", 0);
                    long len = MiniJson.num(line, "len", 0);
                    FileRow row = path == null ? null : store.findFile(auth.userId, auth.deviceId, path);
                    Path file = row == null ? null : safeResolve(deviceDir, path);
                    if (row == null || file == null || !Files.isRegularFile(file)) {
                        Wire.writeLine(out, "{\"ok\":false,\"error\":\"not found\"}");
                        break;
                    }
                    len = Math.max(0, Math.min(len, MAX_READ_CHUNK));
                    long avail = Math.max(0, Files.size(file) - Math.max(0, offset));
                    int n = (int) Math.min(len, avail);
                    byte[] buf = new byte[n];
                    try (InputStream fis = Files.newInputStream(file)) {
                        skipFully(fis, Math.max(0, offset));
                        int got = 0;
                        while (got < n) {
                            int r = fis.read(buf, got, n - got);
                            if (r == -1) break;
                            got += r;
                        }
                        if (got < n) buf = Arrays.copyOf(buf, got);
                    }
                    Wire.writeLine(out, "{\"ok\":true,\"path\":\"" + MiniJson.esc(path)
                            + "\",\"offset\":" + offset + ",\"len\":" + buf.length
                            + ",\"data\":\"" + Base64.getEncoder().encodeToString(buf) + "\"}");
                    break;
                }
                case "THUMB": {
                    String path = MiniJson.str(line, "path");
                    FileRow row = path == null ? null : store.findFile(auth.userId, auth.deviceId, path);
                    String b64 = null;
                    if (row != null && row.thumb != null) {
                        Path t = dataDir.resolve("thumbs").resolve(row.thumb);
                        if (Files.isRegularFile(t)) {
                            b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(t));
                        }
                    }
                    Wire.writeLine(out, "{\"ok\":true,\"thumb\":"
                            + (b64 == null ? "null" : "\"" + b64 + "\"") + "}");
                    break;
                }
                default:
                    Wire.writeLine(out, "{\"ok\":false,\"error\":\"unknown op\"}");
            }
        }
        log("fs session end: user=" + auth.userId + " device=" + auth.deviceId);
    }

    // ------------------------------------------------------------------ thumbnails (§7.1)

    /** Returns the thumbnail file name under dataDir/thumbs, or null. Never throws fatally. */
    String makeThumbnail(Path stored, String sha256) throws IOException {
        Path thumbsDir = dataDir.resolve("thumbs");
        Files.createDirectories(thumbsDir);
        Path out = thumbsDir.resolve(sha256 + ".jpg");
        if (Files.isRegularFile(out)) return out.getFileName().toString();
        String name = stored.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        boolean ok = false;
        if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
                || ext.equals("gif") || ext.equals("bmp")) {
            ok = thumbViaImageIO(stored, out);
        } else if (ext.equals("heic") || ext.equals("heif")) {
            if (convertPresent) {
                ok = runTool(Arrays.asList("convert", stored + "[0]",
                        "-auto-orient", "-resize", THUMB_EDGE + "x" + THUMB_EDGE + ">",
                        out.toString()), 60);
            }
        } else if (ext.equals("mp4") || ext.equals("mov") || ext.equals("mkv")
                || ext.equals("avi") || ext.equals("webm") || ext.equals("3gp") || ext.equals("m4v")) {
            if (ffmpegPresent) {
                ok = runTool(Arrays.asList("ffmpeg", "-y", "-ss", "1", "-i", stored.toString(),
                        "-frames:v", "1", "-vf", "scale=" + THUMB_EDGE + ":-2",
                        out.toString()), 60);
            }
        }
        if (ok && Files.isRegularFile(out) && Files.size(out) > 0) {
            return out.getFileName().toString();
        }
        Files.deleteIfExists(out);
        return null;
    }

    boolean thumbViaImageIO(Path src, Path out) {
        try {
            BufferedImage img = ImageIO.read(src.toFile());
            if (img == null) return false;
            int w = img.getWidth(), h = img.getHeight();
            double scale = Math.min(1.0, (double) THUMB_EDGE / Math.max(w, h));
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));
            BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, nw, nh, null);
            g.dispose();
            return ImageIO.write(scaled, "jpg", out.toFile());
        } catch (Exception e) {
            return false;
        }
    }

    static boolean runTool(List<String> cmd, int timeoutSec) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ shared helpers

    void promote(Entry e) throws IOException {
        try {
            Files.move(e.part, e.dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(e.part, e.dest, StandardCopyOption.REPLACE_EXISTING);
        }
        if (e.mtime > 0) {
            Files.setLastModifiedTime(e.dest, java.nio.file.attribute.FileTime.fromMillis(e.mtime));
        }
    }

    static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r == -1) throw new EOFException("stream ended with " + left + " bytes missing");
            out.write(buf, 0, r);
            left -= r;
        }
    }

    static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() == -1) throw new EOFException("file shorter than offset");
                skipped = 1;
            }
            left -= skipped;
        }
    }

    static Path safeResolve(Path base, String rel) {
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.startsWith("\\")) return null;
        Path p = base;
        for (String seg : rel.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) return null;
            p = p.resolve(seg);
        }
        Path norm = p.normalize();
        return norm.startsWith(base.normalize()) ? norm : null;
    }

    static String sanitizeDevice(String d) {
        StringBuilder b = new StringBuilder();
        for (char c : d.toCharArray()) {
            b.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        String s = b.toString();
        return (s.isEmpty() || s.equals(".") || s.equals("..")) ? "unknown" : s;
    }

    static String receiptJson(List<String> ok, List<String> skipped, List<String> failed) {
        return "{\"ok\":" + arr(ok) + ",\"skipped\":" + arr(skipped) + ",\"failed\":" + arr(failed) + "}";
    }

    static String arr(List<String> items) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(MiniJson.esc(items.get(i))).append('"');
        }
        return b.append(']').toString();
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static byte[] hexToBytes(String hex) {
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static int parseInt(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; }
    }

    static void log(String m) {
        System.out.println("[" + LocalTime.now().withNano(0) + "] " + m);
    }

    static final class Entry {
        String path; long size; long mtime; String sha;
        long offset; Path dest; Path part; Path meta;
    }

    static final class DeviceRow {
        String id, userId, name, kind, tokenHash, pcPubkey;
        boolean approved, revoked;
    }

    static final class FileRow {
        long id; String userId, deviceId, relPath, sha256, integrity, thumb;
        long size, backedAt;
    }

    // ------------------------------------------------------------------ SQLite store

    static final class Store {
        private final Connection db;

        Store(Path file) throws Exception {
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement st = db.createStatement()) {
                st.execute("PRAGMA busy_timeout = 5000");
                st.execute("CREATE TABLE IF NOT EXISTS users ("
                        + "id TEXT PRIMARY KEY, created_at INTEGER NOT NULL DEFAULT 0)");
                st.execute("CREATE TABLE IF NOT EXISTS devices ("
                        + "id TEXT PRIMARY KEY, user_id TEXT NOT NULL, name TEXT, kind TEXT, "
                        + "token_hash TEXT NOT NULL, pc_pubkey TEXT, "
                        + "approved INTEGER NOT NULL DEFAULT 0, revoked INTEGER NOT NULL DEFAULT 0)");
                st.execute("CREATE TABLE IF NOT EXISTS licenses ("
                        + "user_id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL, "
                        + "max_phones INTEGER NOT NULL DEFAULT 2, max_pcs INTEGER NOT NULL DEFAULT 1)");
                st.execute("CREATE TABLE IF NOT EXISTS files ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, "
                        + "device_id TEXT NOT NULL, rel_path TEXT NOT NULL, size INTEGER NOT NULL, "
                        + "sha256 TEXT NOT NULL, backed_at INTEGER NOT NULL, "
                        + "integrity TEXT NOT NULL DEFAULT 'ok', thumb TEXT, "
                        + "UNIQUE(user_id, device_id, rel_path))");
                st.execute("CREATE TABLE IF NOT EXISTS sessions ("
                        + "ts INTEGER NOT NULL, device_id TEXT NOT NULL, files_ok INTEGER NOT NULL, "
                        + "bytes INTEGER NOT NULL, result TEXT NOT NULL)");
            }
        }

        synchronized void seedUserDevice(String user, String device, String name, String kind,
                                         String tokenHash, long licenseExpires) throws SQLException {
            try (PreparedStatement u = db.prepareStatement(
                    "INSERT OR IGNORE INTO users(id, created_at) VALUES(?, ?)")) {
                u.setString(1, user);
                u.setLong(2, System.currentTimeMillis());
                u.executeUpdate();
            }
            try (PreparedStatement d = db.prepareStatement(
                    "INSERT OR REPLACE INTO devices(id, user_id, name, kind, token_hash, "
                            + "pc_pubkey, approved, revoked) VALUES(?, ?, ?, ?, ?, "
                            + "(SELECT pc_pubkey FROM devices WHERE id = ?), 1, 0)")) {
                d.setString(1, device);
                d.setString(2, user);
                d.setString(3, name);
                d.setString(4, kind);
                d.setString(5, tokenHash);
                d.setString(6, device);
                d.executeUpdate();
            }
            try (PreparedStatement l = db.prepareStatement(
                    "INSERT OR REPLACE INTO licenses(user_id, expires_at) VALUES(?, ?)")) {
                l.setString(1, user);
                l.setLong(2, licenseExpires);
                l.executeUpdate();
            }
        }

        synchronized DeviceRow findDevice(String userId, String deviceId) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "SELECT * FROM devices WHERE id = ? AND user_id = ?")) {
                q.setString(1, deviceId);
                q.setString(2, userId);
                try (ResultSet rs = q.executeQuery()) {
                    if (!rs.next()) return null;
                    DeviceRow d = new DeviceRow();
                    d.id = rs.getString("id");
                    d.userId = rs.getString("user_id");
                    d.name = rs.getString("name");
                    d.kind = rs.getString("kind");
                    d.tokenHash = rs.getString("token_hash");
                    d.pcPubkey = rs.getString("pc_pubkey");
                    d.approved = rs.getInt("approved") != 0;
                    d.revoked = rs.getInt("revoked") != 0;
                    return d;
                }
            }
        }

        /** License expiry (epoch millis), or -1 when the user has no license row. */
        synchronized long licenseExpiresAt(String userId) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "SELECT expires_at FROM licenses WHERE user_id = ?")) {
                q.setString(1, userId);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1;
                }
            }
        }

        synchronized FileRow findFile(String userId, String deviceId, String relPath) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "SELECT * FROM files WHERE user_id = ? AND device_id = ? AND rel_path = ?")) {
                q.setString(1, userId);
                q.setString(2, deviceId);
                q.setString(3, relPath);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next() ? toFileRow(rs) : null;
                }
            }
        }

        synchronized List<FileRow> listFiles(String userId, String deviceId) throws SQLException {
            List<FileRow> out = new ArrayList<>();
            try (PreparedStatement q = db.prepareStatement(
                    "SELECT * FROM files WHERE user_id = ? AND device_id = ? ORDER BY rel_path")) {
                q.setString(1, userId);
                q.setString(2, deviceId);
                try (ResultSet rs = q.executeQuery()) {
                    while (rs.next()) out.add(toFileRow(rs));
                }
            }
            return out;
        }

        synchronized List<FileRow> allFiles() throws SQLException {
            List<FileRow> out = new ArrayList<>();
            try (Statement st = db.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM files ORDER BY user_id, device_id, rel_path")) {
                while (rs.next()) out.add(toFileRow(rs));
            }
            return out;
        }

        synchronized void upsertFile(String userId, String deviceId, String relPath, long size,
                                     String sha256, long backedAt, String integrity,
                                     String thumb) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "INSERT INTO files(user_id, device_id, rel_path, size, sha256, backed_at, integrity, thumb) "
                            + "VALUES(?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(user_id, device_id, rel_path) DO UPDATE SET "
                            + "size = excluded.size, sha256 = excluded.sha256, "
                            + "backed_at = excluded.backed_at, integrity = excluded.integrity, "
                            + "thumb = excluded.thumb")) {
                q.setString(1, userId);
                q.setString(2, deviceId);
                q.setString(3, relPath);
                q.setLong(4, size);
                q.setString(5, sha256);
                q.setLong(6, backedAt);
                q.setString(7, integrity);
                q.setString(8, thumb);
                q.executeUpdate();
            }
        }

        synchronized void setIntegrity(long id, String integrity) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "UPDATE files SET integrity = ? WHERE id = ?")) {
                q.setString(1, integrity);
                q.setLong(2, id);
                q.executeUpdate();
            }
        }

        synchronized void insertSession(String deviceId, int filesOk, long bytes,
                                        String result) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "INSERT INTO sessions(ts, device_id, files_ok, bytes, result) VALUES(?, ?, ?, ?, ?)")) {
                q.setLong(1, System.currentTimeMillis());
                q.setString(2, deviceId);
                q.setInt(3, filesOk);
                q.setLong(4, bytes);
                q.setString(5, result);
                q.executeUpdate();
            }
        }

        private static FileRow toFileRow(ResultSet rs) throws SQLException {
            FileRow f = new FileRow();
            f.id = rs.getLong("id");
            f.userId = rs.getString("user_id");
            f.deviceId = rs.getString("device_id");
            f.relPath = rs.getString("rel_path");
            f.size = rs.getLong("size");
            f.sha256 = rs.getString("sha256");
            f.backedAt = rs.getLong("backed_at");
            f.integrity = rs.getString("integrity");
            f.thumb = rs.getString("thumb");
            return f;
        }
    }
}

/** Line I/O on the raw socket stream (never buffered: lines and file bytes share the stream). */
final class Wire {
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            b.write(c);
        }
        if (!any) return null;
        byte[] bytes = b.toByteArray();
        int len = bytes.length;
        if (len > 0 && bytes[len - 1] == '\r') len--;
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }

    static void writeLine(OutputStream out, String line) throws IOException {
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }
}

final class Hash {
    static String sha256(Path p) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String hex(byte[] d) {
        StringBuilder b = new StringBuilder(d.length * 2);
        for (byte x : d) {
            b.append(Character.forDigit((x >> 4) & 0xF, 16));
            b.append(Character.forDigit(x & 0xF, 16));
        }
        return b.toString();
    }
}

/**
 * The bare minimum of JSON for our flat control messages: string escaping on the
 * way out, and flat "key": "string" / "key": number extraction on the way in.
 * Both ends are ours and the shape is fixed, so a real parser would be overkill.
 */
final class MiniJson {
    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    static String str(String json, String key) {
        int i = afterKey(json, key);
        if (i < 0) return null;
        i = skipWs(json, i);
        if (i >= json.length() || json.charAt(i) != '"') return null;
        StringBuilder b = new StringBuilder();
        i++;
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\' && i < json.length()) {
                char e = json.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'u':
                        if (i + 4 <= json.length()) {
                            b.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                            i += 4;
                        }
                        break;
                    default: b.append(e); break;
                }
            } else {
                b.append(c);
            }
        }
        return null;
    }

    static long num(String json, String key, long dflt) {
        int i = afterKey(json, key);
        if (i < 0) return dflt;
        i = skipWs(json, i);
        int start = i;
        if (i < json.length() && json.charAt(i) == '-') i++;
        while (i < json.length() && Character.isDigit(json.charAt(i))) i++;
        if (i == start) return dflt;
        try { return Long.parseLong(json.substring(start, i)); }
        catch (NumberFormatException e) { return dflt; }
    }

    /** Index just past the ':' of "key", or -1. */
    private static int afterKey(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return -1;
        i = json.indexOf(':', i + key.length() + 2);
        return i < 0 ? -1 : i + 1;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
        return i;
    }
}
