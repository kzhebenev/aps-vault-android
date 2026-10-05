package ru.devkz.apsvault.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.devkz.apsvault.ApprovalState
import ru.devkz.apsvault.R
import ru.devkz.apsvault.SecretFull
import ru.devkz.apsvault.SecretItem
import ru.devkz.apsvault.VaultViewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// ---- formatting -------------------------------------------------------------------------------

/** The server writes naive UTC timestamps; show them in the device's zone. */
internal fun formatDateTime(iso: String): String = runCatching {
    LocalDateTime.parse(iso.take(19)).atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}.getOrDefault(iso)

internal fun formatDate(iso: String): String = runCatching {
    LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrDefault(iso)

/** Days until the deadline; negative when it has passed, null when there is none. */
internal fun daysLeft(expiresAt: String): Long? = runCatching {
    java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(expiresAt.take(10)))
}.getOrNull()

private val avatarColors = listOf(
    Color(0xFF3B6FD8), Color(0xFF2E8B57), Color(0xFFB5651D), Color(0xFF8E44AD),
    Color(0xFFC0392B), Color(0xFF16829A), Color(0xFF7A6F2B), Color(0xFF5B5F97),
)

@Composable
internal fun Avatar(name: String, size: Int = 40) {
    val c = avatarColors[(name.lowercase().hashCode() and 0x7fffffff) % avatarColors.size]
    Box(Modifier.size(size.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.SemiBold,
            fontSize = (size * 0.42).sp)
    }
}

