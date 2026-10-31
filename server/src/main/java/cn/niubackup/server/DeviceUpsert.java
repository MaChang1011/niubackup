package cn.niubackup.server;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Stable device identity (design §3.2): a device presents a persistent
 * {@code device_uuid} — generated once on the device, stored separately
 * from any login session, surviving logout — and registration becomes an
 * upsert keyed on (user_id, device_uuid):
 *
 *  - exact uuid match, not revoked → the SAME device row: a fresh device
 *    token is minted (the old one dies), no new row, no slot accounting;
 *  - exact uuid match, revoked → refused (a revoked device is never
 *    resurrected by re-login);
 *  - no uuid match, but the account holds EXACTLY ONE live uuid-less
 *    device of the same kind → one-time legacy merge: the uuid is bound
 *    to that row (audited), so pre-uuid registrations keep their number.
 *    Two or more candidates → no guessing, the caller creates a new row;
 *  - otherwise → null: the caller runs the normal cap-checked insert.
 *
 * Requests without a device_uuid never reach this class and keep the
 * pre-uuid behavior (backwards compatibility for old clients).
 */
final class DeviceUpsert {

    /** The uuid names a device the account holder revoked — refuse. */
    static final class DeviceRevokedException extends Exception {
        final long deviceId;

        DeviceRevokedException(long deviceId) {
            super("device_revoked");
            this.deviceId = deviceId;
        }
    }

    static final class Result {
        final long deviceId;
        final String deviceToken;
        /** True when an old uuid-less row was adopted (legacy merge). */
        final boolean merged;

        Result(long deviceId, String deviceToken, boolean merged) {
            this.deviceId = deviceId;
            this.deviceToken = deviceToken;
            this.merged = merged;
        }
    }

    private DeviceUpsert() {
    }

    /** Normalize a client-supplied uuid; null when absent or malformed. */
    static String cleanUuid(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (u.isEmpty() || u.length() > 64) return null;
        return u;
    }

    /**
     * Try to satisfy a registration from an existing row. Returns null
     * when the caller should create a fresh device (cap check + insert).
     * A blank [name] / null [pubkey] leaves the adopted row's stored
     * values untouched. [source] lands in the merge audit detail
     * ("register" / "qr-approve").
     */
    static Result tryReuse(JdbcTemplate jdbc, long userId, String kind, String name,
                           String pubkey, String deviceUuid, String source)
            throws DeviceRevokedException {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM devices WHERE user_id=? AND device_uuid=?", userId, deviceUuid);
        if (!rows.isEmpty()) {
            Map<String, Object> d = rows.get(0);
            long id = ((Number) d.get("id")).longValue();
            if (Boolean.TRUE.equals(d.get("revoked"))) {
                throw new DeviceRevokedException(id);
            }
            String token = Util.newToken();
            jdbc.update("UPDATE devices SET token_hash=?, name=?, pc_pubkey=? WHERE id=?",
                    Util.sha256Hex(token),
                    keep(d.get("name"), name),
                    keep(d.get("pc_pubkey"), "pc".equals(kind) ? pubkey : null),
                    id);
            return new Result(id, token, false);
        }
        // Legacy merge: exactly one live uuid-less device of this kind.
        List<Map<String, Object>> cand = jdbc.queryForList(
                "SELECT * FROM devices WHERE user_id=? AND kind=? AND NOT revoked " +
                "AND device_uuid IS NULL ORDER BY id", userId, kind);
        if (cand.size() == 1) {
            Map<String, Object> d = cand.get(0);
            long id = ((Number) d.get("id")).longValue();
            String token = Util.newToken();
            int n = jdbc.update(
                    "UPDATE devices SET device_uuid=?, token_hash=?, name=?, pc_pubkey=? " +
                    "WHERE id=? AND device_uuid IS NULL",
                    deviceUuid, Util.sha256Hex(token),
                    keep(d.get("name"), name),
                    keep(d.get("pc_pubkey"), "pc".equals(kind) ? pubkey : null),
                    id);
            if (n == 1) {
                jdbc.update("INSERT INTO admin_audit(admin_username, action, target, detail) " +
                                "VALUES (?,?,?,?)",
                        "system", "device_uuid_bind", "device:" + id,
                        "bound a persistent device_uuid to legacy " + kind + " device of user "
                                + userId + " via " + source + " (one-time merge; id unchanged)");
                return new Result(id, token, true);
            }
            // Lost a race for the merge — fall through to a fresh insert;
            // the unique (user_id, device_uuid) index keeps it consistent.
        }
        return null;
    }

    /** The incoming value when usable, else the stored one. */
    private static Object keep(Object stored, String incoming) {
        return incoming != null && !incoming.isBlank() ? incoming : stored;
    }
}
