package net.machang.backup

import com.easytier.jni.EasyTierJNI

/**
 * Thin, defensive facade over the vendor [EasyTierJNI] object.
 *
 * Every method returns "" on success or a human-readable error string, and never
 * throws — a missing/failed native library (UnsatisfiedLinkError) is reported as
 * an error string so the UI can show it instead of crashing. All signatures come
 * from easytier-contrib/easytier-android-jni (kotlin/com/easytier/jni/EasyTierJNI.kt).
 */
object EasyTierBridge {

    const val INSTANCE_NAME = "machang-phone"

    @Volatile
    var nativeAvailable: Boolean = false
        private set

    private fun err(what: String, code: Int): String {
        val detail = try {
            EasyTierJNI.getLastError()
        } catch (t: Throwable) {
            null
        }
        return "$what 失败 (code=$code)" + if (!detail.isNullOrBlank()) ": $detail" else ""
    }

    /** parseConfig + runNetworkInstance. Returns "" on success, error text otherwise. */
    fun startInstance(configToml: String): String {
        return try {
            val p = EasyTierJNI.parseConfig(configToml)
            if (p != 0) return err("parseConfig", p)
            val r = EasyTierJNI.runNetworkInstance(configToml)
            if (r != 0) return err("runNetworkInstance", r)
            nativeAvailable = true
            ""
        } catch (t: UnsatisfiedLinkError) {
            "原生库 libeasytier_android_jni.so 未加载: ${t.message}"
        } catch (t: Throwable) {
            "启动组网异常: ${t.message}"
        }
    }

    /** Hand the VpnService TUN fd to the running instance. Returns "" on success. */
    fun setTunFd(fd: Int): String = setTunFd(INSTANCE_NAME, fd)

    fun setTunFd(instanceName: String, fd: Int): String {
        return try {
            val r = EasyTierJNI.setTunFd(instanceName, fd)
            if (r != 0) err("setTunFd", r) else ""
        } catch (t: Throwable) {
            "setTunFd 异常: ${t.message}"
        }
    }

    /** Stop our instance (and anything else). Never throws. */
    fun stopInstance() {
        try {
            EasyTierJNI.deleteNetworkInstance(INSTANCE_NAME)
        } catch (t: Throwable) {
            // ignore: stopping must be best-effort
        }
        try {
            EasyTierJNI.stopAllInstances()
        } catch (t: Throwable) {
            // ignore
        }
    }

    /** Raw running-info JSON for diagnostics ("" if unavailable). */
    fun collectInfos(): String {
        return try {
            EasyTierJNI.collectNetworkInfos(10) ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * Best-effort route cost to a peer (1 = p2p direct, >=2 = relay) for the
     * §5.5 direct-only gate: scan the collectNetworkInfos JSON around the
     * peer's IP for a "cost" number. -1 when anything is unavailable or the
     * shape is unexpected — the receiver treats -1 as "unknown" and applies
     * its own view of the tunnel.
     */
    fun peerRouteCost(ip: String): Int {
        val infos = collectInfos()
        if (infos.isBlank()) return -1
        val idx = infos.indexOf(ip)
        if (idx < 0) return -1
        val window = infos.substring(
            maxOf(0, idx - 200),
            minOf(infos.length, idx + 600)
        )
        val m = Regex("\"cost\"\\s*:\\s*(\\d+)").find(window) ?: return -1
        return m.groupValues[1].toIntOrNull() ?: -1
    }

    /**
     * Build the TOML config for machang-net. The secret comes from runtime settings only.
     *
     * The schema mirrors the proven ~/easytier/phone.toml exactly: the core's TOML
     * loader silently ignores unknown keys, so a made-up flat schema (inst_name /
     * network / peers = [...]) parses "successfully" but starts an instance with a
     * default name, no network identity and no peers — setTunFd then reports
     * "instance not found" and the node never joins the mesh.
     */
    fun buildConfig(secret: String): String {
        fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            instance_name = "$INSTANCE_NAME"
            hostname = "machang-phone"
            ipv4 = "10.200.0.3/24"
            dhcp = false

            [network_identity]
            network_name = "machang-net"
            network_secret = "${esc(secret)}"

            [[peer]]
            uri = "tcp://36.133.114.164:11010"

            [[peer]]
            uri = "udp://36.133.114.164:11010"
        """.trimIndent()
    }

    const val PRODUCT_INSTANCE_NAME = "niubackup-phone"

    /**
     * M4c product config (design §8): the per-user network issued by the
     * account service (nb-*), joined through the shared product public node.
     * Same proven TOML schema as [buildConfig], plus secure mode — mesh
     * traffic is encrypted and node-authenticated end to end.
     *
     * Secure-mode SPELLING (fixed 2026-10-31 — the [flags] form below was
     * the "启动组网" failure in the user's screenshot): on the bundled
     * 2.7.0 core, `secure_mode` is NOT a flags field. The 2.6.4 PC sidecar
     * accepts `[flags] secure_mode = true`, but 2.7.0 moved secure mode
     * into a top-level `[secure_mode]` section (source tree ~/src/EasyTier:
     * easytier-core/src/config/toml.rs — `Config.secure_mode:
     * Option<SecureModeConfig>`, normalized at load with the context
     * "failed to normalize [secure_mode] config"; easytier-proto/proto/
     * common.proto — `SecureModeConfig { enabled, local_private_key,
     * local_public_key }`, keys base64 X25519). The flags spelling is a
     * hard parse error there ("unknown field `secure_mode`"), so the
     * instance never started. `enabled = true` alone is complete: config
     * normalization (normalize_secure_mode_config, easytier-core/src/
     * config/mod.rs) generates this node's X25519 keypair when none is
     * given, and with network_secret set above the node stays in
     * shared-secret mode (not credential mode) — the same secure mode
     * the 2.6.4 sidecar runs, so phone ↔ PC interop is unchanged.
     */
    fun buildProductConfig(
        networkName: String,
        networkSecret: String,
        publicNodeUri: String,
        ipv4: String = "10.99.0.3/24"
    ): String {
        fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            instance_name = "$PRODUCT_INSTANCE_NAME"
            hostname = "niubackup-phone"
            ipv4 = "$ipv4"
            dhcp = false

            [network_identity]
            network_name = "${esc(networkName)}"
            network_secret = "${esc(networkSecret)}"

            [[peer]]
            uri = "${esc(publicNodeUri)}"

            [secure_mode]
            enabled = true
        """.trimIndent()
    }
}
