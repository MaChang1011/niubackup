import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.*;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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
 *  - Signed completion receipts: the receiver signs a receipt (user_id,
 *    device_id, ts, verified files[] with sha256) with its
 *    ECDSA key — per file, the moment that file verifies (see
 *    buildFileReceiptDoc), plus a whole-session receipt after BYE.
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
 *  - M4c account mode (--account, after a one-time --login): the receiver logs
 *    in to the account service (M4b), registers THIS PC as a pc device with
 *    the receipt-signing public key, fetches the per-user mesh provisioning,
 *    and keeps the local users/devices/licenses tables synced from the
 *    service (approved/revoked state + license; device token hashes are
 *    learned from successful online /api/license/validate calls, since the
 *    service API never returns stored hashes). HELLOs validate against the
 *    local cache — with the design's offline grace: a previously-synced,
 *    previously-validated device keeps working while the server is down.
 *    Unknown devices are verified online at HELLO time when reachable.
 *    Since 2026-10-31 the service approves devices at registration
 *    (login-is-network: same account = same mesh network, no manual
 *    approval step), so account-mode peers appear in the synced allowlist
 *    as approved the moment they register; revocation still propagates
 *    through the same sync. --approve/--devices/--sync remain as operator
 *    commands over the stored PC device token — approve is now a harmless
 *    no-op against current services, kept for older ones; a supervised
 *    easytier-core sidecar joins the issued nb- network (see MeshSidecar).
 *  - Concurrency (2026-10-31): sessions are handled on a bounded pool of
 *    worker threads, so fs sessions (records / library / playback) are
 *    served WHILE a backup runs — the old sequential accept→handle→close
 *    loop starved them in the backlog for the whole backup and the phone's
 *    Records page showed nothing. At most ONE backup session per device
 *    runs at a time (a second backup HELLO gets HELLO_ERR BUSY) because
 *    ingest mutates per-device .part state; fs sessions are unlimited.
 *    See Store for the database thread-safety scheme.
 *
 * Data dir (default &lt;target&gt;/.niubackup): records.db, receiver-key.pkcs8 (600),
 * receiver-key.pub, thumbs/, account.json (account mode, 600), easytier.toml.
 *
 * Build/run: see receiver/BUILD.md.
 */
public class BackupReceiver {

    static final int PROTO_V2 = 2;
    static final int DEFAULT_PORT = 28741;
    /**
     * Default account-service address for --login / the control API's login
     * when neither --server nor NB_SERVER is given (user decision
     * 2026-10-31): the PUBLIC endpoint, because login must work before any
     * mesh exists. An explicitly passed/env/saved address always wins.
     */
    static final String DEFAULT_SERVER = "http://36.133.114.164:18090";
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

    // M4c account mode (set from main before serve())
    boolean accountMode;
    AccountLink.State acct;
    int syncSeconds = 300;
    MeshSidecar sidecar;
    volatile long lastSyncOkMs = 0;
    static volatile ServerSocket currentServer;

    /**
     * Device dirs (absolute, normalized) with a backup session currently
     * running. Guards the per-device .part/.part.meta ingest state: a
     * second concurrent backup for the same device would corrupt resume
     * offsets, so it is rejected BUSY at HELLO time. Keyed by device dir,
     * which is unique per (user, device) in v2 and per device name in v1.
     */
    final Set<String> activeBackupDirs = ConcurrentHashMap.newKeySet();

    /** Claim the single backup slot for a device dir; false when already running. */
    boolean tryBeginBackup(Path deviceDir) {
        return activeBackupDirs.add(deviceDir.toAbsolutePath().normalize().toString());
    }

    void endBackup(Path deviceDir) {
        activeBackupDirs.remove(deviceDir.toAbsolutePath().normalize().toString());
    }

    // Tauri shell control API (--control): loopback-only JSON HTTP API.
    // controlOnly = the process parked with no saved session, serving ONLY
    // the control API until the shell drives a login and restarts us.
    boolean controlOnly;
    ControlApi controlApi;

