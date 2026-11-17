package org.salvami.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.salvami.app.Accounts
import org.salvami.app.App
import org.salvami.app.Backup
import org.salvami.app.DownloadService
import org.salvami.app.LibState
import org.salvami.app.LoginActivity
import org.salvami.app.Progress
import org.salvami.app.Library
import org.salvami.app.R
import org.salvami.app.Site
import org.salvami.app.Status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadsScreen(state: LibState) {
    val ctx = LocalContext.current
    val progress by DownloadService.progress.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.downloads)) },
                actions = { TextButton(onClick = { Library.clearHistory() }) { Text(stringResource(R.string.clear_history)) } },
            )
        },
    ) { padding ->
        if (state.history.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_history), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(state.history, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        val status = when (e.status) {
                            Status.PENDING -> stringResource(R.string.status_pending)
                            Status.RUNNING -> progress[e.id]?.label(ctx) ?: stringResource(R.string.downloading)
                            Status.COMPLETED -> stringResource(R.string.status_completed_n).format(e.count)
                            Status.FAILED -> stringResource(R.string.status_failed) + (if (e.message.isNotBlank()) ": ${e.message}" else "")
                        }
                        Text(
                            "${fmtDate(e.time)} · $status", maxLines = 4, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (e.status == Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (e.status == Status.RUNNING) {
                            // Only the transfer has a percentage; analysing the link and merging do not.
                            val p = progress[e.id]
                            if (p?.phase == Progress.Phase.DOWNLOADING) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                    }
                    when (e.status) {
                        Status.PENDING, Status.RUNNING ->
                            IconButton(onClick = { DownloadService.cancel(e.id) }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) }
                        Status.FAILED -> {
                            IconButton(onClick = { Library.removeHistory(e.id); DownloadService.enqueue(ctx, e.url) }) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.retry))
                            }
                            IconButton(onClick = { Library.removeHistory(e.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                        }
                        Status.COMPLETED ->
                            IconButton(onClick = { Library.removeHistory(e.id) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun SettingsScreen(state: LibState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var ytStatus by remember { mutableStateOf("") }
    var tagToDelete by remember { mutableStateOf<String?>(null) }
    val signedIn by Accounts.signedIn.collectAsState()

    val exported = stringResource(R.string.exported_n)
    val restored = stringResource(R.string.restored_n)
    val failed = stringResource(R.string.status_failed)
    val upToDate = stringResource(R.string.up_to_date)

    fun task(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val msg = try { withContext(Dispatchers.IO) { block() } } catch (e: Throwable) { "$failed: ${e.message}" }
            busy = false
            toast(ctx, msg)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) task { exported.format(Backup.export(ctx, uri)) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task { restored.format(Backup.restore(ctx, uri)) }
    }
    val restoreFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) task { restored.format(Backup.restoreFolder(ctx, uri)) }
    }
    var showInGallery by remember { mutableStateOf(Library.showInGallery()) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Text(stringResource(R.string.backup), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.library_folder_help, Library.root.path.substringAfter("/storage/emulated/0/")),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(enabled = !busy, onClick = { restoreFolderLauncher.launch(null) }) { Text(stringResource(R.string.restore_folder)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.show_in_gallery), Modifier.weight(1f))
                Switch(checked = showInGallery, onCheckedChange = { on ->
                    showInGallery = on
                    scope.launch(Dispatchers.IO) { Library.setShowInGallery(ctx, on) }
                })
            }
            Text(stringResource(R.string.backup_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    exportLauncher.launch("salvami-backup-$stamp.zip")
                }) { Text(stringResource(R.string.export_backup)) }
                OutlinedButton(enabled = !busy, onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }) {
                    Text(stringResource(R.string.restore_backup))
                }
            }
            HorizontalDivider()

            Text(stringResource(R.string.accounts), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.accounts_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Site.entries.forEach { site ->
                val on = site in signedIn
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(site.label)
                        Text(
                            stringResource(if (on) R.string.signed_in else R.string.signed_out),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (on) OutlinedButton(onClick = { Accounts.signOut(site) }) { Text(stringResource(R.string.sign_out)) }
                    else Button(onClick = { LoginActivity.start(ctx, site) }) { Text(stringResource(R.string.sign_in)) }
                }
            }
            HorizontalDivider()

            Text(stringResource(R.string.tags), style = MaterialTheme.typography.titleMedium)
            if (state.tags.isEmpty()) Text(stringResource(R.string.no_tags), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.tags.forEach { tag ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TagDot(tag)
                    Spacer(Modifier.width(10.dp))
                    Text("${tag.name} (${state.items.count { tag.id in it.tags }})", Modifier.weight(1f))
                    IconButton(onClick = { tagToDelete = tag.id }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                }
            }
            HorizontalDivider()

            Text(stringResource(R.string.engine), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.engine_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(enabled = !busy, onClick = {
                task {
                    App.ytReady.await()?.let { throw it }
                    val result = App.updateYtDlp(ctx, force = true)
                    val version = runCatching { YoutubeDL.getInstance().version(ctx) }.getOrNull().orEmpty()
                    ytStatus = "yt-dlp $version"
                    if (result == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) upToDate else "yt-dlp $version"
                }
            }) { Text(stringResource(R.string.update_engine)) }
            if (ytStatus.isNotEmpty()) Text(ytStatus, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()

            Text(stringResource(R.string.about), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    tagToDelete?.let { id ->
        ConfirmDialog(
            text = stringResource(R.string.delete_tag_confirm), confirm = stringResource(R.string.delete),
            onConfirm = { Library.deleteTag(id); tagToDelete = null },
            onDismiss = { tagToDelete = null },
        )
    }
}
