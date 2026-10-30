import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/**
 * BackupSender — test sender for the EasyTier backup project (Route B, M1 skeleton).
 *
 * Dependency-free (JDK stdlib only). Walks a source directory, hashes every file
 * (SHA-256), offers the manifest to a BackupReceiver, and sends only the files the
 * receiver asks for, starting at the resume offset it offers. In M2 this logic
 * moves into the Android client; here it exists to exercise the protocol PC-to-PC.
 *
 * M3.5: SHA-256 results are kept in a persistent {@link HashCache} in the scanned
 * root, so repeat scans of an unchanged tree are metadata-only. The wire protocol
 * is unchanged (v1) — same HELLO / manifest / RECEIPT exchange.
 *
 * Usage: java BackupSender --host 10.200.0.2 [--port 28741] --device testpc --dir <sourceDir>
 * Exit code: 0 when every file is in place on the receiver, 1 otherwise.
 */
public class BackupSender {

    static final int PROTO = 1;
    static final int DEFAULT_PORT = 28741;

    public static void main(String[] args) throws Exception {
        String host = null, device = "testpc";
        int port = DEFAULT_PORT;
        Path dir = null;
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--host")) host = args[i + 1];
            else if (args[i].equals("--port")) port = Integer.parseInt(args[i + 1]);
            else if (args[i].equals("--device")) device = args[i + 1];
            else if (args[i].equals("--dir")) dir = Paths.get(args[i + 1]);
        }
        if (host == null || dir == null || !Files.isDirectory(dir)) {
            System.err.println("usage: java BackupSender --host <ip> [--port 28741] "
                    + "--device <name> --dir <sourceDir>");
            System.exit(2);
        }

        List<Entry> entries = scan(dir);
        System.out.println("scanned " + entries.size() + " files under " + dir);

        int failures = 0;
        try (Socket s = new Socket(host, port)) {
            s.setSoTimeout(120_000);
            InputStream in = s.getInputStream();
            OutputStream out = new BufferedOutputStream(s.getOutputStream());

            Wire.writeLine(out, "HELLO " + device + " " + PROTO);
            String ok = Wire.readLine(in);
            if (ok == null || !ok.startsWith("OK")) {
                System.err.println("handshake refused: " + ok);
                System.exit(1);
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
                String res = Wire.readLine(in);
                if (res != null && res.startsWith("DONE")) {
                    System.out.println("  sent " + e.path + " (" + bytes + " bytes from offset " + offset + ")");
                } else {
                    failures++;
                    System.out.println("  FAILED " + e.path + " -> " + res);
                }
            }

            Wire.writeLine(out, "BYE");
            String receipt = Wire.readLine(in);
            System.out.println("receipt: " + receipt);
        }
        System.out.println(failures == 0 ? "all files in place" : failures + " file(s) failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    static List<Entry> scan(Path root) throws IOException {
        // Phase 1: metadata-only walk (cheap). Our own cache files live in the
        // root and must never be offered in the manifest.
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !HashCache.isCacheArtifact(root, p))
                .sorted()
                .forEach(files::add);
        }
        // Phase 2: hash only what the cache cannot vouch for.
        HashCache cache = HashCache.load(root.resolve(HashCache.FILE_NAME));
        List<Entry> list = new ArrayList<>(files.size());
        int total = files.size(), done = 0;
        for (Path p : files) {
            Entry e = new Entry();
            e.path = root.relativize(p).toString().replace(File.separatorChar, '/');
            e.size = Files.size(p);
            e.mtime = Files.getLastModifiedTime(p).toMillis();
            e.sha = cache.sha256(e.path, p, e.size, e.mtime);
            list.add(e);
            done++;
            if (done % 200 == 0 || done == total) {
                System.out.println("  hash progress: " + done + "/" + total
                        + ", hashed " + cache.hashedCount() + ", cache hits " + cache.hitCount());
            }
        }
        cache.persistIfDirty();
        System.out.println("hash cache: " + cache.hitCount() + " hits, "
                + cache.hashedCount() + " newly hashed (" + cache.file() + ")");
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
        if (hashed % FLUSH_EVERY == 0) persist();
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
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IllegalStateException(e); }
        try (InputStream in = Files.newInputStream(p)) {
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
