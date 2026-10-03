package ru.devkz.apsvault

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.crypto.Cipher

sealed interface Screen {
    data object Setup : Screen
    data object Unlock : Screen
    data object List : Screen
    data class Detail(val id: Int) : Screen
    data object Settings : Screen
}

sealed interface ApprovalState {
    data object None : ApprovalState
    data object Requesting : ApprovalState
    data class Waiting(val id: Int, val approveUrl: String?) : ApprovalState
    data class Granted(val id: Int) : ApprovalState
    data class Ended(val status: String) : ApprovalState
}

class VaultViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val store = SessionStore(app)

    var base by mutableStateOf(prefs.getString("base", null))
        private set
    var biometricEnabled by mutableStateOf(prefs.getBoolean("biometric", false))
        private set
    var autolockMin by mutableStateOf(prefs.getInt("autolock_min", 5))
        private set

    var screen by mutableStateOf<Screen>(if (base == null) Screen.Setup else Screen.Unlock)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    var needTotp by mutableStateOf(false)
        private set

    var secrets by mutableStateOf<kotlin.collections.List<SecretItem>>(emptyList())
        private set
    var query by mutableStateOf("")

    var detail by mutableStateOf<SecretFull?>(null)
        private set
    var totp by mutableStateOf<Totp?>(null)
        private set
    var approval by mutableStateOf<ApprovalState>(ApprovalState.None)
        private set

    private var api: VaultApi? = base?.let { VaultApi(it) }
    private var totpJob: Job? = null
    private var approvalJob: Job? = null
    private var backgroundSince = 0L

    val canUseStoredSession get() = base?.let { biometricEnabled && store.hasStored(it) } == true

    // ---- setup / settings -----------------------------------------------------------------

    fun saveBase(input: String): Boolean {
        val b = VaultApi.normalizeBase(input) ?: return false.also { error = "bad_url" }
        if (b != base) {
            api?.sid?.let { sid -> viewModelScope.launch { runCatching { VaultApi(base!!, sid).lock() } } }
            store.clear()
        }
        prefs.edit().putString("base", b).apply()
        base = b
        api = VaultApi(b)
        error = null
        screen = Screen.Unlock
        return true
    }

    fun setBiometric(on: Boolean) {
        biometricEnabled = on
        prefs.edit().putBoolean("biometric", on).apply()
        if (!on) store.deleteKey()
        else api?.sid?.let { sid -> base?.let { store.save(it, sid) } }
    }

    fun setAutolock(min: Int) {
        autolockMin = min.coerceIn(1, 480)
        prefs.edit().putInt("autolock_min", autolockMin).apply()
    }

    fun openSettings() { screen = Screen.Settings }
    fun closeSettings() { screen = if (api?.sid != null) Screen.List else if (base == null) Screen.Setup else Screen.Unlock }
    fun editAddress() { screen = Screen.Setup }

    // ---- unlock / lock --------------------------------------------------------------------

    fun unlock(password: String, totpCode: String?) {
        val a = api ?: return
        busy = true; error = null
        viewModelScope.launch {
            try {
                val sid = a.unlock(password, totpCode)
                a.sid = sid
                needTotp = false
                if (biometricEnabled) runCatching { store.save(a.base, sid) }
                enterList()
            } catch (e: VaultException) {
                when {
                    e.needsTotp -> { needTotp = true; error = null }
                    e.webauthnRequired -> error = "webauthn_required"
                    e.status == 429 -> error = "lockout:" + (e.retryAfterSec ?: 900)
                    else -> error = e.message
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally { busy = false }
        }
    }

    /** BiometricPrompt has authorised the cipher: read the stored sid and check it is still alive. */
    fun unlockWithCipher(cipher: Cipher) {
        val a = api ?: return
        val sid = store.read(cipher) ?: run { store.clear(); error = "stored_session_unreadable"; return }
        busy = true; error = null
        viewModelScope.launch {
            try {
                a.sid = sid
                a.checkSession()
                enterList()
            } catch (e: VaultException) {
                a.sid = null
                if (e.status == 401) { store.clear(); error = "session_expired" } else error = e.message
            } catch (e: Exception) {
                a.sid = null
                error = e.message ?: e.javaClass.simpleName
            } finally { busy = false }
        }
    }

    fun lock(everywhere: Boolean = true) {
        val a = api
        val sid = a?.sid
        dropLocal()
        store.clear()
        if (everywhere && a != null && sid != null) {
            viewModelScope.launch { runCatching { VaultApi(a.base, sid).lock() } }
        }
    }

    /** Forget the session in memory only; the biometric-protected copy stays. */
    private fun dropLocal() {
        api?.sid = null
        secrets = emptyList(); detail = null; totp = null; query = ""
        totpJob?.cancel(); approvalJob?.cancel(); approval = ApprovalState.None
        screen = if (base == null) Screen.Setup else Screen.Unlock
    }

    fun onBackground() { backgroundSince = System.currentTimeMillis() }

    fun onForeground() {
        if (backgroundSince == 0L || api?.sid == null) return
        if (System.currentTimeMillis() - backgroundSince > autolockMin * 60_000L) dropLocal()
        backgroundSince = 0L
    }

    // ---- list / detail --------------------------------------------------------------------

    private suspend fun enterList() {
        screen = Screen.List
        loadList()
    }

    fun refresh() = viewModelScope.launch { loadList() }

    private suspend fun loadList() {
        val a = api ?: return
        try { secrets = a.list() } catch (e: Exception) { handle(e) }
    }

    fun filtered(): kotlin.collections.List<SecretItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return secrets.sortedWith(compareByDescending<SecretItem> { it.isFavorite }.thenBy { it.name.lowercase() })
        return secrets.mapNotNull { s ->
            val score = when {
                s.name.lowercase().startsWith(q) -> 3
                "${s.name} ${s.tags} ${s.url} ${s.folderName}".lowercase().contains(q) -> 2
                else -> 0
            }
            if (score > 0) s to score else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    fun open(item: SecretItem) {
        detail = null; totp = null; approval = ApprovalState.None
        screen = Screen.Detail(item.id)
        if (item.requireApproval) return       // reading needs an approval first (contract, section 4)
        load(item.id, null)
    }

    private fun load(id: Int, approvalId: Int?) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                val full = a.get(id, approvalId)
                detail = full
                if (full.item.hasTotp) startTotp(id)
            } catch (e: Exception) { handle(e) }
        }
    }

    fun back() {
        totpJob?.cancel(); approvalJob?.cancel()
        detail = null; totp = null; approval = ApprovalState.None
        screen = Screen.List
    }

    fun toggleFavorite(id: Int) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                a.favorite(id)
                secrets = secrets.map { if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it }
                detail = detail?.let { d -> if (d.item.id == id) d.copy(item = d.item.copy(isFavorite = !d.item.isFavorite)) else d }
            } catch (e: Exception) { handle(e) }
        }
    }

    private fun startTotp(id: Int) {
        totpJob?.cancel()
        val a = api ?: return
        totpJob = viewModelScope.launch {
            while (true) {
                val t = try { a.totp(id) } catch (e: Exception) { handle(e); return@launch }
                for (left in t.remaining downTo 1) {
                    totp = t.copy(remaining = left)
                    delay(1000)
                }
            }
        }
    }

    fun requestApproval(id: Int, reason: String) {
        val a = api ?: return
        approval = ApprovalState.Requesting
        approvalJob?.cancel()
        approvalJob = viewModelScope.launch {
            try {
                val r = a.requestApproval(id, reason)
                approval = ApprovalState.Waiting(r.id, r.approveUrl.takeIf { !r.notified && it.isNotEmpty() }?.let { absolute(it) })
                while (true) {
                    delay(3000)
                    val s = a.approval(r.id)
                    when (s.status) {
                        "pending" -> continue
                        "approved" -> { approval = ApprovalState.Granted(r.id); load(id, r.id); return@launch }
                        else -> { approval = ApprovalState.Ended(s.status); return@launch }
                    }
                }
            } catch (e: Exception) { approval = ApprovalState.None; handle(e) }
        }
    }

    private fun absolute(u: String) = if (u.startsWith("http")) u else (base ?: "") + u

    private fun handle(e: Exception) {
        if (e is VaultException && e.status == 401) {
            store.clear(); dropLocal(); error = "session_expired"; return
        }
        info = null
        error = e.message ?: e.javaClass.simpleName
    }

    fun copy(context: Context, label: String, text: String, toast: String) {
        Clipboard.copy(context, label, text)
        info = toast
    }
}
