package cn.niubackup.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Account lifecycle: register (with 14-day trial license + per-user mesh
 * network), login, refresh (rotating), logout. Access/refresh tokens are
 * opaque random strings; only their SHA-256 hashes are stored (§13.7).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final long TRIAL_DAYS = 14;
    private static final long TRIAL_QUOTA_BYTES = 1L << 40; // 1 TiB trial quota

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;

    public AuthController(JdbcTemplate jdbc, BCryptPasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@RequestBody Map<String, Object> req) {
        String account = Util.str(req, "account");
        String password = Util.str(req, "password");
        if (account == null || account.length() < 3 || account.length() > 128
                || password == null || password.length() < 8) {
            return ResponseEntity.badRequest().body(Util.error("invalid_input"));
        }
        long userId;
        try {
            // RETURNING id: pgjdbc + RETURN_GENERATED_KEYS yields the whole row,
            // which breaks GeneratedKeyHolder.getKey(); RETURNING is exact.
            userId = jdbc.queryForObject(
                    "INSERT INTO users(account, password_hash) VALUES (?,?) RETURNING id",
                    Long.class, account, encoder.encode(password));
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Util.error("account_exists"));
        }

        Instant now = Instant.now();
        jdbc.update("INSERT INTO licenses(user_id, starts_at, expires_at, max_phones, max_pcs, quota_bytes) VALUES (?,?,?,?,?,?)",
                userId, Timestamp.from(now), Timestamp.from(now.plus(TRIAL_DAYS, ChronoUnit.DAYS)),
                2, 1, TRIAL_QUOTA_BYTES);
        // Per-user mesh network (design §4: one network per household).
        String networkName = "nb-" + userId + "-" + Util.randSuffix(8);
        jdbc.update("INSERT INTO networks(user_id, network_name, network_secret) VALUES (?,?,?)",
                userId, networkName, Util.newHexSecret(24));

        return ResponseEntity.status(HttpStatus.CREATED).body(Util.body(
                "user_id", userId,
                "account", account,
                "license_expires_at", jdbc.queryForObject(
                        "SELECT expires_at FROM licenses WHERE user_id=?", Timestamp.class, userId).toInstant().toString(),
                "network_name", networkName));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> req) {
        String account = Util.str(req, "account");
        String password = Util.str(req, "password");
        if (account == null || password == null) {
            return ResponseEntity.badRequest().body(Util.error("invalid_input"));
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, password_hash, status FROM users WHERE account=?", account);
        if (rows.isEmpty() || !encoder.matches(password, (String) rows.get(0).get("password_hash"))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Util.error("bad_credentials"));
        }
        Map<String, Object> user = rows.get(0);
        if (!"active".equals(user.get("status"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Util.error("user_disabled"));
        }
        long userId = ((Number) user.get("id")).longValue();
        return ResponseEntity.ok(issuePair(userId));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, Object> req) {
        String refresh = Util.str(req, "refresh_token");
        if (refresh == null) {
            return ResponseEntity.badRequest().body(Util.error("invalid_input"));
        }
        List<Long> rows = jdbc.queryForList(
                "SELECT user_id FROM auth_tokens WHERE token_hash=? AND kind='refresh' AND NOT revoked AND expires_at > now()",
                Long.class, Util.sha256Hex(refresh));
        if (rows.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Util.error("invalid_refresh_token"));
        }
        long userId = rows.get(0);
        String status = jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, userId);
        if (!"active".equals(status)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Util.error("user_disabled"));
        }
        jdbc.update("UPDATE auth_tokens SET revoked=true WHERE token_hash=? AND kind='refresh'",
                Util.sha256Hex(refresh));
        return ResponseEntity.ok(issuePair(userId));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest req) {
        String t = AuthResolver.bearer(req);
        if (t != null) {
            jdbc.update("UPDATE auth_tokens SET revoked=true WHERE token_hash=?", Util.sha256Hex(t));
        }
        return ResponseEntity.ok(Util.body("ok", true));
    }

    private Map<String, Object> issuePair(long userId) {
        Instant now = Instant.now();
        String access = Util.newToken();
        String refresh = Util.newToken();
        jdbc.update("INSERT INTO auth_tokens(user_id, token_hash, kind, expires_at) VALUES (?,?,?,?)",
                userId, Util.sha256Hex(access), "access", Timestamp.from(now.plus(2, ChronoUnit.HOURS)));
        jdbc.update("INSERT INTO auth_tokens(user_id, token_hash, kind, expires_at) VALUES (?,?,?,?)",
                userId, Util.sha256Hex(refresh), "refresh", Timestamp.from(now.plus(30, ChronoUnit.DAYS)));
        return Util.body("access_token", access, "refresh_token", refresh,
                "token_type", "Bearer", "expires_in", 7200);
    }
}
