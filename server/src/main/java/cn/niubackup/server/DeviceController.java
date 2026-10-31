package cn.niubackup.server;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Device registration. The device token is shown exactly once at
 * registration; only its SHA-256 hash is stored.
 *
 * Login-is-network (design §3.2, decided 2026-10-31): a device is approved
 * the moment it registers under an account — there is no manual approval
 * step anymore, because joining the account's mesh network IS the trust
 * boundary (one account = one network). The /approve endpoint is kept as a
 * harmless no-op for older clients (receivers self-approved their PC at
 * bootstrap); revocation stays the live control.
 *
 * A registration carrying a persistent device_uuid upserts onto the
 * device's existing row (see [DeviceUpsert]) — re-login never mints a new
 * device number again.
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final JdbcTemplate jdbc;
    private final AuthResolver auth;

    public DeviceController(JdbcTemplate jdbc, AuthResolver auth) {
        this.jdbc = jdbc;
        this.auth = auth;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(HttpServletRequest http, @RequestBody Map<String, Object> req) {
        Long userId = auth.userId(http);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Util.error("unauthorized"));
        }
        String kind = Util.str(req, "kind");
        String name = Util.str(req, "name");
        String pubkey = Util.str(req, "pc_pubkey");
        if (!"phone".equals(kind) && !"pc".equals(kind)) {
            return ResponseEntity.badRequest().body(Util.error("invalid_kind"));
        }
        // Stable device identity (design §3.2): with a device_uuid the
        // registration is an upsert — same physical device, same row, fresh
        // token — regardless of how often it logs in. Without one, the
        // pre-uuid behavior below applies unchanged.
        String uuid = DeviceUpsert.cleanUuid(Util.str(req, "device_uuid"));
        if (uuid != null) {
            ResponseEntity<?> reused = tryUpsert(userId, kind, name, pubkey, uuid);
            if (reused != null) return reused;
        }
        Map<String, Object> license = jdbc.queryForMap("SELECT * FROM licenses WHERE user_id=?", userId);
        int cap = ((Number) license.get("phone".equals(kind) ? "max_phones" : "max_pcs")).intValue();
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM devices WHERE user_id=? AND kind=? AND NOT revoked",
                Integer.class, userId, kind);
        if (count != null && count >= cap) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Util.error("device_limit"));
        }
        String token = Util.newToken();
        // RETURNING id instead of GeneratedKeyHolder (pgjdbc returns the whole
        // row for RETURN_GENERATED_KEYS, which breaks getKey()).
        // approved=true at insert: registration IS approval (login-is-network).
        long deviceId;
        try {
            deviceId = jdbc.queryForObject(
                    "INSERT INTO devices(user_id, kind, name, token_hash, pc_pubkey, approved, device_uuid) VALUES (?,?,?,?,?,true,?) RETURNING id",
                    Long.class, userId, kind, name == null ? "" : name,
                    Util.sha256Hex(token), "pc".equals(kind) ? pubkey : null, uuid);
        } catch (DataIntegrityViolationException e) {
            // A concurrent registration won the uuid — answer as its upsert.
            if (uuid == null) throw e;
            ResponseEntity<?> reused = tryUpsert(userId, kind, name, pubkey, uuid);
            if (reused != null) return reused;
            throw e;
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Util.body(
                "device_id", deviceId,
                "device_token", token,
                "approved", true,
                "note", "device_token is shown once; only its hash is stored"));
    }

    /**
     * The uuid upsert branch of register: a ResponseEntity when the uuid
     * resolved to an existing row (or to a hard refusal), null when the
     * caller should fall through to a fresh, cap-checked insert.
     */
    private ResponseEntity<?> tryUpsert(long userId, String kind, String name,
                                        String pubkey, String uuid) {
        DeviceUpsert.Result r;
        try {
            r = DeviceUpsert.tryReuse(jdbc, userId, kind, name, pubkey, uuid, "register");
        } catch (DeviceUpsert.DeviceRevokedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Util.body(
                    "error", "device_revoked", "device_id", e.deviceId));
        }
        if (r == null) return null;
        return ResponseEntity.ok(Util.body(
                "device_id", r.deviceId,
                "device_token", r.deviceToken,
                "approved", true,
                "reused", true,
                "merged", r.merged,
                "note", "same device re-registered; previous device_token is invalid"));
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest http) {
        Long userId = auth.callerUserId(http);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Util.error("unauthorized"));
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, kind, name, approved, revoked, (pc_pubkey IS NOT NULL) AS has_pubkey, last_seen_at, created_at " +
                "FROM devices WHERE user_id=? ORDER BY id", userId);
        return ResponseEntity.ok(Util.body("devices", rows));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(HttpServletRequest http, @PathVariable("id") long deviceId) {
        Map<String, Object> target = findDevice(deviceId);
        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Util.error("device_not_found"));
        }
        long ownerId = ((Number) target.get("user_id")).longValue();

        Long sessionUser = auth.userId(http);
        boolean allowed = sessionUser != null && sessionUser == ownerId;
        if (!allowed) {
            Map<String, Object> caller = auth.device(http);
            if (caller != null
                    && "pc".equals(caller.get("kind"))
                    && Boolean.TRUE.equals(caller.get("approved"))
                    && ((Number) caller.get("user_id")).longValue() == ownerId) {
                allowed = true;
            }
        }
        if (!allowed) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Util.error("cannot_approve"));
        }
        if (Boolean.TRUE.equals(target.get("revoked"))) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Util.error("device_revoked"));
        }
        jdbc.update("UPDATE devices SET approved=true WHERE id=?", deviceId);
        return ResponseEntity.ok(Util.body("device_id", deviceId, "approved", true));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<?> revoke(HttpServletRequest http, @PathVariable("id") long deviceId) {
        Long sessionUser = auth.userId(http);
        if (sessionUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Util.error("unauthorized"));
        }
        int n = jdbc.update("UPDATE devices SET revoked=true WHERE id=? AND user_id=?", deviceId, sessionUser);
        if (n == 0) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Util.error("device_not_found"));
        }
        return ResponseEntity.ok(Util.body("device_id", deviceId, "revoked", true));
    }

    private Map<String, Object> findDevice(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM devices WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