// ---- list -------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListScreen(vm: VaultViewModel, snack: SnackbarHostState) {
    var newFolder by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        vm.identity?.let { Text(whoText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
                    IconButton(onClick = vm::openSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
                    IconButton(onClick = { vm.lock() }) { Icon(Icons.Filled.Lock, stringResource(R.string.lock)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
        floatingActionButton = {
            if (vm.writableFolders.isNotEmpty()) FloatingActionButton(onClick = vm::newSecret) {
                Icon(Icons.Filled.Add, stringResource(R.string.edit_new))
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            UpdateBanner(vm.update)
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it },
                placeholder = { Text(stringResource(R.string.list_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            FolderChips(vm) { newFolder = "" }
            val items = vm.filtered()
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(if (vm.query.isBlank()) R.string.list_empty else R.string.list_nothing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val grouped = vm.folderFilter == VaultViewModel.ALL && vm.query.isBlank() && vm.folders.size > 1
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    if (grouped) {
                        val byFolder = items.groupBy { it.folderId }
                        vm.folders.filter { byFolder.containsKey(it.id) }.forEach { f ->
                            stickyHeader(key = "h${f.id}") { FolderHeader(f.name, vm.identity?.role(f.id)) }
                            items(byFolder.getValue(f.id), key = { it.id }) { s -> SecretRow(s, showFolder = false) { vm.open(s) } }
                        }
                    } else {
                        items(items, key = { it.id }) { s -> SecretRow(s, showFolder = vm.folderFilter < 0 || vm.query.isNotBlank()) { vm.open(s) } }
                    }
                }
            }
        }
    }
    newFolder?.let { name ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            title = { Text(stringResource(R.string.folder_new)) },
            text = {
                OutlinedTextField(value = name, onValueChange = { newFolder = it }, singleLine = true,
                    label = { Text(stringResource(R.string.edit_name)) })
            },
            confirmButton = { TextButton(onClick = { vm.createFolder(name); newFolder = null }, enabled = name.isNotBlank()) { Text(stringResource(R.string.folder_create)) } },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun FolderChips(vm: VaultViewModel, onNewFolder: () -> Unit) {
    val counts = vm.secrets.groupingBy { it.folderId }.eachCount()
    val favs = vm.secrets.count { it.isFavorite }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Chip(stringResource(R.string.folder_all), vm.secrets.size, vm.folderFilter == VaultViewModel.ALL) { vm.selectFolder(VaultViewModel.ALL) } }
        if (favs > 0) item { Chip(stringResource(R.string.folder_favorites), favs, vm.folderFilter == VaultViewModel.FAVORITES) { vm.selectFolder(VaultViewModel.FAVORITES) } }
        items(vm.folders, key = { it.id }) { f -> Chip(f.name, counts[f.id] ?: 0, vm.folderFilter == f.id) { vm.selectFolder(f.id) } }
        if (vm.identity?.isOwner == true) item {
            FilterChip(selected = false, onClick = onNewFolder, label = { Text(stringResource(R.string.folder_new)) },
                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) })
        }
    }
}

@Composable
private fun Chip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = {
        Text(label)
        Spacer(Modifier.width(6.dp))
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    })
}

@Composable
private fun FolderHeader(name: String, role: String?) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (role != null && role != "owner") Text(roleText(role), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SecretRow(s: SecretItem, showFolder: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Avatar(s.name)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (s.isFavorite) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Star, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) }
            }
            val meta = listOfNotNull(s.folderName.takeIf { showFolder }, s.host.ifBlank { null }, s.tags.ifBlank { null }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val tint = MaterialTheme.colorScheme.onSurfaceVariant
        val left = daysLeft(s.expiresAt)
        if (left != null && left <= 14) Icon(Icons.Outlined.EventBusy, null, Modifier.padding(start = 6.dp).size(18.dp),
            tint = if (left < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
        if (s.hasTotp) Icon(Icons.Outlined.Timer, null, Modifier.padding(start = 6.dp).size(18.dp), tint = tint)
        if (s.machineOnly) Icon(Icons.Outlined.SmartToy, null, Modifier.padding(start = 6.dp).size(18.dp), tint = tint)
        if (s.requireApproval) Icon(Icons.Outlined.HowToReg, null, Modifier.padding(start = 6.dp).size(18.dp), tint = tint)
    }
}

// ---- detail -----------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(vm: VaultViewModel, id: Int, snack: SnackbarHostState) {
    BackHandler { vm.back() }
    val item = vm.detail?.item ?: vm.secrets.firstOrNull { it.id == id }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRotate by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val canWrite = item != null && vm.canWrite(item.folderId)
    val canShare = item != null && vm.isManager(item.folderId) && !item.machineOnly && !item.requireApproval
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (item != null) IconButton(onClick = { vm.toggleFavorite(id) }) {
                        Icon(if (item.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder, stringResource(R.string.favorite))
                    }
                    if (canWrite && vm.detail != null) IconButton(onClick = vm::editSecret) { Icon(Icons.Filled.Edit, stringResource(R.string.edit_edit)) }
                    if (item != null) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (vm.detail != null) DropdownMenuItem(text = { Text(stringResource(R.string.act_history)) }, onClick = { menu = false; vm.loadHistory() })
                            if (canShare && vm.detail != null) DropdownMenuItem(text = { Text(stringResource(R.string.act_share)) }, onClick = { menu = false; sharing = true })
                            if (canWrite && vm.detail != null) DropdownMenuItem(text = { Text(stringResource(R.string.act_rotate)) }, onClick = { menu = false; confirmRotate = true })
                            if (canWrite) DropdownMenuItem(text = { Text(stringResource(R.string.act_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        if (item == null) return@Scaffold
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Header(vm, item)
            val d = vm.detail
            when {
                item.requireApproval && vm.approval !is ApprovalState.Granted -> ElevatedCard { Box(Modifier.padding(16.dp)) { ApprovalBlock(vm, id) } }
                d == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> {
                    AccessCard(vm, d)
                    if (d.notes.isNotBlank()) NotesCard(vm, d.notes)
                }
            }
            if (item.tags.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    SuggestionChip(onClick = { vm.query = it; vm.back() }, label = { Text(it) })
                }
            }
            InfoCard(vm, d?.item ?: item)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete && item != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_title, item.name)) },
        text = { Text(stringResource(R.string.delete_text)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text(stringResource(R.string.act_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (confirmRotate) AlertDialog(
        onDismissRequest = { confirmRotate = false },
        title = { Text(stringResource(R.string.act_rotate)) },
        text = { Text(stringResource(R.string.rotate_text)) },
        confirmButton = { TextButton(onClick = { confirmRotate = false; vm.rotateValue() }) { Text(stringResource(R.string.rotate_confirm)) } },
        dismissButton = { TextButton(onClick = { confirmRotate = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (sharing) ShareDialog(vm) { sharing = false; vm.shareLink = null }
    vm.history?.let { HistorySheet(vm, it) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(vm: VaultViewModel, item: SecretItem) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(item.name, 56)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val role = vm.identity?.takeIf { !it.isOwner }?.role(item.folderId)
            Text(listOfNotNull(item.folderName, role?.let { roleText(it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val chips = mutableListOf<Pair<ImageVector, String>>()
    if (item.machineOnly) chips += Icons.Outlined.SmartToy to stringResource(R.string.badge_machine)
    if (item.requireApproval) chips += Icons.Outlined.HowToReg to stringResource(R.string.badge_approval)
    daysLeft(item.expiresAt)?.let { left ->
        chips += Icons.Outlined.EventBusy to if (left < 0) stringResource(R.string.expired_on, formatDate(item.expiresAt))
        else stringResource(R.string.expires_on, formatDate(item.expiresAt))
    }
    item.rotation?.takeIf { it.enabled && it.intervalDays > 0 }?.let {
        chips += Icons.Filled.Refresh to stringResource(R.string.rotation_every, it.intervalDays)
    }
    if (chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (icon, text) ->
            SuggestionChip(onClick = {}, label = { Text(text) }, icon = { Icon(icon, null, Modifier.size(16.dp)) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = if (icon == Icons.Outlined.EventBusy && (daysLeft(item.expiresAt) ?: 1) < 0)
                        MaterialTheme.colorScheme.errorContainer else Color.Transparent))
        }
    }
}

@Composable
private fun AccessCard(vm: VaultViewModel, d: SecretFull) {
    val ctx = LocalContext.current
    val copied = stringResource(R.string.copied)
    val loginL = stringResource(R.string.f_login)
    val passL = stringResource(R.string.f_password)
    val totpL = stringResource(R.string.f_totp)
    val urlL = stringResource(R.string.f_url)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column {
            var rows = 0
            @Composable fun divider() { if (rows++ > 0) HorizontalDivider(Modifier.padding(start = 56.dp)) }
            if (d.login.isNotEmpty()) {
                divider()
                CredRow(Icons.Outlined.Person, loginL, { Text(d.login, style = MaterialTheme.typography.bodyLarge) },
                    onClick = { vm.copy(ctx, loginL, d.login, copied.format(loginL)) }) {
                    CopyIcon { vm.copy(ctx, loginL, d.login, copied.format(loginL)) }
                }
            }
            divider()
            if (d.item.machineOnly || d.valueHidden) {
                CredRow(Icons.Outlined.Key, passL, {
                    Text(stringResource(R.string.machine_only_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }, onClick = null) {}
            } else {
                var shown by rememberSaveable(d.item.id) { mutableStateOf(false) }
                CredRow(Icons.Outlined.Key, passL, {
                    Text(if (shown) d.value else "•".repeat(minOf(d.value.length, 14)), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyLarge)
                }, onClick = { vm.copy(ctx, passL, d.value, copied.format(passL)) }) {
                    IconButton(onClick = { shown = !shown }) {
                        Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, stringResource(if (shown) R.string.hide else R.string.show))
                    }
                    CopyIcon { vm.copy(ctx, passL, d.value, copied.format(passL)) }
                }
            }
            vm.totp?.let { t ->
                divider()
                val code = if (t.code.length == 6) t.code.substring(0, 3) + " " + t.code.substring(3) else t.code
                CredRow(Icons.Outlined.Timer, totpL, {
                    Text(code, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Medium,
                        color = if (t.remaining <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }, onClick = { vm.copy(ctx, totpL, t.code, copied.format(totpL)) }) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { t.remaining / t.period.toFloat() }, Modifier.size(32.dp), strokeWidth = 3.dp)
                        Text("${t.remaining}", style = MaterialTheme.typography.labelSmall)
                    }
                    CopyIcon { vm.copy(ctx, totpL, t.code, copied.format(totpL)) }
                }
            }
            if (d.item.url.isNotEmpty()) {
                divider()
                CredRow(Icons.Outlined.Language, urlL, {
                    Text(d.item.host.ifBlank { d.item.url }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }, onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.item.url))) }) {
                    CopyIcon { vm.copy(ctx, urlL, d.item.url, copied.format(urlL)) }
                    IconButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(d.item.url))) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open))
                    }
                }
            }
        }
    }
}

@Composable
private fun CredRow(icon: ImageVector, label: String, value: @Composable () -> Unit, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            value()
        }
        trailing()
    }
}

@Composable
private fun CopyIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.copy), Modifier.size(20.dp)) }
}

