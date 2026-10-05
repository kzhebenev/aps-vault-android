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
    /** Create (id == null) or edit a secret. */
    data class Edit(val id: Int?) : Screen
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
    companion object {
        const val ALL = -1
        const val FAVORITES = -2
    }

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val store = SessionStore(app)

    var base by mutableStateOf(prefs.getString("base", null))
        private set
    var biometricEnabled by mutableStateOf(prefs.getBoolean("biometric", false))
        private set
    var autolockMin by mutableStateOf(prefs.getInt("autolock_min", 5))
        private set

    var screen by mutableStateOf<Screen>(
        when {
            base == null -> Screen.Setup
            Vault.unlocked(app) -> Screen.List
            else -> Screen.Unlock
        })
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    var needTotp by mutableStateOf(false)
        private set

    /** "owner" signs in with the master password, "user" with e-mail and password (0.20). */
    var loginMode by mutableStateOf(prefs.getString("login_mode", "owner") ?: "owner")
        private set
    var loginEmail by mutableStateOf(prefs.getString("login_email", "") ?: "")
        private set
    var unlockOptions by mutableStateOf(UnlockOptions(hsmPin = false, kmsPin = false))
        private set
    var identity by mutableStateOf<Identity?>(Vault.identity)
        private set
    var folders by mutableStateOf<kotlin.collections.List<Folder>>(emptyList())
        private set

    /** List filter: ALL, FAVORITES or a folder id. */
    var folderFilter by mutableStateOf(prefs.getInt("folder_filter", ALL))
        private set
    var history by mutableStateOf<kotlin.collections.List<HistoryEntry>?>(null)
        private set
    var shareLink by mutableStateOf<ShareLink?>(null)

    /** A newer release on GitHub, checked once per process start; null when none or offline. */
    var update by mutableStateOf<Updater.Release?>(null)
        private set
    var updateChecked by mutableStateOf(false)
        private set

    fun checkUpdate() {
        viewModelScope.launch { update = Updater.check(); updateChecked = true }
    }

    var secrets by mutableStateOf<kotlin.collections.List<SecretItem>>(emptyList())
        private set
    var query by mutableStateOf("")

    var detail by mutableStateOf<SecretFull?>(null)
        private set
    var totp by mutableStateOf<Totp?>(null)
        private set
    var approval by mutableStateOf<ApprovalState>(ApprovalState.None)
        private set

    private var api: VaultApi?
        get() = Vault.api
        set(v) { Vault.api = v }
    private var totpJob: Job? = null
    private var approvalJob: Job? = null
    private var backgroundSince = 0L

    init {
        Vault.current(app)
        if (screen == Screen.List) refresh()
        loadUnlockOptions()
        checkUpdate()
    }

    fun chooseLoginMode(mode: String) {
        loginMode = mode; needTotp = false; error = null
        prefs.edit().putString("login_mode", mode).apply()
    }

    private fun loadUnlockOptions() {
        val a = api ?: return
        viewModelScope.launch { runCatching { unlockOptions = a.unlockOptions() } }
    }

    /** Folders this session may write to: all for the owner, writer / manager grants for a user. */
    val writableFolders get() = identity?.let { id -> folders.filter { id.canWrite(it.id) } }.orEmpty()
    fun canWrite(folderId: Int) = identity?.canWrite(folderId) == true

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
        loadUnlockOptions()
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

    fun unlock(password: String, totpCode: String?, email: String = "") {
        val a = api ?: return
        busy = true; error = null
        if (loginMode == "user") { loginEmail = email.trim(); prefs.edit().putString("login_email", loginEmail).apply() }
        viewModelScope.launch {
            try {
                val sid = if (loginMode == "user") a.login(email, password, totpCode) else a.unlock(password, totpCode)
                a.sid = sid
                Vault.touch()
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

    /** PIN of the hardware token or the KMS cell; gives the owner's session (contract, section 8). */
    fun unlockWithPin(kind: String, pin: String) {
        val a = api ?: return
        busy = true; error = null
        viewModelScope.launch {
            try {
                val sid = a.pinUnlock(kind, pin)
                a.sid = sid
                Vault.touch()
                if (biometricEnabled) runCatching { store.save(a.base, sid) }
                enterList()
            } catch (e: VaultException) {
                error = if (e.status == 429) "lockout:" + (e.retryAfterSec ?: 900) else e.message
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
                identity = a.me(); Vault.identity = identity
                Vault.touch()
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
        Vault.identity = null; identity = null; folders = emptyList()
        secrets = emptyList(); detail = null; totp = null; query = ""
        totpJob?.cancel(); approvalJob?.cancel(); approval = ApprovalState.None
        screen = if (base == null) Screen.Setup else Screen.Unlock
    }

    fun onBackground() { backgroundSince = System.currentTimeMillis() }

    fun onForeground() {
        if (api?.sid == null) return
        if ((backgroundSince != 0L && System.currentTimeMillis() - backgroundSince > autolockMin * 60_000L) ||
            !Vault.unlocked(getApplication())) dropLocal()
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
        try {
            if (identity == null) { identity = a.me(); Vault.identity = identity }
            folders = a.folders()
            secrets = a.list()
            Vault.touch()
        } catch (e: Exception) { handle(e) }
    }

    // ---- create / edit --------------------------------------------------------------------

    fun newSecret() { error = null; detail = null; screen = Screen.Edit(null) }

    fun editSecret() { detail?.let { error = null; totpJob?.cancel(); screen = Screen.Edit(it.item.id) } }

    fun cancelEdit() {
        val s = screen as? Screen.Edit ?: return
        screen = if (s.id != null && detail != null) Screen.Detail(s.id).also { detail?.let { d -> if (d.item.hasTotp) startTotp(d.item.id) } } else Screen.List
    }

    fun save(e: SecretEdit) {
        val a = api ?: return
        val s = screen as? Screen.Edit ?: return
        if (e.name.isBlank()) { error = "name_required"; return }
        if (e.url.isNotBlank() && !e.url.trim().startsWith("http://") && !e.url.trim().startsWith("https://")) { error = "bad_secret_url"; return }
        busy = true; error = null
        viewModelScope.launch {
            try {
                val id = if (s.id == null) a.create(e) else { a.update(s.id, e, detail!!); s.id }
                Vault.touch()
                secrets = a.list()
                val item = secrets.firstOrNull { it.id == id }
                if (item != null) open(item) else back()
                info = "saved"
            } catch (ex: Exception) { handle(ex) } finally { busy = false }
        }
    }

    fun selectFolder(f: Int) {
        folderFilter = f
        prefs.edit().putInt("folder_filter", f).apply()
    }

    fun isManager(folderId: Int) = identity?.let { it.isOwner || it.role(folderId) == "manager" } == true

    fun createFolder(name: String) {
        val a = api ?: return
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val id = a.createFolder(name.trim())
                folders = a.folders()
                selectFolder(id)
            } catch (e: Exception) { handle(e) }
        }
    }

    /** Search ignores the folder filter: a match anywhere is more useful than an empty tab. */
    fun filtered(): kotlin.collections.List<SecretItem> {
        val q = query.trim().lowercase()
        val base = when (folderFilter) {
            ALL -> secrets
            FAVORITES -> secrets.filter { it.isFavorite }
            else -> secrets.filter { it.folderId == folderFilter }
        }
        if (q.isEmpty()) return base.sortedWith(compareByDescending<SecretItem> { it.isFavorite }.thenBy { it.name.lowercase() })
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
                Vault.touch()
                detail = full
                if (full.item.hasTotp) startTotp(id)
            } catch (e: Exception) { handle(e) }
        }
    }

    fun back() {
        totpJob?.cancel(); approvalJob?.cancel()
        detail = null; totp = null; approval = ApprovalState.None; history = null; shareLink = null
        screen = Screen.List
    }

    fun loadHistory() {
        val a = api ?: return
        val id = detail?.item?.id ?: return
        viewModelScope.launch { try { history = a.history(id); Vault.touch() } catch (e: Exception) { handle(e) } }
    }

    fun closeHistory() { history = null }

    fun share(ttlMinutes: Int, maxUses: Int, note: String) {
        val a = api ?: return
        val id = detail?.item?.id ?: return
        viewModelScope.launch { try { shareLink = a.share(id, ttlMinutes, maxUses, note); Vault.touch() } catch (e: Exception) { handle(e) } }
    }

    fun delete() {
        val a = api ?: return
        val id = detail?.item?.id ?: return
        viewModelScope.launch {
            try {
                a.delete(id)
                secrets = secrets.filter { it.id != id }
                back(); info = "deleted"
            } catch (e: Exception) { handle(e) }
        }
    }

    fun rotateValue() {
        val a = api ?: return
        val id = detail?.item?.id ?: return
        viewModelScope.launch {
            try {
                a.rotate(id)
                secrets = a.list()
                load(id, null)
                info = "rotated"
            } catch (e: Exception) { handle(e) }
        }
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