    /** Close the currently-serving socket (used by the PC operator UI's stop button). */
    public static void stopCurrentServer() {
        ServerSocket ss = currentServer;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignore) { }
        }
    }

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
        boolean login = false, logout = false, accountMode = false, syncCmd = false, devicesCmd = false;
        boolean noMesh = false, control = false;
        String server = System.getenv("NB_SERVER");
        String approveId = null, meshBin = null, pcIp = null;
        int syncSeconds = 300;
        String envSync = System.getenv("NB_SYNC_SECONDS");
        if (envSync != null) syncSeconds = Integer.parseInt(envSync.trim());
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--allow-v1": allowV1 = true; break;
                case "--seed": seed = true; break;
                case "--verify": verify = true; break;
                case "--login": login = true; break;
                case "--logout": logout = true; break;
                case "--account": accountMode = true; break;
                case "--sync": syncCmd = true; break;
                case "--devices": devicesCmd = true; break;
                case "--no-mesh": noMesh = true; break;
                case "--control": control = true; break;
                case "--approve": approveId = args[++i]; break;
                case "--server": server = args[++i]; break;
                case "--mesh-bin": meshBin = args[++i]; break;
                case "--pc-ip": pcIp = args[++i]; break;
                case "--sync-seconds": syncSeconds = Integer.parseInt(args[++i]); break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--target": target = Paths.get(args[++i]); break;
                case "--bind": bind = args[++i]; break;
                case "--data-dir": dataDir = Paths.get(args[++i]); break;
                default: opt.put(args[i], i + 1 < args.length ? args[i + 1] : ""); break;
            }
        }
        if (dataDir == null) dataDir = target.resolve(".niubackup");
        // In account mode the default bind is the product-mesh IP from the
        // saved account state; resolve it before constructing the receiver.
        AccountLink.State earlyState = accountMode ? AccountLink.load(dataDir) : null;
        if (accountMode && earlyState != null && (bind == null || bind.isEmpty())) {
            bind = earlyState.pcIp;
        }
        BackupReceiver r = new BackupReceiver(port, target, bind, dataDir, allowV1);
        r.openStore();

        // ---- M4c one-shot account commands ----
        if (login) {
            String account = opt.containsKey("--account-user") ? opt.get("--account-user")
                    : System.getenv("NB_ACCOUNT");
            String password = opt.containsKey("--account-pass") ? opt.get("--account-pass")
                    : System.getenv("NB_PASSWORD");
            if (server == null) server = DEFAULT_SERVER;
            if (account == null || password == null) {
                System.err.println("usage: --login [--server <url>] with credentials in "
                        + "NB_ACCOUNT / NB_PASSWORD (or --account-user / --account-pass)");
                System.exit(2);
            }
            r.loadOrCreateKeys();
            AccountLink.State st = AccountLink.loginFlow(dataDir, r.store, server, account,
                    password, r.pubkeyB64(), pcIp);
            System.out.println("login ok: user_id=" + st.userId + " pc_device_id=" + st.pcDeviceId
                    + " network=" + st.networkName + " public_node=" + st.publicNode
                    + " pc_ip=" + st.pcIp);
            System.out.println("account state saved to " + dataDir.resolve("account.json")
                    + " (mode 600; the pc device token is stored there, never printed)");
            return;
        }
        if (logout) {
            AccountLink.State st = AccountLink.load(dataDir);
            if (st == null) {
                System.out.println("no saved session in " + dataDir + " (already logged out)");
                return;
            }
            AccountLink.logoutSession(st);
            System.out.println("logged out: refresh token revoked server-side (best effort), "
                    + dataDir.resolve("account.json") + " removed");
            return;
        }
        if (approveId != null || syncCmd || devicesCmd) {
            AccountLink.State st = AccountLink.load(dataDir);
            if (st == null) {
                System.err.println("no account state in " + dataDir + " — run --login first");
                System.exit(2);
            }
            if (server != null) st.server = server;
            if (approveId != null) {
                try {
                    String res = AccountLink.approveWithDevice(st, approveId);
                    System.out.println("approve device " + approveId + ": " + res);
                } catch (Exception e) {
                    // e.g. approving a revoked device: the service answers
                    // 409 device_revoked (revocation is terminal). Report it
                    // as a one-line operator error, not a stack trace.
                    System.err.println("approve device " + approveId + " failed: " + e.getMessage());
                    System.exit(1);
                }
            }
            if (devicesCmd) {
                System.out.println("server devices for user " + st.userId + ":");
                for (String[] d : AccountLink.listDevices(st, st.pcDeviceToken)) {
                    System.out.println("  id=" + d[0] + " kind=" + d[1] + " name=" + d[2]
                            + " approved=" + d[3] + " revoked=" + d[4]);
                }
            }
            if (syncCmd) {
                System.out.println(AccountLink.syncOnce(st, r.store));
            }
            return;
        }

        if (seed) { r.seed(opt); return; }
        if (verify) { System.exit(r.verifyAll() ? 0 : 1); }
        r.loadOrCreateKeys();
        r.detectTools();

        // ---- M4c account-mode serve ----
        if (accountMode) {
            AccountLink.State st = earlyState;
            if (st == null && control) {
                // Tauri shell mode with no saved session yet: serve ONLY the
                // loopback control API so the shell can drive
                // POST /account/login. The login persists account.json and
                // answers restart_required; the shell then restarts this
                // process and it comes up as a full account-mode receiver.
                r.accountMode = true;
                r.controlOnly = true;
                r.startControlApi();
                log("control-only mode: no saved account session in " + dataDir
                        + "; waiting for login via the loopback control API");
                new CountDownLatch(1).await();
                return;
            }
            if (st == null) {
                System.err.println("account mode requested but no account state in "
                        + dataDir + " — run --login first");
                System.exit(2);
            }
            if (server != null) st.server = server;
            r.accountMode = true;
            r.acct = st;
            r.syncSeconds = Math.max(5, syncSeconds);
            try {
                log(AccountLink.syncOnce(st, r.store));
                r.lastSyncOkMs = System.currentTimeMillis();
            } catch (Exception e) {
                log("initial account sync failed (continuing on cached state): " + e.getMessage());
            }
            if (!noMesh) {
                r.sidecar = new MeshSidecar(dataDir, st, meshBin);
                final MeshSidecar sc = r.sidecar;
                Runtime.getRuntime().addShutdownHook(new Thread(sc::stopSidecar));
                r.sidecar.start();
                waitForBindAddress(bind, 45);
            }
            r.startSyncThread();
        }
        if (control) r.startControlApi();
        r.serve();
    }

    /** Start the loopback control API (Tauri shell). Failures are logged, never fatal. */
    void startControlApi() {
        try {
            controlApi = new ControlApi(this);
            controlApi.start();
        } catch (Exception e) {
            log("control API failed to start: " + e);
            controlApi = null;
        }
    }

    /** In account+mesh mode the TUN address appears asynchronously; wait for it. */
    static void waitForBindAddress(String bind, int timeoutSec) {
        if (bind == null || bind.isEmpty() || bind.equals("0.0.0.0")) return;
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
                while (nis.hasMoreElements()) {
                    Enumeration<InetAddress> addrs = nis.nextElement().getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        if (addrs.nextElement().getHostAddress().equals(bind)) return;
                    }
                }
            } catch (Exception ignore) { }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
        log("warning: bind address " + bind + " not seen on any interface after "
                + timeoutSec + "s; serve() bind will retry/fail as usual");
    }

    /** Periodic allowlist/license refresh for account mode (daemon thread). */
    void startSyncThread() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(syncSeconds * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    String summary = AccountLink.syncOnce(acct, store);
                    lastSyncOkMs = System.currentTimeMillis();
                    log("account sync: " + summary);
                } catch (Exception e) {
                    String age = lastSyncOkMs == 0 ? "never"
                            : ((System.currentTimeMillis() - lastSyncOkMs) / 1000) + "s ago";
                    log("account sync failed (last ok " + age + "); "
                            + "validating from cache: " + e.getMessage());
                }
            }
        }, "account-sync");
        t.setDaemon(true);
        t.start();
        log("account sync thread started (every " + syncSeconds + "s)");
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
        ServerSocket ss = null;
        // In account+mesh mode the TUN address may appear a moment after the
        // sidecar starts; retry the bind briefly instead of dying.
        for (int attempt = 0; attempt < 30 && ss == null; attempt++) {
            try {
                ss = new ServerSocket(port, 50, addr);
            } catch (BindException e) {
                if (!accountMode) throw e;
                if (attempt == 0) log("bind " + bind + ":" + port + " not ready yet, retrying…");
                try { Thread.sleep(2000); } catch (InterruptedException ie) { throw new IOException(ie); }
            }
        }
        if (ss == null) throw new BindException("cannot bind " + bind + ":" + port);
        currentServer = ss;
        // Sessions run CONCURRENTLY (2026-10-31): a backup session can last
        // hours, and fs sessions must be answered while one is in flight.
        // Bounded pool of named daemon worker threads; when the pool and
        // its queue are saturated the just-accepted socket is closed at
        // once rather than left waiting unanswered in the backlog.
        AtomicInteger sessionSeq = new AtomicInteger();
        ThreadFactory sessionThreads = task -> {
            Thread t = new Thread(task, "nb-session-" + sessionSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ExecutorService pool = new ThreadPoolExecutor(
                4, 32, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(128), sessionThreads);
        try (ServerSocket server = ss) {
            log("listening on " + (addr == null ? "*" : addr.getHostAddress()) + ":" + port
                    + ", target dir " + targetRoot + ", data dir " + dataDir
                    + ", allowV1=" + allowV1 + ", allowRelay=" + allowRelay
                    + ", accountMode=" + accountMode);
            while (true) {
                Socket s;
                try {
                    s = server.accept();
                } catch (SocketException closed) {
                    log("server socket closed, serve loop ending");
                    return;
                }
                log("connection from " + s.getRemoteSocketAddress());
                try {
                    pool.execute(() -> {
                        try {
                            handle(s);
                        } catch (Exception e) {
                            log("session error: " + e);
                        } finally {
                            try { s.close(); } catch (IOException ignore) { }
                        }
                    });
                } catch (RejectedExecutionException full) {
                    log("session pool saturated, dropping connection from "
                            + s.getRemoteSocketAddress());
                    try { s.close(); } catch (IOException ignore) { }
                }
            }
        } finally {
            pool.shutdownNow();
            if (currentServer == ss) currentServer = null;
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

        DeviceRow dev;
        if (accountMode) {
            String acctErr = authenticateAccount(auth, token);
            if (acctErr != null) return acctErr;
            dev = auth.device;
        } else {
            dev = store.findDevice(auth.userId, auth.deviceId);
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
        }

        // No license-expiry gate: licenses are perpetual since 2026-10-31
        // (the product is open-source/free). Identity, approval and
        // revocation checks above still apply unchanged.

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
     * Account-mode identity resolution (M4c). The receiver serves exactly one
     * account. Order:
     *  1. A locally-known revoked device is rejected immediately.
     *  2. A locally-known approved device whose token hash matches validates
     *     from cache (offline grace — the periodic sync keeps the cache's
     *     approved/revoked/license state fresh).
     *  3. Anything else (unknown device, or approved-but-hash-not-yet-learned)
     *     is verified ONLINE via /api/license/validate; on success the token
     *     hash is learned into the cache so later offline sessions work.
     *     A token mismatch on a device whose hash IS known never falls back
     *     online — a wrong token stays wrong.
     * Returns null (auth.device filled) or the HELLO_ERR code.
     */
    String authenticateAccount(Auth auth, String token) throws Exception {
        if (!auth.userId.equals(acct.userId)) {
            log("auth fail (other account's user) user=" + auth.userId);
            return "AUTH";
        }
        DeviceRow dev = store.findDevice(auth.userId, auth.deviceId);
        if (dev != null && dev.revoked) {
            log("auth fail (device revoked, cached) device=" + auth.deviceId);
            return "AUTH";
        }
        boolean hashKnown = dev != null && dev.tokenHash != null && !dev.tokenHash.isEmpty();
        if (dev != null && dev.approved && hashKnown
                && MessageDigest.isEqual(sha256(token), hexToBytes(dev.tokenHash))) {
            String age = lastSyncOkMs == 0 ? "never (offline grace)"
                    : ((System.currentTimeMillis() - lastSyncOkMs) / 1000) + "s ago";
            log("auth ok from cache: device=" + auth.deviceId + " (last sync " + age + ")");
            auth.device = dev;
            return null;
        }
        if (dev != null && dev.approved && hashKnown) {
            log("auth fail (token mismatch, cached hash) device=" + auth.deviceId);
            return "AUTH";
        }
        // Online verification path (unknown device or hash not yet learned).
        String[] v;
        try {
            v = AccountLink.validate(acct.server, auth.deviceId, token);
        } catch (Exception e) {
            log("auth fail (device unknown locally and account service unreachable: "
                    + e.getMessage() + ") device=" + auth.deviceId);
            return "AUTH";
        }
        if (!"true".equals(v[0])) {
            log("auth fail (server validate: " + v[1] + ") device=" + auth.deviceId);
            return "AUTH";
        }
        // Learn the device + hash so future sessions validate offline.
        store.syncDevice(auth.userId, auth.deviceId,
                dev != null ? dev.name : auth.deviceId,
                dev != null ? dev.kind : "phone",
                true, false, Hash.hex(sha256(token)));
        if (v[2] != null) {
            store.touchLicenseExpiry(auth.userId, Long.parseLong(v[2]));
        }
        log("auth ok via online validate (hash learned): device=" + auth.deviceId);
        auth.device = store.findDevice(auth.userId, auth.deviceId);
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
        if (auth.mode.equals("fs")) {
            // fs sessions are read-only and unlimited — no backup slot.
            Wire.writeLine(out, "HELLO_ACK {\"ok\":true,\"batch\":1,\"streamhash\":1}");
            log("v2 session start: user=" + auth.userId + " device=" + auth.deviceId + " mode=" + auth.mode);
            handleFs(in, out, auth);
            return;
        }
        Path deviceDir = targetRoot.resolve("users").resolve(auth.userId)
                .resolve("devices").resolve(auth.deviceId);
        if (!tryBeginBackup(deviceDir)) {
            Wire.writeLine(out, "HELLO_ERR {\"code\":\"BUSY\"}");
            log("backup HELLO rejected (BUSY — a session for this device is already running):"
                    + " user=" + auth.userId + " device=" + auth.deviceId);
            return;
        }
        try {
            Wire.writeLine(out, "HELLO_ACK {\"ok\":true,\"batch\":1,\"streamhash\":1}");
            log("v2 session start: user=" + auth.userId + " device=" + auth.deviceId + " mode=" + auth.mode);
            Files.createDirectories(deviceDir);
            runBackupSession(in, out, deviceDir, auth);
        } finally {
            endBackup(deviceDir);
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
        Path deviceDir = targetRoot.resolve(device);
        if (!tryBeginBackup(deviceDir)) {
            Wire.writeLine(out, "ERR busy: another backup session for this device is in progress");
            log("legacy v1 HELLO rejected (BUSY): device=" + device);
            return;
        }
        try {
            Wire.writeLine(out, "OK 1");
            Files.createDirectories(deviceDir);
            log("legacy v1 session, device=" + device);
            runBackupSession(in, out, deviceDir, null);
        } finally {
            endBackup(deviceDir);
        }
    }

    // ------------------------------------------------------------------ backup session (v1 flow, v2-aware)

    /**
     * The manifest/decision/transfer flow, round-based since the batch
     * extension (protocol v2): a backup session is one or more manifest
     * rounds over one connection. Legacy clients send exactly one round
     * terminated by MANIFEST-END; batch-capable clients (the HELLO_ACK
     * advertised {@code "batch":1}) send rounds terminated by BATCH-END
     * and close the session with BYE, so the first files land while the
     * client is still hashing the rest of the library. Each round is
     * decided and transferred with the established logic; session totals
     * accumulate across rounds, ONE session row is written and the
     * RECEIPT carries a signed completion receipt over the whole session.
     * When {@code auth} is non-null (v2), decisions consult the files
     * table, verified files are recorded and thumbnails are generated.
     * Legacy v1 (auth == null) behaves exactly like the M1 receiver.
     *
     * <p>Batch rounds also carry the FILE-ERROR escape: a client that
     * cannot open a wanted file when its turn comes (deleted/unreadable
     * between scan and send) sends {@code FILE-ERROR {path,error}}
     * instead of the FILE header; the file counts failed for the session
     * and the batch continues. In legacy single-manifest rounds the same
     * line is a protocol violation, exactly like any other unexpected
     * header.
     *
     * <p>Stream-hash rounds (the HELLO_ACK advertised
     * {@code "streamhash":1}; entries carry {@code "sha256":""}): the
     * scan was metadata-only, so decisions key on size+mtime against the
     * files table, transfers are verified by the RECEIVER hashing the
     * assembled file (its hash goes into the DONE reply, the record and
     * the signed receipt), and the client cross-checks that hash against
     * its own stream-computed one. A mismatch is reported with
     * {@code HASH-MISMATCH {path}} in the slot where the next FILE header
     * would go — inside the round's transfer loop, or, for the round's
     * last file, as the first line before the next round / BYE (handled
     * below via the pending-mismatch window).
     */
    void runBackupSession(InputStream in, OutputStream out, Path deviceDir, Auth auth) throws Exception {
        List<String> ok = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<Entry> allEntries = new ArrayList<>(); // every round, for the signed receipt
        long sessionBytes = 0;
        boolean byeConsumed = false;
        int rounds = 0;
        // Stream-hash: the last stream entry whose DONE cross-check window
        // is still open (the client may answer HASH-MISMATCH as the first
        // line of the next round instead of inside the transfer loop).
        Entry pendingMismatch = null;
        // Per-file receipts: the same stream entry's signed receipt is held
        // back while its cross-check window is open (a HASH-MISMATCH would
        // delete the copy, and no receipt may vouch for a deleted file) and
        // flushed the moment the window closes without a matching verdict.
        Entry pendingReceipt = null;
        while (true) {
            // ---- read one manifest round ----
            List<Entry> round = new ArrayList<>();
            String terminator = null;
            String line;
            boolean firstLine = true;
            while ((line = Wire.readLine(in)) != null) {
                boolean first = firstLine;
                firstLine = false;
                if (first && pendingMismatch != null) {
                    boolean mismatchHit = false;
                    if (line.startsWith("HASH-MISMATCH ")) {
                        String fpath = MiniJson.str(line.substring("HASH-MISMATCH ".length()), "path");
                        if (fpath != null && fpath.equals(pendingMismatch.path)) {
                            applyHashMismatch(auth, pendingMismatch, ok, failed);
                            mismatchHit = true;
                        } else {
                            log("ignoring HASH-MISMATCH for unexpected path: " + line);
                        }
                    }
                    if (mismatchHit) {
                        pendingMismatch = null;
                        pendingReceipt = null; // rejected file: its receipt dies with the copy
                        continue;
                    }
                    // Any other first line closes the window cleanly: the
                    // stream file stands, so its per-file receipt goes out.
                    flushFileReceipt(out, auth, pendingReceipt);
                    pendingReceipt = null;
                    pendingMismatch = null;
                }
                if (line.equals("BATCH-END") || line.equals("MANIFEST-END") || line.equals("BYE")) {
                    terminator = line;
                    break;
                }
                Entry e = new Entry();
                e.path = MiniJson.str(line, "path");
                e.size = MiniJson.num(line, "size", -1);
                e.mtime = MiniJson.num(line, "mtime", 0);
                e.sha = MiniJson.str(line, "sha256");
                if (e.path != null && e.size >= 0 && e.sha != null) round.add(e);
            }
            if ("BYE".equals(terminator)) { byeConsumed = true; break; }
            rounds++;
            boolean batchRound = "BATCH-END".equals(terminator);
            RoundResult rr = runManifestRound(in, out, deviceDir, auth, round, batchRound,
                    ok, skipped, failed);
            pendingMismatch = rr.pendingMismatch;
            pendingReceipt = rr.pendingReceipt;
            sessionBytes += rr.bytes;
            allEntries.addAll(round);
            if (rr.byeReceived) { byeConsumed = true; break; }
            if (terminator == null || "MANIFEST-END".equals(terminator) || rr.senderGone) break;
            // BATCH-END: the sender either starts another round or sends BYE.
        }

        // The last stream file's cross-check window may still be open here
        // (session ended by BYE/EOF/vanish without a further line): the
        // client never reported a mismatch, so the file stands and its
        // per-file receipt precedes the session receipt.
        flushFileReceipt(out, auth, pendingReceipt);
        pendingReceipt = null;
        pendingMismatch = null;
        if (!byeConsumed) Wire.readLine(in); // BYE (best effort)
        String receipt = receiptJson(ok, skipped, failed);
        if (auth != null) {
            String signedDoc = buildSignedReceipt(auth, allEntries, ok);
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
        log("session done: ok=" + ok.size() + " skipped=" + skipped.size() + " failed=" + failed.size()
                + (rounds > 1 ? " rounds=" + rounds : ""));
    }

    /** Outcome of one manifest round: bytes received + whether the sender vanished mid-transfer. */
    static final class RoundResult {
        long bytes;
        boolean senderGone;
        /** The client sent BYE mid-round (a paused session): the round's
         *  un-sent wants are simply not transferred — neither ok nor
         *  failed — and the session closes through the normal epilogue. */
        boolean byeReceived;
        /** Last stream-hash entry whose DONE was sent in this round and whose
         *  HASH-MISMATCH window is still open at round end (the verdict may
         *  arrive as the first line of the next round / before BYE). */
        Entry pendingMismatch;
        /** The same entry's per-file receipt, held back while its
         *  cross-check window is open and flushed by the session loop (or
         *  dropped when the verdict is HASH-MISMATCH). */
        Entry pendingReceipt;
    }

    /**
     * Decide + transfer ONE manifest round (the pre-batch session body):
     * decision lines for the files to receive, READY, then the
     * stop-and-wait transfer. Session accumulators (ok/skipped/failed)
     * are appended to; returns the round's byte count. When
     * {@code batchRound} is set, a {@code FILE-ERROR} line in place of a
     * FILE header counts that file failed and the round continues.
     */
    RoundResult runManifestRound(InputStream in, OutputStream out, Path deviceDir, Auth auth,
                                 List<Entry> manifest, boolean batchRound,
                                 List<String> ok, List<String> skipped, List<String> failed) throws Exception {
        RoundResult rr = new RoundResult();
        // ---- decide ----
        List<Entry> toSend = new ArrayList<>();
        Map<String, Entry> byPath = new HashMap<>();
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
            // Stream-hash entry (batch rounds only): the client scanned
            // metadata-only and does not know the content hash yet.
            e.stream = batchRound && (e.sha == null || e.sha.isEmpty());

            // Receiver-side index: a matching files-table row + present copy = skip,
            // no re-hash of the stored file (the M3.5 receiver bottleneck fix).
            // Stream-hash entries key the row match on size+mtime instead of
            // the (unknown) content hash; the row's stored hash stands.
            if (auth != null) {
                FileRow row = store.findFile(auth.userId, auth.deviceId, e.path);
                if (row != null && "ok".equals(row.integrity)
                        && Files.isRegularFile(dest) && Files.size(dest) == e.size
                        && (e.stream
                            ? (row.size == e.size && row.mtime == e.mtime)
                            : (row.size == e.size && row.sha256.equals(e.sha)))) {
                    skipped.add(e.path);
                    // Per-file receipt for the skip: the record already
                    // vouches for this exact content, so the phone gets its
                    // deletion-gate evidence even if the session dies later.
                    sendFileReceipt(out, auth, e.path, e.size, row.sha256);
                    continue;
                }
            }
            if (!e.stream && Files.isRegularFile(dest) && Files.size(dest) == e.size
                    && Hash.sha256(dest).equals(e.sha)) {
                skipped.add(e.path);
                if (auth != null) {
                    recordVerified(auth, e, dest);
                    sendFileReceipt(out, auth, e.path, e.size, e.sha);
                }
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
                    if (e.stream) {
                        // Stream-hash: the .part IS the assembled file; the
                        // receiver's own hash of it becomes the record +
                        // receipt hash (no manifest hash exists to compare).
                        e.sha = Hash.sha256(e.part);
                        promote(e);
                        ok.add(e.path);
                        if (auth != null) {
                            recordVerified(auth, e, dest);
                            sendFileReceipt(out, auth, e.path, e.size, e.sha);
                        }
                        log("completed from leftover .part (stream-hash, receiver-hashed): "
                                + e.path);
                        continue;
                    }
                    if (Hash.sha256(e.part).equals(e.sha)) {
                        promote(e);
                        ok.add(e.path);
                        if (auth != null) {
                            recordVerified(auth, e, dest);
                            sendFileReceipt(out, auth, e.path, e.size, e.sha);
                        }
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
                + toSend.size() + " to receive" + (batchRound ? " (batch round)" : ""));

        // ---- transfer, stop-and-wait per file ----
        Entry lastStreamDone = null; // stream entry whose cross-check window is open
        Entry pendingReceipt = null; // that entry's per-file receipt, held for the window
        for (int i = 0; i < toSend.size(); i++) {
            String hdr = Wire.readLine(in);
            if (hdr == null) {
                for (int j = i; j < toSend.size(); j++) failed.add(toSend.get(j).path);
                log("sender stopped early");
                rr.senderGone = true;
                break;
            }
            // Stream-hash cross-check window: the line right after a
            // stream DONE may be the client's HASH-MISMATCH verdict for
            // THAT file instead of this file's header.
            if (batchRound && lastStreamDone != null && hdr.startsWith("HASH-MISMATCH ")) {
                String fjson = hdr.substring("HASH-MISMATCH ".length());
                String fpath = MiniJson.str(fjson, "path");
                if (fpath != null && fpath.equals(lastStreamDone.path)) {
                    applyHashMismatch(auth, lastStreamDone, ok, failed);
                    pendingReceipt = null; // rejected: no receipt for a deleted copy
                } else {
                    log("ignoring HASH-MISMATCH for unexpected path: " + hdr);
                    flushFileReceipt(out, auth, pendingReceipt); // window closed cleanly
                    pendingReceipt = null;
                }
                lastStreamDone = null;
                hdr = Wire.readLine(in);
                if (hdr == null) {
                    for (int j = i; j < toSend.size(); j++) failed.add(toSend.get(j).path);
                    log("sender stopped early");
                    rr.senderGone = true;
                    break;
                }
            } else if (lastStreamDone != null) {
                // Any other line closes the cross-check window: the stream
                // file stands, so its held per-file receipt goes out now.
                flushFileReceipt(out, auth, pendingReceipt);
                pendingReceipt = null;
                lastStreamDone = null;
            }
            if (hdr.equals("BYE")) {
                // Cooperative pause: the client ends the session with
                // wants still outstanding in this round. Those files are
                // not failures — they were never started — so leave them
                // uncounted and let the session epilogue run (deferred
                // stream receipt flush + whole-session receipt + the
                // session row). Without this, BYE fell into the
                // "sender stopped early" path below and the epilogue's
                // best-effort BYE read then deadlocked against the
                // client's receipt drain until the read timeout.
                rr.byeReceived = true;
                break;
            }
            if (batchRound && hdr.startsWith("FILE-ERROR ")) {
                // The sender cannot open this file (deleted/unreadable since
                // the scan). No bytes follow and no reply is expected — the
                // sender has already moved on to the next file.
                String fjson = hdr.substring("FILE-ERROR ".length());
                String fpath = MiniJson.str(fjson, "path");
                if (fpath == null || !fpath.equals(toSend.get(i).path)) {
                    for (int j = i; j < toSend.size(); j++) failed.add(toSend.get(j).path);
                    log("protocol violation in FILE-ERROR header: " + hdr);
                    rr.senderGone = true;
                    break;
                }
                failed.add(fpath);
                log("sender could not open " + fpath + " (" + MiniJson.str(fjson, "error")
                        + ") — counted failed, batch continues");
                continue;
            }
            if (!hdr.startsWith("FILE ")) {
                for (int j = i; j < toSend.size(); j++) failed.add(toSend.get(j).path);
                log("sender stopped early");
                rr.senderGone = true;
                break;
            }
            String fjson = hdr.substring(5);
            String fpath = MiniJson.str(fjson, "path");
            long bytes = MiniJson.num(fjson, "bytes", -1);
            Entry e = byPath.get(fpath);
            if (e == null || bytes < 0 || bytes != e.size - e.offset) {
                for (int j = i; j < toSend.size(); j++) failed.add(toSend.get(j).path);
                log("protocol violation in FILE header: " + hdr);
                rr.senderGone = true;
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
            rr.bytes += bytes;
            if (e.stream) {
                // Stream-hash: the receiver hashes the ASSEMBLED file;
                // that hash becomes the record + receipt value and goes
                // back in DONE so the client can cross-check it against
                // the hash it computed while streaming.
                String assembled = Hash.sha256(e.part);
                e.sha = assembled;
                promote(e);
                Files.deleteIfExists(e.meta);
                ok.add(e.path);
                if (auth != null) recordVerified(auth, e, e.dest);
                Wire.writeLine(out, "DONE {\"path\":\"" + MiniJson.esc(e.path)
                        + "\",\"sha256\":\"" + assembled + "\"}");
                log("received " + e.path + " (" + e.size + " bytes, resumed at " + e.offset
                        + ", stream-hash sha256=" + assembled + ")");
                lastStreamDone = e;
                // The per-file receipt is HELD until the cross-check window
                // closes: a HASH-MISMATCH verdict deletes this copy, and no
                // receipt may vouch for a deleted file.
                pendingReceipt = e;
            } else if (Hash.sha256(e.part).equals(e.sha)) {
                promote(e);
                Files.deleteIfExists(e.meta);
                ok.add(e.path);
                if (auth != null) recordVerified(auth, e, e.dest);
                Wire.writeLine(out, "DONE {\"path\":\"" + MiniJson.esc(e.path) + "\"}");
                // Receiver-side verification against the manifest hash is
                // final (no client cross-check in hashed mode): sign now.
                sendFileReceipt(out, auth, e.path, e.size, e.sha);
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
        rr.pendingMismatch = lastStreamDone;
        rr.pendingReceipt = pendingReceipt;
        return rr;
    }

    /**
     * Stream-hash only: the client's cross-check rejected the copy this
     * receiver just assembled (its stream hash differs from the DONE
     * hash). The stored copy cannot be trusted: delete it, mark the
     * files-table row integrity "bad", and move the file from ok to
     * failed for the session (so it also leaves the signed receipt).
     * The content-addressed thumbnail is left alone — other files with
     * identical content may legitimately reference it.
     */
    void applyHashMismatch(Auth auth, Entry e, List<String> ok, List<String> failed) {
        try { Files.deleteIfExists(e.dest); } catch (IOException ignore) { }
        try { Files.deleteIfExists(e.part); } catch (IOException ignore) { }
        try { Files.deleteIfExists(e.meta); } catch (IOException ignore) { }
        if (auth != null) {
            try {
                store.markFileBad(auth.userId, auth.deviceId, e.path);
            } catch (Exception ex) {
                log("mark-bad failed for " + e.path + ": " + ex);
            }
        }
        ok.remove(e.path);
        failed.add(e.path);
        log("HASH-MISMATCH from client for " + e.path
                + " — stored copy deleted, record marked bad, counted failed");
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
            store.upsertFile(auth.userId, auth.deviceId, e.path, e.size, e.mtime, e.sha,
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

    /**
     * Per-file receipts (2026-10-31, replacing session-only issuance as the
     * primary evidence): the same signed document shape as the session
     * receipt, narrowed to ONE file, emitted the moment that file's backup
     * is beyond dispute — a transfer whose hash verified (DONE), a skip
     * whose files-table record matched, or a leftover .part this receiver
     * just verified. A session that dies mid-way therefore still leaves
     * phone-side proof for every file it completed, which is what the
     * §6.4 free-up-space gate consumes; the BYE session receipt is still
     * sent as well (whole-session summary, older clients' semantics).
     * Stream-hash transfers are the one exception: their receipt is held
     * until the client's HASH-MISMATCH cross-check window closes, because
     * a mismatch deletes the stored copy.
     */
    String buildFileReceiptDoc(Auth auth, String path, long size, String sha256) {
        return "{\"user_id\":\"" + MiniJson.esc(auth.userId)
                + "\",\"device_id\":\"" + MiniJson.esc(auth.deviceId)
                + "\",\"ts\":" + System.currentTimeMillis()
                + ",\"files\":[{\"path\":\"" + MiniJson.esc(path)
                + "\",\"size\":" + size
                + ",\"sha256\":\"" + sha256 + "\"}]}";
    }

    /** Emit one per-file signed RECEIPT line. No-op for v1 (auth == null). */
    void sendFileReceipt(OutputStream out, Auth auth, String path, long size, String sha256) {
        if (auth == null || path == null || sha256 == null) return;
        try {
            String doc = buildFileReceiptDoc(auth, path, size, sha256);
            Wire.writeLine(out, "RECEIPT {\"signed\":{\"receipt\":\"" + MiniJson.esc(doc)
                    + "\",\"signature\":\"" + sign(doc)
                    + "\",\"pubkey\":\"" + pubkeyB64() + "\"}}");
        } catch (Exception ex) {
            // Evidence, not payload: never let a receipt failure break the
            // transfer; the session receipt can still cover this file.
            log("per-file receipt send failed for " + path + ": " + ex);
        }
    }

    /** Flush a held per-file receipt (null-safe; used when a window closes). */
    void flushFileReceipt(OutputStream out, Auth auth, Entry e) {
        if (e != null) sendFileReceipt(out, auth, e.path, e.size, e.sha);
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
        // Generate into a unique temp file, then rename into place: with
        // concurrent sessions, two devices can ingest identical content
        // (same sha → same thumb path) at the same time. Temp + rename
        // means writers never interleave into one file, and fs THUMB
        // readers only ever see a complete thumbnail.
        Path tmp = Files.createTempFile(thumbsDir, "thumb-", ".jpg");
        try {
            boolean ok = false;
            if (ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png")
                    || ext.equals("gif") || ext.equals("bmp")) {
                ok = thumbViaImageIO(stored, tmp);
            } else if (ext.equals("heic") || ext.equals("heif")) {
                if (convertPresent) {
                    ok = runTool(Arrays.asList("convert", stored + "[0]",
                            "-auto-orient", "-resize", THUMB_EDGE + "x" + THUMB_EDGE + ">",
                            tmp.toString()), 60);
                }
            } else if (ext.equals("mp4") || ext.equals("mov") || ext.equals("mkv")
                    || ext.equals("avi") || ext.equals("webm") || ext.equals("3gp") || ext.equals("m4v")) {
                if (ffmpegPresent) {
                    ok = runTool(Arrays.asList("ffmpeg", "-y", "-ss", "1", "-i", stored.toString(),
                            "-frames:v", "1", "-vf", "scale=" + THUMB_EDGE + ":-2",
                            tmp.toString()), 60);
                }
            }
            if (ok && Files.isRegularFile(tmp) && Files.size(tmp) > 0) {
                try {
                    Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
                }
                return out.getFileName().toString();
            }
            return null;
        } finally {
            Files.deleteIfExists(tmp);
        }
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

    // In-memory log ring for the control API's GET /logs (the Tauri shell).
    static final int LOG_RING_MAX = 400;
    static final ArrayDeque<String> LOG_RING = new ArrayDeque<>();

    static void log(String m) {
        String line = "[" + LocalTime.now().withNano(0) + "] " + m;
        System.out.println(line);
        synchronized (LOG_RING) {
            LOG_RING.addLast(line);
            while (LOG_RING.size() > LOG_RING_MAX) LOG_RING.removeFirst();
        }
    }

    static List<String> logTail(int n) {
        synchronized (LOG_RING) {
            List<String> all = new ArrayList<>(LOG_RING);
            return new ArrayList<>(all.subList(Math.max(0, all.size() - n), all.size()));
        }
    }

    static final class Entry {
        String path; long size; long mtime; String sha;
        long offset; Path dest; Path part; Path meta;
        /** Stream-hash entry: batch round + empty manifest sha (set at decide time). */
        boolean stream;
    }

    static final class DeviceRow {
        String id, userId, name, kind, tokenHash, pcPubkey;
        boolean approved, revoked;
    }

    static final class FileRow {
        long id; String userId, deviceId, relPath, sha256, integrity, thumb;
        long size, backedAt;
        /** Source mtime recorded at ingest (stream-hash skip decisions key on size+mtime). */
        long mtime;
    }

    // ------------------------------------------------------------------ SQLite store

    /**
     * The record store — and the process-wide database thread-safety
     * scheme: ONE Store instance holds ONE JDBC Connection, and EVERY
     * method is synchronized on the Store monitor, so all database access
     * is serialized no matter which thread calls (session worker threads,
     * the account-sync thread, control-API threads, one-shot commands in
     * this process). A bare JDBC Connection is NOT thread-safe; the
     * monitor is what makes sharing this one safe. WAL + busy_timeout
     * additionally cover short-lived EXTERNAL processes (--seed, --verify,
     * --devices/--approve CLI runs) opening the same file while the
     * server is up. Rules for future code: never touch {@code db} outside
     * a synchronized Store method; never open a second in-process
     * connection; keep statements short (the monitor is held for the
     * duration of each call — no network or disk I/O inside Store).
     */
    static final class Store {
        private final Connection db;

        Store(Path file) throws Exception {
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement st = db.createStatement()) {
                st.execute("PRAGMA busy_timeout = 5000");
                try {
                    st.execute("PRAGMA journal_mode = WAL");
                } catch (SQLException ignore) {
                    // Keep the default journal mode if WAL is unavailable
                    // (e.g. exotic filesystem); correctness does not depend
                    // on it — the Store monitor is the in-process scheme.
                }
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
                        + "mtime INTEGER NOT NULL DEFAULT 0, "
                        + "UNIQUE(user_id, device_id, rel_path))");
                // Migration for pre-stream-hash databases: files.mtime did
                // not exist before. Rows written earlier keep mtime = 0,
                // which simply never matches a real source mtime — a
                // stream-hash session re-receives such a file once and the
                // row is then complete. Safe, no backfill attempted.
                boolean hasMtime = false;
                try (ResultSet cols = db.getMetaData().getColumns(null, null, "files", "mtime")) {
                    hasMtime = cols.next();
                }
                if (!hasMtime) {
                    st.execute("ALTER TABLE files ADD COLUMN mtime INTEGER NOT NULL DEFAULT 0");
                }
                st.execute("CREATE TABLE IF NOT EXISTS sessions ("
                        + "ts INTEGER NOT NULL, device_id TEXT NOT NULL, files_ok INTEGER NOT NULL, "
                        + "bytes INTEGER NOT NULL, result TEXT NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS meta ("
                        + "k TEXT PRIMARY KEY, v TEXT)");
            }
        }

        // ---- M4c account-mode sync support ----

        synchronized String getMeta(String k) throws SQLException {
            try (PreparedStatement q = db.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
                q.setString(1, k);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        }

        synchronized void setMeta(String k, String v) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "INSERT INTO meta(k, v) VALUES(?, ?) "
                            + "ON CONFLICT(k) DO UPDATE SET v = excluded.v")) {
                q.setString(1, k);
                q.setString(2, v);
                q.executeUpdate();
            }
        }

        /**
         * Upsert a device row from the account service's view. A null
         * tokenHash keeps the locally stored hash (the service API never
         * returns stored hashes; hashes are learned at validate time).
         */
        synchronized void syncDevice(String userId, String deviceId, String name, String kind,
                                     boolean approved, boolean revoked,
                                     String tokenHashOrNull) throws SQLException {
            // token_hash is NOT NULL in the local schema: an unknown hash is
            // stored as "" (authenticate treats "" as "not learned yet"), and
            // an upsert with an unknown hash never clobbers a learned one.
            try (PreparedStatement q = db.prepareStatement(
                    "INSERT INTO devices(id, user_id, name, kind, token_hash, approved, revoked) "
                            + "VALUES(?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(id) DO UPDATE SET user_id = excluded.user_id, "
                            + "name = excluded.name, kind = excluded.kind, "
                            + "approved = excluded.approved, revoked = excluded.revoked, "
                            + "token_hash = CASE WHEN excluded.token_hash = '' "
                            + "THEN devices.token_hash ELSE excluded.token_hash END")) {
                q.setString(1, deviceId);
                q.setString(2, userId);
                q.setString(3, name);
                q.setString(4, kind);
                q.setString(5, tokenHashOrNull == null ? "" : tokenHashOrNull);
                q.setInt(6, approved ? 1 : 0);
                q.setInt(7, revoked ? 1 : 0);
                q.executeUpdate();
            }
        }

        synchronized void upsertLicense(String userId, long expiresAt, int maxPhones,
                                        int maxPcs) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "INSERT OR REPLACE INTO licenses(user_id, expires_at, max_phones, max_pcs) "
                            + "VALUES(?, ?, ?, ?)")) {
                q.setString(1, userId);
                q.setLong(2, expiresAt);
                q.setInt(3, maxPhones);
                q.setInt(4, maxPcs);
                q.executeUpdate();
            }
        }

        /** Update only the expiry (insert with defaults when absent). */
        synchronized void touchLicenseExpiry(String userId, long expiresAt) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "INSERT INTO licenses(user_id, expires_at) VALUES(?, ?) "
                            + "ON CONFLICT(user_id) DO UPDATE SET expires_at = excluded.expires_at")) {
                q.setString(1, userId);
                q.setLong(2, expiresAt);
                q.executeUpdate();
            }
        }

        synchronized List<DeviceRow> listDevices(String userId) throws SQLException {
            List<DeviceRow> out = new ArrayList<>();
            try (PreparedStatement q = db.prepareStatement(
                    "SELECT * FROM devices WHERE user_id = ? ORDER BY id")) {
                q.setString(1, userId);
                try (ResultSet rs = q.executeQuery()) {
                    while (rs.next()) {
                        DeviceRow d = new DeviceRow();
                        d.id = rs.getString("id");
                        d.userId = rs.getString("user_id");
                        d.name = rs.getString("name");
                        d.kind = rs.getString("kind");
                        d.tokenHash = rs.getString("token_hash");
                        d.pcPubkey = rs.getString("pc_pubkey");
                        d.approved = rs.getInt("approved") != 0;
                        d.revoked = rs.getInt("revoked") != 0;
                        out.add(d);
                    }
                }
            }
            return out;
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
                                     long mtime, String sha256, long backedAt, String integrity,
                                     String thumb) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "INSERT INTO files(user_id, device_id, rel_path, size, mtime, sha256, backed_at, integrity, thumb) "
                            + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT(user_id, device_id, rel_path) DO UPDATE SET "
                            + "size = excluded.size, mtime = excluded.mtime, sha256 = excluded.sha256, "
                            + "backed_at = excluded.backed_at, integrity = excluded.integrity, "
                            + "thumb = excluded.thumb")) {
                q.setString(1, userId);
                q.setString(2, deviceId);
                q.setString(3, relPath);
                q.setLong(4, size);
                q.setLong(5, mtime);
                q.setString(6, sha256);
                q.setLong(7, backedAt);
                q.setString(8, integrity);
                q.setString(9, thumb);
                q.executeUpdate();
            }
        }

        /** Stream-hash cross-check failure: keep the row (and its receiver
         *  hash) but flag the stored copy as untrusted. */
        synchronized void markFileBad(String userId, String deviceId, String relPath)
                throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "UPDATE files SET integrity = 'bad' WHERE user_id = ? AND device_id = ? AND rel_path = ?")) {
                q.setString(1, userId);
                q.setString(2, deviceId);
                q.setString(3, relPath);
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

        /** Recent sessions, newest first (PC UI session log view). */
        synchronized List<String[]> listSessions(int limit) throws SQLException {
            List<String[]> out = new ArrayList<>();
            try (PreparedStatement q = db.prepareStatement(
                    "SELECT ts, device_id, files_ok, bytes, result FROM sessions"
                            + " ORDER BY rowid DESC LIMIT ?")) {
                q.setInt(1, limit);
                try (ResultSet rs = q.executeQuery()) {
                    while (rs.next()) {
                        out.add(new String[]{
                                String.valueOf(rs.getLong(1)), rs.getString(2),
                                String.valueOf(rs.getInt(3)),
                                String.valueOf(rs.getLong(4)), rs.getString(5)});
                    }
                }
            }
            return out;
        }

        /** One page of a user's files, newest backup first (control API GET /records). */
        synchronized List<FileRow> listFilesPage(String userId, int limit, int offset)
                throws SQLException {
            List<FileRow> out = new ArrayList<>();
            try (PreparedStatement q = db.prepareStatement(
                    "SELECT * FROM files WHERE user_id = ? "
                            + "ORDER BY backed_at DESC, id DESC LIMIT ? OFFSET ?")) {
                q.setString(1, userId);
                q.setInt(2, limit);
                q.setInt(3, offset);
                try (ResultSet rs = q.executeQuery()) {
                    while (rs.next()) out.add(toFileRow(rs));
                }
            }
            return out;
        }

        synchronized int countFiles(String userId) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "SELECT COUNT(*) FROM files WHERE user_id = ?")) {
                q.setString(1, userId);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }

        synchronized FileRow findFileById(long id) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "SELECT * FROM files WHERE id = ?")) {
                q.setLong(1, id);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next() ? toFileRow(rs) : null;
                }
            }
        }

        /** Total stored bytes for one user (PC UI quota display). */
        synchronized long filesBytesTotal(String userId) throws SQLException {
            try (PreparedStatement q = db.prepareStatement(
                    "SELECT COALESCE(SUM(size), 0) FROM files WHERE user_id = ?")) {
                q.setString(1, userId);
                try (ResultSet rs = q.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0;
                }
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
            f.mtime = rs.getLong("mtime");
            return f;
        }
    }
}

