package ru.devkz.apsvault.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.devkz.apsvault.ApprovalState
import ru.devkz.apsvault.Identity
import ru.devkz.apsvault.BuildConfig
import ru.devkz.apsvault.R
import ru.devkz.apsvault.Screen
import ru.devkz.apsvault.SecretFull
import ru.devkz.apsvault.SecretItem
import ru.devkz.apsvault.VaultViewModel

@Composable
fun VaultApp(vm: VaultViewModel, biometricAvailable: Boolean, onBiometric: () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = colors) {
        val snack = remember { SnackbarHostState() }
        val errorText = vm.error?.let { errorMessage(it) }
        val known = mapOf("saved" to stringResource(R.string.edit_saved), "deleted" to stringResource(R.string.msg_deleted),
            "rotated" to stringResource(R.string.msg_rotated))
        LaunchedEffect(vm.info) { vm.info?.let { snack.showSnackbar(known[it] ?: it); vm.info = null } }
        LaunchedEffect(errorText, vm.screen) {
            if (errorText != null && vm.screen !is Screen.Unlock && vm.screen !is Screen.Setup && vm.screen !is Screen.Edit) {
                snack.showSnackbar(errorText); vm.error = null
            }
        }
        Surface(Modifier.fillMaxSize()) {
            when (val s = vm.screen) {
                Screen.Setup -> SetupScreen(vm)
                Screen.Unlock -> UnlockScreen(vm, biometricAvailable, onBiometric, errorText)
                Screen.List -> ListScreen(vm, snack)
                is Screen.Detail -> DetailScreen(vm, s.id, snack)
                is Screen.Edit -> EditScreen(vm, s.id)
                Screen.Settings -> SettingsScreen(vm, biometricAvailable)
            }
        }
    }
}

@Composable
private fun errorMessage(code: String): String = when {
    code == "bad_url" -> stringResource(R.string.setup_bad_url)
    code == "webauthn_required" -> stringResource(R.string.err_webauthn)
    code == "session_expired" -> stringResource(R.string.err_session_expired)
    code == "stored_session_unreadable" -> stringResource(R.string.err_stored_unreadable)
    code == "name_required" -> stringResource(R.string.edit_name_required)
    code == "bad_secret_url" -> stringResource(R.string.edit_bad_url)
    code.startsWith("lockout:") -> stringResource(R.string.err_lockout, ((code.substringAfter(':').toIntOrNull() ?: 900) + 59) / 60)
    else -> code
}

// ---- setup ----------------------------------------------------------------------------------

@Composable
private fun SetupScreen(vm: VaultViewModel) {
    var url by rememberSaveable { mutableStateOf(vm.base ?: "https://") }
    CenteredForm {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it; vm.error = null },
            label = { Text(stringResource(R.string.setup_title)) },
            supportingText = { Text(if (vm.error == "bad_url") stringResource(R.string.setup_bad_url) else stringResource(R.string.setup_hint)) },
            isError = vm.error == "bad_url",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.saveBase(url) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { vm.saveBase(url) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_save)) }
    }
}

// ---- unlock ---------------------------------------------------------------------------------

@Composable
private fun UnlockScreen(vm: VaultViewModel, biometricAvailable: Boolean, onBiometric: () -> Unit, errorText: String?) {
    var password by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(vm.loginEmail) }
    var pinMode by remember { mutableStateOf(false) }
    val user = vm.loginMode == "user"
    val canBio = biometricAvailable && vm.canUseStoredSession
    var autoPrompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(canBio) { if (canBio && !autoPrompted) { autoPrompted = true; onBiometric() } }
    val pinKind = if (vm.unlockOptions.hsmPin) "hsm" else "kms"
    val submit = {
        if (password.isNotEmpty() && !vm.busy && (!user || email.isNotBlank())) {
            if (pinMode && !user) vm.unlockWithPin(pinKind, password) else vm.unlock(password, totp.ifBlank { null }, email)
        }
    }
    CenteredForm {
        Icon(Icons.Filled.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Text(vm.base.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !user, onClick = { vm.chooseLoginMode("owner"); totp = "" },
                shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.unlock_mode_owner)) }
            SegmentedButton(selected = user, onClick = { vm.chooseLoginMode("user"); totp = ""; pinMode = false },
                shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.unlock_mode_user)) }
        }
        Spacer(Modifier.height(12.dp))
        if (user) {
            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text(stringResource(R.string.unlock_email)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text(stringResource(when { user -> R.string.unlock_password; pinMode -> R.string.unlock_pin; else -> R.string.unlock_master })) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (vm.needTotp) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (vm.needTotp) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = totp, onValueChange = { v -> totp = v.filter { it.isDigit() }.take(6) },
                label = { Text(stringResource(R.string.unlock_totp)) },
                supportingText = { Text(stringResource(R.string.unlock_totp_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (errorText != null) {
            Spacer(Modifier.height(8.dp))
            Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = submit, enabled = !vm.busy && password.isNotEmpty() && (!user || email.isNotBlank()), modifier = Modifier.fillMaxWidth()) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.unlock_button))
        }
        if (canBio) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBiometric, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Fingerprint, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.unlock_biometric))
            }
        }
        if (!user && (vm.unlockOptions.hsmPin || vm.unlockOptions.kmsPin)) {
            TextButton(onClick = { pinMode = !pinMode; password = "" }) {
                Text(stringResource(if (pinMode) R.string.unlock_use_master else R.string.unlock_use_pin))
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = vm::editAddress) { Text(stringResource(R.string.unlock_change_address)) }
    }
}

