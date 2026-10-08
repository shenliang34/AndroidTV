package com.phonetv.phone

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

data class AuthorizedTv(val id: String, val name: String)

class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("phone", Context.MODE_PRIVATE)
    private val random = SecureRandom()

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

        val tokenBytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.encodeToString(tokenBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
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

    private fun hashToken(token: String): String = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        Base64.NO_WRAP or Base64.NO_PADDING
    )

    companion object {
        private const val KEY_CODE = "pairingCode"
        private const val KEY_CODE_EXPIRY = "pairingCodeExpiry"
        private const val KEY_DEVICES = "authorizedTvs"
        const val CODE_LIFETIME_MS = 5 * 60 * 1000L
    }
}
