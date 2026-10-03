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
        LaunchedEffect(vm.info) { vm.info?.let { snack.showSnackbar(it); vm.info = null } }
        LaunchedEffect(errorText, vm.screen) {
            if (errorText != null && vm.screen !is Screen.Unlock && vm.screen !is Screen.Setup) {
                snack.showSnackbar(errorText); vm.error = null
            }
        }
        Surface(Modifier.fillMaxSize()) {
            when (val s = vm.screen) {
                Screen.Setup -> SetupScreen(vm)
                Screen.Unlock -> UnlockScreen(vm, biometricAvailable, onBiometric, errorText)
                Screen.List -> ListScreen(vm, snack)
                is Screen.Detail -> DetailScreen(vm, s.id, snack)
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
    val canBio = biometricAvailable && vm.canUseStoredSession
    var autoPrompted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(canBio) { if (canBio && !autoPrompted) { autoPrompted = true; onBiometric() } }
    val submit = { if (password.isNotEmpty() && !vm.busy) vm.unlock(password, totp.ifBlank { null }) }
    CenteredForm {
        Icon(Icons.Filled.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
        Text(vm.base.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text(stringResource(R.string.unlock_master)) },
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
        Button(onClick = submit, enabled = !vm.busy && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.unlock_button))
        }
        if (canBio) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBiometric, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Fingerprint, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.unlock_biometric))
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = vm::editAddress) { Text(stringResource(R.string.unlock_change_address)) }
    }
}

@Composable
private fun CenteredForm(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            content()
        }
    }
}

// ---- list -----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListScreen(vm: VaultViewModel, snack: SnackbarHostState) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
                    IconButton(onClick = vm::openSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                    IconButton(onClick = { vm.lock() }) { Icon(Icons.Filled.Lock, stringResource(R.string.lock)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it },
                placeholder = { Text(stringResource(R.string.list_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val items = vm.filtered()
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(if (vm.query.isBlank()) R.string.list_empty else R.string.list_nothing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items, key = { it.id }) { s ->
                        SecretRow(s) { vm.open(s) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SecretRow(s: SecretItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (s.isFavorite) { Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) }
            }
            val meta = listOf(s.folderName, s.host, s.tags).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.machineOnly) Badge(stringResource(R.string.badge_machine))
        if (s.requireApproval) Badge(stringResource(R.string.badge_approval))
    }
}

@Composable
private fun Badge(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp))
}

// ---- detail ---------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScreen(vm: VaultViewModel, id: Int, snack: SnackbarHostState) {
    BackHandler { vm.back() }
    val item = vm.detail?.item ?: vm.secrets.firstOrNull { it.id == id }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (item != null) IconButton(onClick = { vm.toggleFavorite(id) }) {
                        Icon(if (item.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(R.string.favorite))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (item == null) return@Column
            val d = vm.detail
            if (item.requireApproval && vm.approval !is ApprovalState.Granted) {
                ApprovalBlock(vm, id)
            } else if (d == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Fields(vm, d)
            }
            Meta(item)
        }
    }
}

@Composable
private fun Fields(vm: VaultViewModel, d: SecretFull) {
    val ctx = LocalContext.current
    val copiedFmt = stringResource(R.string.copied)
    val loginLabel = stringResource(R.string.f_login)
    val passwordLabel = stringResource(R.string.f_password)
    val totpLabel = stringResource(R.string.f_totp)
    if (d.login.isNotEmpty()) Field(loginLabel, d.login) {
        vm.copy(ctx, loginLabel, d.login, copiedFmt.format(loginLabel))
    }
    if (d.item.machineOnly || d.valueHidden) {
        Column {
            FieldLabel(passwordLabel)
            Text(stringResource(R.string.machine_only_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        var revealed by remember(d.item.id) { mutableStateOf(false) }
        Column {
            FieldLabel(passwordLabel)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (revealed) d.value else "•".repeat(minOf(d.value.length, 16)), fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { revealed = !revealed }) { Text(stringResource(if (revealed) R.string.hide else R.string.show)) }
                TextButton(onClick = { vm.copy(ctx, passwordLabel, d.value, copiedFmt.format(passwordLabel)) }) { Text(stringResource(R.string.copy)) }
            }
        }
    }
    vm.totp?.let { t ->
        Column {
            FieldLabel(totpLabel)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val code = if (t.code.length == 6) t.code.substring(0, 3) + " " + t.code.substring(3) else t.code
                Text(code, fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.totp_left, t.remaining), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.copy(ctx, totpLabel, t.code, copiedFmt.format(totpLabel)) }) { Text(stringResource(R.string.copy)) }
            }
            LinearProgressIndicator(progress = { t.remaining / t.period.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (d.item.url.isNotEmpty()) Column {
        FieldLabel(stringResource(R.string.f_url))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.item.url, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.item.url))) }) { Text(stringResource(R.string.open)) }
        }
    }
    if (d.notes.isNotEmpty()) Column {
        FieldLabel(stringResource(R.string.f_notes))
        Text(d.notes, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Field(label: String, value: String, onCopy: () -> Unit) {
    Column {
        FieldLabel(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onCopy) { Text(stringResource(R.string.copy)) }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Meta(item: SecretItem) {
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MetaLine(stringResource(R.string.f_folder), item.folderName)
        if (item.tags.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
        }
        if (item.expiresAt.isNotBlank()) MetaLine(stringResource(R.string.f_expires), item.expiresAt.take(10))
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ApprovalBlock(vm: VaultViewModel, id: Int) {
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
