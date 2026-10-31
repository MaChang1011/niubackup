package cn.niubackup.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * QR-code login for the PC client (design §3.2, 2026-10-31).
 *
 * Flow: an unauthenticated PC creates a ticket and renders a QR code with
 * {ticket_id, secret, service address}; the phone — already holding an
 * account session — scans it and approves; the service registers a pc-kind
 * device under the phone's account (approved at once: login-is-network) and
 * seals the fresh credentials into the ticket; the PC polls, claims them
 * exactly once, and the ticket is void. Same account = same per-user mesh
 * network, so the PC joins the phone's network without any pairing step.
 *
 * The ticket secret is stored only as a SHA-256 hash and tickets live
 * 2 minutes. The sealed credential payload is the one deliberate exception
 * to the hash-only rule: it exists only between approval and claim and is
 * wiped on claim or expiry.
 */
@RestController
@RequestMapping("/api/pc-login/tickets")
public class PcLoginController {

    private static final long TICKET_TTL_SECONDS = 120;
    private static final long REFRESH_TTL_DAYS = 30;

    private final JdbcTemplate jdbc;
    private final AuthResolver auth;

    public PcLoginController(JdbcTemplate jdbc, AuthResolver auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    /** PC (unauthenticated): create a login ticket to be scanned by the phone. */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> req) {
        String deviceName = Util.str(req, "device_name");
        if (deviceName != null && deviceName.length() > 128) {
            deviceName = deviceName.substring(0, 128);
        }
        String ticketId = Util.newToken();
        String secret = Util.newToken();
        Instant expires = Instant.now().plusSeconds(TICKET_TTL_SECONDS);
        jdbc.update("INSERT INTO pc_login_tickets(id, secret_hash, device_name, expires_at) VALUES (?,?,?,?)",
                ticketId, Util.sha256Hex(secret), deviceName == null ? "" : deviceName,
                Timestamp.from(expires));
        return ResponseEntity.status(HttpStatus.CREATED).body(Util.body(
                "ticket_id", ticketId,
                "secret", secret,
                "expires_at", expires.toString()));
    }