/**
 * AccountLink — the receiver's client for the M4b account service (M4c).
 *
 * Bootstrap is the only password moment: {@link #loginFlow} logs in with the
 * account credentials, registers (or reuses) this PC as a pc device — sending
 * the receipt-signing public key — and persists everything later steps need
 * into &lt;dataDir&gt;/account.json (mode 600): user id, PC device id + token,
 * refresh token, and the mesh provisioning (per-user network name/secret +
 * public node). Registration is approval (login-is-network, 2026-10-31);
 * the historical self-approve call in loginFlow is a legacy no-op.
 * (QR login — the phone approving a ticket that makes the service register
 * this PC — lands on the same device row and account.json shape.)
 *
 * After bootstrap everything runs on the PC device token: device list,
 * license, provisioning, and the (now no-op) approve call.
 * {@link #syncOnce} merges the service view into the receiver's local SQLite:
 * approved/revoked flags and the license land in the same tables the M4a
 * standalone mode uses, so HELLO validation is identical afterwards. Token
 * hashes are NOT synced (the API never exposes stored hashes); a device's
 * hash is learned by BackupReceiver.authenticateAccount after a successful
 * online /api/license/validate.
 */
final class AccountLink {

    static final String DEFAULT_PC_IP = "10.99.0.2";

    static final class State {
        String server, account, userId, pcDeviceId, pcDeviceToken, refreshToken;
        String networkName, networkSecret, publicNode, pcIp;
        transient Path dataDir;

