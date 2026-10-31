package cn.niubackup.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Endpoints used by paired clients: mesh provisioning, license state and
 * validation, and count-level session log reporting.
 *
 * Provisioning deliberately works for a registered-but-not-yet-approved device
 * so the client can join its per-user network first; actual backup sessions
 * are gated by /api/license/validate, which requires approval. Disabled users
 * and revoked devices are refused everywhere.
 */
@RestController
@RequestMapping("/api")
public class ClientApiController {

    private final JdbcTemplate jdbc;
    private final AuthResolver auth;

    @Value("${nb.public-node}")
    private String publicNode;

    public ClientApiController(JdbcTemplate jdbc, AuthResolver auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Util.body("ok", true, "service", "niubackup-server");
    }

    @GetMapping("/provisioning")
    public ResponseEntity<?> provisioning(HttpServletRequest http) {
        Long userId = auth.userId(http);
        if (userId == null) {
            Map<String, Object> device = auth.device(http);
            if (device == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Util.error("unauthorized"));
            }
            userId = ((Number) device.get("user_id")).longValue();
        }
        if (!auth.userActive(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Util.error("user_disabled"));
        }
        Map<String, Object> net = jdbc.queryForMap(
                "SELECT network_name, network_secret FROM networks WHERE user_id=?", userId);
        return ResponseEntity.ok(Util.body(
                "public_node", publicNode,
                "network_name", net.get("network_name"),
                "network_secret", net.get("network_secret")));
    }

    @GetMapping("/license")
    public ResponseEntity<?> license(HttpServletRequest http) {
        Long userId = auth.callerUserId(http);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Util.error("unauthorized"));
        }
        Map<String, Object> lic = jdbc.queryForMap("SELECT * FROM licenses WHERE user_id=?", userId);
        boolean valid = licenseCurrentlyValid(userId, lic);
        return ResponseEntity.ok(Util.body(
                "user_id", userId,
                "starts_at", ts(lic.get("starts_at")),
                "expires_at", ts(lic.get("expires_at")),
                "max_phones", lic.get("max_phones"),
                "max_pcs", lic.get("max_pcs"),
                "quota_bytes", lic.get("quota_bytes"),
                "valid", valid));
    }

    /**
     * Gate used by the PC receiver before accepting a backup session.
     * Always answers 200 with {valid, reason} for well-formed requests.
     */
    @PostMapping("/license/validate")
    public ResponseEntity<?> validate(@RequestBody Map<String, Object> req) {
        Long deviceId = Util.lng(req, "device_id");
        String token = Util.str(req, "device_token");
        if (deviceId == null || token == null) {
            return ResponseEntity.badRequest().body(Util.error("invalid_input"));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM devices WHERE id=?", deviceId);
        if (rows.isEmpty()) {
            return ResponseEntity.ok(Util.body("valid", false, "reason", "device_not_found"));
        }
        Map<String, Object> d = rows.get(0);
        if (!Util.sha256Hex(token).equals(d.get("token_hash"))) {
            return ResponseEntity.ok(Util.body("valid", false, "reason", "bad_token"));
        }
        if (Boolean.TRUE.equals(d.get("revoked"))) {
            return ResponseEntity.ok(Util.body("valid", false, "reason", "device_revoked"));
        }
        if (!Boolean.TRUE.equals(d.get("approved"))) {
            return ResponseEntity.ok(Util.body("valid", false, "reason", "not_approved"));
        }
        long userId = ((Number) d.get("user_id")).longValue();
        if (!auth.userActive(userId)) {
            return ResponseEntity.ok(Util.body("valid", false, "reason", "user_disabled"));
        }
        Map<String, Object> lic = jdbc.queryForMap("SELECT * FROM licenses WHERE user_id=?", userId);
        Instant now = Instant.now();
        if (now.isBefore(((Timestamp) lic.get("starts_at")).toInstant())) {
            return ResponseEntity.ok(Util.body("valid", false, "reason", "license_not_started"));
        }
        if (now.isAfter(((Timestamp) lic.get("expires_at")).toInstant())) {
            return ResponseEntity.ok(Util.body("valid", false, "reason", "license_expired",
                    "expires_at", ts(lic.get("expires_at"))));
        }
        return ResponseEntity.ok(Util.body("valid", true, "reason", "ok",
                "expires_at", ts(lic.get("expires_at"))));
    }

    /**
     * Count-level session reporting (design §8): by construction only
     * files_count / bytes / result are read from the body — no filename or
     * hash fields exist in this API or its table.
     */
    @PostMapping("/session-logs")
    public ResponseEntity<?> sessionLog(HttpServletRequest http, @RequestBody Map<String, Object> req) {
        Map<String, Object> device = auth.device(http);
        if (device == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Util.error("unauthorized"));
        }
        if (!Boolean.TRUE.equals(device.get("approved"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Util.error("not_approved"));
        }
        long userId = ((Number) device.get("user_id")).longValue();
        if (!auth.userActive(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Util.error("user_disabled"));
        }
        Integer files = Util.integer(req, "files_count");
        Long bytes = Util.lng(req, "bytes");
        String result = Util.str(req, "result");
        if (files == null || bytes == null || result == null) {
            return ResponseEntity.badRequest().body(Util.error("invalid_input"));
        }
        jdbc.update("INSERT INTO session_logs(user_id, device_id, files_count, bytes, result) VALUES (?,?,?,?,?)",
                userId, ((Number) device.get("id")).longValue(), files, bytes, result);
        return ResponseEntity.status(HttpStatus.CREATED).body(Util.body("ok", true));
    }

    private boolean licenseCurrentlyValid(long userId, Map<String, Object> lic) {
        if (!auth.userActive(userId)) return false;
        Instant now = Instant.now();
        return !now.isBefore(((Timestamp) lic.get("starts_at")).toInstant())
                && !now.isAfter(((Timestamp) lic.get("expires_at")).toInstant());
    }

    private static String ts(Object o) {
        return o instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(o);
    }
}
