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
    val totpRequired: Boolean = false,
) : Exception(message) {
    /** The owner's unlock says so in the text; a user's login (0.30) by the `X-TOTP-Required` header. */
    val needsTotp get() = status == 401 && (totpRequired || message.orEmpty().contains("totp_code is required", true))
}

/** Who holds the session: the owner (master password) or a named user with roles per folder (0.20). */
data class Identity(val kind: String, val email: String, val name: String, val grants: Map<Int, String>) {
    val isOwner get() = kind == "owner"
    fun role(folderId: Int): String? = if (isOwner) "owner" else grants[folderId]
    fun canWrite(folderId: Int) = isOwner || role(folderId) in setOf("writer", "manager")
}

data class Folder(val id: Int, val name: String, val role: String?)

/** Fields a person edits; null means "leave as is" when updating. */
data class SecretEdit(
    val folderId: Int,
    val name: String,
    val value: String?,
    val login: String,
    val url: String,
    val notes: String,
    val tags: String,
    val totpSeed: String?,
    /** YYYY-MM-DD; empty clears the deadline. */
    val expiresAt: String = "",
    val machineOnly: Boolean = false,
    val requireApproval: Boolean = false,
)

/** Unlock paths besides the master password (contract, section 8). */
data class UnlockOptions(val hsmPin: Boolean, val kmsPin: Boolean)