        Path file() { return dataDir.resolve("account.json"); }

        void save() throws IOException {
            String json = "{"
                    + "\"server\":\"" + MiniJson.esc(server)
                    + "\",\"account\":\"" + MiniJson.esc(account)
                    + "\",\"user_id\":\"" + MiniJson.esc(userId)
                    + "\",\"pc_device_id\":\"" + MiniJson.esc(pcDeviceId)
                    + "\",\"pc_device_token\":\"" + MiniJson.esc(pcDeviceToken)
                    + "\",\"refresh_token\":\"" + MiniJson.esc(refreshToken == null ? "" : refreshToken)
                    + "\",\"network_name\":\"" + MiniJson.esc(networkName)
                    + "\",\"network_secret\":\"" + MiniJson.esc(networkSecret)
                    + "\",\"public_node\":\"" + MiniJson.esc(publicNode)
                    + "\",\"pc_ip\":\"" + MiniJson.esc(pcIp)
                    + "\"}";
            Files.createDirectories(dataDir);
            Files.writeString(file(), json, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignore) { /* non-POSIX fs */ }
        }
    }

    static State load(Path dataDir) {
        Path f = dataDir.resolve("account.json");
        if (!Files.isRegularFile(f)) return null;
        try {
            String json = Files.readString(f, StandardCharsets.UTF_8);
            State st = new State();
            st.dataDir = dataDir;
            st.server = MiniJson.str(json, "server");
            st.account = MiniJson.str(json, "account");
            st.userId = MiniJson.str(json, "user_id");
            st.pcDeviceId = MiniJson.str(json, "pc_device_id");
            st.pcDeviceToken = MiniJson.str(json, "pc_device_token");
            st.refreshToken = MiniJson.str(json, "refresh_token");
            st.networkName = MiniJson.str(json, "network_name");
            st.networkSecret = MiniJson.str(json, "network_secret");
            st.publicNode = MiniJson.str(json, "public_node");
            st.pcIp = MiniJson.str(json, "pc_ip");
            if (st.server == null || st.userId == null || st.pcDeviceToken == null) return null;
            if (st.pcIp == null) st.pcIp = DEFAULT_PC_IP;
            st.server = normalize(st.server);
            return st;
        } catch (Exception e) {
            return null;
        }
    }