    /**
     * PC: poll the ticket. While pending → {status:"pending", device_name,
     * created_at}. Once approved, the first poll claims the sealed credentials
     * (plus device_name/created_at) and voids the ticket; later polls — like
     * expired tickets — answer {status:"expired"}. device_name/created_at let
     * the phone's confirm card show which PC is asking and when it asked.
     */
    @GetMapping("/{ticketId}")
    @Transactional
    public ResponseEntity<?> poll(@PathVariable("ticketId") String ticketId,
                                  @RequestParam("secret") String secret) {
        Map<String, Object> t = find(ticketId);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Util.error("ticket_not_found"));
        }
        if (!Util.sha256Hex(secret).equals(t.get("secret_hash"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Util.error("bad_secret"));
        }
        String status = String.valueOf(t.get("status"));
        if ("claimed".equals(status) || "expired".equals(status)) {
            return ResponseEntity.ok(Util.body("status", "expired"));
        }
        Instant expires = ((Timestamp) t.get("expires_at")).toInstant();
        if (Instant.now().isAfter(expires)) {
            jdbc.update("UPDATE pc_login_tickets SET status='expired', payload=NULL WHERE id=?", ticketId);
            return ResponseEntity.ok(Util.body("status", "expired"));
        }
        if ("pending".equals(status)) {
            return ResponseEntity.ok(Util.body(
                    "status", "pending",
                    "device_name", String.valueOf(t.get("device_name")),
                    "created_at", createdAt(t)));
        }
        // approved → one-shot claim: the payload was read with the row above;
        // flip the status atomically — only the poll that wins the flip may
        // read the credentials out — then wipe the payload.
        // (An UPDATE…RETURNING via queryForList proved unreliable on pgjdbc:
        // the update landed but no row came back, eating a live claim.)
        String payload = (String) t.get("payload");
        int flipped = jdbc.update(
                "UPDATE pc_login_tickets SET status='claimed', payload=NULL WHERE id=? AND status='approved'",
                ticketId);
        if (flipped == 0 || payload == null) {
            return ResponseEntity.ok(Util.body("status", "expired"));
        }
        Map<String, Object> body = claimBody(payload);
        body.put("device_name", String.valueOf(t.get("device_name")));
        body.put("created_at", createdAt(t));
        return ResponseEntity.ok(body);
    }

    /** created_at in the same ISO-8601 form create() uses for expires_at. */
    private static String createdAt(Map<String, Object> t) {
        Object c = t.get("created_at");
        if (c instanceof Timestamp ts) return ts.toInstant().toString();
        return c == null ? null : String.valueOf(c);
    }

    /**
     * Phone (account session): approve a scanned ticket. Registers a pc-kind
     * device under the caller's account — approved at once — and seals its
     * credentials plus a fresh refresh token into the ticket for the PC's
     * one-shot claim. When the single PC slot is taken, the stale PC is
     * revoked first (the same reclaim the receiver's password login performs:
     * the approver is the account holder, so reclaiming is legitimate).
     */
    @PostMapping("/{ticketId}/approve")
    @Transactional
    public ResponseEntity<?> approve(HttpServletRequest http, @PathVariable("ticketId") String ticketId,
                                     @RequestBody Map<String, Object> req) {
        Long userId = auth.userId(http);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Util.error("unauthorized"));
        }
        Map<String, Object> t = find(ticketId);
        if (t == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Util.error("ticket_not_found"));
        }
        String secret = Util.str(req, "secret");
        if (secret == null || !Util.sha256Hex(secret).equals(t.get("secret_hash"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Util.error("bad_secret"));
        }
        if (!"pending".equals(String.valueOf(t.get("status")))) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Util.error("ticket_used"));
        }
        Instant expires = ((Timestamp) t.get("expires_at")).toInstant();
        if (Instant.now().isAfter(expires)) {
            jdbc.update("UPDATE pc_login_tickets SET status='expired', payload=NULL WHERE id=? AND status='pending'",
                    ticketId);
            return ResponseEntity.status(HttpStatus.GONE).body(Util.error("ticket_expired"));
        }

        String account = jdbc.queryForObject("SELECT account FROM users WHERE id=?", String.class, userId);
        Map<String, Object> license = jdbc.queryForMap("SELECT * FROM licenses WHERE user_id=?", userId);
        int cap = ((Number) license.get("max_pcs")).intValue();
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM devices WHERE user_id=? AND kind='pc' AND NOT revoked",
                Integer.class, userId);
        if (count != null && count >= cap) {
            int n = jdbc.update("UPDATE devices SET revoked=true WHERE user_id=? AND kind='pc' AND NOT revoked",
                    userId);
            jdbc.update("INSERT INTO admin_audit(admin_username, action, target, detail) VALUES (?,?,?,?)",
                    "system", "pc_slot_reclaim", "user:" + userId,
                    "qr-login approve revoked " + n + " stale pc device(s) to free the pc slot");
        }

        String deviceToken = Util.newToken();
        String name = String.valueOf(t.get("device_name"));
        if (name.isBlank()) name = "niubackup-pc";
        long deviceId = jdbc.queryForObject(
                "INSERT INTO devices(user_id, kind, name, token_hash, approved) VALUES (?,'pc',?,?,true) RETURNING id",
                Long.class, userId, name, Util.sha256Hex(deviceToken));

        String refresh = Util.newToken();
        jdbc.update("INSERT INTO auth_tokens(user_id, token_hash, kind, expires_at) VALUES (?,?,?,?)",
                userId, Util.sha256Hex(refresh), "refresh",
                Timestamp.from(Instant.now().plus(REFRESH_TTL_DAYS, ChronoUnit.DAYS)));

        String payload = "{\"account\":\"" + esc(account) + "\",\"user_id\":" + userId
                + ",\"pc_device_id\":" + deviceId
                + ",\"device_token\":\"" + esc(deviceToken)
                + "\",\"refresh_token\":\"" + esc(refresh) + "\"}";
        int n = jdbc.update(
                "UPDATE pc_login_tickets SET status='approved', user_id=?, pc_device_id=?, payload=? WHERE id=? AND status='pending'",
                userId, deviceId, payload, ticketId);
        if (n == 0) {
            throw new IllegalStateException("ticket changed under approve");
        }
        return ResponseEntity.ok(Util.body(
                "ok", true,
                "account", account,
                "user_id", userId,
                "pc_device_id", deviceId));
    }

    private Map<String, Object> find(String ticketId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM pc_login_tickets WHERE id=?", ticketId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Parse the sealed payload JSON back into the claim response body. */
    private static Map<String, Object> claimBody(String payload) {
        return Util.body(
                "status", "approved",
                "account", jsonStr(payload, "account"),
                "user_id", jsonNum(payload, "user_id"),
                "pc_device_id", jsonNum(payload, "pc_device_id"),
                "device_token", jsonStr(payload, "device_token"),
                "refresh_token", jsonStr(payload, "refresh_token"));
    }

    private static String jsonStr(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int j = i + needle.length(); j < json.length(); j++) {
            char c = json.charAt(j);
            if (c == '\\' && j + 1 < json.length()) {
                sb.append(json.charAt(++j));
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Long jsonNum(String json, String key) {
        String needle = "\"" + key + "\":";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int j = i + needle.length();
        int k = j;
        while (k < json.length() && (Character.isDigit(json.charAt(k)) || json.charAt(k) == '-')) k++;
        try {
            return Long.parseLong(json.substring(j, k));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
