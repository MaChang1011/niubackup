package net.machang.backup;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * FsClient (M4c) — client for the receiver's file service (protocol v2,
 * HELLO mode "fs"): LIST the PC-side backup records, READ file ranges
 * (download-back with offset resume, and the ranged reads behind playback),
 * and THUMB for PC-generated thumbnails.
 *
 * One instance = one socket. All operations are synchronized: a connection
 * is used from one thread at a time (the Media3 DataSource opens its own).
 * Blocking — call from worker threads.
 */
public final class FsClient implements Closeable {

    public static final class Record {
        public String path;
        public long size;
        public String sha256;
        public long backedAt;
        public String integrity;
        public boolean thumb;
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    private final String host;
    private final int port;
    private final String userId;
    private final String deviceId;
    private final String deviceToken;
    private final IntSupplier routeCostSupplier;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    public FsClient(String host, int port, String userId, String deviceId,
                    String deviceToken, IntSupplier routeCostSupplier) {
        this.host = host;
        this.port = port;
        this.userId = userId;
        this.deviceId = deviceId;
        this.deviceToken = deviceToken;
        this.routeCostSupplier = routeCostSupplier != null ? routeCostSupplier : () -> -1;
    }

    public synchronized void connect() throws IOException {
        if (socket != null) return;
        Socket s = new Socket();
        s.connect(new java.net.InetSocketAddress(host, port), 8_000);
        s.setSoTimeout(120_000);
        InputStream i = s.getInputStream();
        OutputStream o = new BufferedOutputStream(s.getOutputStream());
        int routeCost = -1;
        try {
            routeCost = routeCostSupplier.getAsInt();
        } catch (Exception ignore) { /* -1 = unknown */ }
        Wire.writeLine(o, "HELLO {\"version\":2,\"user_id\":\"" + MiniJson.esc(userId)
                + "\",\"device_id\":\"" + MiniJson.esc(deviceId)
                + "\",\"device_token\":\"" + MiniJson.esc(deviceToken)
                + "\",\"route_cost\":" + routeCost + ",\"mode\":\"fs\"}");
        String resp = Wire.readLine(i);
        if (resp == null || !resp.startsWith("HELLO_ACK")) {
            try { s.close(); } catch (IOException ignore) { }
            throw new IOException("fs handshake refused: " + resp);
        }
        socket = s;
        in = i;
        out = o;
    }

    private String rpc(String json) throws IOException {
        connect();
        Wire.writeLine(out, json);
        String resp = Wire.readLine(in);
        if (resp == null) throw new IOException("fs connection closed by receiver");
        return resp;
    }

    public synchronized List<Record> list() throws IOException {
        String resp = rpc("{\"op\":\"LIST\"}");
        List<Record> outList = new ArrayList<>();
        for (String obj : ReceiptStore.arrayObjects(resp, "files")) {
            Record r = new Record();
            r.path = MiniJson.str(obj, "path");
            r.size = MiniJson.num(obj, "size", -1);
            r.sha256 = MiniJson.str(obj, "sha256");
            r.backedAt = MiniJson.num(obj, "backed_at", 0);
            r.integrity = MiniJson.str(obj, "integrity");
            r.thumb = obj.contains("\"thumb\":true");
            if (r.path != null) outList.add(r);
        }
        return outList;
    }

    /** One ranged read; returns fewer bytes at EOF, empty past EOF. */
    public synchronized byte[] read(String path, long offset, int len) throws IOException {
        String resp = rpc("{\"op\":\"READ\",\"path\":\"" + MiniJson.esc(path)
                + "\",\"offset\":" + offset + ",\"len\":" + len + "}");
        String data = MiniJson.str(resp, "data");
        if (data == null) throw new IOException("READ failed: " + resp);
        return Base64.getDecoder().decode(data);
    }

    /** Thumbnail JPEG bytes, or null when the PC has none for this file. */
    public synchronized byte[] thumb(String path) throws IOException {
        String resp = rpc("{\"op\":\"THUMB\",\"path\":\"" + MiniJson.esc(path) + "\"}");
        String thumb = MiniJson.str(resp, "thumb");
        return thumb == null ? null : Base64.getDecoder().decode(thumb);
    }

    /**
     * Download a PC copy into destDir with offset resume (§6.3): progress is
     * the &lt;name&gt;.part file next to the destination; an interrupted
     * download continues where it stopped. Returns the completed file.
     */
    public File download(String path, long totalSize, File destDir, Progress progress)
            throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            throw new IOException("cannot create " + destDir);
        }
        String base = path;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        File dest = new File(destDir, base);
        File part = new File(destDir, base + ".part");
        long offset = part.isFile() ? part.length() : 0;
        if (offset > totalSize) { // stale partial from another version: restart
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            offset = 0;
        }
        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            raf.seek(offset);
            long done = offset;
            while (done < totalSize) {
                int want = (int) Math.min(512 * 1024, totalSize - done);
                byte[] chunk = read(path, done, want);
                if (chunk.length == 0) throw new IOException("unexpected EOF at " + done);
                raf.write(chunk);
                done += chunk.length;
                if (progress != null) progress.onProgress(done, totalSize);
            }
        }
        if (dest.exists() && !dest.delete()) {
            throw new IOException("cannot replace " + dest);
        }
        if (!part.renameTo(dest)) throw new IOException("cannot finalize " + dest);
        return dest;
    }

    @Override
    public synchronized void close() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignore) { }
            socket = null;
            in = null;
            out = null;
        }
    }
}
