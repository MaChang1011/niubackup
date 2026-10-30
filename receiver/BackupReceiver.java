import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalTime;
import java.util.*;

/**
 * BackupReceiver — PC-side receiver for the EasyTier backup project (Route B, M1 skeleton).
 *
 * Dependency-free (JDK stdlib only). Listens on a TCP port and serves one backup
 * session at a time. Files land in <target>/<device>/<relpath>.
 *
 * Wire protocol (control lines are UTF-8, '\n'-terminated; file payloads are raw):
 *   C->S  HELLO <device> <protoVersion>
 *   S->C  OK <protoVersion>                      (or ERR <msg>)
 *   C->S  {"path":"...","size":N,"mtime":N,"sha256":"..."}   one per file
 *   C->S  MANIFEST-END
 *   S->C  {"path":"...","offset":N}              one per file it wants (others are skipped)
 *   S->C  READY
 *   C->S  FILE {"path":"...","bytes":N}          then exactly N raw bytes (stop-and-wait)
 *   S->C  DONE {"path":"..."}  |  FAIL {"path":"...","error":"..."}
 *   C->S  BYE
 *   S->C  RECEIPT {"ok":[...],"skipped":[...],"failed":[...]}
 *
 * Resume: bytes are appended to <dest>.part; the offset offered in the manifest
 * phase is the current .part length. On completion the .part is SHA-256 verified
 * and atomically renamed into place. A .part that is already complete and hash-
 * valid is promoted without any transfer.
 *
 * Usage: java BackupReceiver [--port 28741] [--target ~/PhoneBackup] [--bind 10.200.0.2]
 * Env fallbacks: BACKUP_PORT, BACKUP_DIR, BACKUP_BIND.
 * NOTE (M3): no authentication and no TLS — v1 trust is EasyTier mesh membership.
 */
public class BackupReceiver {

    static final int PROTO = 1;
    static final int DEFAULT_PORT = 28741;

    final int port;
    final Path targetRoot;
    final String bind;

    public BackupReceiver(int port, Path targetRoot, String bind) {
        this.port = port;
        this.targetRoot = targetRoot;
        this.bind = bind;
    }

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String envPort = System.getenv("BACKUP_PORT");
        if (envPort != null) port = Integer.parseInt(envPort.trim());
        Path target = Paths.get(System.getProperty("user.home"), "PhoneBackup");
        String envDir = System.getenv("BACKUP_DIR");
        if (envDir != null && !envDir.isEmpty()) target = Paths.get(envDir);
        String bind = System.getenv("BACKUP_BIND");

        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--port")) port = Integer.parseInt(args[i + 1]);
            else if (args[i].equals("--target")) target = Paths.get(args[i + 1]);
            else if (args[i].equals("--bind")) bind = args[i + 1];
        }
        new BackupReceiver(port, target, bind).serve();
    }

    void serve() throws IOException {
        Files.createDirectories(targetRoot);
        InetAddress addr = (bind == null || bind.isEmpty()) ? null : InetAddress.getByName(bind);
        try (ServerSocket ss = new ServerSocket(port, 50, addr)) {
            log("listening on " + (addr == null ? "*" : addr.getHostAddress()) + ":" + port
                    + ", target dir " + targetRoot);
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

    void handle(Socket s) throws IOException {
        s.setSoTimeout(120_000);
        InputStream in = s.getInputStream();
        OutputStream out = new BufferedOutputStream(s.getOutputStream());

        // ---- handshake ----
        String hello = Wire.readLine(in);
        if (hello == null || !hello.startsWith("HELLO ")) {
            Wire.writeLine(out, "ERR expected HELLO");
            return;
        }
        String[] hp = hello.split(" ");
        String device = sanitizeDevice(hp.length > 1 ? hp[1] : "unknown");
        int clientProto = hp.length > 2 ? parseInt(hp[2], 0) : 0;
        if (clientProto != PROTO) {
            Wire.writeLine(out, "ERR protocol version mismatch, server speaks " + PROTO);
            return;
        }
        Wire.writeLine(out, "OK " + PROTO);
        Path deviceDir = targetRoot.resolve(device);
        Files.createDirectories(deviceDir);
        log("session start, device=" + device);

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

        // ---- decide what to receive, and from which offset ----
        List<Entry> toSend = new ArrayList<>();
        List<String> ok = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
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
            byPath.put(e.path, e);

            if (Files.isRegularFile(dest) && Files.size(dest) == e.size
                    && Hash.sha256(dest).equals(e.sha)) {
                skipped.add(e.path);           // identical copy already in place
                continue;
            }
            long offset = 0;
            if (Files.isRegularFile(e.part)) {
                long plen = Files.size(e.part);
                if (plen == e.size) {          // leftover .part may already be complete
                    if (Hash.sha256(e.part).equals(e.sha)) {
                        promote(e);
                        skipped.add(e.path);
                        log("completed from leftover .part: " + e.path);
                        continue;
                    }
                    Files.delete(e.part);      // complete length but wrong content: restart
                } else if (plen < e.size) {
                    offset = plen;             // resume
                } else {
                    Files.delete(e.part);      // longer than the source: corrupt, restart
                }
            }
            e.offset = offset;
            toSend.add(e);
            Wire.writeLine(out, "{\"path\":\"" + MiniJson.esc(e.path) + "\",\"offset\":" + offset + "}");
        }
        Wire.writeLine(out, "READY");
        log("manifest: " + manifest.size() + " files, " + skipped.size() + " skipped, "
                + toSend.size() + " to receive");

        // ---- receive files, stop-and-wait per file ----
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
                // Drain nothing; protocol violation — abort the session.
                for (int j = i; j < toSend.size(); j++) failed.add(toSend.get(j).path);
                log("protocol violation in FILE header: " + hdr);
                break;
            }
            Files.createDirectories(e.dest.getParent());
            if (e.offset == 0) Files.deleteIfExists(e.part);
            try (OutputStream fos = Files.newOutputStream(e.part,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                copyN(in, fos, bytes);
            }
            if (Hash.sha256(e.part).equals(e.sha)) {
                promote(e);
                ok.add(e.path);
                Wire.writeLine(out, "DONE {\"path\":\"" + MiniJson.esc(e.path) + "\"}");
                log("received " + e.path + " (" + e.size + " bytes, resumed at " + e.offset + ")");
            } else {
                Files.deleteIfExists(e.part);
                failed.add(e.path);
                Wire.writeLine(out, "FAIL {\"path\":\"" + MiniJson.esc(e.path)
                        + "\",\"error\":\"sha256 mismatch\"}");
                log("FAILED (sha256 mismatch): " + e.path);
            }
        }

        Wire.readLine(in); // BYE (best effort)
        try {
            Wire.writeLine(out, "RECEIPT " + receiptJson(ok, skipped, failed));
        } catch (IOException ignore) { }
        log("session done: ok=" + ok.size() + " skipped=" + skipped.size() + " failed=" + failed.size());
    }

    /** Verify-then-publish: rename a complete .part into place and restore mtime. */
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

    /** Resolve relpath under base, rejecting absolute paths and ".." escapes. Null = unsafe. */
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

    static int parseInt(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; }
    }

    static void log(String m) {
        System.out.println("[" + LocalTime.now().withNano(0) + "] " + m);
    }

    static final class Entry {
        String path; long size; long mtime; String sha;
        long offset; Path dest; Path part;
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
