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
     * Route cost to a peer (1 = p2p direct, >=2 = relay) for the §5.5
     * direct-only gate; -1 when the mesh is down or the peer has no route
     * yet — the receiver treats -1 as "unknown" and applies its own view.
     *
     * Parses the REAL collectNetworkInfos JSON of the bundled 2.7.0 core
     * (captured on-device 2026-10-31; the previous implementation searched
     * for the dotted IP string plus a nearby "cost" and could never match):
     *
     *   {"map":{"<instance>":{"my_node_info":{...},"events":[...],
     *     "routes":[{"peer_id":3304561304,
     *                "ipv4_addr":{"address":{"addr":174260226},
     *                             "network_length":24},
     *                "next_hop_peer_id":3304561304,"cost":1,
     *                "hostname":"niubackup-pc",...}],
     *     "peers":[...],"peer_route_pairs":[{"route":{...same...}}],
     *     "running":true}}}
     *
     * Addresses are NUMERIC (addr = IPv4 as a big-endian u32, so
     * 10.99.0.2 = 174260226); dotted strings appear only inside event
     * payloads. A direct route has cost 1 and next_hop_peer_id == peer_id;
     * a relayed route costs >=2 via the relay's peer id. When several
     * routes carry the target address (a stale route can linger after a
     * peer restarts), the minimum cost wins — that is the route OSPF
     * actually forwards on.
     */
    fun peerRouteCost(ip: String): Int {
        val infos = collectInfos()
        if (infos.isBlank()) return -1
        val target = ipv4ToU32(ip)
        if (target < 0) return -1
        var best = -1
        try {
            val map = org.json.JSONObject(infos).optJSONObject("map") ?: return -1
            val names = map.keys()
            while (names.hasNext()) {
                val info = map.optJSONObject(names.next()) ?: continue
                val routes = info.optJSONArray("routes")
                if (routes != null) {
                    for (i in 0 until routes.length()) {
                        val c = costIfAddrMatches(routes.optJSONObject(i), target)
                        if (c >= 0 && (best < 0 || c < best)) best = c
                    }
                }
                val pairs = info.optJSONArray("peer_route_pairs")
                if (pairs != null) {
                    for (i in 0 until pairs.length()) {
                        val route = pairs.optJSONObject(i)?.optJSONObject("route")
                        val c = costIfAddrMatches(route, target)
                        if (c >= 0 && (best < 0 || c < best)) best = c
                    }
                }
            }
        } catch (t: Throwable) {
            return -1
        }
        // One logcat line per change only (the UI polls this every 2 s).
        val prev = lastCostLog
        if (prev == null || prev.first != ip || prev.second != best) {
            lastCostLog = ip to best
            android.util.Log.i("NiuRoute", "peerRouteCost($ip) = $best")
        }
        return best
    }

    @Volatile
    private var lastCostLog: Pair<String, Int>? = null

    /** The route's cost when its ipv4_addr equals [target], else -1. */
    private fun costIfAddrMatches(route: org.json.JSONObject?, target: Long): Int {
        if (route == null) return -1
        val addr = route.optJSONObject("ipv4_addr")
            ?.optJSONObject("address")
            ?.optLong("addr", -1) ?: -1
        if (addr != target) return -1
        return route.optInt("cost", -1)
    }

    /** Dotted-quad IPv4 → big-endian u32 as a Long; -1 when malformed. */
    private fun ipv4ToU32(ip: String): Long {
        val parts = ip.split('.')
        if (parts.size != 4) return -1
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return -1
            if (n < 0 || n > 255) return -1
            v = (v shl 8) or n.toLong()
        }
        return v
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
