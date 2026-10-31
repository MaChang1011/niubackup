package net.machang.backup;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
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
 * sessions' receipts: every verified signed receipt block is kept, and
 * {@link #loadMerged} verifies each signature (ECDSA P-256 against the
 * public key embedded in the block) and merges the file lists — a file
 * counts as "backed up" only when it appears in a receipt whose signature
 * verifies.
 *
 * Storage: receipts are issued per file since 2026-10-31 (one block can
 * arrive per backed-up file — thousands per library), so save() appends
 * one block per line to receipts/receipts.jsonl instead of writing one
 * file per receipt. loadMerged reads the jsonl AND the legacy layout
 * (receipts/receipt-*.json from the session-receipt era, plus
 * last-receipt.json), so evidence stored by older builds keeps counting.
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

    private static final Object SAVE_LOCK = new Object();

    /**
     * Persist one signed block ({receipt, signature, pubkey} JSON) verbatim:
     * appended as ONE line to receipts/receipts.jsonl (a stored block never
     * contains a raw newline — the document rides inside it as a JSON
     * object with escaped strings). Appends are serialized on SAVE_LOCK;
     * readers tolerate a torn final line (it fails verification and is
     * skipped, and the file it covered is re-receipted on a later session).
     */
    public static void save(File filesDir, String storedJson) {
        try {
            File dir = receiptsDir(filesDir);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File f = new File(dir, "receipts.jsonl");
            byte[] line = (storedJson + "\n").getBytes(StandardCharsets.UTF_8);
            synchronized (SAVE_LOCK) {
                Files.write(f.toPath(), line,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Exception ignore) {
            // A failed receipt save must never fail the backup itself; the
            // caller still has last-receipt.json from the M4a path.
        }
    }

    /**
     * All signature-verified receipt entries, keyed by path (newest wins),
     * merged from every stored receipt: the append-only receipts.jsonl,
     * any legacy receipt-*.json files, and the legacy last-receipt.json.
     */
    public static Map<String, ReceiptFile> loadMerged(File filesDir) {
        Map<String, ReceiptFile> out = new HashMap<>();
        List<String> blocks = new ArrayList<>();
        File dir = receiptsDir(filesDir);
        File jsonl = new File(dir, "receipts.jsonl");
        if (jsonl.isFile()) {
            try {
                for (String line : Files.readAllLines(jsonl.toPath(), StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (!t.isEmpty()) blocks.add(t);
                }
            } catch (Exception ignore) {
                // Unreadable jsonl: the legacy files below still count.
            }
        }
        List<File> files = new ArrayList<>();
        File[] kids = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (kids != null) {
            for (File k : kids) files.add(k);
        }
        File legacy = new File(filesDir, "last-receipt.json");
        if (legacy.isFile()) files.add(legacy);
        for (File f : files) {
            try {
                blocks.add(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (Exception ignore) {
                // Skip unreadable receipt files; others still count.
            }
        }
        for (String stored : blocks) {
            try {
                String doc = extractDoc(stored);
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

    /**
     * The receipt document inside a stored block. handleSignedReceipt
     * embeds it as a raw JSON object ({"receipt":{...},"signature":...}),
     * which MiniJson.str cannot read (it expects a quoted string value);
     * accept that form by brace-matching the object span — string-aware,
     * like arrayObjects — and the quoted-string form as a fallback.
     */
    static String extractDoc(String stored) {
        int i = stored.indexOf("\"receipt\"");
        if (i < 0) return null;
        int colon = stored.indexOf(':', i);
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < stored.length() && (stored.charAt(j) == ' ' || stored.charAt(j) == '\t')) j++;
        if (j >= stored.length()) return null;
        if (stored.charAt(j) == '"') return MiniJson.str(stored, "receipt");
        if (stored.charAt(j) != '{') return null;
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int k = j; k < stored.length(); k++) {
            char c = stored.charAt(k);
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
                if (depth == 0) return stored.substring(j, k + 1);
            }
        }
        return null;
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
