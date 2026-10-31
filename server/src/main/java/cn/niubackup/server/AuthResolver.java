package cn.niubackup.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Resolves the three credential types accepted by the API:
 * user access tokens (auth_tokens), device tokens (devices) and admin
 * tokens (admin_tokens). All lookups are by SHA-256 hash of the bearer value.
 */
@Component
public class AuthResolver {

    private final JdbcTemplate jdbc;

    public AuthResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String bearer(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return null;
        String t = h.substring("Bearer ".length()).trim();
        return t.isEmpty() ? null : t;
    }

    /** @return user id when the bearer is a live access token of an active user, else null. */
    public Long userId(HttpServletRequest req) {
        String t = bearer(req);
        if (t == null) return null;
        List<Long> rows = jdbc.queryForList(
                "SELECT user_id FROM auth_tokens WHERE token_hash=? AND kind='access' AND NOT revoked AND expires_at > now()",
                Long.class, Util.sha256Hex(t));
        if (rows.isEmpty()) return null;
        Long uid = rows.get(0);
        String status = jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, uid);
        return "active".equals(status) ? uid : null;
    }

    /** @return the device row when the bearer is a live (non-revoked) device token, else null. */
    public Map<String, Object> device(HttpServletRequest req) {
        String t = bearer(req);
        if (t == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM devices WHERE token_hash=? AND NOT revoked", Util.sha256Hex(t));
        if (rows.isEmpty()) return null;
        Map<String, Object> d = rows.get(0);
        jdbc.update("UPDATE devices SET last_seen_at=now() WHERE id=?", d.get("id"));
        return d;
    }

    /** User id of the caller, from either an access token or a device token. */
    public Long callerUserId(HttpServletRequest req) {
        Long uid = userId(req);
        if (uid != null) return uid;
        Map<String, Object> d = device(req);
        if (d != null) return ((Number) d.get("user_id")).longValue();
        return null;
    }

    public boolean userActive(long userId) {
        String status = jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, userId);
        return "active".equals(status);
    }

    /** @return admin username when the bearer is a live admin token, else null. */
    public String adminUsername(HttpServletRequest req) {
        String t = bearer(req);
        if (t == null) return null;
        List<String> rows = jdbc.queryForList(
                "SELECT a.username FROM admin_tokens at JOIN admins a ON a.id=at.admin_id " +
                "WHERE at.token_hash=? AND NOT at.revoked AND at.expires_at > now()",
                String.class, Util.sha256Hex(t));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
