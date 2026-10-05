package ru.devkz.apsvault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ru.devkz.apsvault.R
import ru.devkz.apsvault.SecretEdit
import ru.devkz.apsvault.VaultViewModel
import java.security.SecureRandom

private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789-_.!@#%"

/** 20 characters from a 64-symbol alphabet without look-alikes: about 120 bits. */
fun generatePassword(length: Int = 20): String {
    val r = SecureRandom()
    return (1..length).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(vm: VaultViewModel, id: Int?) {
    BackHandler { vm.cancelEdit() }
    val old = vm.detail?.takeIf { id != null && it.item.id == id }
    val folders = vm.writableFolders
    var folderId by rememberSaveable { mutableStateOf(old?.item?.folderId ?: folders.firstOrNull()?.id ?: 0) }
    var name by rememberSaveable { mutableStateOf(old?.item?.name.orEmpty()) }
    var login by rememberSaveable { mutableStateOf(old?.login.orEmpty()) }
    var value by rememberSaveable { mutableStateOf(if (old == null) generatePassword() else old.value) }
    var url by rememberSaveable { mutableStateOf(old?.item?.url.orEmpty()) }
    var tags by rememberSaveable { mutableStateOf(old?.item?.tags.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(old?.notes.orEmpty()) }
    var totpSeed by rememberSaveable { mutableStateOf("") }
    var expires by rememberSaveable { mutableStateOf(old?.item?.expiresAt?.take(10).orEmpty()) }
    var machineOnly by rememberSaveable { mutableStateOf(old?.item?.machineOnly ?: false) }
    var requireApproval by rememberSaveable { mutableStateOf(old?.item?.requireApproval ?: false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var shown by rememberSaveable { mutableStateOf(old == null) }
    val valueHidden = old?.valueHidden == true

    val save = {
        vm.save(SecretEdit(folderId, name, if (valueHidden) null else value, login, url, notes, tags, totpSeed.ifBlank { null },
            expires, machineOnly, requireApproval))
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(if (id == null) R.string.edit_new else R.string.edit_edit)) },
            navigationIcon = { IconButton(onClick = vm::cancelEdit) { Icon(Icons.Filled.Close, stringResource(R.string.back)) } },
            actions = {
                if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                else TextButton(onClick = save, enabled = name.isNotBlank() && folderId != 0) { Text(stringResource(R.string.edit_save)) }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            vm.error?.let { Text(errorMessageFor(it), color = MaterialTheme.colorScheme.error) }

            var open by rememberSaveable { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
                OutlinedTextField(
                    value = folders.firstOrNull { it.id == folderId }?.name ?: vm.folders.firstOrNull { it.id == folderId }?.name.orEmpty(),
                    onValueChange = {}, readOnly = true,
                    label = { Text(stringResource(R.string.f_folder)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    folders.forEach { f -> DropdownMenuItem(text = { Text(f.name) }, onClick = { folderId = f.id; open = false }) }
                }
            }
            Field(name, { name = it }, R.string.edit_name)
            Field(login, { login = it }, R.string.f_login, KeyboardType.Email)
            if (valueHidden) {
                Text(stringResource(R.string.machine_only_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedTextField(
                    value = value, onValueChange = { value = it },
                    label = { Text(stringResource(R.string.f_password)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { shown = !shown }) {
                                Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    stringResource(if (shown) R.string.hide else R.string.show))
                            }
                            IconButton(onClick = { value = generatePassword(); shown = true }) {
                                Icon(Icons.Filled.Refresh, stringResource(R.string.edit_generate))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Field(url, { url = it }, R.string.f_url, KeyboardType.Uri, hint = "https://")
            Field(tags, { tags = it }, R.string.f_tags, hint = stringResource(R.string.edit_tags_hint))
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text(stringResource(R.string.f_notes)) },
                minLines = 3, modifier = Modifier.fillMaxWidth())
            Field(totpSeed, { totpSeed = it }, R.string.edit_totp_seed,
                hint = stringResource(if (old?.item?.hasTotp == true) R.string.edit_totp_keep else R.string.edit_totp_hint))
            OutlinedTextField(
                value = if (expires.isBlank()) "" else formatDate(expires), onValueChange = {}, readOnly = true,
                label = { Text(stringResource(R.string.f_expires)) },
                supportingText = { Text(stringResource(R.string.edit_expires_hint)) },
                trailingIcon = {
                    Row {
                        if (expires.isNotBlank()) IconButton(onClick = { expires = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.edit_clear)) }
                        IconButton(onClick = { picking = true }) { Icon(Icons.Filled.DateRange, stringResource(R.string.edit_pick_date)) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(stringResource(R.string.edit_machine_only), stringResource(R.string.edit_machine_only_hint), machineOnly) { machineOnly = it }
            SwitchRow(stringResource(R.string.edit_require_approval), stringResource(R.string.edit_require_approval_hint), requireApproval) { requireApproval = it }
        }
    }
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = runCatching {
            java.time.LocalDate.parse(expires).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrNull())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        expires = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                    }
                    picking = false
                }) { Text(stringResource(R.string.done)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: Int, type: KeyboardType = KeyboardType.Text, hint: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(label)) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun errorMessageFor(code: String) = when (code) {
    "name_required" -> stringResource(R.string.edit_name_required)
    "bad_secret_url" -> stringResource(R.string.edit_bad_url)
    else -> code
}
