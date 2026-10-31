package net.machang.backup

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the M4b account service (M4c). Plain HttpURLConnection + org.json
 * (both platform built-ins; no new dependencies). All calls are blocking —
 * call from a worker thread.
 *
 * API shapes (docs/m4b-notes.md):
 *  - POST /api/auth/register {account,password} -> {user_id, ...}
 *  - POST /api/auth/login {account,password} -> {access_token, refresh_token}
 *  - POST /api/devices/register (session) {kind,name} -> {device_id, device_token} (token once)
 *  - GET  /api/devices (session or device bearer) -> {devices:[...]}
 *  - GET  /api/provisioning (session or device bearer) -> {public_node, network_name, network_secret}
 *  - POST /api/license/validate {device_id, device_token} -> {valid, reason}
 *  - POST /api/session-logs (device bearer) {files_count, bytes, result}
 */
class AccountClient(private val baseUrl: String) {

    data class Session(val accessToken: String, val refreshToken: String)
    data class DeviceInfo(
        val id: Long, val kind: String, val name: String,
        val approved: Boolean, val revoked: Boolean
    )

    data class Provisioning(
        val publicNode: String, val networkName: String, val networkSecret: String
    )

    private fun normalize(url: String): String = url.trim().trimEnd('/')

    private fun request(
        method: String, path: String, bearer: String?, body: JSONObject?
    ): JSONObject {
        val conn = (URL(normalize(baseUrl) + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val text = try {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        } catch (e: Exception) {
            ""
        }
        if (code !in 200..299) {
            val err = try {
                JSONObject(text).optString("error", "")
            } catch (e: Exception) {
                ""
            }
            throw IOException("HTTP $code" + if (err.isNotEmpty()) " ($err)" else "")
        }
        return JSONObject(text.ifEmpty { "{}" })
    }

    fun register(account: String, password: String): Long {
        val r = request(
            "POST", "/api/auth/register", null,
            JSONObject().put("account", account).put("password", password)
        )
        return r.getLong("user_id")
    }

    fun login(account: String, password: String): Session {
        val r = request(
            "POST", "/api/auth/login", null,
            JSONObject().put("account", account).put("password", password)
        )
        return Session(r.getString("access_token"), r.getString("refresh_token"))
    }

    fun refresh(refreshToken: String): Session {
        val r = request(
            "POST", "/api/auth/refresh", null,
            JSONObject().put("refresh_token", refreshToken)
        )
        return Session(r.getString("access_token"), r.getString("refresh_token"))
    }

    /** Returns (device_id, device_token); the token is shown exactly once. */
    fun registerDevice(session: Session, kind: String, name: String): Pair<Long, String> {
        val r = request(
            "POST", "/api/devices/register", session.accessToken,
            JSONObject().put("kind", kind).put("name", name)
        )
        return r.getLong("device_id") to r.getString("device_token")
    }

    fun listDevices(bearer: String): List<DeviceInfo> {
        val r = request("GET", "/api/devices", bearer, null)
        val arr = r.getJSONArray("devices")
        val out = ArrayList<DeviceInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            out += DeviceInfo(
                d.getLong("id"), d.optString("kind"), d.optString("name"),
                d.optBoolean("approved"), d.optBoolean("revoked")
            )
        }
        return out
    }

    /** The caller's numeric user_id (from GET /api/license). */
    fun licenseUserId(bearer: String): Long {
        val r = request("GET", "/api/license", bearer, null)
        return r.getLong("user_id")
    }

    fun provisioning(bearer: String): Provisioning {
        val r = request("GET", "/api/provisioning", bearer, null)
        return Provisioning(
            r.getString("public_node"), r.getString("network_name"),
            r.getString("network_secret")
        )
    }

    /** Pairing/authorization state for our own device: (valid, reason). */
    fun validate(deviceId: Long, deviceToken: String): Pair<Boolean, String> {
        val r = request(
            "POST", "/api/license/validate", null,
            JSONObject().put("device_id", deviceId).put("device_token", deviceToken)
        )
        return r.optBoolean("valid") to r.optString("reason", "")
    }

    /** Count-level session report (design §8 — no filenames, no hashes). */
    fun postSessionLog(deviceToken: String, files: Int, bytes: Long, result: String) {
        request(
            "POST", "/api/session-logs", deviceToken,
            JSONObject().put("files_count", files).put("bytes", bytes).put("result", result)
        )
    }
}
