package cn.niubackup.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Admin backend API. Every mutation writes an admin_audit row. Admin sessions
 * are opaque tokens in admin_tokens (hash stored), separate from user tokens.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;
    private final AuthResolver auth;

    public AdminController(JdbcTemplate jdbc, BCryptPasswordEncoder encoder, AuthResolver auth) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.auth = auth;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> req) {
        String username = Util.str(req, "username");
        String password = Util.str(req, "password");
        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Util.error("invalid_input"));
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, password_hash FROM admins WHERE username=?", username);
        if (rows.isEmpty() || !encoder.matches(password, (String) rows.get(0).get("password_hash"))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Util.error("bad_credentials"));
        }
        long adminId = ((Number) rows.get(0).get("id")).longValue();
        String token = Util.newToken();
        jdbc.update("INSERT INTO admin_tokens(admin_id, token_hash, expires_at) VALUES (?,?,?)",
                adminId, Util.sha256Hex(token),
                Timestamp.from(Instant.now().plus(12, ChronoUnit.HOURS)));
        return ResponseEntity.ok(Util.body("admin_token", token, "username", username));
    }

    @GetMapping("/users")
    public ResponseEntity<?> users(HttpServletRequest http,
                                   @RequestParam(value = "search", required = false) String search) {
        if (admin(http) == null) return unauthorized();
        String like = "%" + (search == null ? "" : search) + "%";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT u.id, u.account, u.status, u.created_at, " +
                "       l.starts_at, l.expires_at, l.max_phones, l.max_pcs, l.quota_bytes, " +
                "       n.network_name, " +
                "       (SELECT count(*) FROM devices d WHERE d.user_id=u.id AND NOT d.revoked) AS device_count " +
                "FROM users u LEFT JOIN licenses l ON l.user_id=u.id LEFT JOIN networks n ON n.user_id=u.id " +
                "WHERE u.account LIKE ? ORDER BY u.id DESC LIMIT 200", like);
        return ResponseEntity.ok(Util.body("users", rows));
    }

    @GetMapping("/users/{id}/devices")
    public ResponseEntity<?> userDevices(HttpServletRequest http, @PathVariable("id") long userId) {
        if (admin(http) == null) return unauthorized();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, kind, name, approved, revoked, (pc_pubkey IS NOT NULL) AS has_pubkey, last_seen_at, created_at " +
                "FROM devices WHERE user_id=? ORDER BY id", userId);
        return ResponseEntity.ok(Util.body("devices", rows));
    }

    @PostMapping("/users/{id}/license")
    public ResponseEntity<?> renewLicense(HttpServletRequest http, @PathVariable("id") long userId,
                                          @RequestBody Map<String, Object> req) {
        String admin = admin(http);
        if (admin == null) return unauthorized();
        Integer days = Util.integer(req, "days");
        if (days == null) {
            return ResponseEntity.badRequest().body(Util.error("invalid_input"));
        }
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM licenses WHERE user_id=?", userId);
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Util.error("user_not_found"));
        }
        Timestamp before = (Timestamp) rows.get(0).get("expires_at");
        jdbc.update("UPDATE licenses SET expires_at = GREATEST(expires_at, now()) + (? * INTERVAL '1 day') WHERE user_id=?",
                days, userId);
        Timestamp after = jdbc.queryForObject("SELECT expires_at FROM licenses WHERE user_id=?", Timestamp.class, userId);
        audit(admin, "license_renew", "user:" + userId,
                "days=" + days + " expires " + before.toInstant() + " -> " + after.toInstant());
        return ResponseEntity.ok(Util.body("user_id", userId, "expires_at", after.toInstant().toString()));
    }

    @PostMapping("/users/{id}/disable")
    public ResponseEntity<?> disable(HttpServletRequest http, @PathVariable("id") long userId) {
        String admin = admin(http);
        if (admin == null) return unauthorized();
        int n = jdbc.update("UPDATE users SET status='disabled' WHERE id=?", userId);
        if (n == 0) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Util.error("user_not_found"));
        jdbc.update("UPDATE auth_tokens SET revoked=true WHERE user_id=?", userId);
        audit(admin, "user_disable", "user:" + userId, "status -> disabled; user tokens revoked");
        return ResponseEntity.ok(Util.body("user_id", userId, "status", "disabled"));
    }

    @PostMapping("/users/{id}/enable")
    public ResponseEntity<?> enable(HttpServletRequest http, @PathVariable("id") long userId) {
        String admin = admin(http);
        if (admin == null) return unauthorized();
        int n = jdbc.update("UPDATE users SET status='active' WHERE id=?", userId);
        if (n == 0) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Util.error("user_not_found"));
        audit(admin, "user_enable", "user:" + userId, "status -> active");
        return ResponseEntity.ok(Util.body("user_id", userId, "status", "active"));
    }

    @PostMapping("/devices/{id}/revoke")
    public ResponseEntity<?> revokeDevice(HttpServletRequest http, @PathVariable("id") long deviceId) {
        String admin = admin(http);
        if (admin == null) return unauthorized();
        int n = jdbc.update("UPDATE devices SET revoked=true WHERE id=?", deviceId);
        if (n == 0) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Util.error("device_not_found"));
        audit(admin, "device_revoke", "device:" + deviceId, "revoked by admin");
        return ResponseEntity.ok(Util.body("device_id", deviceId, "revoked", true));
    }

    @GetMapping("/overview")
    public ResponseEntity<?> overview(HttpServletRequest http) {
        if (admin(http) == null) return unauthorized();
        Map<String, Object> stats = jdbc.queryForMap(
                "SELECT " +
                "(SELECT count(*) FROM users) AS users_total, " +
                "(SELECT count(*) FROM users WHERE status='active') AS users_active, " +
                "(SELECT count(*) FROM devices WHERE NOT revoked) AS devices_total, " +
                "(SELECT count(*) FROM devices WHERE NOT revoked AND kind='phone') AS phones, " +
                "(SELECT count(*) FROM devices WHERE NOT revoked AND kind='pc') AS pcs, " +
                "(SELECT count(*) FROM session_logs WHERE ts::date = current_date) AS sessions_today, " +
                "(SELECT COALESCE(sum(bytes),0) FROM session_logs WHERE ts::date = current_date) AS bytes_today, " +
                "(SELECT COALESCE(sum(bytes),0) FROM session_logs) AS bytes_total");
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/audit")
    public ResponseEntity<?> auditList(HttpServletRequest http) {
        if (admin(http) == null) return unauthorized();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, admin_username, action, target, detail, ts FROM admin_audit ORDER BY id DESC LIMIT 100");
        return ResponseEntity.ok(Util.body("audit", rows));
    }

    private String admin(HttpServletRequest http) {
        return auth.adminUsername(http);
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Util.error("unauthorized"));
    }

    private void audit(String admin, String action, String target, String detail) {
        jdbc.update("INSERT INTO admin_audit(admin_username, action, target, detail) VALUES (?,?,?,?)",
                admin, action, target, detail);
    }
}
