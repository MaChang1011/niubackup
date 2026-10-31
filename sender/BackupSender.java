import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.stream.Stream;

/**
 * BackupSender — PC test sender for NiuBackup （牛备）.
 *
 * M3.5: SHA-256 results are kept in a persistent {@link HashCache} in the scanned
 * root, so repeat scans of an unchanged tree are metadata-only.
 *
 * M4a (protocol v2): when user/device/token are supplied, the sender speaks
 * protocol v2 — HELLO carries {version:2, user_id, device_id, device_token,
 * route_cost, mode}; route cost comes from --route-cost or env NB_ROUTE_COST
 * (default 1 = p2p direct). After the session it extracts the receiver's
 * signed completion receipt from the RECEIPT message, VERIFIES the ECDSA
 * P-256 signature against the included public key, and stores the signed
 * receipt JSON to a file (--receipt-out, default ./niubackup-receipt.json).
 * File-service test ops (mode "fs"): --fs-list, --fs-read &lt;path&gt; &lt;offset&gt; &lt;len&gt;,
 * --fs-thumb &lt;path&gt; (binary outputs saved to --out or a derived name).
 * Without v2 credentials it falls back to the legacy v1 HELLO.
 *
 * Batch mode: when the receiver's HELLO_ACK advertises "batch":1, a backup
 * session is incremental — enumerate once, then hash a batch (default 200
 * files / 256 MiB; override with -Dnb.batch.files / -Dnb.batch.bytes or env
 * NB_BATCH_FILES / NB_BATCH_BYTES), send that batch (BATCH-END), transfer
 * its files, continue. A wanted file that cannot be opened at its turn is
 * reported as FILE-ERROR (counts failed, session continues). Receivers
 * without the token get the single-manifest flow, unchanged.
 *
 * Stream-hash mode: when the HELLO_ACK additionally advertises
 * "streamhash":1, the scan is METADATA-ONLY (path/size/mtime, manifest
 * sha256 empty): no hash cache, no content reads before sending. Each
 * file is SHA-256'd while it streams (a resumed file's already-sent
 * prefix is hashed from local disk first), the receiver answers DONE
 * with the hash IT computed over the assembled file, and the two are
 * compared here. A mismatch is reported as HASH-MISMATCH in the slot
 * where the next FILE header would go; the receiver then deletes its
 * copy and marks the record bad. Test hook: env NB_TAMPER=1 flips one
 * byte mid-stream of the first sent file (the client hash still covers
 * the true bytes) to exercise that path — never set it outside tests.
 *
 * Usage:
 *   java BackupSender --host 10.200.0.2 [--port 28742] --dir &lt;sourceDir&gt;
 *       --user &lt;id&gt; --device-id &lt;id&gt; --token &lt;hex&gt; [--route-cost 1]
 *   java BackupSender --host ... --user ... --device-id ... --token ... --fs-list
 *   java BackupSender --host ... --user ... --device-id ... --token ... --fs-read a.jpg 10 100 [--out chunk.bin]
 * Exit code: 0 when every file is in place / the fs op succeeded, 1 otherwise.
 */
public class BackupSender {

    static final int PROTO_V1 = 1;
    static final int DEFAULT_PORT = 28741;

    // v2 identity (flags > env)
    static String optUser, optDeviceId, optToken;
    static int optRouteCost = Integer.MIN_VALUE;
    static Path optReceiptOut;
    // fs ops
    static boolean fsList = false;
    static String fsReadPath; static long fsReadOff, fsReadLen;
    static String fsThumbPath;
    static Path optOut;

