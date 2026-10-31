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
        this.logger = logger;
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

    /** Runs one backup session for {@code rootDir}. Returns the number of failed files. */
    public int runBackup(File rootDir) {
        return runBackup(DEFAULT_HOST, DEFAULT_PORT, DEVICE_NAME, rootDir, null);
    }

    public int runBackup(String host, int port, String device, File rootDir) {
        return runBackup(host, port, device, rootDir, null);
    }

    public int runBackup(String host, int port, String device, File rootDir,
                         ProgressListener progress) {
        List<Entry> entries;
        try {
            entries = scan(rootDir);
        } catch (IOException e) {
            logger.log("扫描目录失败: " + e.getMessage());
            return 1;
        }
        logger.log("已扫描 " + entries.size() + " 个文件 (" + rootDir.getAbsolutePath() + ")");
        if (lastSkippedCount > 0) {
            logger.log("已跳过 " + lastSkippedCount + " 个无法读取的文件（逐条见上方扫描日志）");
        }
        if (entries.isEmpty()) {
            logger.log("没有可备份的文件。若 DCIM 里明明有照片，请到系统设置给本应用「所有文件访问」权限后再试。");
            return 0;
        }

        int failures = 0;
        Socket rawSocket = new Socket();
        try (Socket s = rawSocket) {
            rawSocket.connect(new java.net.InetSocketAddress(host, port), 10_000);
            s.setSoTimeout(120_000);
            InputStream in = s.getInputStream();
            OutputStream out = new BufferedOutputStream(s.getOutputStream());

            if (v2()) {
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
            } else {
                Wire.writeLine(out, "HELLO " + device + " " + PROTO);
                String ok = Wire.readLine(in);
                if (ok == null || !ok.startsWith("OK")) {
                    logger.log("握手被拒绝: " + ok);
                    return 1;
                }
            }

            for (Entry e : entries) {
                Wire.writeLine(out, "{\"path\":\"" + MiniJson.esc(e.path) + "\",\"size\":" + e.size
                        + ",\"mtime\":" + e.mtime + ",\"sha256\":\"" + e.sha + "\"}");
            }
            Wire.writeLine(out, "MANIFEST-END");

            // Receiver replies: one {"path":...,"offset":N} per wanted file, then READY.
            Map<String, Long> wanted = new LinkedHashMap<>();
            String line;
            while ((line = Wire.readLine(in)) != null) {
                if (line.equals("READY")) break;
                String p = MiniJson.str(line, "path");
                long off = MiniJson.num(line, "offset", -1);
                if (p != null && off >= 0) wanted.put(p, off);
            }
            int skipped = entries.size() - wanted.size();
            logger.log("接收端需要 " + wanted.size() + " 个文件，跳过 " + skipped + " 个");

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
            }
        } catch (IOException e) {
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
            logger.log("回执不含签名块");
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
            if (valid) ReceiptStore.save(cacheDir, stored);
        }
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

    public List<Entry> scan(File root) throws IOException {
        lastSkippedCount = 0;
        List<String> skipped = new ArrayList<>();
        // Phase 1: collect files only (cheap metadata walk; unreadable dirs
        // under scoped storage are skipped silently, as before).
        List<File> files = new ArrayList<>();
        collectFiles(root, files);
        Collections.sort(files, (a, b) -> relPath(root, a).compareTo(relPath(root, b)));
        // Phase 2: hash only what the persistent cache cannot vouch for.
        // Per-file failures land here: stat/open/read of ONE file (an
        // EACCES .trashed-* entry, a file deleted after the walk) skips
        // just that file — it must never abort the whole scan/session.
        // Only scan-level failures (the root itself, the cache load) throw.
        File cacheFile = cacheDir != null
                ? new File(cacheDir, HashCache.FILE_NAME)
                : new File(root, HashCache.FILE_NAME);
        HashCache cache = HashCache.load(cacheFile);
        List<Entry> list = new ArrayList<>(files.size());
        int total = files.size(), done = 0;
        for (File f : files) {
            String path = relPath(root, f);
            try {
                Entry e = new Entry();
                e.path = path;
                e.size = f.length();
                e.mtime = f.lastModified();
                e.sha = cache.sha256(e.path, f, e.size, e.mtime);
                list.add(e);
            } catch (Exception ex) { // per-file stat/open/read failure -> skip
                skipped.add(path);
                logger.log("跳过无法读取的文件: " + path + " (" + ex.getMessage() + ")");
            }
            done++;
            if (done % 200 == 0 || done == total) {
                logger.log("哈希进度: " + done + "/" + total
                        + "，新算 " + cache.hashedCount() + "，命中缓存 " + cache.hitCount());
            }
        }
        try {
            cache.persistIfDirty();
        } catch (IOException e) {
            // Cache persistence is bookkeeping: the hashes are all computed
            // in memory already, so losing the flush must not fail the scan.
            logger.log("哈希缓存保存失败（不影响本次备份）: " + e.getMessage());
        }
        logger.log("哈希缓存: 命中 " + cache.hitCount() + "，新算 " + cache.hashedCount());
        lastSkippedCount = skipped.size();
        if (!skipped.isEmpty()) {
            logger.log("扫描完成: " + list.size() + " 个文件可备份，已跳过 "
                    + skipped.size() + " 个无法读取的文件");
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
    static String sha256(File f) throws IOException {
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException(e); }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        byte[] d = md.digest();
        StringBuilder b = new StringBuilder(d.length * 2);
        for (byte x : d) {
            b.append(Character.forDigit((x >> 4) & 0xF, 16));
            b.append(Character.forDigit(x & 0xF, 16));
        }
        return b.toString();
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
