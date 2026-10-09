package com.phonetv.phone

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

data class AuthorizedTv(val id: String, val name: String)
data class PendingTvPairing(val requestId: String, val tvId: String, val tvName: String, val expiresAt: Long)
data class TvPairingStatus(val status: String, val token: String? = null)

class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("phone", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    @Synchronized
    fun requestPairing(tvId: String, tvName: String, now: Long = System.currentTimeMillis()): PendingTvPairing? {
        if (tvId.isBlank() || tvId.length > 128 || tvName.isBlank() || tvName.length > 80) return null
        val requests = readRequests()
        pruneRequests(requests, now)
        val activeForTv = requests.keys().asSequence().mapNotNull { id ->
            requests.optJSONObject(id)?.takeIf { it.optString("status") == STATUS_PENDING && it.optString("tvId") == tvId }
                ?.let { PendingTvPairing(id, tvId, it.optString("tvName"), it.optLong("expiresAt")) }
        }.firstOrNull()
        if (activeForTv != null) {
            prefs.edit().putString(KEY_REQUESTS, requests.toString()).apply()
            return activeForTv
        }
        val pendingCount = requests.keys().asSequence().count { requests.optJSONObject(it)?.optString("status") == STATUS_PENDING }
        if (pendingCount >= MAX_PENDING_REQUESTS) return null

        val requestId = Base64.encodeToString(ByteArray(24).also(random::nextBytes), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val expiresAt = now + REQUEST_LIFETIME_MS
        requests.put(requestId, JSONObject()
            .put("tvId", tvId)
            .put("tvName", tvName)
            .put("status", STATUS_PENDING)
            .put("createdAt", now)
            .put("expiresAt", expiresAt))
        prefs.edit().putString(KEY_REQUESTS, requests.toString()).apply()
        return PendingTvPairing(requestId, tvId, tvName, expiresAt)
    }

    @Synchronized
    fun pendingRequests(now: Long = System.currentTimeMillis()): List<PendingTvPairing> {
        val requests = readRequests()
        pruneRequests(requests, now)
        prefs.edit().putString(KEY_REQUESTS, requests.toString()).apply()
        val result = mutableListOf<PendingTvPairing>()
        val keys = requests.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val record = requests.optJSONObject(id) ?: continue
            if (record.optString("status") == STATUS_PENDING) {
                result += PendingTvPairing(id, record.optString("tvId"), record.optString("tvName"), record.optLong("expiresAt"))
            }
        }
        return result.sortedBy { requests.optJSONObject(it.requestId)?.optLong("createdAt") ?: 0L }
    }

    @Synchronized
    fun approveRequest(requestId: String, now: Long = System.currentTimeMillis()): Boolean {
        val requests = readRequests()
        pruneRequests(requests, now)
        val request = requests.optJSONObject(requestId) ?: return false
        if (request.optString("status") != STATUS_PENDING || now >= request.optLong("expiresAt")) return false

        val token = newToken()
        val tvId = request.optString("tvId")
        readDevices().put(tvId, JSONObject().put("name", request.optString("tvName")).put("tokenHash", hashToken(token)))
            .also { devices -> prefs.edit().putString(KEY_DEVICES, devices.toString()).apply() }
        request.put("status", STATUS_APPROVED).put("token", token).put("finishedAt", now)
        prefs.edit().putString(KEY_REQUESTS, requests.toString()).apply()
        return true
    }

    @Synchronized
    fun rejectRequest(requestId: String, now: Long = System.currentTimeMillis()): Boolean {
        val requests = readRequests()
        pruneRequests(requests, now)
        val request = requests.optJSONObject(requestId) ?: return false
        if (request.optString("status") != STATUS_PENDING || now >= request.optLong("expiresAt")) return false
        request.put("status", STATUS_REJECTED).put("finishedAt", now)
        prefs.edit().putString(KEY_REQUESTS, requests.toString()).apply()
        return true
    }

    @Synchronized
    fun requestStatus(requestId: String, now: Long = System.currentTimeMillis()): TvPairingStatus {
        val requests = readRequests()
        pruneRequests(requests, now)
        prefs.edit().putString(KEY_REQUESTS, requests.toString()).apply()
        val request = requests.optJSONObject(requestId) ?: return TvPairingStatus(STATUS_EXPIRED)
        return TvPairingStatus(request.optString("status", STATUS_EXPIRED), request.optString("token").takeIf { it.isNotBlank() })
    }

    @Synchronized
    fun completeRequest(requestId: String) {
        val requests = readRequests()
        requests.remove(requestId)
        prefs.edit().putString(KEY_REQUESTS, requests.toString()).apply()
    }

    @Synchronized
    fun cancelRequest(requestId: String) {
        val requests = readRequests()
        val request = requests.optJSONObject(requestId)
        if (request?.optString("status") == STATUS_PENDING) {
            requests.remove(requestId)
        } else if (request?.optString("status") == STATUS_APPROVED) {
            val devices = readDevices()
            devices.remove(request.optString("tvId"))
            prefs.edit().putString(KEY_DEVICES, devices.toString()).apply()
            requests.remove(requestId)
        }
        prefs.edit().putString(KEY_REQUESTS, requests.toString()).apply()
    }

    @Synchronized
    fun activeCode(now: Long = System.currentTimeMillis()): String {
        val code = prefs.getString(KEY_CODE, null)
        val expiresAt = prefs.getLong(KEY_CODE_EXPIRY, 0L)
        if (code != null && now < expiresAt) return code
        return rotateCode(now)
    }

    @Synchronized
    fun rotateCode(now: Long = System.currentTimeMillis()): String {
        val code = String.format(Locale.US, "%06d", random.nextInt(1_000_000))
        prefs.edit().putString(KEY_CODE, code).putLong(KEY_CODE_EXPIRY, now + CODE_LIFETIME_MS).apply()
        return code
    }

    @Synchronized
    fun invalidateCode() {
        prefs.edit().remove(KEY_CODE).remove(KEY_CODE_EXPIRY).apply()
    }

    @Synchronized
    fun pair(code: String, tvId: String, tvName: String, now: Long = System.currentTimeMillis()): String? {
        if (tvId.isBlank() || tvId.length > 128 || tvName.isBlank() || tvName.length > 80) return null
        val expected = prefs.getString(KEY_CODE, null) ?: return null
        val expiresAt = prefs.getLong(KEY_CODE_EXPIRY, 0L)
        if (now >= expiresAt || !MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), code.toByteArray(Charsets.US_ASCII))) return null

        val token = newToken()
        val devices = readDevices()
        devices.put(tvId, JSONObject().put("name", tvName).put("tokenHash", hashToken(token)))
        prefs.edit().putString(KEY_DEVICES, devices.toString()).remove(KEY_CODE).remove(KEY_CODE_EXPIRY).apply()
        return token
    }

    @Synchronized
    fun isAuthorized(token: String?): Boolean {
        if (token.isNullOrBlank() || token.length > 256) return false
        val expectedHash = hashToken(token)
        val devices = readDevices()
        val keys = devices.keys()
        while (keys.hasNext()) {
            val record = devices.optJSONObject(keys.next()) ?: continue
            val stored = record.optString("tokenHash", "")
            if (stored.isNotEmpty() && MessageDigest.isEqual(stored.toByteArray(Charsets.US_ASCII), expectedHash.toByteArray(Charsets.US_ASCII))) return true
        }
        return false
    }

    @Synchronized
    fun devices(): List<AuthorizedTv> {
        val json = readDevices()
        val keys = json.keys()
        val result = mutableListOf<AuthorizedTv>()
        while (keys.hasNext()) {
            val id = keys.next()
            val name = json.optJSONObject(id)?.optString("name")?.takeIf { it.isNotBlank() } ?: continue
            result += AuthorizedTv(id, name)
        }
        return result.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    @Synchronized
    fun revoke(tvId: String) {
        val devices = readDevices()
        devices.remove(tvId)
        prefs.edit().putString(KEY_DEVICES, devices.toString()).apply()
    }

    private fun readDevices(): JSONObject = try {
        JSONObject(prefs.getString(KEY_DEVICES, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun readRequests(): JSONObject = try {
        JSONObject(prefs.getString(KEY_REQUESTS, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun pruneRequests(requests: JSONObject, now: Long) {
        val keys = requests.keys().asSequence().toList()
        keys.forEach { id ->
            val request = requests.optJSONObject(id) ?: run { requests.remove(id); return@forEach }
            if (request.optString("status") == STATUS_PENDING && now >= request.optLong("expiresAt")) {
                request.put("status", STATUS_EXPIRED).put("finishedAt", now).remove("token")
            }
            val finishedAt = request.optLong("finishedAt", 0L)
            if (finishedAt > 0 && now - finishedAt >= REQUEST_RESULT_LIFETIME_MS) requests.remove(id)
        }
    }

    private fun newToken(): String = Base64.encodeToString(
        ByteArray(32).also(random::nextBytes), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )

    private fun hashToken(token: String): String = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        Base64.NO_WRAP or Base64.NO_PADDING
    )

    companion object {
        private const val KEY_CODE = "pairingCode"
        private const val KEY_CODE_EXPIRY = "pairingCodeExpiry"
        private const val KEY_DEVICES = "authorizedTvs"
        private const val KEY_REQUESTS = "pendingTvPairings"
        private const val MAX_PENDING_REQUESTS = 8
        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_REJECTED = "rejected"
        const val STATUS_EXPIRED = "expired"
        const val REQUEST_LIFETIME_MS = 2 * 60 * 1000L
        private const val REQUEST_RESULT_LIFETIME_MS = 5 * 60 * 1000L
        const val CODE_LIFETIME_MS = 5 * 60 * 1000L
    }
}
