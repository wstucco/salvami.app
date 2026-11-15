package org.salvami.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import org.salvami.app.Library
import org.salvami.app.MediaItem
import org.salvami.app.R
import org.salvami.app.Tag
import java.text.DateFormat
import java.util.Date

fun fmtSize(bytes: Long): String {
    var n = bytes.toDouble()
    val units = listOf("B", "KB", "MB", "GB")
    var i = 0
    while (n >= 1024 && i < units.size - 1) { n /= 1024; i++ }
    return if (i == 0) "${bytes} B" else String.format("%.1f %s", n, units[i])
}

fun fmtDate(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

private fun uriOf(ctx: Context, item: MediaItem): Uri =
    FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", Library.fileOf(item))

fun shareItems(ctx: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { uriOf(ctx, it) })
    val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    intent.type = when {
        items.all { it.video } -> "video/*"
        items.none { it.video } -> "image/*"
        else -> "*/*"
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(intent, null)) }
}

/** Opens the file in another gallery/player app. */
fun openExternal(ctx: Context, item: MediaItem) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uriOf(ctx, item), if (item.video) "video/*" else "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(intent) }
}

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

@Composable
fun TagDot(tag: Tag, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).background(Color(tag.color), CircleShape))
}

/** Tick tags on/off for one or more items, and create new ones. */
@Composable
fun TagPickerDialog(
    tags: List<Tag>,
    active: Set<String>,
    onToggle: (tagId: String, on: Boolean) -> Unit,
    onCreate: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tags)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                tags.forEach { tag ->
                    val on = tag.id in active
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(tag.id, !on) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { onToggle(tag.id, it) })
                        TagDot(tag)
                        Spacer(Modifier.width(8.dp))
                        Text(tag.name)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it.take(30) }, singleLine = true,
                        label = { Text(stringResource(R.string.new_tag)) }, modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()); name = "" }) {
                        Text(stringResource(R.string.add))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    singleLine: Boolean,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = singleLine,
                minLines = if (singleLine) 1 else 3, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
