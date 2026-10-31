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
    fun setTunFd(fd: Int): String {
        return try {
            val r = EasyTierJNI.setTunFd(INSTANCE_NAME, fd)
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
}