    public static void main(String[] args) throws Exception {
        String host = null;
        int port = DEFAULT_PORT;
        String v1Device = "testpc";
        Path dir = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--device": v1Device = args[++i]; break;
                case "--dir": dir = Paths.get(args[++i]); break;
                case "--user": optUser = args[++i]; break;
                case "--device-id": optDeviceId = args[++i]; break;
                case "--token": optToken = args[++i]; break;
                case "--route-cost": optRouteCost = Integer.parseInt(args[++i]); break;
                case "--receipt-out": optReceiptOut = Paths.get(args[++i]); break;
                case "--out": optOut = Paths.get(args[++i]); break;
                case "--fs-list": fsList = true; break;
                case "--fs-read":
                    fsReadPath = args[++i];
                    fsReadOff = Long.parseLong(args[++i]);
                    fsReadLen = Long.parseLong(args[++i]);
                    break;
                case "--fs-thumb": fsThumbPath = args[++i]; break;
                default: break;
            }
        }
        if (optUser == null) optUser = env("NB_USER");
        if (optDeviceId == null) {
            optDeviceId = env("NB_DEVICE_ID");
            if (optDeviceId == null) optDeviceId = env("NB_DEVICE");
        }
        if (optToken == null) optToken = env("NB_TOKEN");
        if (optRouteCost == Integer.MIN_VALUE) {
            String rc = env("NB_ROUTE_COST");
            optRouteCost = rc == null ? 1 : Integer.parseInt(rc.trim());
        }
        boolean v2 = optUser != null && optDeviceId != null && optToken != null;

        boolean fsMode = fsList || fsReadPath != null || fsThumbPath != null;
        if (host == null || (fsMode && !v2) || (!fsMode && (dir == null || !Files.isDirectory(dir)))) {
            System.err.println("usage: BackupSender --host <ip> [--port 28742] --dir <sourceDir> "
                    + "--user <id> --device-id <id> --token <hex>");
            System.err.println("   or: BackupSender --host <ip> --user <id> --device-id <id> --token <hex> "
                    + "(--fs-list | --fs-read <path> <off> <len> | --fs-thumb <path>) [--out <file>]");
            System.exit(2);
        }

        int rc = fsMode
                ? runFs(host, port)
                : runBackup(host, port, v1Device, dir, v2);
        System.exit(rc);
    }

    static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? null : v;
    }

    static Socket connect(String host, int port) throws IOException {
        Socket s = new Socket(host, port);
        s.setSoTimeout(120_000);
        return s;
    }

    /** v2 HELLO for the given mode; returns the HELLO_ACK line, or null (after printing) when refused. */
    static String helloV2(InputStream in, OutputStream out, String mode) throws IOException {
        Wire.writeLine(out, "HELLO {\"version\":2,\"user_id\":\"" + MiniJson.esc(optUser)
                + "\",\"device_id\":\"" + MiniJson.esc(optDeviceId)
                + "\",\"device_token\":\"" + MiniJson.esc(optToken)
                + "\",\"route_cost\":" + optRouteCost
                + ",\"mode\":\"" + mode + "\"}");
        String resp = Wire.readLine(in);
        if (resp != null && resp.startsWith("HELLO_ACK")) return resp;
        System.err.println("handshake refused: " + resp);
        return null;
    }

    // ---------------------------------------------------------------- backup

    static int runBackup(String host, int port, String v1Device, Path dir, boolean v2) throws Exception {
        if (v2) {
            // HELLO first: the HELLO_ACK decides the session shape. When it
            // advertises "batch":1 the scan runs incrementally (enumerate,
            // then hash a batch -> send that batch -> continue) so the first
            // files land while the rest of the library is still hashing.
            // Otherwise the pre-batch single-manifest flow runs unchanged.
            try (Socket s = connect(host, port)) {
                InputStream in = s.getInputStream();
                OutputStream out = new BufferedOutputStream(s.getOutputStream());
                System.out.println("protocol v2: user=" + optUser + " device=" + optDeviceId
                        + " route_cost=" + optRouteCost);
                String ack = helloV2(in, out, "backup");
                if (ack == null) return 1;
                if (MiniJson.num(ack, "batch", 0) == 1) {
                    boolean streamHash = MiniJson.num(ack, "streamhash", 0) == 1;
                    return runBatchBackup(in, out, dir, streamHash);
                }
                List<Entry> entries = scan(dir);
                System.out.println("scanned " + entries.size() + " files under " + dir);
                return singleManifestBackup(in, out, dir, entries, true);
            }
        }
        List<Entry> entries = scan(dir);
        System.out.println("scanned " + entries.size() + " files under " + dir);
        try (Socket s = connect(host, port)) {
            InputStream in = s.getInputStream();
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            Wire.writeLine(out, "HELLO " + v1Device + " " + PROTO_V1);
            String ok = Wire.readLine(in);
            if (ok == null || !ok.startsWith("OK")) {
                System.err.println("handshake refused: " + ok);
                return 1;
            }
            return singleManifestBackup(in, out, dir, entries, false);
        }
    }

    /** The pre-batch session body: one manifest, decisions, transfers, BYE, receipt. */
    static int singleManifestBackup(InputStream in, OutputStream out, Path dir,
                                    List<Entry> entries, boolean v2) throws Exception {
        int failures = 0;
        for (Entry e : entries) {
            Wire.writeLine(out, "{\"path\":\"" + MiniJson.esc(e.path) + "\",\"size\":" + e.size
                    + ",\"mtime\":" + e.mtime + ",\"sha256\":\"" + e.sha + "\"}");
        }
        Wire.writeLine(out, "MANIFEST-END");

        Map<String, Long> wanted = readDecisions(in);
        int skipped = entries.size() - wanted.size();
        System.out.println("receiver wants " + wanted.size() + " files, skips " + skipped);

        Map<String, Entry> byPath = new HashMap<>();
        for (Entry e : entries) byPath.put(e.path, e);
        for (Map.Entry<String, Long> w : wanted.entrySet()) {
            Entry e = byPath.get(w.getKey());
            long offset = w.getValue();
            long bytes = e.size - offset;
            Wire.writeLine(out, "FILE {\"path\":\"" + MiniJson.esc(e.path)
                    + "\",\"bytes\":" + bytes + "}");
            try (InputStream fis = Files.newInputStream(dir.resolve(e.path))) {
                skipFully(fis, offset);
                copyN(fis, out, bytes);
            }
            out.flush();
            String res = readVerdict(in);
            if (res != null && res.startsWith("DONE")) {
                System.out.println("  sent " + e.path + " (" + bytes + " bytes from offset " + offset + ")");
            } else {
                failures++;
                System.out.println("  FAILED " + e.path + " -> " + res);
            }
        }

        Wire.writeLine(out, "BYE");
        if (v2 && readReceiptsAfterBye(in)) failures++;
        System.out.println(failures == 0 ? "all files in place" : failures + " file(s) failed");
        return failures == 0 ? 0 : 1;
    }

    /** Decision lines up to READY: {path -> resume offset} for the files to send. */
    static Map<String, Long> readDecisions(InputStream in) throws IOException {
        Map<String, Long> wanted = new LinkedHashMap<>();
        String line;
        while ((line = Wire.readLine(in)) != null) {
            if (line.equals("READY")) break;
            if (line.startsWith("RECEIPT ")) {
                // Per-file receipts ride along with the decisions (skip
                // evidence): verify + store like any signed receipt.
                try {
                    handleSignedReceipt(line.substring(8));
                } catch (Exception e) {
                    System.out.println("  per-file receipt error: " + e);
                }
                continue;
            }
            String p = MiniJson.str(line, "path");
            long off = MiniJson.num(line, "offset", -1);
            if (p != null && off >= 0) wanted.put(p, off);
        }
        return wanted;
    }

    /** Per-file verdict read: process interleaved per-file RECEIPT lines
     *  and return the first DONE/FAIL line. */
    static String readVerdict(InputStream in) throws Exception {
        String line;
        while ((line = Wire.readLine(in)) != null) {
            if (line.startsWith("RECEIPT ")) {
                handleSignedReceipt(line.substring(8));
                continue;
            }
            return line;
        }
        return null;
    }

    /** Post-BYE: process every RECEIPT line (trailing per-file receipts,
     *  then the whole-session receipt) until the receiver closes.
     *  Returns true when any receipt failed verification. */
    static boolean readReceiptsAfterBye(InputStream in) throws Exception {
        boolean invalid = false;
        String line;
        while ((line = Wire.readLine(in)) != null) {
            if (!line.startsWith("RECEIPT ")) {
                System.out.println("receipt: " + line);
                break;
            }
            if (!handleSignedReceipt(line.substring(8))) invalid = true;
        }
        return invalid;
    }

    // ------------------------------------------------------- batch mode (v2)

    static final int DEFAULT_BATCH_FILES = 200;
    static final long DEFAULT_BATCH_BYTES = 256L * 1024 * 1024;

    /** Batch file-count limit: -Dnb.batch.files > env NB_BATCH_FILES > default. */
    static int batchFilesLimit() {
        String v = System.getProperty("nb.batch.files");
        if (v == null) v = env("NB_BATCH_FILES");
        if (v != null) {
            try {
                int n = Integer.parseInt(v.trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignore) { }
        }
        return DEFAULT_BATCH_FILES;
    }

    /** Batch byte limit: -Dnb.batch.bytes > env NB_BATCH_BYTES > default. */
    static long batchBytesLimit() {
        String v = System.getProperty("nb.batch.bytes");
        if (v == null) v = env("NB_BATCH_BYTES");
        if (v != null) {
            try {
                long n = Long.parseLong(v.trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignore) { }
        }
        return DEFAULT_BATCH_BYTES;
    }

    /**
     * Batch session (v2 + receiver advertised "batch":1): enumerate once,
     * then loop — hash up to the batch limit, send that batch's manifest
     * (BATCH-END), read its decisions, send its files — until the library
     * is exhausted; the receiver signs per-file receipts as files verify,
     * and BYE closes the session with a whole-session signed receipt too.
     * A wanted file that cannot
     * be opened when its turn comes is reported as FILE-ERROR instead of
     * the FILE header (it may have vanished between scan and send) and
     * counts failed without aborting the session. With streamHash (the
     * receiver also advertised "streamhash":1) the per-batch "hash" step
     * is metadata-only; see the class doc.
     */
    static int runBatchBackup(InputStream in, OutputStream out, Path dir,
                              boolean streamHash) throws Exception {
        List<Path> files = enumerate(dir);
        System.out.println("batch mode: enumerated " + files.size() + " files under " + dir
                + " (batch limit " + batchFilesLimit() + " files / " + batchBytesLimit() + " bytes)");
        if (streamHash) {
            System.out.println("stream-hash mode: metadata-only scan — no hash cache, "
                    + "no content reads before sending; files are hashed while streaming");
        }
        ScanState st = new ScanState(dir, files.size(), streamHash);
        String pendingMismatch = null; // stream-hash verdict owed for the previous file
        boolean[] tamperArmed = { streamHash && env("NB_TAMPER") != null };
        int filesLimit = batchFilesLimit();
        long bytesLimit = batchBytesLimit();
        int idx = 0, batchNo = 0, failures = 0;
        while (idx < files.size()) {
            List<Entry> batch = new ArrayList<>();
            long batchBytes = 0;
            while (idx < files.size() && batch.size() < filesLimit
                    && (batch.isEmpty() || batchBytes < bytesLimit)) {
                Entry e = streamHash ? st.metaOne(files.get(idx++)) : st.hashOne(files.get(idx++));
                if (e != null) {
                    batch.add(e);
                    batchBytes += e.size;
                }
            }
            if (batch.isEmpty()) continue; // every file in this slice was skipped as unreadable
            batchNo++;
            for (Entry e : batch) {
                Wire.writeLine(out, "{\"path\":\"" + MiniJson.esc(e.path) + "\",\"size\":" + e.size
                        + ",\"mtime\":" + e.mtime + ",\"sha256\":\"" + e.sha + "\"}");
            }
            Wire.writeLine(out, "BATCH-END");

            Map<String, Long> wanted = readDecisions(in);
            System.out.println("batch " + batchNo + ": " + batch.size() + " files, receiver wants "
                    + wanted.size() + ", skips " + (batch.size() - wanted.size()));

            Map<String, Entry> byPath = new HashMap<>();
            for (Entry e : batch) byPath.put(e.path, e);
            for (Map.Entry<String, Long> w : wanted.entrySet()) {
                Entry e = byPath.get(w.getKey());
                long offset = w.getValue();
                long bytes = e.size - offset;
                if (pendingMismatch != null) {
                    // The verdict for the PREVIOUS file goes in the slot
                    // where this file's header would go (no reply awaited).
                    Wire.writeLine(out, "HASH-MISMATCH {\"path\":\""
                            + MiniJson.esc(pendingMismatch) + "\"}");
                    pendingMismatch = null;
                }
                InputStream fis;
                try {
                    fis = Files.newInputStream(dir.resolve(e.path));
                    try {
                        // Stream-hash reads the prefix back for hashing
                        // during the send; only hashed mode skips blindly.
                        if (!streamHash) skipFully(fis, offset);
                    } catch (IOException skipErr) {
                        try { fis.close(); } catch (IOException ignore) { }
                        throw skipErr;
                    }
                } catch (IOException openErr) {
                    // Open BEFORE the FILE header is written, so a file that
                    // vanished since the scan degrades to FILE-ERROR and the
                    // batch continues (the receiver expects no bytes and no
                    // reply is awaited).
                    Wire.writeLine(out, "FILE-ERROR {\"path\":\"" + MiniJson.esc(e.path)
                            + "\",\"error\":\"" + MiniJson.esc(String.valueOf(openErr.getMessage())) + "\"}");
                    failures++;
                    System.out.println("  FILE-ERROR " + e.path + " -> " + openErr.getMessage());
                    continue;
                }
                Wire.writeLine(out, "FILE {\"path\":\"" + MiniJson.esc(e.path)
                        + "\",\"bytes\":" + bytes + "}");
                String clientSha = null;
                try (InputStream f = fis) {
                    if (streamHash) {
                        MessageDigest md = Hash.digest();
                        if (offset > 0) hashPrefix(f, md, offset);
                        if (tamperArmed[0] && bytes >= 2) {
                            tamperArmed[0] = false;
                            copyNHashTamper(f, out, bytes, md, bytes / 2);
                        } else {
                            copyNHash(f, out, bytes, md);
                        }
                        clientSha = Hash.hex(md.digest());
                    } else {
                        copyN(f, out, bytes);
                    }
                }
                out.flush();
                String res = readVerdict(in);
                if (res != null && res.startsWith("DONE")) {
                    String rsha = streamHash ? MiniJson.str(res, "sha256") : null;
                    if (streamHash && (rsha == null || !rsha.equals(clientSha))) {
                        failures++;
                        pendingMismatch = e.path;
                        System.out.println("  HASH-MISMATCH " + e.path + ": client sha256="
                                + clientSha + " receiver sha256=" + rsha);
                    } else {
                        System.out.println("  sent " + e.path + " (" + bytes + " bytes from offset "
                                + offset + ")" + (streamHash ? ", stream-hash verified" : ""));
                    }
                } else {
                    failures++;
                    System.out.println("  FAILED " + e.path + " -> " + res);
                }
            }
            if (pendingMismatch != null) {
                // Last file of the batch: the verdict goes out before the
                // next batch's manifest (or BYE); the receiver consumes it
                // as the first line of the next round.
                Wire.writeLine(out, "HASH-MISMATCH {\"path\":\""
                        + MiniJson.esc(pendingMismatch) + "\"}");
                pendingMismatch = null;
            }
        }
        st.finish();

        Wire.writeLine(out, "BYE");
        if (readReceiptsAfterBye(in)) failures++;
        System.out.println(failures == 0 ? "all files in place" : failures + " file(s) failed");
        return failures == 0 ? 0 : 1;
    }

    /** Verify + persist the signed completion receipt. Returns false when invalid/absent. */
    static boolean handleSignedReceipt(String receiptJsonLine) throws Exception {
        String doc = MiniJson.str(receiptJsonLine, "receipt");
        String sig = MiniJson.str(receiptJsonLine, "signature");
        String pub = MiniJson.str(receiptJsonLine, "pubkey");
        if (doc == null || sig == null || pub == null) {
            System.out.println("receipt signature: ABSENT (no signed block)");
            return false;
        }
        boolean valid = verifyEcdsa(doc, sig, pub);
        Path outFile = optReceiptOut != null ? optReceiptOut : Paths.get("niubackup-receipt.json");
        String stored = "{\"receipt\":" + doc + ",\"signature\":\"" + sig
                + "\",\"pubkey\":\"" + pub + "\"}";
        Files.writeString(outFile, stored, StandardCharsets.UTF_8);
        System.out.println("receipt signature: " + (valid ? "VALID" : "INVALID")
                + " (stored to " + outFile + ")");
        return valid;
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
            System.out.println("receipt verify error: " + e);
            return false;
        }
    }

    // ---------------------------------------------------------------- file service

    static int runFs(String host, int port) throws Exception {
        try (Socket s = connect(host, port)) {
            InputStream in = s.getInputStream();
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            if (helloV2(in, out, "fs") == null) return 1;

            if (fsList) {
                Wire.writeLine(out, "{\"op\":\"LIST\"}");
                String resp = Wire.readLine(in);
                System.out.println("LIST: " + resp);
                return resp != null && resp.contains("\"ok\":true") ? 0 : 1;
            }
            if (fsReadPath != null) {
                Wire.writeLine(out, "{\"op\":\"READ\",\"path\":\"" + MiniJson.esc(fsReadPath)
                        + "\",\"offset\":" + fsReadOff + ",\"len\":" + fsReadLen + "}");
                String resp = Wire.readLine(in);
                String data = resp == null ? null : MiniJson.str(resp, "data");
                if (data == null) {
                    System.err.println("READ failed: " + resp);
                    return 1;
                }
                byte[] bytes = Base64.getDecoder().decode(data);
                Path outFile = optOut != null ? optOut
                        : Paths.get(baseName(fsReadPath) + ".chunk");
                Files.write(outFile, bytes);
                System.out.println("READ " + fsReadPath + " offset=" + fsReadOff
                        + " -> " + bytes.length + " bytes written to " + outFile
                        + " sha256=" + Hash.hex(sha256Bytes(bytes)));
                return 0;
            }
            // fsThumbPath
            Wire.writeLine(out, "{\"op\":\"THUMB\",\"path\":\"" + MiniJson.esc(fsThumbPath) + "\"}");
            String resp = Wire.readLine(in);
            String thumb = resp == null ? null : MiniJson.str(resp, "thumb");
            if (thumb == null) {
                System.out.println("THUMB " + fsThumbPath + " -> no thumbnail available");
                return 0;
            }
            byte[] bytes = Base64.getDecoder().decode(thumb);
            Path outFile = optOut != null ? optOut
                    : Paths.get(baseName(fsThumbPath) + ".thumb.jpg");
            Files.write(outFile, bytes);
            System.out.println("THUMB " + fsThumbPath + " -> " + bytes.length
                    + " bytes written to " + outFile);
            return 0;
        }
    }

    static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    static byte[] sha256Bytes(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ---------------------------------------------------------------- scan (M3.5)

    /** Phase 1 of the scan: the sorted metadata walk (cheap; no file content is read). */
    static List<Path> enumerate(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !HashCache.isCacheArtifact(root, p))
                .sorted()
                .forEach(files::add);
        }
        return files;
    }

    /**
     * Phase 2 state: hashes files on demand against the persistent cache.
     * Per-file tolerance (2026-10-31, mirrored from the Android client): a
     * file that cannot be stat'ed / opened / read (unreadable entry, file
     * deleted mid-scan) is SKIPPED and counted instead of aborting the
     * whole scan. Skipped files are simply absent from the manifest.
     * Used all-at-once by {@link #scan} and incrementally by batch mode.
     */
    static final class ScanState {
        final Path root;
        final HashCache cache;
        final int total;
        int done;
        int skippedCount;

        ScanState(Path root, int total) {
            this(root, total, false);
        }

        /** metadataOnly (stream-hash sessions): no cache is loaded at all. */
        ScanState(Path root, int total, boolean metadataOnly) {
            this.root = root;
            this.total = total;
            this.cache = metadataOnly ? null : HashCache.load(root.resolve(HashCache.FILE_NAME));
        }

        /** Metadata-only twin of hashOne for stream-hash sessions: stat
         *  only — no cache, no content reads. Same per-file skip tolerance. */
        Entry metaOne(Path p) {
            String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
            Entry e = null;
            try {
                e = new Entry();
                e.path = rel;
                e.size = Files.size(p);
                e.mtime = Files.getLastModifiedTime(p).toMillis();
                e.sha = ""; // unknown until the file streams (stream-hash)
            } catch (Exception ex) { // per-file stat failure -> skip
                skippedCount++;
                System.out.println("  skipped unreadable file: " + rel
                        + " (" + ex.getMessage() + ")");
                e = null;
            }
            done++;
            if (done % 200 == 0 || done == total) {
                System.out.println("  scan progress (metadata): " + done + "/" + total);
            }
            return e;
        }

        /** Hash one enumerated file; null when it was skipped as unreadable. */
        Entry hashOne(Path p) {
            String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
            Entry e = null;
            try {
                e = new Entry();
                e.path = rel;
                e.size = Files.size(p);
                e.mtime = Files.getLastModifiedTime(p).toMillis();
                e.sha = cache.sha256(e.path, p, e.size, e.mtime);
            } catch (Exception ex) { // per-file stat/open/read failure -> skip
                skippedCount++;
                System.out.println("  skipped unreadable file: " + rel
                        + " (" + ex.getMessage() + ")");
                e = null;
            }
            done++;
            if (done % 200 == 0 || done == total) {
                System.out.println("  hash progress: " + done + "/" + total
                        + ", hashed " + cache.hashedCount() + ", cache hits " + cache.hitCount());
            }
            return e;
        }

        /** Flush the cache and print the scan summary lines. */
        void finish() {
            if (cache == null) { // metadata-only (stream-hash) scan
                if (skippedCount > 0) {
                    System.out.println("scan skipped " + skippedCount + " unreadable file(s)");
                }
                return;
            }
            try {
                cache.persistIfDirty();
            } catch (IOException ex) {
                // Bookkeeping only — the in-memory hashes are unaffected.
                System.out.println("hash cache persist failed (backup continues): "
                        + ex.getMessage());
            }
            System.out.println("hash cache: " + cache.hitCount() + " hits, "
                    + cache.hashedCount() + " newly hashed (" + cache.file() + ")");
            if (skippedCount > 0) {
                System.out.println("scan skipped " + skippedCount + " unreadable file(s)");
            }
        }
    }

    static List<Entry> scan(Path root) throws IOException {
        List<Path> files = enumerate(root);
        ScanState st = new ScanState(root, files.size());
        List<Entry> list = new ArrayList<>(files.size());
        for (Path p : files) {
            Entry e = st.hashOne(p);
            if (e != null) list.add(e);
        }
        st.finish();
        return list;
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

    /** Stream-hash: copyN that also feeds every sent byte into md. */
    static void copyNHash(InputStream in, OutputStream out, long n,
                          MessageDigest md) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r == -1) throw new EOFException("source file shrank during send");
            md.update(buf, 0, r);
            out.write(buf, 0, r);
            left -= r;
        }
    }

    /**
     * TEST HOOK (NB_TAMPER): like copyNHash, but flips one byte of the
     * stream at flipAt (offset within the sent range) WITHOUT telling the
     * digest — md still covers the true bytes, so the receiver's hash of
     * what actually arrived differs and the HASH-MISMATCH path fires.
     */
    static void copyNHashTamper(InputStream in, OutputStream out, long n,
                                MessageDigest md, long flipAt) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = n, pos = 0;
        boolean flipped = false;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r == -1) throw new EOFException("source file shrank during send");
            md.update(buf, 0, r); // the client hash always covers the TRUE bytes
            if (!flipped && flipAt >= pos && flipAt < pos + r) {
                byte[] mut = buf.clone();
                mut[(int) (flipAt - pos)] ^= 0xFF;
                out.write(mut, 0, r);
                flipped = true;
                System.out.println("  [NB_TAMPER] flipped one byte at sent-range offset " + flipAt);
            } else {
                out.write(buf, 0, r);
            }
            pos += r;
            left -= r;
        }
    }

    static final class Entry {
        String path; long size; long mtime; String sha;
    }
}

