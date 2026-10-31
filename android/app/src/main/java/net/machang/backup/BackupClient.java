package net.machang.backup;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * BackupClient — Android port of the M1 BackupSender (same wire protocol,
 * same receiver). Scans a source directory (DCIM), hashes every file
 * (SHA-256), offers the manifest to the BackupReceiver on the laptop, and
 * sends only the files the receiver asks for, starting at the resume offset
 * it offers. Progress and the final receipt go to the {@link Logger}.
 *
 * M3.5: SHA-256 results are kept in a persistent {@link HashCache} in the
 * app's files dir, so repeat scans of an unchanged library are
 * metadata-only, and a first hash interrupted halfway resumes where it
 * stopped. The wire protocol is unchanged (v1).
 *
 * Hotfix (2026-10-31): the scan tolerates per-file failures. A file that
 * cannot be stat'ed / opened / read — ColorOS {@code .trashed-*} entries
 * are the proven case (EACCES on open), as are files deleted mid-scan —
 * is skipped and counted in {@link #lastSkippedCount} instead of aborting
 * the whole session before it even connects. Skipped files are simply
 * absent from the manifest (no record, no receipt implications).
 *
 * Batch sessions (protocol v2 extension): when the receiver's HELLO_ACK
 * advertises {@code "batch":1}, the client enumerates once and then loops —
 * hash up to the batch limit (200 files / 256 MiB), offer that batch,
 * transfer its files — so the first files land while the rest of the
 * library is still hashing. A wanted file that cannot be opened when its
 * turn comes is reported as FILE-ERROR instead of the FILE header and
 * counts failed without aborting the session. Receivers without the
 * token get the pre-batch flow byte-for-byte (full scan before the
 * backup connection; capability is probed on a throwaway fs-mode HELLO).
 *
 * Stream-hash sessions (batch + {@code "streamhash":1}): the scan is
 * METADATA-ONLY — no hash cache, no content reads before sending; the
 * manifest carries an empty sha256. Each file is SHA-256'd while it
 * streams (a resumed file's already-sent prefix is hashed from local
 * storage first), the receiver answers DONE with the hash IT computed
 * over the assembled file, and the two are compared here. A mismatch is
 * reported as HASH-MISMATCH in the slot where the next FILE header would
 * go; the receiver then deletes its copy and marks the record bad.
 *
 * M4a (protocol v2): when user_id / device_id / device token are configured,
 * the client speaks protocol v2 — HELLO carries the identity plus the
 * EasyTier route cost to the PC (1 = p2p direct, &gt;=2 relay, -1 unknown;
 * the receiver enforces the direct-only gate). After the session it stores
 * the receiver's signed completion receipt (ECDSA P-256) into the app files
 * dir as last-receipt.json and logs whether the signature verifies against
 * the public key included in the receipt. Without v2 credentials it falls
 * back to the legacy v1 HELLO (for the still-running v1 receiver).
 *
 * Pure JDK APIs, safe on Android (minSdk 26). Blocking: run on a worker thread.
 */
public class BackupClient {

    public interface Logger {
        void log(String msg);
    }

    /** Overall transfer progress (M4c file-library 「备份中 x%」 badge). */
    public interface ProgressListener {
        void onProgress(long sentBytes, long totalBytes, String currentPath);
    }

    /**
     * Optional local file-index feed (the Android library page's SQLite
     * index): the engine reports per-file metadata as it scans, confirmed
     * backups as they land or are receiver-skipped, and the files of a
     * signature-verified receipt when one arrives — so the library page
     * can render backup state without ever hashing the library itself.
     * Null (the default) disables the feed; the PC sender and v1-only
     * clients never set one. Every call is guarded: a sink failure can
     * never disturb a session.
     */
    public interface IndexSink {
        /** One scanned file (sha256 null when unknown, e.g. stream-hash). */
        void onMeta(String path, long size, long mtime, String sha256);
        /** The PC holds this exact file (DONE, or receiver-skipped as
         *  already stored); sha256 null when the session never learned it. */
        void onBacked(String path, long size, long mtime, String sha256);
        /** A file listed by a signature-verified session receipt. */
        void onReceiptFile(String path, long size, String sha256);
        /** Flush any buffered events (session end). */
        void flush();
    }

    private volatile IndexSink indexSink;

    /** Attach the local file-index feed (Android only; see IndexSink). */
    public void setIndexSink(IndexSink sink) {
        this.indexSink = sink;
    }

    private void sinkMeta(Entry e) {
        IndexSink s = indexSink;
        if (s == null) return;
        String sha = (e.sha == null || e.sha.isEmpty()) ? null : e.sha;
        try {
            s.onMeta(e.path, e.size, e.mtime, sha);
        } catch (Throwable ignore) { /* the index must never break a session */ }
    }

    private void sinkBacked(Entry e, String sha256) {
        IndexSink s = indexSink;
        if (s == null) return;
        try {
            s.onBacked(e.path, e.size, e.mtime, sha256);
        } catch (Throwable ignore) { /* the index must never break a session */ }
    }

    private void sinkFlush() {
        IndexSink s = indexSink;
        if (s == null) return;
        try {
            s.flush();
        } catch (Throwable ignore) { /* the index must never break a session */ }
    }

    /** Stats of the most recent session (for the session-log report). */
    public volatile int lastOkCount;
    public volatile int lastFailCount;
    public volatile long lastBytesSent;
    /** Files the most recent scan skipped as unreadable (see class doc). */
    public volatile int lastSkippedCount;

    static final int PROTO = 1;
    public static final String DEFAULT_HOST = "10.200.0.2"; // laptop, mesh address
    public static final int DEFAULT_PORT = 28741;           // BackupReceiver
    public static final String DEVICE_NAME = "android-phone";

    private final Logger logger;
    private final File cacheDir; // app files dir; null -> cache inside scanned root
    private final String userId;       // v2 identity; null -> legacy v1
    private final String deviceId;
    private final String deviceToken;
    private final IntSupplier routeCostSupplier; // EasyTier peer cost to the PC; -1 = unknown

    public BackupClient(Logger logger) {
        this(logger, null);
    }

    public BackupClient(Logger logger, File cacheDir) {
        this(logger, cacheDir, null, null, null, () -> -1);
    }

    public BackupClient(Logger logger, File cacheDir, String userId, String deviceId,
                        String deviceToken, IntSupplier routeCostSupplier) {
        Logger base = logger != null ? logger : msg -> { };
        // Durability: mirror every session log line to <filesDir>/backup-log.txt
        // so a real-device session can be diagnosed after the fact (the on-screen
        // AppLog is in-memory only). Session lines never contain the device
        // token or key material — the client never logs them.
        if (cacheDir != null) {
            FileLog fileLog = new FileLog(cacheDir);
            this.logger = msg -> {
                base.log(msg);
                fileLog.write(msg);
            };
        } else {
            this.logger = base;
        }
        this.cacheDir = cacheDir;
        this.userId = userId;
        this.deviceId = deviceId;
        this.deviceToken = deviceToken;
        this.routeCostSupplier = routeCostSupplier != null ? routeCostSupplier : () -> -1;
    }

    private boolean v2() {
        return userId != null && !userId.isEmpty()
                && deviceId != null && !deviceId.isEmpty()
                && deviceToken != null && !deviceToken.isEmpty();
    }

    /**
     * Append-only mirror of the session log at {@code <filesDir>/backup-log.txt},
     * rotated once past ~512 KiB (one previous generation kept). Strictly
     * best effort: every failure is swallowed — the file log must never
     * disturb a backup session.
     */
    private static final class FileLog {
        private static final long MAX_BYTES = 512L * 1024;
        private final File dir;
        private BufferedWriter writer;

        FileLog(File dir) {
            this.dir = dir;
            try {
                writer = open();
                writer.write("==== backup session log opened "
                        + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()) + " ====");
                writer.newLine();
                writer.flush();
            } catch (Throwable ignore) {
                writer = null;
            }
        }

        private BufferedWriter open() throws IOException {
            File f = new File(dir, "backup-log.txt");
            if (f.length() > MAX_BYTES) {
                File prev = new File(dir, "backup-log.txt.1");
                try { java.nio.file.Files.deleteIfExists(prev.toPath()); } catch (Throwable ignore) { }
                try {
                    java.nio.file.Files.move(f.toPath(), prev.toPath());
                } catch (Throwable ignore) {
                    try { java.nio.file.Files.deleteIfExists(f.toPath()); } catch (Throwable ignore2) { }
                }
            }
            return new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(f, true), StandardCharsets.UTF_8));
        }

        synchronized void write(String msg) {
            if (writer == null) return;
            try {
                // Rotate mid-session too: a long stream-hash session logs
                // one line per file and can pass the cap on its own.
                if (new File(dir, "backup-log.txt").length() > MAX_BYTES) {
                    try { writer.close(); } catch (Throwable ignore) { }
                    writer = open();
                }
                writer.write(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()));
                writer.write(' ');
                writer.write(msg);
                writer.newLine();
                writer.flush();
            } catch (Throwable ignore) {
                // Never let logging break the session.
            }
        }
    }

    /** Runs one backup session for {@code rootDir}. Returns the number of failed files. */
    public int runBackup(File rootDir) {
        return runBackup(DEFAULT_HOST, DEFAULT_PORT, DEVICE_NAME, rootDir, null);
    }

    public int runBackup(String host, int port, String device, File rootDir) {
        return runBackup(host, port, device, rootDir, null);
    }

    public int runBackup(String host, int port, String device, File rootDir,
                         ProgressListener progress) {
        if (!v2()) {
            // Legacy v1 (the still-running M1 receiver): unchanged flow —
            // full scan first, then the single-manifest session.
            List<Entry> entries;
            try {
                entries = scan(rootDir);
            } catch (IOException e) {
                logger.log("扫描目录失败: " + e.getMessage());
                return 1;
            }
            logScanSummary(rootDir, entries.size());
            if (entries.isEmpty()) {
                logger.log("没有可备份的文件。若 DCIM 里明明有照片，请到系统设置给本应用「所有文件访问」权限后再试。");
                return 0;
            }
            Socket rawSocket = new Socket();
            try (Socket s = rawSocket) {
                rawSocket.connect(new java.net.InetSocketAddress(host, port), 10_000);
                s.setSoTimeout(120_000);
                InputStream in = s.getInputStream();
                OutputStream out = new BufferedOutputStream(s.getOutputStream());
                Wire.writeLine(out, "HELLO " + device + " " + PROTO);
                String ok = Wire.readLine(in);
                if (ok == null || !ok.startsWith("OK")) {
                    logger.log("握手被拒绝: " + ok);
                    return 1;
                }
                return runSingleManifest(in, out, rootDir, entries, progress);
            } catch (IOException e) {
                logger.log("连接/传输失败: " + e.getMessage());
                return 1;
            }
        }

        // ---- protocol v2 ----
        // Phase 1 (cheap metadata walk) happens before any connection, so
        // an empty library keeps today's no-session behavior.
        List<File> files = enumerateFiles(rootDir);
        if (files.isEmpty()) {
            lastSkippedCount = 0;
            logger.log("已扫描 0 个文件 (" + rootDir.getAbsolutePath() + ")");
            logger.log("没有可备份的文件。若 DCIM 里明明有照片，请到系统设置给本应用「所有文件访问」权限后再试。");
            return 0;
        }
        // Batching is used only when the receiver advertises it (HELLO_ACK
        // "batch":1). A non-batch receiver must see today's flow unchanged —
        // in particular the full scan BEFORE the backup connection, because
        // a connection that sits silent through a long first hash would be
        // killed by the receiver's session read timeout. Capability is
        // therefore probed on a throwaway fs-mode HELLO, which writes no
        // backup session state on the receiver.
        boolean[] caps = probeCaps(host, port);
        boolean batchCapable = caps[0];
        boolean streamCapable = caps[1];
        HashSession hs = null; // lazy: stream-hash sessions never hash up front
        List<Entry> entries = null;
        if (!batchCapable) {
            hs = new HashSession(rootDir, files.size());
            entries = hs.hashAll(rootDir, files);
            hs.finish();
            lastSkippedCount = hs.skippedCount;
            logScanSummary(rootDir, entries.size());
            if (entries.isEmpty()) {
                logger.log("没有可备份的文件。若 DCIM 里明明有照片，请到系统设置给本应用「所有文件访问」权限后再试。");
                return 0;
            }
        }
        Socket rawSocket = new Socket();
        try (Socket s = rawSocket) {
            rawSocket.connect(new java.net.InetSocketAddress(host, port), 10_000);
            s.setSoTimeout(120_000);
            InputStream in = s.getInputStream();
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            int routeCost = -1;
            try {
                routeCost = routeCostSupplier.getAsInt();
            } catch (Exception ignore) { /* best effort; -1 = unknown */ }
            Wire.writeLine(out, "HELLO {\"version\":2,\"user_id\":\"" + MiniJson.esc(userId)
                    + "\",\"device_id\":\"" + MiniJson.esc(deviceId)
                    + "\",\"device_token\":\"" + MiniJson.esc(deviceToken)
                    + "\",\"route_cost\":" + routeCost + ",\"mode\":\"backup\"}");
            String resp = Wire.readLine(in);
            if (resp == null || !resp.startsWith("HELLO_ACK")) {
                logger.log("v2 握手被拒绝: " + resp);
                return 1;
            }
            logger.log("协议 v2 握手成功（直连 cost=" + routeCost + "）");
            if (batchCapable && MiniJson.num(resp, "batch", 0) == 1) {
                boolean streamHash = streamCapable && MiniJson.num(resp, "streamhash", 0) == 1;
                if (!streamHash && hs == null) hs = new HashSession(rootDir, files.size());
                return runBatchSession(in, out, rootDir, files, hs, streamHash, progress);
            }
            if (entries == null) {
                // The probe advertised batch but this HELLO_ACK does not:
                // fall back to the single-manifest flow on this connection.
                if (hs == null) hs = new HashSession(rootDir, files.size());
                entries = hs.hashAll(rootDir, files);
                hs.finish();
                lastSkippedCount = hs.skippedCount;
                logScanSummary(rootDir, entries.size());
                if (entries.isEmpty()) {
                    // Nothing to offer; close gracefully with an empty
                    // manifest instead of leaving the receiver waiting.
                    Wire.writeLine(out, "MANIFEST-END");
                    readDecisions(in);
                    Wire.writeLine(out, "BYE");
                    String receipt = Wire.readLine(in);
                    logger.log("回执: " + receipt);
                    if (receipt != null && receipt.startsWith("RECEIPT ")) {
                        handleSignedReceipt(receipt.substring("RECEIPT ".length()));
                    }
                    logger.log("没有可备份的文件。若 DCIM 里明明有照片，请到系统设置给本应用「所有文件访问」权限后再试。");
                    return 0;
                }
            }
            return runSingleManifest(in, out, rootDir, entries, progress);
        } catch (IOException e) {
            logger.log("连接/传输失败: " + e.getMessage());
            return 1;
        }
    }

    private void logScanSummary(File rootDir, int entryCount) {
        logger.log("已扫描 " + entryCount + " 个文件 (" + rootDir.getAbsolutePath() + ")");
        if (lastSkippedCount > 0) {
            logger.log("已跳过 " + lastSkippedCount + " 个无法读取的文件（逐条见上方扫描日志）");
        }
    }

    /**
     * Capability probe on a throwaway fs-mode connection: returns
     * {batch, streamhash} as advertised by the receiver's HELLO_ACK
     * ({@code "batch":1} / {@code "streamhash":1}). fs sessions write
     * no backup session state on the receiver, so probing is side-effect
     * free apart from one session log pair. Any failure → both false.
     */
    private boolean[] probeCaps(String host, int port) {
        Socket rawSocket = new Socket();
        try (Socket s = rawSocket) {
            rawSocket.connect(new java.net.InetSocketAddress(host, port), 10_000);
            s.setSoTimeout(30_000);
            InputStream in = s.getInputStream();
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            int routeCost = -1;
            try {
                routeCost = routeCostSupplier.getAsInt();
            } catch (Exception ignore) { /* best effort; -1 = unknown */ }
            Wire.writeLine(out, "HELLO {\"version\":2,\"user_id\":\"" + MiniJson.esc(userId)
                    + "\",\"device_id\":\"" + MiniJson.esc(deviceId)
                    + "\",\"device_token\":\"" + MiniJson.esc(deviceToken)
                    + "\",\"route_cost\":" + routeCost + ",\"mode\":\"fs\"}");
            String resp = Wire.readLine(in);
            if (resp == null || !resp.startsWith("HELLO_ACK")) return new boolean[]{false, false};
            return new boolean[]{MiniJson.num(resp, "batch", 0) == 1,
                    MiniJson.num(resp, "streamhash", 0) == 1};
        } catch (Exception e) {
            return new boolean[]{false, false};
        }
    }

    /** Decision lines up to READY: {path -> resume offset} for the files to send. */
    private Map<String, Long> readDecisions(InputStream in) throws IOException {
        Map<String, Long> wanted = new LinkedHashMap<>();
        String line;
        while ((line = Wire.readLine(in)) != null) {
            if (line.equals("READY")) break;
            String p = MiniJson.str(line, "path");
            long off = MiniJson.num(line, "offset", -1);
            if (p != null && off >= 0) wanted.put(p, off);
        }
        return wanted;
    }

    /**
     * The pre-batch session body on an established connection: one
     * manifest, the receiver's decisions, stop-and-wait transfers, BYE,
     * and the RECEIPT (verified + stored when signed, v2 only).
     */
    private int runSingleManifest(InputStream in, OutputStream out, File rootDir,
                                  List<Entry> entries, ProgressListener progress) {
        int failures = 0;
        try {
            for (Entry e : entries) {
                Wire.writeLine(out, "{\"path\":\"" + MiniJson.esc(e.path) + "\",\"size\":" + e.size
                        + ",\"mtime\":" + e.mtime + ",\"sha256\":\"" + e.sha + "\"}");
            }
            Wire.writeLine(out, "MANIFEST-END");

            Map<String, Long> wanted = readDecisions(in);
            int skipped = entries.size() - wanted.size();
            logger.log("接收端需要 " + wanted.size() + " 个文件，跳过 " + skipped + " 个");
            for (Entry e : entries) {
                // Not wanted = the receiver already holds this exact file.
                if (!wanted.containsKey(e.path)) sinkBacked(e, e.sha);
            }

            Map<String, Entry> byPath = new HashMap<>();
            for (Entry e : entries) byPath.put(e.path, e);
            long totalBytes = 0;
            for (Map.Entry<String, Long> w : wanted.entrySet()) {
                Entry e = byPath.get(w.getKey());
                if (e != null) totalBytes += e.size - w.getValue();
            }
            long[] sentBytes = {0};
            int okCount = 0;
            for (Map.Entry<String, Long> w : wanted.entrySet()) {
                Entry e = byPath.get(w.getKey());
                long offset = w.getValue();
                long bytes = e.size - offset;
                Wire.writeLine(out, "FILE {\"path\":\"" + MiniJson.esc(e.path)
                        + "\",\"bytes\":" + bytes + "}");
                try (InputStream fis = new FileInputStream(new File(rootDir, e.path))) {
                    skipFully(fis, offset);
                    copyN(fis, out, bytes, progress, sentBytes, totalBytes, e.path);
                }
                out.flush();
                String res = Wire.readLine(in);
                if (res != null && res.startsWith("DONE")) {
                    okCount++;
                    sinkBacked(e, e.sha);
                    logger.log("  已发送 " + e.path + " (" + bytes + " 字节，偏移 " + offset + ")");
                } else {
                    failures++;
                    logger.log("  失败 " + e.path + " -> " + res);
                }
            }
            lastOkCount = okCount + skipped;
            lastFailCount = failures;
            lastBytesSent = sentBytes[0];

            Wire.writeLine(out, "BYE");
            String receipt = Wire.readLine(in);
            logger.log("回执: " + receipt);
            if (v2() && receipt != null && receipt.startsWith("RECEIPT ")) {
                handleSignedReceipt(receipt.substring("RECEIPT ".length()));
            } else if (v2()) {
                logger.log("回执未取得：BYE 后未收到签名回执（收到: "
                        + (receipt == null ? "连接已断开" : receipt)
                        + "），本轮文件在手机侧无回执凭证");
            }
            sinkFlush();
        } catch (IOException e) {
            sinkFlush();
            logger.log("连接/传输失败: " + e.getMessage());
            return Math.max(failures, 1);
        }
        String tail = lastSkippedCount > 0
                ? "，另跳过 " + lastSkippedCount + " 个无法读取的文件" : "";
        logger.log((failures == 0 ? "全部文件已就位 ✔" : failures + " 个文件失败") + tail);
        return failures;
    }

    // ------------------------------------------------------------- batch mode

    static final int DEFAULT_BATCH_FILES = 200;
    static final long DEFAULT_BATCH_BYTES = 256L * 1024 * 1024;

    /** Batch file-count limit (-Dnb.batch.files wins; default 200). */
    static int batchFilesLimit() {
        String v = System.getProperty("nb.batch.files");
        if (v != null) {
            try {
                int n = Integer.parseInt(v.trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignore) { }
        }
        return DEFAULT_BATCH_FILES;
    }

    /** Batch byte limit (-Dnb.batch.bytes wins; default 256 MiB). */
    static long batchBytesLimit() {
        String v = System.getProperty("nb.batch.bytes");
        if (v != null) {
            try {
                long n = Long.parseLong(v.trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignore) { }
        }
        return DEFAULT_BATCH_BYTES;
    }

    /**
     * Batch session (v2 + the receiver advertised "batch":1): hash up to
     * the batch limit, offer that batch (BATCH-END), transfer its files,
     * and continue — the first files land while the rest of the library
     * is still hashing. BYE closes the session; the receiver answers
     * with ONE signed receipt over the whole session. A wanted file that
     * cannot be opened when its turn comes is reported as FILE-ERROR
     * instead of the FILE header (it may have vanished between scan and
     * send); it counts failed and the session continues. The progress
     * total is the bytes decided SO FAR, so it grows as later batches
     * are decided — the UI percentage can step back when a new batch
     * lands; that is expected, not a regression. With streamHash the
     * per-batch step is metadata-only and each file is hashed while it
     * streams (see the class doc).
     */
    private int runBatchSession(InputStream in, OutputStream out, File rootDir,
                                List<File> files, HashSession hs, boolean streamHash,
                                ProgressListener progress) {
        int failures = 0;
        try {
            int filesLimit = batchFilesLimit();
            long bytesLimit = batchBytesLimit();
            logger.log("分批备份模式：每批最多 " + filesLimit + " 个文件 / "
                    + (bytesLimit / (1024 * 1024)) + " MiB，扫一批传一批");
            MetaSession ms = null;
            if (streamHash) {
                logger.log("边传边算模式：不预先计算哈希，边传输边校验");
                logger.log("扫描完成（元数据）：" + files.size() + " 个文件");
                ms = new MetaSession(files.size());
            }
            String pendingMismatch = null; // stream-hash verdict owed for the previous file
            int idx = 0, batchNo = 0, okCount = 0, receiverSkipped = 0, offered = 0;
            long decidedTotal = 0;
            long[] sentBytes = {0};
            while (idx < files.size()) {
                List<Entry> batch = new ArrayList<>();
                long batchBytes = 0;
                while (idx < files.size() && batch.size() < filesLimit
                        && (batch.isEmpty() || batchBytes < bytesLimit)) {
                    Entry e = streamHash ? ms.metaOne(rootDir, files.get(idx++))
                            : hs.hashOne(rootDir, files.get(idx++));
                    if (e != null) {
                        batch.add(e);
                        batchBytes += e.size;
                    }
                }
                if (batch.isEmpty()) continue; // this slice was all unreadable skips
                batchNo++;
                offered += batch.size();
                for (Entry e : batch) {
                    Wire.writeLine(out, "{\"path\":\"" + MiniJson.esc(e.path) + "\",\"size\":" + e.size
                            + ",\"mtime\":" + e.mtime + ",\"sha256\":\"" + e.sha + "\"}");
                }
                Wire.writeLine(out, "BATCH-END");

                Map<String, Long> wanted = readDecisions(in);
                int skippedHere = batch.size() - wanted.size();
                receiverSkipped += skippedHere;
                for (Entry e : batch) {
                    // Not wanted = the receiver's stored copy matched this
                    // (size, mtime) with integrity ok: it is backed up.
                    // Stream-hash skips never reveal the stored sha.
                    if (!wanted.containsKey(e.path)) {
                        sinkBacked(e, streamHash ? null : e.sha);
                    }
                }
                Map<String, Entry> byPath = new HashMap<>();
                for (Entry e : batch) byPath.put(e.path, e);
                for (Map.Entry<String, Long> w : wanted.entrySet()) {
                    Entry e = byPath.get(w.getKey());
                    if (e != null) decidedTotal += e.size - w.getValue();
                }
                logger.log("批次 " + batchNo + "：本批 " + batch.size() + " 个文件，接收端需要 "
                        + wanted.size() + " 个，跳过 " + skippedHere + " 个");
                for (Map.Entry<String, Long> w : wanted.entrySet()) {
                    Entry e = byPath.get(w.getKey());
                    long offset = w.getValue();
                    long bytes = e.size - offset;
                    if (pendingMismatch != null) {
                        // The verdict for the PREVIOUS file goes in the
                        // slot where this file's header would go.
                        Wire.writeLine(out, "HASH-MISMATCH {\"path\":\""
                                + MiniJson.esc(pendingMismatch) + "\"}");
                        pendingMismatch = null;
                    }
                    InputStream fis;
                    try {
                        fis = new FileInputStream(new File(rootDir, e.path));
                        try {
                            // Stream-hash reads the prefix back for
                            // hashing during the send instead of skipping.
                            if (!streamHash) skipFully(fis, offset);
                        } catch (IOException skipErr) {
                            try { fis.close(); } catch (IOException ignore) { }
                            throw skipErr;
                        }
                    } catch (IOException openErr) {
                        // Open BEFORE the FILE header: a file deleted or made
                        // unreadable since its hash degrades to FILE-ERROR —
                        // the receiver expects no bytes and no reply is awaited.
                        Wire.writeLine(out, "FILE-ERROR {\"path\":\"" + MiniJson.esc(e.path)
                                + "\",\"error\":\"" + MiniJson.esc(String.valueOf(openErr.getMessage())) + "\"}");
                        failures++;
                        logger.log("  文件无法打开，本次跳过 " + e.path + " (" + openErr.getMessage() + ")");
                        continue;
                    }
                    Wire.writeLine(out, "FILE {\"path\":\"" + MiniJson.esc(e.path)
                            + "\",\"bytes\":" + bytes + "}");
                    String clientSha = null;
                    try (InputStream f = fis) {
                        if (streamHash) {
                            MessageDigest md = Hash.digest();
                            if (offset > 0) hashPrefix(f, md, offset);
                            copyNHash(f, out, bytes, md, progress, sentBytes, decidedTotal, e.path);
                            clientSha = Hash.hex(md.digest());
                        } else {
                            copyN(f, out, bytes, progress, sentBytes, decidedTotal, e.path);
                        }
                    }
                    out.flush();
                    String res = Wire.readLine(in);
                    if (res != null && res.startsWith("DONE")) {
                        String rsha = streamHash ? MiniJson.str(res, "sha256") : null;
                        if (streamHash && (rsha == null || !rsha.equals(clientSha))) {
                            failures++;
                            pendingMismatch = e.path;
                            logger.log("  哈希不一致 " + e.path + "：本机=" + clientSha
                                    + " 接收端=" + rsha + "，将通知接收端删除该副本");
                        } else {
                            okCount++;
                            sinkBacked(e, streamHash ? rsha : e.sha);
                            logger.log("  已发送 " + e.path + " (" + bytes + " 字节，偏移 " + offset + ")"
                                    + (streamHash ? "，哈希校验一致" : ""));
                        }
                    } else {
                        failures++;
                        logger.log("  失败 " + e.path + " -> " + res);
                    }
                }
                if (pendingMismatch != null) {
                    // Last file of the batch: the verdict goes out before
                    // the next batch's manifest (or BYE).
                    Wire.writeLine(out, "HASH-MISMATCH {\"path\":\""
                            + MiniJson.esc(pendingMismatch) + "\"}");
                    pendingMismatch = null;
                }
            }
            if (streamHash) {
                lastSkippedCount = ms.skippedCount;
            } else {
                hs.finish();
                lastSkippedCount = hs.skippedCount;
            }
            lastOkCount = okCount + receiverSkipped;
            lastFailCount = failures;
            lastBytesSent = sentBytes[0];
            logScanSummary(rootDir, offered);

            Wire.writeLine(out, "BYE");
            String receipt = Wire.readLine(in);
            logger.log("回执: " + receipt);
            if (receipt != null && receipt.startsWith("RECEIPT ")) {
                handleSignedReceipt(receipt.substring("RECEIPT ".length()));
            } else {
                // Without the signed receipt nothing on this phone can vouch
                // for the session's files (§6.4 stays empty) — say so loudly
                // in the (now persisted) log so the break is findable later.
                logger.log("回执未取得：BYE 后未收到签名回执（收到: "
                        + (receipt == null ? "连接已断开" : receipt)
                        + "），本轮文件在手机侧无回执凭证");
            }
            sinkFlush();
            if (offered == 0) {
                logger.log("没有可备份的文件。若 DCIM 里明明有照片，请到系统设置给本应用「所有文件访问」权限后再试。");
                return 0;
            }
        } catch (IOException e) {
            sinkFlush();
            logger.log("连接/传输失败: " + e.getMessage());
            return Math.max(failures, 1);
        }
        String tail = lastSkippedCount > 0
                ? "，另跳过 " + lastSkippedCount + " 个无法读取的文件" : "";
        logger.log((failures == 0 ? "全部文件已就位 ✔" : failures + " 个文件失败") + tail);
        return failures;
    }

    /**
     * The v2 RECEIPT carries a signed completion receipt: {receipt, signature,
     * pubkey}. Verify the ECDSA P-256 signature against the included public
     * key, persist the whole signed block to the app files dir
     * (last-receipt.json) as the phone-side proof of backup, and log the
     * verification result.
     */
    private void handleSignedReceipt(String receiptJson) {
        String doc = MiniJson.str(receiptJson, "receipt");
        String sig = MiniJson.str(receiptJson, "signature");
        String pub = MiniJson.str(receiptJson, "pubkey");
        if (doc == null || sig == null || pub == null) {
            logger.log("回执不含签名块 (receipt=" + (doc != null ? "有" : "无")
                    + " signature=" + (sig != null ? "有" : "无")
                    + " pubkey=" + (pub != null ? "有" : "无") + ")");
            return;
        }
        boolean valid = verifyEcdsa(doc, sig, pub);
        logger.log("回执签名验证: " + (valid ? "通过 ✔" : "失败 ✘"));
        if (cacheDir != null) {
            File f = new File(cacheDir, "last-receipt.json");
            String stored = "{\"receipt\":" + doc + ",\"signature\":\"" + sig
                    + "\",\"pubkey\":\"" + pub + "\"}";
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(f), StandardCharsets.UTF_8))) {
                w.write(stored);
                logger.log("签名回执已保存: " + f.getAbsolutePath());
            } catch (IOException e) {
                logger.log("回执保存失败: " + e.getMessage());
            }
            // M4c: also keep the receipt in the generalized store — but only
            // a VERIFIED receipt may vouch for a file (§6.4 deletion gate).
            if (valid) {
                ReceiptStore.save(cacheDir, stored);
                // Feed the local file index: each receipt file pins its
                // (path, size, sha256) as receipt-verified evidence.
                IndexSink s = indexSink;
                if (s != null) {
                    try {
                        for (String obj : ReceiptStore.arrayObjects(doc, "files")) {
                            String p = MiniJson.str(obj, "path");
                            String sha = MiniJson.str(obj, "sha256");
                            long sz = MiniJson.num(obj, "size", -1);
                            if (p != null && sha != null) s.onReceiptFile(p, sz, sha);
                        }
                    } catch (Throwable ignore) { /* index feed is best effort */ }
                }
            }
        }
        sinkFlush();
    }

    static boolean verifyEcdsa(String payload, String sigB64, String pubB64) {
        try {
            KeyFactory kf = KeyFactory.getInstance("EC");
            PublicKey pub = kf.generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)));
            Signature v = Signature.getInstance("SHA256withECDSA");
            v.initVerify(pub);
            v.update(payload.getBytes(StandardCharsets.UTF_8));
            return v.verify(Base64.getDecoder().decode(sigB64));
        } catch (Exception e) {
            return false;
        }
    }

    /** Phase 1 of the scan: collect candidate files (cheap metadata walk only). */
    private List<File> enumerateFiles(File root) {
        List<File> files = new ArrayList<>();
        collectFiles(root, files);
        Collections.sort(files, (a, b) -> relPath(root, a).compareTo(relPath(root, b)));
        return files;
    }

    /**
     * Metadata-only scan state for stream-hash sessions: stat only — no
     * hash cache, no content reads; entries carry an empty sha. Same
     * per-file skip tolerance and logging shape as {@link HashSession}.
     */
    private final class MetaSession {
        final int total;
        int done;
        int skippedCount;

        MetaSession(int total) {
            this.total = total;
        }

        /** Stat one enumerated file; null when it was skipped as unreadable. */
        Entry metaOne(File root, File f) {
            String path = relPath(root, f);
            Entry e = null;
            try {
                e = new Entry();
                e.path = path;
                e.size = f.length();
                e.mtime = f.lastModified();
                e.sha = ""; // unknown until the file streams (stream-hash)
            } catch (Exception ex) { // per-file stat failure -> skip
                skippedCount++;
                logger.log("跳过无法读取的文件: " + path + " (" + ex.getMessage() + ")");
                e = null;
            }
            if (e != null) sinkMeta(e);
            done++;
            if (done % 200 == 0 || done == total) {
                logger.log("扫描进度（元数据）: " + done + "/" + total);
            }
            return e;
        }
    }

    /**
     * Phase 2 state: hashes enumerated files on demand against the
     * persistent cache. Per-file failures skip just that file (see the
     * class doc) — a file that cannot be stat'ed / opened / read is
     * counted in {@link #skippedCount} and never aborts the scan. Used
     * all-at-once by {@link #scan} and incrementally by the batch
     * session, so both paths share the exact skip logging.
     */
    private final class HashSession {
        final HashCache cache;
        final int total;
        int done;
        int skippedCount;

        HashSession(File root, int total) {
            this.total = total;
            File cacheFile = cacheDir != null
                    ? new File(cacheDir, HashCache.FILE_NAME)
                    : new File(root, HashCache.FILE_NAME);
            this.cache = HashCache.load(cacheFile);
        }

        /** Hash one enumerated file; null when it was skipped as unreadable. */
        Entry hashOne(File root, File f) {
            String path = relPath(root, f);
            Entry e = null;
            try {
                e = new Entry();
                e.path = path;
                e.size = f.length();
                e.mtime = f.lastModified();
                e.sha = cache.sha256(e.path, f, e.size, e.mtime);
            } catch (Exception ex) { // per-file stat/open/read failure -> skip
                skippedCount++;
                logger.log("跳过无法读取的文件: " + path + " (" + ex.getMessage() + ")");
                e = null;
            }
            if (e != null) sinkMeta(e);
            done++;
            if (done % 200 == 0 || done == total) {
                logger.log("哈希进度: " + done + "/" + total
                        + "，新算 " + cache.hashedCount() + "，命中缓存 " + cache.hitCount());
            }
            return e;
        }

        List<Entry> hashAll(File root, List<File> files) {
            List<Entry> list = new ArrayList<>(files.size());
            for (File f : files) {
                Entry e = hashOne(root, f);
                if (e != null) list.add(e);
            }
            return list;
        }

        /** Flush the cache (best effort) and log the cache summary line. */
        void finish() {
            try {
                cache.persistIfDirty();
            } catch (IOException e) {
                // Cache persistence is bookkeeping: the hashes are all
                // computed in memory already, so losing the flush must
                // not fail the scan.
                logger.log("哈希缓存保存失败（不影响本次备份）: " + e.getMessage());
            }
            logger.log("哈希缓存: 命中 " + cache.hitCount() + "，新算 " + cache.hashedCount());
        }
    }

    public List<Entry> scan(File root) throws IOException {
        List<File> files = enumerateFiles(root);
        HashSession hs = new HashSession(root, files.size());
        List<Entry> list = hs.hashAll(root, files);
        hs.finish();
        lastSkippedCount = hs.skippedCount;
        if (hs.skippedCount > 0) {
            logger.log("扫描完成: " + list.size() + " 个文件可备份，已跳过 "
                    + hs.skippedCount + " 个无法读取的文件");
        }
        return list;
    }

    private static String relPath(File root, File f) {
        return root.toURI().relativize(f.toURI()).getPath();
    }

    private static void collectFiles(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return; // unreadable (scoped storage) — skip silently
        for (File f : kids) {
            // A single pathological entry must never abort the walk.
            try {
                if (f.isDirectory()) {
                    collectFiles(f, out);
                } else if (f.isFile()) {
                    out.add(f);
                }
            } catch (Exception ignore) { /* skip this entry/subtree */ }
        }
    }

    static void skipFully(InputStream in, long n) throws IOException {
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() == -1) throw new EOFException("file shorter than resume offset");
                skipped = 1;
            }
            left -= skipped;
        }
    }

    static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r == -1) throw new EOFException("source file shrank during send");
            out.write(buf, 0, r);
            left -= r;
        }
    }

    /** copyN variant reporting cumulative progress (M4c badges). */
    static void copyN(InputStream in, OutputStream out, long n, ProgressListener progress,
                      long[] sentBytes, long totalBytes, String currentPath) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r == -1) throw new EOFException("source file shrank during send");
            out.write(buf, 0, r);
            left -= r;
            sentBytes[0] += r;
            if (progress != null) {
                progress.onProgress(sentBytes[0], totalBytes, currentPath);
            }
        }
    }

    /** Stream-hash: read and hash n bytes WITHOUT sending (the resume
     *  prefix [0, offset) of a partially transferred file), so the digest
     *  ends up covering the whole file. */
    static void hashPrefix(InputStream in, MessageDigest md, long n) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r == -1) throw new EOFException("source file shorter than resume offset");
            md.update(buf, 0, r);
            left -= r;
        }
    }

    /** Stream-hash: copyN-with-progress that also feeds every sent byte into md. */
    static void copyNHash(InputStream in, OutputStream out, long n, MessageDigest md,
                          ProgressListener progress, long[] sentBytes, long totalBytes,
                          String currentPath) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r == -1) throw new EOFException("source file shrank during send");
            md.update(buf, 0, r);
            out.write(buf, 0, r);
            left -= r;
            sentBytes[0] += r;
            if (progress != null) {
                progress.onProgress(sentBytes[0], totalBytes, currentPath);
            }
        }
    }

    static final class Entry {
        String path; long size; long mtime; String sha;
    }
}