@Composable
private fun NotesCard(vm: VaultViewModel, notes: String) {
    val ctx = LocalContext.current
    val label = stringResource(R.string.f_notes)
    val copied = stringResource(R.string.copied)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                CopyIcon { vm.copy(ctx, label, notes, copied.format(label)) }
            }
            SelectionContainer { Text(notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp)) }
        }
    }
}

@Composable
private fun InfoCard(vm: VaultViewModel, item: SecretItem) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            InfoRow(stringResource(R.string.info_version), "${item.version}",
                action = if (vm.detail != null) stringResource(R.string.act_history) to { vm.loadHistory() } else null)
            if (item.updatedAt.isNotBlank()) InfoRow(stringResource(R.string.info_updated), formatDateTime(item.updatedAt))
            if (item.createdAt.isNotBlank()) InfoRow(stringResource(R.string.info_created), formatDateTime(item.createdAt))
            InfoRow(stringResource(R.string.info_last_read), if (item.lastAccessed.isNotBlank()) formatDateTime(item.lastAccessed) else stringResource(R.string.info_never))
            InfoRow(stringResource(R.string.info_reads), "${item.accessCount}")
            InfoRow(stringResource(R.string.f_expires), if (item.expiresAt.isNotBlank()) formatDate(item.expiresAt) else stringResource(R.string.info_none))
            item.rotation?.let { r ->
                InfoRow(stringResource(R.string.info_rotation), listOfNotNull(
                    r.target,
                    if (r.intervalDays > 0) stringResource(R.string.rotation_every, r.intervalDays) else null,
                    r.lastAt.takeIf { it.isNotBlank() }?.let { stringResource(R.string.rotation_last, formatDateTime(it), r.lastStatus.ifBlank { "-" }) },
                ).joinToString(", "))
                if (r.lastError.isNotBlank()) Text(r.lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, action: Pair<String, () -> Unit>? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        action?.let { (t, f) -> TextButton(onClick = f, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(t) } }
    }
}