data class SecretItem(
    val id: Int,
    val folderId: Int,
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
    val createdAt: String = "",
    val lastAccessed: String = "",
    val accessCount: Int = 0,
    val version: Int = 1,
    val rotation: Rotation? = null,
) {
    val host: String get() = runCatching { URI(url).host ?: "" }.getOrDefault("")

    companion object {
        fun from(o: JSONObject) = SecretItem(
            id = o.getInt("id"),
            folderId = o.optInt("folder_id"),
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
            createdAt = o.optStr("created_at"),
            lastAccessed = o.optStr("last_accessed"),
            accessCount = o.optInt("access_count"),
            version = o.optInt("version", 1),
            rotation = o.optJSONObject("rotation")?.let {
                Rotation(it.optStr("target"), it.optInt("interval_days"), it.optBoolean("enabled"),
                    it.optStr("next_at"), it.optStr("last_at"), it.optStr("last_status"), it.optStr("last_error"))
            },
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

/** Rotation in a target system (0.24): where, how often, how the last run went. */
data class Rotation(
    val target: String, val intervalDays: Int, val enabled: Boolean,
    val nextAt: String, val lastAt: String, val lastStatus: String, val lastError: String,
)

data class HistoryEntry(val version: Int, val value: String, val hidden: Boolean, val changedAt: String, val changedBy: String)

data class ShareLink(val url: String, val expiresAt: String, val maxUses: Int)

data class Approval(val id: Int, val status: String, val approveUrl: String, val notified: Boolean)

private fun JSONObject.optStr(k: String): String = if (isNull(k)) "" else optString(k, "")

/**
 * The vault's REST API as a person's client sees it (docs/CLIENT-CONTRACT.md).
 * The session travels as `Authorization: Bearer <sid>`; no cookie jar, so no CSRF either.
 */
class VaultApi(val base: String, @Volatile var sid: String? = null) {

    suspend fun health(): JSONObject = call("GET", "/api/health").obj()

    /** The owner unlocks with the master password; returns the session id. */
    suspend fun unlock(password: String, totp: String?): String {
        val body = JSONObject().put("master_password", password)
        if (!totp.isNullOrBlank()) body.put("totp_code", totp.trim())
        return session("/api/auth/unlock", body)
    }

    /** A named user signs in with e-mail and password (0.20), TOTP when they enabled it (0.30). */
    suspend fun login(email: String, password: String, totp: String?): String {
        val body = JSONObject().put("email", email.trim()).put("password", password)
        if (!totp.isNullOrBlank()) body.put("totp_code", totp.trim())
        return session("/api/auth/login", body)
    }

    /** PIN of the hardware token (`hsm`) or of the cloud KMS cell (`kms`), 0.15 / 0.16. */
    suspend fun pinUnlock(kind: String, pin: String): String = session("/api/auth/$kind/unlock", JSONObject().put("pin", pin))

    suspend fun unlockOptions(): UnlockOptions {
        val hsm = runCatching { call("GET", "/api/auth/hsm/status").obj() }.getOrNull()
        val kms = runCatching { call("GET", "/api/auth/kms/status").obj() }.getOrNull()
        return UnlockOptions(
            hsmPin = hsm?.optBoolean("enabled") == true && hsm.optBoolean("auto") != true,
            kmsPin = kms?.optBoolean("enabled") == true && kms.optBoolean("pin_bound"),
        )
    }

    suspend fun me(): Identity {
        val o = call("GET", "/api/me").obj()
        val g = o.optJSONObject("grants") ?: JSONObject()
        return Identity(o.optStr("kind").ifEmpty { "owner" }, o.optStr("email"), o.optStr("name"),
            g.keys().asSequence().associate { it.toInt() to g.getString(it) })
    }

    suspend fun folders(): List<Folder> {
        val a = call("GET", "/api/folders").arr()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { Folder(it.getInt("id"), it.optStr("name"), it.optStr("role").ifEmpty { null }) }
    }

    suspend fun create(e: SecretEdit): Int =
        call("POST", "/api/secrets", fields(e, null).put("folder_id", e.folderId)).obj().optInt("id")

    /** Sends only what differs from [old]; a hidden (machine-only) value is never sent back. */
    suspend fun update(id: Int, e: SecretEdit, old: SecretFull) {
        val body = fields(e, old)
        if (e.folderId != old.item.folderId) body.put("folder_id", e.folderId)
        if (body.length() > 0) call("PATCH", "/api/secrets/$id", body)
    }

    private fun fields(e: SecretEdit, old: SecretFull?): JSONObject {
        val o = JSONObject()
        fun put(k: String, v: String?, was: String?) { if (v != null && v != was) o.put(k, v) }
        put("name", e.name.trim(), old?.item?.name)
        if (old == null || !old.valueHidden) put("value", e.value, old?.value)
        put("login", e.login, old?.login)
        put("url", e.url.trim(), old?.item?.url)
        put("notes", e.notes, old?.notes)
        put("tags", e.tags.trim(), old?.item?.tags)
        if (!e.totpSeed.isNullOrBlank()) o.put("totp_seed", e.totpSeed.replace(" ", ""))
        val oldExp = old?.item?.expiresAt?.take(10).orEmpty()
        if (e.expiresAt != oldExp) { if (e.expiresAt.isEmpty()) { if (old != null) o.put("clear_expires", true) } else o.put("expires_at", e.expiresAt) }
        if (e.machineOnly != (old?.item?.machineOnly ?: false)) o.put("machine_only", e.machineOnly)
        if (e.requireApproval != (old?.item?.requireApproval ?: false)) o.put("require_approval", e.requireApproval)
        return o
    }

    /** Posts a sign-in request and returns the session id, read from the `vault_session` cookie. */
    private suspend fun session(path: String, body: JSONObject): String {
        return withContext(Dispatchers.IO) {
            client.newCall(request("POST", path, body, null)).execute().use { r ->
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

    suspend fun delete(id: Int) { call("DELETE", "/api/secrets/$id") }

    /** A fresh server-generated value; the old one becomes the previous version. */
    suspend fun rotate(id: Int, generate: String = "alnum:24") { call("POST", "/api/secrets/$id/rotate", JSONObject().put("generate", generate)) }

    suspend fun history(id: Int): List<HistoryEntry> {
        val o = call("GET", "/api/secrets/$id/history").obj()
        val a = o.optJSONArray("history") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { !it.has("error") }.map {
            HistoryEntry(it.optInt("version"), it.optStr("value"), it.optBoolean("hidden"), it.optStr("changed_at"), it.optStr("changed_by"))
        }
    }

    /** One-time link for a person (contract, section 6); needs the manager role. */
    suspend fun share(id: Int, ttlMinutes: Int, maxUses: Int, note: String): ShareLink {
        val o = call("POST", "/api/share", JSONObject().put("secret_id", id).put("ttl_minutes", ttlMinutes)
            .put("max_uses", maxUses).put("note", note)).obj()
        val u = o.optStr("url")
        return ShareLink(if (u.startsWith("http")) u else base + u, o.optStr("expires_at"), o.optInt("max_uses", maxUses))
    }

    suspend fun createFolder(name: String, description: String = ""): Int =
        call("POST", "/api/folders", JSONObject().put("name", name).put("description", description)).obj().optInt("id")

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
            totpRequired = r.header("X-TOTP-Required") == "1",
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