/**
 * Persistent SHA-256 cache (M3.5) — Android twin of the PC sender's cache.
 * One line per file: relpath TAB size TAB mtimeMillis TAB sha256hex, stored
 * as ".machang-hashcache" in the app's files dir. An entry is reused only
 * when BOTH size and mtime still match, so any edit forces a re-hash. New
 * hashes are flushed incrementally — every 100 files and at scan end — via
 * a temp file + rename, so an interrupted first hash over the 300 GB DCIM
 * library resumes where it stopped instead of starting over.
 */
final class HashCache {
    static final String FILE_NAME = ".machang-hashcache";
    private static final String TMP_NAME = FILE_NAME + ".tmp";
    private static final int FLUSH_EVERY = 100;

    private final File file;
    private final Map<String, String[]> entries = new LinkedHashMap<>(); // path -> {size, mtime, sha}
    private int hits, hashed;
    private boolean dirty;

    private HashCache(File file) { this.file = file; }

    static HashCache load(File file) {
        HashCache c = new HashCache(file);
        if (file.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.split("\t", -1);
                    if (f.length == 4) c.entries.put(f[0], new String[]{f[1], f[2], f[3]});
                }
            } catch (IOException e) {
                c.entries.clear(); // unreadable cache: start fresh
            }
        }
        return c;
    }

    String sha256(String relPath, File f, long size, long mtime) throws IOException {
        String[] e = entries.get(relPath);
        if (e != null && e[0].equals(Long.toString(size)) && e[1].equals(Long.toString(mtime))) {
            hits++;
            return e[2];
        }
        String sha = Hash.sha256(f);
        entries.put(relPath, new String[]{Long.toString(size), Long.toString(mtime), sha});
        hashed++;
        dirty = true;
        if (hashed % FLUSH_EVERY == 0) {
            // A cache-write hiccup must not be attributed to the file just
            // hashed (the scan loop skips any file whose sha256() throws).
            // dirty stays set, so the flush is retried on a later round.
            try { persist(); } catch (IOException ignore) { /* retried later */ }
        }
        return sha;
    }

    void persistIfDirty() throws IOException {
        if (dirty) persist();
    }

    void persist() throws IOException {
        File tmp = new File(file.getParentFile(), TMP_NAME);
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, String[]> e : entries.entrySet()) {
                String[] v = e.getValue();
                w.write(e.getKey()); w.write('\t');
                w.write(v[0]); w.write('\t');
                w.write(v[1]); w.write('\t');
                w.write(v[2]); w.write('\n');
            }
        }
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse to rename over an existing file.
            if (file.exists() && !file.delete()) {
                throw new IOException("cannot replace hash cache " + file);
            }
            if (!tmp.renameTo(file)) {
                throw new IOException("cannot persist hash cache " + file);
            }
        }
        dirty = false;
    }

    int hitCount() { return hits; }
    int hashedCount() { return hashed; }
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

    static String sha256(File f) throws IOException {
        MessageDigest md = digest();
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }
}

/**
 * The bare minimum of JSON for our flat control messages: string escaping on
 * the way out, and flat "key": "string" / "key": number extraction on the way
 * in. Both ends are ours and the shape is fixed, so a real parser would be
 * overkill. (Identical to the M1 sender's MiniJson.)
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