    static String normalize(String server) {
        String s = server.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * This PC's persistent device identity (design §3.2): a random UUID
     * minted once into &lt;dataDir&gt;/device-identity.json (mode 600) and
     * NEVER deleted afterwards — in particular logoutSession removes only
     * account.json, so identity and session stay separate. Every device
     * registration (password login, QR ticket) presents this uuid and the
     * service upserts onto the same device row: re-login keeps the PC's
     * device number instead of minting a new one and revoking the old.
     * The identity dies only with the data dir itself (reinstall / wipe).
     */
    static String deviceUuid(Path dataDir) throws IOException {
        Path f = dataDir.resolve("device-identity.json");
        if (Files.isRegularFile(f)) {
            try {
                String uuid = MiniJson.str(Files.readString(f, StandardCharsets.UTF_8),
                        "device_uuid");
                if (uuid != null && !uuid.isBlank()) return uuid.trim();
            } catch (Exception ignore) { /* regenerate below */ }
        }
        String uuid = UUID.randomUUID().toString();
        Files.createDirectories(dataDir);
        Files.writeString(f, "{\"device_uuid\":\"" + uuid
                + "\",\"created_at_ms\":" + System.currentTimeMillis() + "}",
                StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignore) { /* non-POSIX fs */ }
        return uuid;
    }

    // ------------------------------------------------------------- HTTP core

    static final class ApiResult {
        final int status; final String body;
        ApiResult(int status, String body) { this.status = status; this.body = body; }
        boolean ok() { return status >= 200 && status < 300; }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    static ApiResult http(String method, String url, String bearer, String json)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        if ("GET".equals(method)) {
            b.GET();
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(json == null ? "" : json));
        }
        HttpResponse<String> resp = HTTP.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new ApiResult(resp.statusCode(), resp.body());
    }

    static ApiResult http(State st, String method, String path, String bearer, String json)
            throws IOException, InterruptedException {
        return http(method, st.server + path, bearer, json);
    }

    static String requireOk(ApiResult r, String what) throws IOException {
        if (!r.ok()) throw new IOException(what + " failed: HTTP " + r.status + " " + r.body);
        return r.body;
    }

    // ------------------------------------------------------------- API calls

    /** POST /api/auth/login → {access, refresh}. */
    static String[] login(String server, String account, String password) throws Exception {
        ApiResult r = http("POST", normalize(server) + "/api/auth/login", null,
                "{\"account\":\"" + MiniJson.esc(account)
                        + "\",\"password\":\"" + MiniJson.esc(password) + "\"}");
        requireOk(r, "login");
        return new String[]{MiniJson.str(r.body, "access_token"),
                MiniJson.str(r.body, "refresh_token")};
    }

    /** Outcome of a session-restore attempt against the stored refresh token. */
    enum RefreshResult { OK, REJECTED, UNREACHABLE }

    /**
     * Session persistence (2026-10-31): POST /api/auth/refresh (rotating)
     * with the state's refresh token — the pair the server issued at login
     * (access 2 h, refresh 30 days, each refresh mints a fresh 30-day
     * refresh token, so a client that reaches the service at least once a
     * month stays signed in indefinitely; no password is ever stored).
     * OK: st.refreshToken replaced by the rotated one and account.json
     * re-saved. REJECTED: the server answered 401/403 — the session is dead
     * (refresh revoked/expired or the user disabled); the caller drops the
     * saved state and asks for the password again. UNREACHABLE: transport
     * failure or no refresh token stored — the session may still be fine,
     * keep the saved state untouched.
     */
    static RefreshResult refreshSession(State st) {
        if (st == null || st.refreshToken == null || st.refreshToken.isEmpty()) {
            return RefreshResult.UNREACHABLE;
        }
        ApiResult r;
        try {
            r = http("POST", st.server + "/api/auth/refresh", null,
                    "{\"refresh_token\":\"" + MiniJson.esc(st.refreshToken) + "\"}");
        } catch (Exception e) {
            return RefreshResult.UNREACHABLE;
        }
        if (r.status == 401 || r.status == 403) return RefreshResult.REJECTED;
        if (!r.ok()) return RefreshResult.UNREACHABLE;
        String next = MiniJson.str(r.body, "refresh_token");
        if (next == null || next.isEmpty()) return RefreshResult.UNREACHABLE;
        st.refreshToken = next;
        try {
            st.save();
        } catch (IOException ignore) { /* next launch retries with the old token */ }
        return RefreshResult.OK;
    }

    /**
     * Sign out: best-effort server-side revocation, then wipe the local
     * state file. /api/auth/logout revokes whatever bearer it is given;
     * presenting the refresh token kills the stored session (the access
     * half is short-lived and the PC never keeps one). After this returns,
     * this PC holds no account credential material until the next login.
     * The device identity (device-identity.json) is NOT touched: it is
     * not a credential, and keeping it is what lets the next login return
     * to the same server device row.
     */
    static void logoutSession(State st) {
        if (st == null) return;
        if (st.refreshToken != null && !st.refreshToken.isEmpty()) {
            try {
                http("POST", st.server + "/api/auth/logout", st.refreshToken, "");
            } catch (Exception ignore) { /* best effort */ }
        }
        try {
            Files.deleteIfExists(st.file());
        } catch (Exception ignore) { }
    }

    /** POST /api/license/validate (no bearer) → {valid, reason, expiresEpochMsOrNull}. */
    static String[] validate(String server, String deviceId, String token) throws Exception {
        long idNum;
        try {
            idNum = Long.parseLong(deviceId.trim());
        } catch (NumberFormatException e) {
            throw new IOException("device_id is not numeric: " + deviceId);
        }
        ApiResult r = http("POST", normalize(server) + "/api/license/validate", null,
                "{\"device_id\":" + idNum + ",\"device_token\":\"" + MiniJson.esc(token) + "\"}");
        requireOk(r, "validate");
        boolean valid = r.body.contains("\"valid\":true");
        String reason = MiniJson.str(r.body, "reason");
        String exp = MiniJson.str(r.body, "expires_at");
        String expMs = null;
        if (exp != null) {
            try { expMs = Long.toString(Instant.parse(exp).toEpochMilli()); }
            catch (Exception ignore) { }
        }
        return new String[]{valid ? "true" : "false", reason == null ? "" : reason, expMs};
    }

    /** GET /api/license → {expiresMs, maxPhones, maxPcs, quotaBytes, userId}. */
    static long[] license(State st, String bearer) throws Exception {
        ApiResult r = http(st, "GET", "/api/license", bearer, null);
        requireOk(r, "license");
        String exp = MiniJson.str(r.body, "expires_at");
        long expMs = exp == null ? -1 : Instant.parse(exp).toEpochMilli();
        return new long[]{expMs,
                MiniJson.num(r.body, "max_phones", 2),
                MiniJson.num(r.body, "max_pcs", 1),
                MiniJson.num(r.body, "quota_bytes", 0),
                MiniJson.num(r.body, "user_id", -1)};
    }

    /** GET /api/provisioning → {publicNode, networkName, networkSecret}. */
    static String[] provisioning(State st, String bearer) throws Exception {
        ApiResult r = http(st, "GET", "/api/provisioning", bearer, null);
        requireOk(r, "provisioning");
        return new String[]{MiniJson.str(r.body, "public_node"),
                MiniJson.str(r.body, "network_name"),
                MiniJson.str(r.body, "network_secret")};
    }

    /** GET /api/devices → rows {id, kind, name, approved, revoked}. */
    static List<String[]> listDevices(State st, String bearer) throws Exception {
        ApiResult r = http(st, "GET", "/api/devices", bearer, null);
        requireOk(r, "device list");
        List<String[]> out = new ArrayList<>();
        for (String obj : arrayObjects(r.body, "devices")) {
            out.add(new String[]{
                    Long.toString(MiniJson.num(obj, "id", -1)),
                    MiniJson.str(obj, "kind"),
                    MiniJson.str(obj, "name"),
                    Boolean.toString(obj.contains("\"approved\":true")),
                    Boolean.toString(obj.contains("\"revoked\":true"))});
        }
        return out;
    }