// ---- history, share ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(vm: VaultViewModel, entries: List<ru.devkz.apsvault.HistoryEntry>) {
    val ctx = LocalContext.current
    val copied = stringResource(R.string.copied)
    ModalBottomSheet(onDismissRequest = vm::closeHistory) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.act_history), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.history_current, vm.detail?.item?.version ?: 1), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (entries.isEmpty()) Text(stringResource(R.string.history_empty), modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn {
                items(entries) { h ->
                    var shown by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.history_version, h.version), style = MaterialTheme.typography.titleSmall)
                            val by = if (h.changedBy == "master" || h.changedBy.startsWith("master:")) stringResource(R.string.role_owner_actor) else h.changedBy
                            Text(listOf(formatDateTime(h.changedAt), by).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (h.hidden) Text(stringResource(R.string.history_hidden), style = MaterialTheme.typography.bodySmall)
                            else Text(if (shown) h.value else "•".repeat(minOf(h.value.length, 14)), fontFamily = FontFamily.Monospace)
                        }
                        if (!h.hidden) {
                            IconButton(onClick = { shown = !shown }) { Icon(if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) }
                            val label = stringResource(R.string.history_version, h.version)
                            CopyIcon { vm.copy(ctx, label, h.value, copied.format(label)) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ShareDialog(vm: VaultViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val ttls = listOf(15 to R.string.ttl_15m, 60 to R.string.ttl_1h, 1440 to R.string.ttl_1d, 10080 to R.string.ttl_7d)
    var ttl by remember { mutableStateOf(60) }
    var uses by remember { mutableStateOf(1) }
    var note by remember { mutableStateOf("") }
    val link = vm.shareLink
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.act_share)) },
        text = {
            if (link == null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.share_ttl), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ttls.forEach { (m, r) -> FilterChip(selected = ttl == m, onClick = { ttl = m }, label = { Text(stringResource(r)) }) }
                }
                Text(stringResource(R.string.share_uses), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 3, 10).forEach { n -> FilterChip(selected = uses == n, onClick = { uses = n }, label = { Text("$n") }) }
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text(stringResource(R.string.share_note)) }, singleLine = true)
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.share_ready, formatDateTime(link.expiresAt), link.maxUses))
                SelectionContainer { Text(link.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val label = stringResource(R.string.act_share)
                    OutlinedButton(onClick = { vm.copy(ctx, label, link.url, ctx.getString(R.string.copied, label)) }) { Text(stringResource(R.string.copy)) }
                    Button(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link.url), label))
                    }) { Text(stringResource(R.string.approval_share_link)) }
                }
            }
        },
        confirmButton = {
            if (link == null) TextButton(onClick = { vm.share(ttl, uses, note) }) { Text(stringResource(R.string.share_create)) }
            else TextButton(onClick = onClose) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { if (link == null) TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}