/**
 * Persistent SHA-256 cache (M3.5). One line per file:
 *   relpath TAB size TAB mtimeMillis TAB sha256hex
 * stored as ".machang-hashcache" in the scanned root. An entry is reused only
 * when BOTH size and mtime still match the file on disk, so any edit (which
 * bumps mtime) forces a re-hash. Newly computed hashes are flushed
 * incrementally — every 100 files and at scan end — via temp file + atomic
 * rename, so an interrupted first hash over a huge library resumes where it
 * stopped instead of starting over.
 */
final class HashCache {
    static final String FILE_NAME = ".machang-hashcache";
    private static final String TMP_NAME = FILE_NAME + ".tmp";
    private static final int FLUSH_EVERY = 100;

    private final Path file;
    private final Map<String, String[]> entries = new LinkedHashMap<>(); // path -> {size, mtime, sha}
    private int hits, hashed;
    private boolean dirty;

    private HashCache(Path file) { this.file = file; }

    static HashCache load(Path file) {
        HashCache c = new HashCache(file);
        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] f = line.split("\t", -1);
                    if (f.length == 4) c.entries.put(f[0], new String[]{f[1], f[2], f[3]});
                }
            } catch (IOException e) {
                System.err.println("hash cache unreadable, starting fresh: " + e.getMessage());
                c.entries.clear();
            }
        }
        return c;
    }

    /** The cache and its temp sibling must never be offered in the manifest. */
    static boolean isCacheArtifact(Path root, Path p) {
        Path parent = p.getParent();
        if (parent == null || !parent.equals(root)) return false;
        String name = p.getFileName().toString();
        return name.equals(FILE_NAME) || name.equals(TMP_NAME);
    }

    String sha256(String relPath, Path p, long size, long mtime) throws IOException {
        String[] e = entries.get(relPath);
        if (e != null && e[0].equals(Long.toString(size)) && e[1].equals(Long.toString(mtime))) {
            hits++;
            return e[2];
        }
        String sha = Hash.sha256(p);
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
        Path tmp = file.resolveSibling(TMP_NAME);
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String[]> e : entries.entrySet()) {
                String[] v = e.getValue();
                w.write(e.getKey()); w.write('\t');
                w.write(v[0]); w.write('\t');
                w.write(v[1]); w.write('\t');
                w.write(v[2]); w.write('\n');
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        dirty = false;
    }

    int hitCount() { return hits; }
    int hashedCount() { return hashed; }
    Path file() { return file; }
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