    /** Split a top-level JSON array field into its object literals. */
    static List<String> arrayObjects(String json, String key) {
        List<String> out = new ArrayList<>();
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) return out;
        i = json.indexOf('[', i);
        if (i < 0) return out;
        int depth = 0, start = -1;
        boolean inStr = false, esc = false;
        for (int j = i; j < json.length(); j++) {
            char c = json.charAt(j);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') { if (depth == 0) start = j; depth++; }
            if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(json.substring(start, j + 1));
                    start = -1;
                }
            }
            if (c == ']' && depth == 0) break;
        }
        return out;
    }

    /** POST /api/devices/{id}/approve with the PC device token → response body. */
    static String approveWithDevice(State st, String deviceId) throws Exception {
        ApiResult r = http(st, "POST", "/api/devices/" + deviceId + "/approve",
                st.pcDeviceToken, "");
        if (!r.ok()) {
            throw new IOException("approve failed: HTTP " + r.status + " " + r.body);
        }
        return r.body;
    }

    /** The stored session is dead (refresh rejected): password required again. */
    static final class SessionDead extends Exception {
        SessionDead(String m) { super(m); }
    }

    // ------------------------------------------------------- QR pc-login

    /**
     * POST /api/pc-login/tickets {device_name, device_uuid} → {ticket_id,
     * secret, expires_at} (design v1.4 扫码登录）. The ticket is a one-time
     * login offer: the desktop renders {t, s, srv} as a QR code and the
     * account holder approves it from the phone. The device_uuid rides in
     * the ticket (never in the QR payload) so the approve upserts this
     * PC's existing device row. expires_at comes back raw — the control
     * API normalizes it for the frontend countdown.
     */
    static String[] createPcLoginTicket(String server, String deviceName,
                                        String deviceUuid) throws Exception {
        ApiResult r = http("POST", normalize(server) + "/api/pc-login/tickets", null,
                "{\"device_name\":\"" + MiniJson.esc(deviceName)
                        + "\",\"device_uuid\":\"" + MiniJson.esc(deviceUuid) + "\"}");
        requireOk(r, "pc-login ticket create");
        return new String[]{MiniJson.str(r.body, "ticket_id"),
                MiniJson.str(r.body, "secret"), MiniJson.str(r.body, "expires_at")};
    }

    /** GET /api/pc-login/tickets/{id}?secret= — raw passthrough (the
     *  caller interprets status / HTTP 404|410 = expired). */
    static ApiResult pcLoginTicketStatus(String server, String ticketId, String secret)
            throws IOException, InterruptedException {
        String url = normalize(server) + "/api/pc-login/tickets/"
                + URLEncoder.encode(ticketId, StandardCharsets.UTF_8)
                + "?secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        return http("GET", url, null, null);
    }

    /** A JSON field that may be encoded as a string or a bare number. */
    static String strOrNum(String json, String key) {
        String s = MiniJson.str(json, key);
        if (s != null) return s;
        long n = MiniJson.num(json, key, Long.MIN_VALUE);
        return n == Long.MIN_VALUE ? null : Long.toString(n);
    }

    /**
     * QR-login bootstrap: the phone approved a pc-login ticket and the
     * service registered THIS PC, handing back one-time credentials
     * {account, user_id, pc_device_id, device_token, refresh_token}.
     * Lands on exactly the account.json shape [loginFlow] produces, so
     * session persistence, sync and the mesh sidecar behave identically.
     *
     * The credentials are ONE-TIME (the ticket dies at approval), so the
     * state is saved BEFORE anything that can fail; provisioning + sync
     * are then best-effort — the periodic sync repairs them if the
     * service hiccups, because syncOnce itself re-fetches provisioning.
     */
    static State qrLoginFlow(Path dataDir, BackupReceiver.Store store, String server,
                             String credsJson, String pcIpOverride) throws Exception {
        State st = new State();
        st.dataDir = dataDir;
        st.server = normalize(server);
        st.account = MiniJson.str(credsJson, "account");
        st.userId = strOrNum(credsJson, "user_id");
        st.pcDeviceId = strOrNum(credsJson, "pc_device_id");
        st.pcDeviceToken = MiniJson.str(credsJson, "device_token");
        st.refreshToken = MiniJson.str(credsJson, "refresh_token");
        if (st.pcDeviceToken == null || st.userId == null || st.pcDeviceId == null) {
            throw new IOException("pc-login credentials incomplete");
        }
        st.pcIp = pcIpOverride != null ? pcIpOverride : DEFAULT_PC_IP;
        // Provisioning fills these in AFTER this first save (the one-time
        // credentials must land first); keep them non-null so save() never
        // serializes a null, whatever MiniJson variant is on the classpath.
        if (st.networkName == null) st.networkName = "";
        if (st.networkSecret == null) st.networkSecret = "";
        if (st.publicNode == null) st.publicNode = "";
        st.save();
        try {
            String[] prov = provisioning(st, st.pcDeviceToken);
            st.publicNode = prov[0];
            st.networkName = prov[1];
            st.networkSecret = prov[2];
            st.save();
        } catch (Exception e) {
            BackupReceiver.log("qr login: provisioning deferred (" + e.getMessage() + ")");
        }
        try {
            AccountLink.syncOnce(st, store);
        } catch (Exception e) {
            BackupReceiver.log("qr login: initial sync deferred (" + e.getMessage() + ")");
        }
        return st;
    }

    /**
     * Mint a short-lived account access token from the stored refresh token
     * (rotating it + re-saving, exactly like refreshSession). Some service
     * calls — device revoke — accept only an account session, not the PC
     * device token that approve/sync/provisioning run on.
     */
    static String accessViaRefresh(State st) throws Exception {
        if (st.refreshToken == null || st.refreshToken.isEmpty()) {
            throw new SessionDead("no refresh token stored");
        }
        ApiResult r = http("POST", st.server + "/api/auth/refresh", null,
                "{\"refresh_token\":\"" + MiniJson.esc(st.refreshToken) + "\"}");
        if (r.status == 401 || r.status == 403) {
            throw new SessionDead("refresh rejected: HTTP " + r.status);
        }
        requireOk(r, "refresh");
        String next = MiniJson.str(r.body, "refresh_token");
        if (next != null && !next.isEmpty()) {
            st.refreshToken = next;
            try { st.save(); } catch (IOException ignore) { }
        }
        String access = MiniJson.str(r.body, "access_token");
        if (access == null) throw new IOException("refresh returned no access_token");
        return access;
    }

    /**
     * POST /api/devices/{id}/revoke with an account session (see
     * accessViaRefresh — the device token gets a 401 here) → response body.
     */
    static String revokeWithSession(State st, String deviceId) throws Exception {
        String session = accessViaRefresh(st);
        ApiResult r = http(st, "POST", "/api/devices/" + deviceId + "/revoke",
                session, "");
        if (!r.ok()) {
            throw new IOException("revoke failed: HTTP " + r.status + " " + r.body);
        }
        return r.body;
    }

    // ------------------------------------------------------------- flows

    /**
     * Full bootstrap: password login → (reuse or register the PC device —
     * the service approves at registration since login-is-network; the
     * approve calls below are legacy no-ops kept for older services) →
     * provisioning → save account.json → one sync. Returns the state.
     */
    static State loginFlow(Path dataDir, BackupReceiver.Store store, String server,
                           String account, String password, String pubkeyB64,
                           String pcIpOverride) throws Exception {
        server = normalize(server);
        String[] pair = login(server, account, password);
        String session = pair[0];

        State st = new State();
        st.dataDir = dataDir;
        st.server = server;
        st.account = account;
        st.refreshToken = pair[1];

        long[] lic = license(st, session);
        st.userId = Long.toString(lic[4]);

        // Reuse a previous PC registration for this same server+account when
        // it is still live — the license caps PCs at 1, so re-registering
        // blindly would hit device_limit.
        State old = load(dataDir);
        boolean reused = false;
        if (old != null && old.server.equals(server) && old.account.equals(account)
                && old.pcDeviceId != null && old.pcDeviceToken != null) {
            try {
                for (String[] d : listDevices(old, old.pcDeviceToken)) {
                    if (d[0].equals(old.pcDeviceId) && !"true".equals(d[4])) {
                        st.pcDeviceId = old.pcDeviceId;
                        st.pcDeviceToken = old.pcDeviceToken;
                        st.pcIp = old.pcIp;
                        reused = true;
                        if (!"true".equals(d[3])) {
                            http(st, "POST", "/api/devices/" + st.pcDeviceId + "/approve",
                                    session, "");
                        }
                        break;
                    }
                }
            } catch (Exception ignore) { /* fall through to fresh registration */ }
        }
        if (!reused) {
            if (old != null && old.pcDeviceId != null && old.server.equals(server)
                    && old.account.equals(account)) {
                // Free the single PC slot held by our previous registration.
                try {
                    http(st, "POST", "/api/devices/" + old.pcDeviceId + "/revoke", session, "");
                } catch (Exception ignore) { }
            }
            String hostname;
            try {
                hostname = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                hostname = "niubackup-pc";
            }
            String regBody = "{\"kind\":\"pc\",\"name\":\"" + MiniJson.esc(hostname)
                    + "\",\"pc_pubkey\":\"" + MiniJson.esc(pubkeyB64)
                    + "\",\"device_uuid\":\"" + MiniJson.esc(deviceUuid(dataDir)) + "\"}";
            ApiResult r = http(st, "POST", "/api/devices/register", session, regBody);
            if (r.status == 409 && r.body.contains("device_limit")) {
                // Reinstall case: a previous registration of this user still
                // holds the single PC slot. The caller authenticated with the
                // account password, so reclaiming the slot is legitimate:
                // revoke the stale pc devices and retry once.
                for (String[] d : listDevices(st, session)) {
                    if ("pc".equals(d[1]) && !"true".equals(d[4])) {
                        http(st, "POST", "/api/devices/" + d[0] + "/revoke", session, "");
                    }
                }
                r = http(st, "POST", "/api/devices/register", session, regBody);
            }
            requireOk(r, "pc device register");
            st.pcDeviceId = Long.toString(MiniJson.num(r.body, "device_id", -1));
            st.pcDeviceToken = MiniJson.str(r.body, "device_token");
            ApiResult ap = http(st, "POST", "/api/devices/" + st.pcDeviceId + "/approve",
                    session, "");
            requireOk(ap, "pc device self-approve");
        }
        if (pcIpOverride != null) st.pcIp = pcIpOverride;
        if (st.pcIp == null) st.pcIp = DEFAULT_PC_IP;

        String[] prov = provisioning(st, st.pcDeviceToken);
        st.publicNode = prov[0];
        st.networkName = prov[1];
        st.networkSecret = prov[2];
        st.save();
        syncOnce(st, store);
        return st;
    }

    /**
     * One refresh cycle against the service: device states + license into the
     * local store, provisioning rotation pickup. Runs on the PC device token.
     */
    static String syncOnce(State st, BackupReceiver.Store store) throws Exception {
        List<String[]> devices = listDevices(st, st.pcDeviceToken);
        int approved = 0, pending = 0;
        for (String[] d : devices) {
            boolean ap = "true".equals(d[3]);
            boolean rv = "true".equals(d[4]);
            if (ap && !rv) approved++; else if (!rv) pending++;
            String hash = d[0].equals(st.pcDeviceId)
                    ? Hash.hex(BackupReceiver.sha256(st.pcDeviceToken)) : null;
            store.syncDevice(st.userId, d[0], d[2], d[1], ap, rv, hash);
        }
        long[] lic = license(st, st.pcDeviceToken);
        store.upsertLicense(st.userId, lic[0], (int) lic[1], (int) lic[2]);
        store.setMeta("quota_bytes", Long.toString(lic[3]));
        store.setMeta("license_expires_at", Long.toString(lic[0]));
        String[] prov = provisioning(st, st.pcDeviceToken);
        if (prov[1] != null && (!prov[1].equals(st.networkName)
                || !prov[2].equals(st.networkSecret) || !prov[0].equals(st.publicNode))) {
            st.networkName = prov[1];
            st.networkSecret = prov[2];
            st.publicNode = prov[0];
            st.save();
        }
        store.setMeta("last_sync_ok", Long.toString(System.currentTimeMillis()));
        return "synced " + devices.size() + " devices (" + approved + " approved, "
                + pending + " pending), "
                + (lic[0] < 0 || lic[0] > 4_102_444_800_000L // <0 = none on file; >2100 = perpetual sentinel
                        ? "license perpetual"
                        : "license expires " + new java.util.Date(lic[0]).toString())
                + ", network " + st.networkName;
    }
}

/**
 * MeshSidecar — a supervised easytier-core child process joining THIS user's
 * nb- network (M4c). The receiver writes the TOML config from the account
 * provisioning (schema validated against easytier-core 2.7.0
 * easytier-core/src/config/toml.rs: top-level identity + [network_identity]
 * + [[peer]], `listeners = []`, `[secure_mode] enabled`), then launches the
 * core with its own RPC portal and
 * supervises it: on exit it is relaunched (rewriting the config first, so a
 * rotated network secret is picked up).
 *
 * Secure mode: the first launch tries `[secure_mode] enabled = true`. If the
 * process dies within the probe window for a non-privilege reason, it is
 * retried once without secure mode and the fallback is logged loudly —
 * 2.6.4 exposes no stable node public key to pin (see docs/m4b-notes.md),
 * so product clients run on the per-network legacy keys in that case.
 *
 * Linux privilege note: creating the TUN device needs CAP_NET_ADMIN or
 * root. A user-level launch fails with "tun device error / Operation not
 * permitted"; that failure is classified separately (secure mode is NOT
 * degraded for it). Installers must grant the capability (setcap on the
 * sidecar binary or a root systemd unit) — see docs/m4c-notes.md.
 */
final class MeshSidecar {

    static final int RPC_PORT = 15891;

    private final Path dataDir;
    private final AccountLink.State st;
    private final String binOverride;
    private final int rpcPort = RPC_PORT;

    private volatile boolean stop;
    private volatile Process proc;
    private volatile boolean secureMode = true;
    private volatile boolean secureFellBack;
    private Thread supervisor;

    MeshSidecar(Path dataDir, AccountLink.State st, String binOverride) {
        this.dataDir = dataDir;
        this.st = st;
        this.binOverride = binOverride;
    }

    Path configFile() { return dataDir.resolve("easytier.toml"); }
    Path logFile() { return dataDir.resolve("easytier.log"); }
    boolean secureModeActive() { return secureMode && !secureFellBack; }
    boolean isAlive() { return proc != null && proc.isAlive(); }

    String resolveBin() {
        List<String> candidates = new ArrayList<>();
        if (binOverride != null) candidates.add(binOverride);
        String env = System.getenv("NB_EASYTIER_BIN");
        if (env != null && !env.isEmpty()) candidates.add(env);
        try { // sibling of the jar/classes (jpackage app dir carries the sidecar here)
            Path code = Paths.get(MeshSidecar.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(code) ? code : code.getParent();
            if (dir != null) candidates.add(dir.resolve("easytier-core").toString());
        } catch (Exception ignore) { }
        candidates.add(dataDir.resolve("sidecar").resolve("easytier-core").toString());
        candidates.add("easytier-core");
        for (String c : candidates) {
            if (c.equals("easytier-core")) {
                if (BackupReceiver.onPath("easytier-core")) return c;
            } else if (Files.isExecutable(Paths.get(c))) {
                return c;
            }
        }
        return null;
    }

    void writeConfig() throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("instance_name = \"niubackup-pc\"\n");
        b.append("hostname = \"niubackup-pc\"\n");
        b.append("ipv4 = \"").append(st.pcIp).append("/24\"\n");
        b.append("dhcp = false\n");
        b.append("listeners = []\n\n");
        b.append("[network_identity]\n");
        b.append("network_name = \"").append(tomlEsc(st.networkName)).append("\"\n");
        b.append("network_secret = \"").append(tomlEsc(st.networkSecret)).append("\"\n\n");
        b.append("[[peer]]\n");
        b.append("uri = \"").append(tomlEsc(st.publicNode)).append("\"\n");
        if (secureMode && !secureFellBack) {
            // easytier-core 2.7.0 schema (easytier-core/src/config/toml.rs:
            // Config.secure_mode is a top-level field): secure mode is its
            // own [secure_mode] section. The 2.6.4 spelling
            // `[flags] secure_mode = true` is rejected by 2.7.0.
            b.append("\n[secure_mode]\nenabled = true\n");
        }
        Files.writeString(configFile(), b.toString(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(configFile(),
                    PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignore) { }
    }

    private static String tomlEsc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean launch() {
        try {
            writeConfig();
            String bin = resolveBin();
            if (bin == null) {
                BackupReceiver.log("mesh sidecar: easytier-core binary not found "
                        + "(set --mesh-bin or NB_EASYTIER_BIN)");
                return false;
            }
            // Clear an orphaned instance of THIS config (a previous receiver
            // process that died without stopping its child) — it would hold
            // the TUN address and RPC portal and make this launch fail.
            try {
                new ProcessBuilder("pkill", "-f",
                        "easytier-core -c " + configFile()).start().waitFor();
                Thread.sleep(500);
            } catch (Exception ignore) { }
            ProcessBuilder pb = new ProcessBuilder(bin, "-c", configFile().toString(),
                    "--rpc-portal", "127.0.0.1:" + rpcPort);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile().toFile()));
            proc = pb.start();
            BackupReceiver.log("mesh sidecar started: bin=" + bin + " network="
                    + st.networkName + " ip=" + st.pcIp + " peer=" + st.publicNode
                    + " secure_mode=" + (secureMode && !secureFellBack)
                    + " (log: " + logFile() + ")");
            return true;
        } catch (Exception e) {
            BackupReceiver.log("mesh sidecar launch failed: " + e);
            return false;
        }
    }

