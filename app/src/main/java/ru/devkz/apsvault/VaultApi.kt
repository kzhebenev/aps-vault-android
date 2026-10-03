package ru.devkz.apsvault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/** Error with the server's `detail` text, which is meant to be shown as is (contract, section 9). */
class VaultException(
    val status: Int,
    message: String,
    val approvalRequired: Boolean = false,
    val webauthnRequired: Boolean = false,
    val retryAfterSec: Int? = null,
) : Exception(message) {
    val needsTotp get() = status == 401 && message.orEmpty().contains("totp_code is required", true)
}

data class SecretItem(
    val id: Int,
    val folderName: String,
    val name: String,
    val tags: String,
    val url: String,
    val hasLogin: Boolean,
    val hasTotp: Boolean,
    val hasNotes: Boolean,
    val isFavorite: Boolean,
    val machineOnly: Boolean,
    val requireApproval: Boolean,
    val updatedAt: String,
    val expiresAt: String,
) {
    val host: String get() = runCatching { URI(url).host ?: "" }.getOrDefault("")

    companion object {
        fun from(o: JSONObject) = SecretItem(
            id = o.getInt("id"),
            folderName = o.optStr("folder_name"),
            name = o.optStr("name"),
            tags = o.optStr("tags"),
            url = o.optStr("url"),
            hasLogin = o.optBoolean("has_login"),
            hasTotp = o.optBoolean("has_totp"),
            hasNotes = o.optBoolean("has_notes"),
            isFavorite = o.optBoolean("is_favorite"),
            machineOnly = o.optBoolean("machine_only"),
            requireApproval = o.optBoolean("require_approval"),
            updatedAt = o.optStr("updated_at"),
            expiresAt = o.optStr("expires_at"),
        )
    }
}

data class SecretFull(
    val item: SecretItem,
    val value: String,
    val valueHidden: Boolean,
    val login: String,
    val notes: String,
) {
    companion object {
        fun from(o: JSONObject) = SecretFull(
            item = SecretItem.from(o),
            value = o.optStr("value"),
            valueHidden = o.optBoolean("value_hidden"),
            login = o.optStr("login"),
            notes = o.optStr("notes"),
        )
    }
}

data class Totp(val code: String, val period: Int, val remaining: Int)

data class Approval(val id: Int, val status: String, val approveUrl: String, val notified: Boolean)

private fun JSONObject.optStr(k: String): String = if (isNull(k)) "" else optString(k, "")

/**
 * The vault's REST API as a person's client sees it (docs/CLIENT-CONTRACT.md).
 * The session travels as `Authorization: Bearer <sid>`; no cookie jar, so no CSRF either.
 */
class VaultApi(val base: String, @Volatile var sid: String? = null) {

    suspend fun health(): JSONObject = call("GET", "/api/health").obj()

    /** Unlocks and returns the session id, read from the `vault_session` cookie of the response. */
    suspend fun unlock(password: String, totp: String?): String {
        val body = JSONObject().put("master_password", password)
        if (!totp.isNullOrBlank()) body.put("totp_code", totp.trim())
        return withContext(Dispatchers.IO) {
            client.newCall(request("POST", "/api/auth/unlock", body, null)).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw error(r, text)
                val json = runCatching { JSONObject(text) }.getOrNull()
                json?.optString("session_id")?.takeIf { it.isNotEmpty() }
                    ?: r.headers("Set-Cookie").firstNotNullOfOrNull { c ->
                        c.split(';').first().trim().takeIf { it.startsWith("vault_session=") }?.substringAfter('=')
                    }
                    ?: throw VaultException(0, "unlock succeeded but the response carries no session")
            }
        }
    }

    suspend fun lock() { call("POST", "/api/auth/lock") }

    /** `/api/health` ignores the Bearer header (server note 1), so this is the session probe. */
    suspend fun checkSession() { call("GET", "/api/folders") }

    suspend fun list(): List<SecretItem> {
        val a = call("GET", "/api/secrets").arr()
        return (0 until a.length()).map { SecretItem.from(a.getJSONObject(it)) }
    }

    suspend fun get(id: Int, approval: Int? = null): SecretFull =
        SecretFull.from(call("GET", "/api/secrets/$id" + (approval?.let { "?approval=$it" } ?: "")).obj())

    suspend fun totp(id: Int): Totp = call("GET", "/api/secrets/$id/totp").obj().let {
        Totp(it.optString("code"), it.optInt("period", 30), it.optInt("remaining", 30))
    }

    suspend fun favorite(id: Int) { call("POST", "/api/secrets/$id/favorite") }

    suspend fun requestApproval(id: Int, reason: String): Approval =
        approval(call("POST", "/api/secrets/$id/approvals", JSONObject().put("reason", reason)).obj())

    suspend fun approval(aid: Int): Approval = approval(call("GET", "/api/approvals/$aid").obj())

    private fun approval(o: JSONObject) =
        Approval(o.getInt("id"), o.optStr("status"), o.optStr("approve_url"), o.optBoolean("notified"))

    // ---------------------------------------------------------------------------------------

    private suspend fun call(method: String, path: String, body: JSONObject? = null): Any? =
        withContext(Dispatchers.IO) {
            client.newCall(request(method, path, body, sid)).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw error(r, text)
                if (text.isBlank()) null else if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
            }
        }

    private fun request(method: String, path: String, body: JSONObject?, sid: String?): Request {
        val b = Request.Builder().url(base + path)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        if (sid != null) b.header("Authorization", "Bearer $sid")
        val rb = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "POST" -> "{}".toRequestBody(JSON)
            else -> null
        }
        return b.method(method, rb).build()
    }

    private fun error(r: Response, text: String): VaultException {
        val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: "HTTP ${r.code}"
        return VaultException(
            status = r.code,
            message = detail,
            approvalRequired = r.header("X-Approval-Required") == "1",
            webauthnRequired = r.header("X-WebAuthn-Required") == "1",
            retryAfterSec = r.header("Retry-After")?.toIntOrNull(),
        )
    }

    private fun Any?.obj() = this as? JSONObject ?: JSONObject()
    private fun Any?.arr() = this as? JSONArray ?: JSONArray()

    companion object {
        const val USER_AGENT = "aps-vault-android/" + BuildConfig.VERSION_NAME
        private val JSON = "application/json".toMediaType()
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        /** https anywhere; plain http only for the device itself and the emulator's host alias. */
        fun normalizeBase(input: String): String? {
            val v = input.trim().trimEnd('/')
            val u = runCatching { URI(v) }.getOrNull() ?: return null
            val host = u.host?.lowercase() ?: return null
            return when (u.scheme?.lowercase()) {
                "https" -> v
                "http" -> v.takeIf { host in setOf("localhost", "127.0.0.1", "10.0.2.2") }
                else -> null
            }
        }
    }
}
