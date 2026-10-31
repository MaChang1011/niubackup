package net.machang.backup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ReceiptStore (M4c) — the phone-side store of PC-signed completion receipts.
 *
 * M4a kept only the latest receipt (last-receipt.json). For the §6.1 status
 * badges and the §6.4 free-up-space gate the phone needs the UNION of all
 * sessions' receipts: every verified signed receipt block is kept as
 * receipts/receipt-&lt;ts&gt;.json, and {@link #loadMerged} verifies each
 * signature (ECDSA P-256 against the public key embedded in the block) and
 * merges the file lists — a file counts as "backed up" only when it appears
 * in a receipt whose signature verifies.
 */
public final class ReceiptStore {

    public static final class ReceiptFile {
        public String path;
        public long size;
        public String sha256;
        public long ts;
    }

    private ReceiptStore() { }

    static File receiptsDir(File filesDir) {
        return new File(filesDir, "receipts");
    }

    /** Persist one signed block ({receipt, signature, pubkey} JSON) verbatim. */
    public static void save(File filesDir, String storedJson) {
        try {
            String doc = MiniJson.str(storedJson, "receipt");
            long ts = doc == null ? System.currentTimeMillis()
                    : MiniJson.num(doc, "ts", System.currentTimeMillis());
            File dir = receiptsDir(filesDir);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(dir, "receipt-" + ts + ".json");
            Files.write(f.toPath(), storedJson.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignore) {
            // A failed receipt save must never fail the backup itself; the
            // caller still has last-receipt.json from the M4a path.
        }
    }

    /**
     * All signature-verified receipt entries, keyed by path (newest wins),
     * merged from every stored receipt plus the legacy last-receipt.json.
     */
    public static Map<String, ReceiptFile> loadMerged(File filesDir) {
        Map<String, ReceiptFile> out = new HashMap<>();
        List<File> files = new ArrayList<>();
        File dir = receiptsDir(filesDir);
        File[] kids = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (kids != null) {
            for (File k : kids) files.add(k);
        }
        File legacy = new File(filesDir, "last-receipt.json");
        if (legacy.isFile()) files.add(legacy);
        for (File f : files) {
            try {
                String stored = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                String doc = MiniJson.str(stored, "receipt");
                String sig = MiniJson.str(stored, "signature");
                String pub = MiniJson.str(stored, "pubkey");
                if (doc == null || sig == null || pub == null) continue;
                if (!BackupClient.verifyEcdsa(doc, sig, pub)) continue; // forged/corrupt: ignore
                long ts = MiniJson.num(doc, "ts", 0);
                for (String obj : arrayObjects(doc, "files")) {
                    ReceiptFile rf = new ReceiptFile();
                    rf.path = MiniJson.str(obj, "path");
                    rf.size = MiniJson.num(obj, "size", -1);
                    rf.sha256 = MiniJson.str(obj, "sha256");
                    rf.ts = ts;
                    if (rf.path == null || rf.sha256 == null) continue;
                    ReceiptFile prev = out.get(rf.path);
                    if (prev == null || ts >= prev.ts) out.put(rf.path, rf);
                }
            } catch (Exception ignore) {
                // Skip unreadable receipt files; others still count.
            }
        }
        return out;
    }

    /** Set of sha256 values vouched for by any verified receipt (any path). */
    public static Set<String> backedShas(File filesDir) {
        Set<String> out = new HashSet<>();
        for (ReceiptFile rf : loadMerged(filesDir).values()) out.add(rf.sha256);
        return out;
    }

    /** Split a JSON array field into its object literals (flat objects). */
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
}