    /** Tail of the sidecar log, for failure-cause classification. */
    private String logTail() {
        try {
            if (!Files.isRegularFile(logFile())) return "";
            byte[] all = Files.readAllBytes(logFile());
            int from = Math.max(0, all.length - 4096);
            return new String(all, from, all.length - from, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Start the sidecar (with the secure-mode fallback) + supervision thread. */
    void start() {
        if (!launch()) return;
        // Probe: a process that dies almost immediately gets classified by
        // its log tail. A TUN permission failure is NOT a secure-mode
        // failure (the sidecar needs CAP_NET_ADMIN or root — a packaging /
        // install-time concern); only other startup deaths degrade secure
        // mode, once.
        try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        if (!isAlive()) {
            String tail = logTail();
            if (tail.contains("tun") && tail.contains("not permitted")) {
                BackupReceiver.log("mesh sidecar cannot create its TUN device: "
                        + "easytier-core needs CAP_NET_ADMIN or root on Linux "
                        + "(install-time privilege; not a secure-mode failure). "
                        + "Supervisor keeps retrying; see " + logFile());
            } else if (secureMode && !secureFellBack) {
                secureFellBack = true;
                BackupReceiver.log("mesh sidecar exited immediately with secure_mode=true; "
                        + "retrying without secure mode (2.6.4 fallback, see docs)");
                if (!launch()) return;
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }
        if (!isAlive()) {
            BackupReceiver.log("mesh sidecar is not running after launch attempts; "
                    + "see " + logFile());
        }
        supervisor = new Thread(() -> {
            long backoffMs = 5000;
            while (!stop) {
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException e) {
                    return;
                }
                if (stop) return;
                if (!isAlive()) {
                    BackupReceiver.log("mesh sidecar exited; relaunching");
                    launch();
                    try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                    backoffMs = isAlive() ? 5000 : Math.min(60_000, backoffMs * 2);
                } else {
                    backoffMs = 5000;
                }
            }
        }, "mesh-sidecar");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    void stopSidecar() {
        stop = true;
        if (supervisor != null) supervisor.interrupt();
        Process p = proc;
        if (p != null) p.destroy();
    }
}

/**
 * ControlApi — the loopback control API for the Tauri desktop shell
 * (--control). Binds 127.0.0.1 ONLY: the first free port in 28799..28808
 * wins, and the chosen port + a random per-process control token are
 * written to &lt;dataDir&gt;/control.json (mode 600). Every request must
 * carry the token in the X-Control-Token header. The shell is the only
 * intended client; the backup protocol socket is untouched.
 *
 * Login/logout ride the existing AccountLink flows (account.json is the
 * persisted session — see docs/session-persistence.md). Both answer
 * restart_required: the shell restarts the engine process so identity,
 * mesh bind and sidecar all come up under the new state, exactly as a
 * fresh launch would see it.
 */
final class ControlApi {

    static final int BASE_PORT = 28799;
    static final int PORT_TRIES = 10;

    private final BackupReceiver r;
    private HttpServer server;
    private String token;
    private int port = -1;

    ControlApi(BackupReceiver r) {
        this.r = r;
    }

    Path file() { return r.dataDir.resolve("control.json"); }

    void start() throws IOException {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        token = Hash.hex(raw);
        IOException last = null;
        for (int i = 0; i < PORT_TRIES && server == null; i++) {
            try {
                server = HttpServer.create(
                        new InetSocketAddress("127.0.0.1", BASE_PORT + i), 0);
                port = BASE_PORT + i;
            } catch (BindException e) {
                last = e;
            }
        }
        if (server == null) throw last != null ? last : new IOException("no control port free");
        server.createContext("/", this::dispatch);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(t -> {
            Thread th = new Thread(t, "control-api");
            th.setDaemon(true);
            return th;
        }));
        server.start();
        writeFile();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.deleteIfExists(file()); } catch (Exception ignore) { }
        }));
        BackupReceiver.log("control API listening on 127.0.0.1:" + port
                + " (token in " + file() + ")");
    }

    private void writeFile() throws IOException {
        String json = "{\"port\":" + port
                + ",\"token\":\"" + token + "\""
                + ",\"pid\":" + ProcessHandle.current().pid()
                + ",\"started_at_ms\":" + System.currentTimeMillis()
                + ",\"data_dir\":\"" + MiniJson.esc(r.dataDir.toString()) + "\"}";
        Files.createDirectories(r.dataDir);
        Files.writeString(file(), json, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file(),
                    PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignore) { /* non-POSIX fs */ }
    }

    // ------------------------------------------------------------- plumbing

    private void dispatch(HttpExchange ex) {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if ("OPTIONS".equals(method)) {
                ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                ex.getResponseHeaders().set("Access-Control-Allow-Headers",
                        "X-Control-Token, Content-Type");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            String presented = ex.getRequestHeaders().getFirst("X-Control-Token");
            if (presented == null || !MessageDigest.isEqual(
                    presented.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8))) {
                sendJson(ex, 401, "{\"ok\":false,\"error\":\"bad control token\"}");
                return;
            }
            route(ex, method, path);
        } catch (Exception e) {
            try {
                sendJson(ex, 500, "{\"ok\":false,\"error\":\""
                        + MiniJson.esc(String.valueOf(e)) + "\"}");
            } catch (Exception ignore) { }
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex, String method, String path) throws Exception {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        if ("GET".equals(method) && path.equals("/status")) { status(ex); return; }
        if ("GET".equals(method) && path.equals("/devices")) { devices(ex); return; }
        if ("POST".equals(method) && path.startsWith("/devices/")
                && path.endsWith("/approve")) {
            String id = path.substring("/devices/".length(),
                    path.length() - "/approve".length());
            approve(ex, id);
            return;
        }
        if ("POST".equals(method) && path.startsWith("/devices/")
                && path.endsWith("/revoke")) {
            String id = path.substring("/devices/".length(),
                    path.length() - "/revoke".length());
            revoke(ex, id);
            return;
        }
        if ("GET".equals(method) && path.equals("/sessions")) {
            sessions(ex, parseInt(q.get("limit"), 50));
            return;
        }
        if ("GET".equals(method) && path.equals("/records")) {
            records(ex, parseInt(q.get("limit"), 50), parseInt(q.get("offset"), 0));
            return;
        }
        if ("GET".equals(method) && path.startsWith("/records/")) {
            String rest = path.substring("/records/".length());
            int slash = rest.indexOf('/');
            if (slash > 0) {
                long id = parseLong(rest.substring(0, slash), -1);
                String what = rest.substring(slash + 1);
                if (id >= 0 && what.equals("thumb")) { recordBytes(ex, id, true); return; }
                if (id >= 0 && what.equals("preview")) { recordBytes(ex, id, false); return; }
            }
        }
        if ("GET".equals(method) && path.equals("/logs")) {
            logs(ex, parseInt(q.get("tail"), 200));
            return;
        }
        if ("POST".equals(method) && path.equals("/account/login")) { login(ex); return; }
        if ("POST".equals(method) && path.equals("/account/qr-ticket")) { qrTicketCreate(ex); return; }
        if ("GET".equals(method) && path.startsWith("/account/qr-ticket/")) {
            qrTicketPoll(ex, path.substring("/account/qr-ticket/".length()),
                    q.get("secret"));
            return;
        }
        if ("POST".equals(method) && path.equals("/account/logout")) { logout(ex); return; }
        if ("POST".equals(method) && path.equals("/sync")) { sync(ex); return; }
        sendJson(ex, 404, "{\"ok\":false,\"error\":\"no such endpoint\"}");
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static int parseInt(String s, int dflt) {
        try { return s == null ? dflt : Integer.parseInt(s.trim()); }
        catch (Exception e) { return dflt; }
    }

    private static long parseLong(String s, long dflt) {
        try { return s == null ? dflt : Long.parseLong(s.trim()); }
        catch (Exception e) { return dflt; }
    }

    private void sendJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private void sendBytes(HttpExchange ex, byte[] b, String contentType,
                           String extraKey, String extraVal) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        if (extraKey != null) ex.getResponseHeaders().set(extraKey, extraVal);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** 409 when an endpoint needs an account session the engine doesn't have. */
    private boolean needAccount(HttpExchange ex) throws IOException {
        if (r.acct != null) return true;
        sendJson(ex, 409, "{\"ok\":false,\"error\":\"no_account\","
                + "\"detail\":\"no saved session; POST /account/login first\"}");
        return false;
    }

    // ------------------------------------------------------------- endpoints

    private void status(HttpExchange ex) throws Exception {
        StringBuilder b = new StringBuilder("{\"ok\":true");
        b.append(",\"receiver\":{\"serving\":").append(BackupReceiver.currentServer != null)
         .append(",\"backup_port\":").append(r.port)
         .append(",\"bind\":").append(r.bind == null ? "null"
                 : "\"" + MiniJson.esc(r.bind) + "\"")
         .append(",\"target_dir\":\"").append(MiniJson.esc(r.targetRoot.toString()))
         .append("\",\"data_dir\":\"").append(MiniJson.esc(r.dataDir.toString()))
         .append("\",\"account_mode\":").append(r.accountMode)
         .append(",\"control_only\":").append(r.controlOnly).append('}');
        // mesh
        b.append(",\"mesh\":");
        if (r.sidecar == null) {
            b.append("{\"state\":\"off\"}");
        } else {
            b.append("{\"state\":\"").append(r.sidecar.isAlive() ? "up" : "down")
             .append("\",\"secure_mode\":").append(r.sidecar.secureModeActive())
             .append(",\"peers\":").append(peersJson()).append('}');
        }
        // account + license + storage
        if (r.acct == null) {
            b.append(",\"account\":null");
        } else {
            long exp = r.store.licenseExpiresAt(r.acct.userId);
            String quota = r.store.getMeta("quota_bytes");
            Long[] disk = diskSpace(r.targetRoot);
            b.append(",\"account\":{\"account\":\"").append(MiniJson.esc(r.acct.account))
             .append("\",\"user_id\":\"").append(MiniJson.esc(r.acct.userId))
             .append("\",\"server\":\"").append(MiniJson.esc(r.acct.server))
             .append("\",\"network\":\"").append(MiniJson.esc(r.acct.networkName))
             .append("\",\"pc_ip\":\"").append(MiniJson.esc(r.acct.pcIp))
             .append("\",\"pc_device_id\":\"").append(MiniJson.esc(r.acct.pcDeviceId))
             .append("\"}")
             .append(",\"license_expires_at\":").append(exp < 0 ? "null" : Long.toString(exp))
             .append(",\"storage\":{\"used_bytes\":").append(r.store.filesBytesTotal(r.acct.userId))
             .append(",\"quota_bytes\":").append(quota == null ? "null" : quota)
             .append(",\"files\":").append(r.store.countFiles(r.acct.userId))
             .append(",\"disk_total_bytes\":").append(disk == null ? "null" : Long.toString(disk[0]))
             .append(",\"disk_free_bytes\":").append(disk == null ? "null" : Long.toString(disk[1]))
             .append('}')
             .append(",\"last_sync_ok_ms\":").append(r.lastSyncOkMs);
        }
        b.append(",\"server_time_ms\":").append(System.currentTimeMillis()).append('}');
        sendJson(ex, 200, b.toString());
    }

    /**
     * {total, usable} bytes of the volume holding p, or null when the
     * volume cannot be read — the desktop storage page is disk-based
     * (the license quota is gone: open-source/perpetual), so /status
     * reports real capacity and the UI never has to invent numbers.
     */
    private static Long[] diskSpace(Path p) {
        try {
            Path q = p;
            while (q != null && !Files.exists(q)) q = q.getParent();
            if (q == null) return null;
            FileStore fs = Files.getFileStore(q);
            return new Long[]{fs.getTotalSpace(), fs.getUsableSpace()};
        } catch (Exception e) {
            return null;
        }
    }

    /** Best-effort peer view from the sidecar's easytier-cli RPC portal. */
    private String peersJson() {
        if (!BackupReceiver.onPath("easytier-cli")) return "null";
        Path tmp = null;
        try {
            tmp = Files.createTempFile("etpeerctl", ".txt");
            Process p = new ProcessBuilder("easytier-cli",
                    "--rpc-portal", "127.0.0.1:" + MeshSidecar.RPC_PORT, "peer")
                    .redirectOutput(tmp.toFile())
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "null";
            }
            Pattern relay = Pattern.compile("relay\\((\\d+)\\)");
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (String line : Files.readAllLines(tmp, StandardCharsets.UTF_8)) {
                if (!line.contains("10.99.")) continue;
                String ip = null;
                for (String tok : line.split("\\s+")) {
                    if (tok.startsWith("10.99.")) { ip = tok; break; }
                }
                if (ip == null) continue;
                int cost;
                Matcher m = relay.matcher(line);
                if (m.find()) cost = Integer.parseInt(m.group(1));
                else if (line.contains("p2p")) cost = 1;
                else continue;
                if (!first) b.append(',');
                first = false;
                b.append("{\"ipv4\":\"").append(ip).append("\",\"cost\":").append(cost)
                 .append(",\"route\":\"").append(cost == 1 ? "p2p" : "relay").append("\"}");
            }
            return b.append(']').toString();
        } catch (Exception e) {
            return "null";
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignore) { }
        }
    }

    private void devices(HttpExchange ex) throws Exception {
        if (!needAccount(ex)) return;
        List<BackupReceiver.DeviceRow> rows = r.store.listDevices(r.acct.userId);
        StringBuilder bound = new StringBuilder(), pending = new StringBuilder();
        for (BackupReceiver.DeviceRow d : rows) {
            String o = "{\"id\":\"" + MiniJson.esc(d.id)
                    + "\",\"name\":\"" + MiniJson.esc(d.name == null ? "" : d.name)
                    + "\",\"kind\":\"" + MiniJson.esc(d.kind == null ? "" : d.kind)
                    + "\",\"approved\":" + d.approved
                    + ",\"revoked\":" + d.revoked
                    + ",\"is_this_pc\":" + d.id.equals(r.acct.pcDeviceId) + "}";
            StringBuilder dst = (d.approved || d.revoked) ? bound : pending;
            if (dst.length() > 0) dst.append(',');
            dst.append(o);
        }
        sendJson(ex, 200, "{\"ok\":true,\"bound\":[" + bound + "],\"pending\":[" + pending + "]}");
    }

    private void approve(HttpExchange ex, String deviceId) throws Exception {
        if (!needAccount(ex)) return;
        try {
            String res = AccountLink.approveWithDevice(r.acct, deviceId);
            String syncNote = null;
            try {
                AccountLink.syncOnce(r.acct, r.store);
                r.lastSyncOkMs = System.currentTimeMillis();
            } catch (Exception e) {
                syncNote = e.getMessage();
            }
            StringBuilder b = new StringBuilder("{\"ok\":true,\"result\":");
            if (res != null && res.trim().startsWith("{")) b.append(res.trim());
            else b.append('"').append(MiniJson.esc(res == null ? "" : res)).append('"');
            if (syncNote != null) {
                b.append(",\"sync_warning\":\"").append(MiniJson.esc(syncNote)).append('"');
            }
            sendJson(ex, 200, b.append('}').toString());
        } catch (Exception e) {
            sendJson(ex, 502, "{\"ok\":false,\"error\":\""
                    + MiniJson.esc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    private void revoke(HttpExchange ex, String deviceId) throws Exception {
        if (!needAccount(ex)) return;
        try {
            String res = AccountLink.revokeWithSession(r.acct, deviceId);
            try {
                AccountLink.syncOnce(r.acct, r.store);
                r.lastSyncOkMs = System.currentTimeMillis();
            } catch (Exception ignore) { /* local view refreshes on next sync */ }
            StringBuilder b = new StringBuilder("{\"ok\":true,\"result\":");
            if (res != null && res.trim().startsWith("{")) b.append(res.trim());
            else b.append('"').append(MiniJson.esc(res == null ? "" : res)).append('"');
            sendJson(ex, 200, b.append('}').toString());
        } catch (AccountLink.SessionDead e) {
            sendJson(ex, 401, "{\"ok\":false,\"error\":\"session_expired\","
                    + "\"detail\":\"" + MiniJson.esc(String.valueOf(e.getMessage())) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 502, "{\"ok\":false,\"error\":\""
                    + MiniJson.esc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    private void sessions(HttpExchange ex, int limit) throws Exception {
        List<String[]> rows = r.store.listSessions(Math.max(1, Math.min(limit, 200)));
        StringBuilder b = new StringBuilder("{\"ok\":true,\"sessions\":[");
        boolean first = true;
        for (String[] s : rows) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"ts\":").append(s[0])
             .append(",\"device_id\":\"").append(MiniJson.esc(s[1]))
             .append("\",\"files_ok\":").append(s[2])
             .append(",\"bytes\":").append(s[3])
             .append(",\"result\":\"").append(MiniJson.esc(s[4])).append("\"}");
        }
        sendJson(ex, 200, b.append("]}").toString());
    }

    private void records(HttpExchange ex, int limit, int offset) throws Exception {
        if (!needAccount(ex)) return;
        limit = Math.max(1, Math.min(limit, 200));
        offset = Math.max(0, offset);
        List<BackupReceiver.FileRow> rows =
                r.store.listFilesPage(r.acct.userId, limit, offset);
        int total = r.store.countFiles(r.acct.userId);
        StringBuilder b = new StringBuilder("{\"ok\":true,\"total\":").append(total)
                .append(",\"records\":[");
        boolean first = true;
        for (BackupReceiver.FileRow f : rows) {
            if (!first) b.append(',');
            first = false;
            b.append("{\"id\":").append(f.id)
             .append(",\"device_id\":\"").append(MiniJson.esc(f.deviceId))
             .append("\",\"path\":\"").append(MiniJson.esc(f.relPath))
             .append("\",\"size\":").append(f.size)
             .append(",\"sha256\":\"").append(f.sha256)
             .append("\",\"backed_at\":").append(f.backedAt)
             .append(",\"integrity\":\"").append(MiniJson.esc(f.integrity))
             .append("\",\"thumb\":").append(f.thumb != null)
             .append(",\"kind\":\"").append(kindOf(f.relPath))
             .append("\",\"mime\":\"").append(mimeOf(f.relPath)).append("\"}");
        }
        sendJson(ex, 200, b.append("]}").toString());
    }

    private void recordBytes(HttpExchange ex, long id, boolean thumbOnly) throws Exception {
        BackupReceiver.FileRow f = r.store.findFileById(id);
        if (f == null) {
            sendJson(ex, 404, "{\"ok\":false,\"error\":\"no such record\"}");
            return;
        }
        Path thumbPath = null;
        if (f.thumb != null) {
            Path name = Paths.get(f.thumb).getFileName();
            if (name != null) thumbPath = r.dataDir.resolve("thumbs").resolve(name);
        }
        boolean hasThumb = thumbPath != null && Files.isRegularFile(thumbPath);
        if (thumbOnly) {
            if (!hasThumb) {
                sendJson(ex, 404, "{\"ok\":false,\"error\":\"no thumbnail\"}");
                return;
            }
            sendBytes(ex, Files.readAllBytes(thumbPath), "image/jpeg", null, null);
            return;
        }
        // preview: full bytes for web-renderable images; poster frame for
        // videos; the JPEG thumb stands in for HEIC (WebKit cannot render it).
        String kind = kindOf(f.relPath);
        String mime = mimeOf(f.relPath);
        if (kind.equals("image") && !mime.equals("image/heic") && !mime.equals("image/heif")) {
            Path stored = BackupReceiver.safeResolve(r.targetRoot.resolve("users")
                    .resolve(f.userId).resolve("devices").resolve(f.deviceId), f.relPath);
            if (stored == null || !Files.isRegularFile(stored)) {
                sendJson(ex, 404, "{\"ok\":false,\"error\":\"stored file missing\"}");
                return;
            }
            sendBytes(ex, Files.readAllBytes(stored), mime, null, null);
            return;
        }
        if (hasThumb) {
            sendBytes(ex, Files.readAllBytes(thumbPath), "image/jpeg",
                    "X-Preview-Fallback", "thumb");
            return;
        }
        sendJson(ex, 404, "{\"ok\":false,\"error\":\"no preview available\"}");
    }

    private void logs(HttpExchange ex, int tail) throws IOException {
        List<String> lines = BackupReceiver.logTail(Math.max(1, Math.min(tail, 400)));
        StringBuilder b = new StringBuilder("{\"ok\":true,\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(MiniJson.esc(lines.get(i))).append('"');
        }
        sendJson(ex, 200, b.append("]}").toString());
    }

    private void login(HttpExchange ex) throws Exception {
        String json = body(ex);
        String server = MiniJson.str(json, "server");
        String account = MiniJson.str(json, "account");
        String password = MiniJson.str(json, "password");
        if (server == null || server.isEmpty()) server = BackupReceiver.DEFAULT_SERVER;
        if (account == null || password == null || account.isEmpty() || password.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"account and password are required\"}");
            return;
        }
        try {
            r.loadOrCreateKeys();
            AccountLink.State st = AccountLink.loginFlow(r.dataDir, r.store,
                    server, account, password, r.pubkeyB64(), null);
            r.acct = st;
            r.accountMode = true;
            BackupReceiver.log("control API: login ok for " + st.account
                    + " (user " + st.userId + "); engine restart required to serve under it");
            sendJson(ex, 200, "{\"ok\":true,\"restart_required\":true"
                    + ",\"user_id\":\"" + MiniJson.esc(st.userId)
                    + "\",\"account\":\"" + MiniJson.esc(st.account)
                    + "\",\"network\":\"" + MiniJson.esc(st.networkName) + "\"}");
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            int code = msg.contains("401") ? 401 : msg.contains("403") ? 403 : 502;
            sendJson(ex, code, "{\"ok\":false,\"error\":\"" + MiniJson.esc(msg) + "\"}");
        }
    }

    /** Server the in-flight QR ticket was created against (the poll must
     *  hit the same service; the QR payload carries it for the phone). */
    private volatile String qrTicketServer;

    /**
     * 扫码登录 step 1 (design v1.4): create a one-time pc-login ticket at
     * the account service and hand the shell everything it renders —
     * the QR payload is ONLY {t, s, srv} (no credential material), the
     * countdown runs off expires_at_ms.
     */
    private void qrTicketCreate(HttpExchange ex) throws Exception {
        if (r.acct != null) {
            sendJson(ex, 409, "{\"ok\":false,\"error\":\"already_logged_in\"}");
            return;
        }
        String json = body(ex);
        String server = MiniJson.str(json, "server");
        if (server == null || server.isEmpty()) server = BackupReceiver.DEFAULT_SERVER;
        server = AccountLink.normalize(server);
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "niubackup-pc";
        }
        try {
            String[] t = AccountLink.createPcLoginTicket(server, hostname,
                    AccountLink.deviceUuid(r.dataDir));
            if (t[0] == null || t[1] == null) {
                sendJson(ex, 502, "{\"ok\":false,\"error\":\"ticket response incomplete\"}");
                return;
            }
            qrTicketServer = server;
            long expMs = normalizeExpires(t[2]);
            String payload = "{\"t\":\"" + MiniJson.esc(t[0])
                    + "\",\"s\":\"" + MiniJson.esc(t[1])
                    + "\",\"srv\":\"" + MiniJson.esc(server) + "\"}";
            sendJson(ex, 200, "{\"ok\":true,\"ticket_id\":\"" + MiniJson.esc(t[0])
                    + "\",\"secret\":\"" + MiniJson.esc(t[1])
                    + "\",\"expires_at_ms\":" + expMs
                    + ",\"server\":\"" + MiniJson.esc(server)
                    + "\",\"qr_payload\":\"" + MiniJson.esc(payload) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 502, "{\"ok\":false,\"error\":\""
                    + MiniJson.esc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    /** expires_at arrives as epoch ms, epoch s, or an ISO-8601 string. */
    private static long normalizeExpires(String raw) {
        if (raw != null && !raw.isEmpty()) {
            try {
                long n = Long.parseLong(raw.trim());
                return n < 10_000_000_000L ? n * 1000 : n;
            } catch (NumberFormatException ignore) { }
            try {
                return Instant.parse(raw.trim()).toEpochMilli();
            } catch (Exception ignore) { }
        }
        // Contract lifetime is 2 minutes; fall back to exactly that.
        return System.currentTimeMillis() + 120_000;
    }

    /** The object literal containing "device_token" (top-level or nested). */
    private static String credsObject(String bodyJson) {
        int i = bodyJson.indexOf("\"device_token\"");
        if (i < 0) return null;
        int start = bodyJson.lastIndexOf('{', i);
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int j = start; j < bodyJson.length(); j++) {
            char c = bodyJson.charAt(j);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) return bodyJson.substring(start, j + 1);
            }
        }
        return null;
    }

    /**
     * 扫码登录 step 2: poll the ticket. Non-approved states pass through
     * ({status}); on approved the engine immediately runs the QR login
     * flow — persisting account.json via the same shape as password
     * login — and answers restart_required so the shell restarts the
     * engine under the new identity.
     */
    private void qrTicketPoll(HttpExchange ex, String ticketId, String secret) throws Exception {
        if (secret == null || secret.isEmpty()) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"secret required\"}");
            return;
        }
        String server = qrTicketServer != null ? qrTicketServer
                : (r.acct != null ? r.acct.server : BackupReceiver.DEFAULT_SERVER);
        AccountLink.ApiResult res;
        try {
            res = AccountLink.pcLoginTicketStatus(server, ticketId, secret);
        } catch (Exception e) {
            sendJson(ex, 502, "{\"ok\":false,\"error\":\""
                    + MiniJson.esc(String.valueOf(e.getMessage())) + "\"}");
            return;
        }
        if (res.status == 404 || res.status == 410) {
            sendJson(ex, 200, "{\"ok\":true,\"status\":\"expired\"}");
            return;
        }
        if (!res.ok()) {
            sendJson(ex, 502, "{\"ok\":false,\"error\":\"ticket poll HTTP "
                    + res.status + "\"}");
            return;
        }
        String st = MiniJson.str(res.body, "status");
        if (st == null) st = "pending";
        if (!"approved".equals(st)) {
            sendJson(ex, 200, "{\"ok\":true,\"status\":\"" + MiniJson.esc(st) + "\"}");
            return;
        }
        String creds = credsObject(res.body);
        if (creds == null) {
            sendJson(ex, 502, "{\"ok\":false,\"error\":\"approved but no credentials\"}");
            return;
        }
        try {
            r.loadOrCreateKeys();
            AccountLink.State state = AccountLink.qrLoginFlow(
                    r.dataDir, r.store, server, creds, null);
            r.acct = state;
            r.accountMode = true;
            qrTicketServer = null;
            BackupReceiver.log("control API: QR login ok for " + state.account
                    + " (user " + state.userId + "); engine restart required");
            sendJson(ex, 200, "{\"ok\":true,\"status\":\"approved\",\"restart_required\":true"
                    + ",\"user_id\":\"" + MiniJson.esc(state.userId)
                    + "\",\"account\":\"" + MiniJson.esc(state.account)
                    + "\",\"network\":\"" + MiniJson.esc(state.networkName) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 502, "{\"ok\":false,\"error\":\""
                    + MiniJson.esc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    private void logout(HttpExchange ex) throws IOException {
        AccountLink.State st = r.acct;
        if (st == null) st = AccountLink.load(r.dataDir);
        if (st != null) AccountLink.logoutSession(st);
        r.acct = null;
        BackupReceiver.log("control API: logged out (saved session wiped)");
        sendJson(ex, 200, "{\"ok\":true,\"restart_required\":true}");
    }

    private void sync(HttpExchange ex) throws Exception {
        if (!needAccount(ex)) return;
        try {
            String summary = AccountLink.syncOnce(r.acct, r.store);
            r.lastSyncOkMs = System.currentTimeMillis();
            BackupReceiver.log("control API sync: " + summary);
            sendJson(ex, 200, "{\"ok\":true,\"summary\":\""
                    + MiniJson.esc(summary) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 502, "{\"ok\":false,\"error\":\""
                    + MiniJson.esc(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    // ------------------------------------------------------------- media types

    static String kindOf(String path) {
        String ext = extOf(path);
        switch (ext) {
            case "jpg": case "jpeg": case "png": case "gif": case "bmp":
            case "webp": case "heic": case "heif":
                return "image";
            case "mp4": case "mov": case "mkv": case "avi": case "webm":
            case "3gp": case "m4v":
                return "video";
            default:
                return "other";
        }
    }

    static String mimeOf(String path) {
        switch (extOf(path)) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "webp": return "image/webp";
            case "heic": return "image/heic";
            case "heif": return "image/heif";
            case "mp4": case "m4v": return "video/mp4";
            case "mov": return "video/quicktime";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "avi": return "video/x-msvideo";
            case "3gp": return "video/3gpp";
            default: return "application/octet-stream";
        }
    }

    private static String extOf(String path) {
        String name = path == null ? "" : path;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
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
        if (s == null) return "";
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
