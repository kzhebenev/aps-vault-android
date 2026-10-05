package ru.devkz.apsvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.devkz.apsvault.BuildConfig
import ru.devkz.apsvault.R
import ru.devkz.apsvault.Updater

/** Shown only when GitHub has a release newer than the installed version. */
@Composable
fun UpdateBanner(release: Updater.Release?) {
    release ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf<String?>(null) }
    val mismatch = stringResource(R.string.update_bad_signature)

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (downloading) R.string.update_downloading else R.string.update_available, release.version),
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(failed ?: stringResource(R.string.update_installed, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!downloading) TextButton(onClick = {
                if (!Updater.canInstall(ctx)) { ctx.startActivity(Updater.permissionIntent(ctx)); return@TextButton }
                downloading = true; failed = null
                scope.launch {
                    runCatching {
                        val apk = Updater.download(ctx, release) { p -> progress = if (p < 0) 0f else p }
                        ctx.startActivity(Updater.installIntent(ctx, apk))
                    }.onFailure { failed = if (it.message == "signature mismatch") mismatch else it.message ?: it.javaClass.simpleName }
                    downloading = false
                }
            }) { Text(stringResource(R.string.update_button)) }
        }
        if (downloading) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}
