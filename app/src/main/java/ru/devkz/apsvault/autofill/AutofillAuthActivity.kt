package ru.devkz.apsvault.autofill

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.os.Parcelable
import android.service.autofill.Dataset
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.devkz.apsvault.BuildConfig
import ru.devkz.apsvault.R
import ru.devkz.apsvault.SecretItem
import ru.devkz.apsvault.SessionStore
import ru.devkz.apsvault.Vault
import ru.devkz.apsvault.VaultApi
import ru.devkz.apsvault.VaultException

/** Opened by the autofill framework: unlock if needed, then read one secret or let the person pick one. */
class AutofillAuthActivity : FragmentActivity() {
    private lateinit var form: Form
    private var mode = MODE_DATASET
    private var secretId = 0
    private val store by lazy { SessionStore(this) }

    private var phase by mutableStateOf(Phase.Working)
    private var needTotp by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var secrets by mutableStateOf<List<SecretItem>>(emptyList())

    enum class Phase { Working, Password, Pick }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        form = intent.form()
        mode = intent.getIntExtra(EXTRA_MODE, MODE_DATASET)
        secretId = intent.getIntExtra(EXTRA_SECRET, 0)
        setResult(Activity.RESULT_CANCELED)
        setContent { Screen() }
        if (Vault.unlocked(this)) proceed() else startUnlock()
    }

    // ---- unlock -------------------------------------------------------------------------------

    private fun startUnlock() {
        val base = Vault.base(this) ?: return finish()
        val bioReady = BiometricManager.from(this).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        val cipher = if (bioReady && Vault.biometricEnabled(this) && store.hasStored(base)) store.decryptCipher() else null
        if (cipher == null) { phase = Phase.Password; return }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val sid = result.cryptoObject?.cipher?.let(store::read) ?: return run { phase = Phase.Password }
                lifecycleScope.launch {
                    val api = Vault.current(this@AutofillAuthActivity) ?: return@launch finish()
                    api.sid = sid
                    try { api.checkSession(); Vault.touch(); proceed() }
                    catch (e: Exception) {
                        api.sid = null
                        if (e is VaultException && e.status == 401) store.clear()
                        phase = Phase.Password
                    }
                }
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) { phase = Phase.Password }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.bio_title))
                .setNegativeButtonText(getString(R.string.bio_cancel)).setAllowedAuthenticators(BIOMETRIC_STRONG).build(),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    private fun unlockWithPassword(password: String, totp: String) {
        val api = Vault.current(this) ?: return finish()
        phase = Phase.Working; error = null
        lifecycleScope.launch {
            try {
                val sid = api.unlock(password, totp.ifBlank { null })
                api.sid = sid
                Vault.touch()
                if (Vault.biometricEnabled(this@AutofillAuthActivity)) runCatching { store.save(api.base, sid) }
                proceed()
            } catch (e: VaultException) {
                if (e.needsTotp) needTotp = true else error = e.message
                phase = Phase.Password
            } catch (e: Exception) {
                error = e.message; phase = Phase.Password
            }
        }
    }

    // ---- results ------------------------------------------------------------------------------

    private fun proceed() {
        val api = Vault.current(this) ?: return finish()
        phase = Phase.Working
        lifecycleScope.launch {
            try {
                when (mode) {
                    MODE_UNLOCK -> {
                        val list = api.list()
                        done(VaultAutofillService.response(this@AutofillAuthActivity, form, FormParser.matches(form, list)))
                    }
                    MODE_PICK -> {
                        secrets = api.list().filter { !it.machineOnly && !it.requireApproval }
                        phase = Phase.Pick
                    }
                    else -> done(dataset(secretId))
                }
            } catch (e: VaultException) {
                if (e.approvalRequired) toast(getString(R.string.af_needs_approval)) else toast(e.message.orEmpty())
                finish()
            } catch (e: Exception) { toast(e.message.orEmpty()); finish() }
        }
    }

    private suspend fun dataset(id: Int): Dataset {
        val api = Vault.current(this)!!
        val s = api.get(id)
        Vault.touch()
        val otp = if (form.otp != null && s.item.hasTotp) runCatching { api.totp(id).code }.getOrNull() else null
        val p = VaultAutofillService.presentation(this, s.item.name, null)
        @Suppress("DEPRECATION")
        val b = Dataset.Builder(p)
        form.username?.let { b.setValue(it, AutofillValue.forText(s.login), p) }
        form.password?.let { if (!s.valueHidden) b.setValue(it, AutofillValue.forText(s.value), p) }
        form.otp?.let { if (otp != null) b.setValue(it, AutofillValue.forText(otp), p) }
        return b.build()
    }

    private fun pick(s: SecretItem) {
        phase = Phase.Working
        lifecycleScope.launch {
            try { done(dataset(s.id)) } catch (e: Exception) { toast(e.message.orEmpty()); finish() }
        }
    }

    private fun done(result: Parcelable) {
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, result))
        finish()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---- UI -----------------------------------------------------------------------------------

    @androidx.compose.runtime.Composable
    private fun Screen() {
        val ctx = LocalContext.current
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
            Surface(Modifier.fillMaxSize()) {
                when (phase) {
                    Phase.Working -> {}
                    Phase.Password -> PasswordForm()
                    Phase.Pick -> PickList()
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun PasswordForm() {
        var password by remember { mutableStateOf("") }
        var totp by remember { mutableStateOf("") }
        Column(Modifier.fillMaxWidth().imePadding().padding(24.dp)) {
            Text(stringResource(R.string.bio_title), style = MaterialTheme.typography.titleLarge)
            Text(Vault.base(this@AutofillAuthActivity).orEmpty(), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.unlock_master)) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { unlockWithPassword(password, totp) }))
            if (needTotp) OutlinedTextField(totp, { v -> totp = v.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.unlock_totp)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { unlockWithPassword(password, totp) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.unlock_button)) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun PickList() {
        var q by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize().imePadding().padding(top = 24.dp)) {
            OutlinedTextField(q, { q = it }, placeholder = { Text(stringResource(R.string.list_search)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            val needle = q.trim().lowercase()
            LazyColumn {
                items(secrets.filter { needle.isEmpty() || "${it.name} ${it.tags} ${it.url} ${it.folderName}".lowercase().contains(needle) }) { s ->
                    Column(Modifier.fillMaxWidth().clickable { pick(s) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge)
                        Text(listOf(s.folderName, s.host).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    companion object {
        const val MODE_UNLOCK = 1
        const val MODE_DATASET = 2
        const val MODE_PICK = 3
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_SECRET = "secret"

        fun sender(ctx: Context, mode: Int, form: Form, secretId: Int?): IntentSender =
            pending(ctx, Intent(ctx, AutofillAuthActivity::class.java).putForm(form)
                .putExtra(EXTRA_MODE, mode).putExtra(EXTRA_SECRET, secretId ?: 0))
    }
}