@Composable
internal fun CenteredForm(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            content()
        }
    }
}

@Composable
internal fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ApprovalBlock(vm: VaultViewModel, id: Int) {
    val ctx = LocalContext.current
    var reason by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.approval_note), style = MaterialTheme.typography.bodyMedium)
        when (val a = vm.approval) {
            ApprovalState.None, is ApprovalState.Ended -> {
                if (a is ApprovalState.Ended) Text(stringResource(if (a.status == "denied") R.string.approval_denied else R.string.approval_expired),
                    color = MaterialTheme.colorScheme.error)
                OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text(stringResource(R.string.approval_reason)) },
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.requestApproval(id, reason) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.approval_request)) }
            }
            ApprovalState.Requesting -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is ApprovalState.Waiting -> {
                if (a.approveUrl == null) Text(stringResource(R.string.approval_waiting))
                else {
                    Text(stringResource(R.string.approval_pass_link))
                    Text(a.approveUrl, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    val shareLabel = stringResource(R.string.approval_share_link)
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, a.approveUrl), shareLabel))
                    }) { Text(shareLabel) }
                }
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is ApprovalState.Granted -> Text(stringResource(R.string.approval_granted))
        }
    }
}

// ---- settings -------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(vm: VaultViewModel, biometricAvailable: Boolean) {
    BackHandler { vm.closeSettings() }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = { IconButton(onClick = vm::closeSettings) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            vm.identity?.let { who ->
                Column {
                    FieldLabel(stringResource(R.string.s_signed_in))
                    Text(whoText(who))
                    if (!who.isOwner) {
                        val names = vm.folders.associate { it.id to it.name }
                        who.grants.forEach { (fid, role) ->
                            Text("${names[fid] ?: "#$fid"}: ${roleText(role)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Column {
                FieldLabel(stringResource(R.string.s_address))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(vm.base.orEmpty(), modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.lock(); vm.editAddress() }) { Text(stringResource(R.string.s_change)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.s_biometric), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(if (biometricAvailable) R.string.s_biometric_hint else R.string.s_biometric_unavailable),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = vm.biometricEnabled && biometricAvailable, enabled = biometricAvailable, onCheckedChange = vm::setBiometric)
            }
            val ctx = LocalContext.current
            val afm = ctx.getSystemService(android.view.autofill.AutofillManager::class.java)
            var autofillOn by remember { mutableStateOf(afm?.hasEnabledAutofillServices() == true) }
            LaunchedEffect(Unit) { autofillOn = afm?.hasEnabledAutofillServices() == true }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.s_autofill), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.s_autofill_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (autofillOn) Text(stringResource(R.string.s_autofill_on), color = MaterialTheme.colorScheme.primary)
                else TextButton(onClick = {
                    ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                        .setData(Uri.parse("package:" + ctx.packageName)))
                }) { Text(stringResource(R.string.s_autofill_enable)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.s_autolock), modifier = Modifier.weight(1f))
                listOf(1, 5, 15, 60).forEach { m ->
                    val sel = vm.autolockMin == m
                    TextButton(onClick = { vm.setAutolock(m) }) {
                        Text("$m", fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            OutlinedButton(onClick = { vm.lock() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.lock)) }
            Text(stringResource(R.string.s_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun roleText(role: String) = stringResource(when (role) {
    "manager" -> R.string.role_manager
    "writer" -> R.string.role_writer
    "owner" -> R.string.role_owner
    else -> R.string.role_reader
})

@Composable
internal fun whoText(id: Identity) =
    if (id.isOwner) stringResource(R.string.who_owner) else listOf(id.name, id.email).filter { it.isNotBlank() }.distinct().joinToString(" · ")
