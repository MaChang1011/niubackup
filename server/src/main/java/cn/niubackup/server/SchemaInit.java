package cn.niubackup.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent schema bootstrap. Privacy invariant: this schema intentionally
 * contains NO file-level tables and no filename/content-hash columns anywhere —
 * the server stores accounts, devices, licenses, per-user mesh networks and
 * count-level session logs only. File records live on the user's PC (design §8).
 */
@Component
public class SchemaInit implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInit.class);

    private static final String[] DDL = {
        """
        CREATE TABLE IF NOT EXISTS users(
            id BIGSERIAL PRIMARY KEY,
            account VARCHAR(128) NOT NULL UNIQUE,
            password_hash VARCHAR(100) NOT NULL,
            status VARCHAR(16) NOT NULL DEFAULT 'active',
            created_at TIMESTAMPTZ NOT NULL DEFAULT now())
        """,
        """
        CREATE TABLE IF NOT EXISTS auth_tokens(
            id BIGSERIAL PRIMARY KEY,
            user_id BIGINT NOT NULL REFERENCES users(id),
            token_hash CHAR(64) NOT NULL UNIQUE,
            kind VARCHAR(16) NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL,
            revoked BOOLEAN NOT NULL DEFAULT false,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now())
        """,
        "CREATE INDEX IF NOT EXISTS idx_auth_tokens_user ON auth_tokens(user_id)",
        """
        CREATE TABLE IF NOT EXISTS devices(
            id BIGSERIAL PRIMARY KEY,
            user_id BIGINT NOT NULL REFERENCES users(id),
            kind VARCHAR(16) NOT NULL,
            name VARCHAR(128) NOT NULL DEFAULT '',
            token_hash CHAR(64) NOT NULL UNIQUE,
            approved BOOLEAN NOT NULL DEFAULT false,
            revoked BOOLEAN NOT NULL DEFAULT false,
            pc_pubkey TEXT,
            last_seen_at TIMESTAMPTZ,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now())
        """,
        "CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id)",
        """
        CREATE TABLE IF NOT EXISTS networks(
            user_id BIGINT PRIMARY KEY REFERENCES users(id),
            network_name VARCHAR(128) NOT NULL UNIQUE,
            network_secret VARCHAR(128) NOT NULL)
        """,
        """
        CREATE TABLE IF NOT EXISTS licenses(
            user_id BIGINT PRIMARY KEY REFERENCES users(id),
            starts_at TIMESTAMPTZ NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL,
            max_phones INT NOT NULL DEFAULT 2,
            max_pcs INT NOT NULL DEFAULT 1,
            quota_bytes BIGINT NOT NULL DEFAULT 0)
        """,
        """
        CREATE TABLE IF NOT EXISTS session_logs(
            id BIGSERIAL PRIMARY KEY,
            user_id BIGINT NOT NULL REFERENCES users(id),
            device_id BIGINT NOT NULL REFERENCES devices(id),
            ts TIMESTAMPTZ NOT NULL DEFAULT now(),
            files_count INT NOT NULL,
            bytes BIGINT NOT NULL,
            result VARCHAR(32) NOT NULL)
        """,
        "CREATE INDEX IF NOT EXISTS idx_session_logs_ts ON session_logs(ts)",
        """
        CREATE TABLE IF NOT EXISTS admins(
            id BIGSERIAL PRIMARY KEY,
            username VARCHAR(64) NOT NULL UNIQUE,
            password_hash VARCHAR(100) NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now())
        """,
        """
        CREATE TABLE IF NOT EXISTS admin_tokens(
            id BIGSERIAL PRIMARY KEY,
            admin_id BIGINT NOT NULL REFERENCES admins(id),
            token_hash CHAR(64) NOT NULL UNIQUE,
            expires_at TIMESTAMPTZ NOT NULL,
            revoked BOOLEAN NOT NULL DEFAULT false,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now())
        """,
        """
        CREATE TABLE IF NOT EXISTS admin_audit(
            id BIGSERIAL PRIMARY KEY,
            admin_username VARCHAR(64) NOT NULL,
            action VARCHAR(64) NOT NULL,
            target VARCHAR(128) NOT NULL DEFAULT '',
            detail TEXT NOT NULL DEFAULT '',
            ts TIMESTAMPTZ NOT NULL DEFAULT now())
        """
    };

    private final JdbcTemplate jdbc;

    public SchemaInit(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String stmt : DDL) {
            jdbc.execute(stmt);
        }
        log.info("niubackup schema ready");
    }
}
