package org.salvami.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import org.salvami.app.LibState
import org.salvami.app.Library
import org.salvami.app.MediaItem
import org.salvami.app.R
import java.io.File

/** Full-screen viewer: swipe between items, pinch/double-tap to zoom photos, play videos. */
@Composable
fun DetailScreen(state: LibState, ids: List<String>, start: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val byId = remember(state.items) { state.items.associateBy { it.id } }
    val items = ids.mapNotNull { byId[it] }
    BackHandler(onBack = onClose)
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.size - 1)) { items.size }
    val current = items[pager.currentPage.coerceIn(0, items.size - 1)]
    var infoDialog by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf(false) }
    var memoDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            HorizontalPager(state = pager, key = { items[it].id }, modifier = Modifier.fillMaxSize()) { page ->
                val item = items[page]
                if (item.video) VideoPlayer(Library.fileOf(item), active = pager.currentPage == page)
                else ZoomableImage(Library.fileOf(item), item.title)
            }
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    Column(Modifier.weight(1f)) {
                        Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        val sub = listOf(current.username, current.platform).filter { it.isNotBlank() }.joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    // Position inside a post, as on Instagram ("2/5").
                    val post = items.filter { current.group.isNotEmpty() && it.group == current.group }
                    if (post.size > 1) Text("${post.indexOf(current) + 1}/${post.size}", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { infoDialog = true }) { Icon(Icons.Default.Info, stringResource(R.string.details)) }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f))) {
                if (current.memo.isNotBlank()) {
                    Text(current.memo, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
                // Videos keep their own controls at the bottom; actions sit above them.
                Row(Modifier.fillMaxWidth().padding(bottom = if (current.video) 72.dp else 0.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { Library.setFavorite(setOf(current.id), !current.favorite) }) {
                        Icon(
                            if (current.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(R.string.favorite),
                            tint = if (current.favorite) Color(0xFFFF5A79) else Color.White,
                        )
                    }
                    IconButton(onClick = { tagDialog = true }) { Icon(Icons.AutoMirrored.Filled.Label, stringResource(R.string.tags)) }
                    IconButton(onClick = { memoDialog = true }) { Icon(Icons.Default.Edit, stringResource(R.string.memo)) }
                    IconButton(onClick = { shareItems(ctx, listOf(current)) }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                    IconButton(onClick = { openExternal(ctx, current) }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.open_with)) }
                    IconButton(onClick = { deleteDialog = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                }
            }
        }
    }

    if (infoDialog) InfoDialog(state, current) { infoDialog = false }
    if (tagDialog) {
        TagPickerDialog(
            tags = state.tags, active = current.tags.toSet(),
            onToggle = { id, on -> Library.setTag(setOf(current.id), id, on) },
            onCreate = { name -> Library.setTag(setOf(current.id), Library.createTag(name).id, true) },
            onDismiss = { tagDialog = false },
        )
    }
    if (memoDialog) {
        TextInputDialog(
            title = stringResource(R.string.memo), initial = current.memo, singleLine = false, confirm = stringResource(R.string.save),
            onConfirm = { Library.setMemo(current.id, it); memoDialog = false },
            onDismiss = { memoDialog = false },
        )
    }
    if (deleteDialog) {
        ConfirmDialog(
            text = stringResource(R.string.delete_confirm_n).format(1), confirm = stringResource(R.string.delete),
            onConfirm = { deleteDialog = false; Library.deleteItems(setOf(current.id)) },
            onDismiss = { deleteDialog = false },
        )
    }
}

@Composable
private fun InfoDialog(state: LibState, item: MediaItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val tagNames = state.tags.filter { it.id in item.tags }.joinToString(", ") { it.name }
    val rows = listOfNotNull(
        R.string.info_file to item.file,
        if (item.width > 0) R.string.info_resolution to "${item.width} × ${item.height}" else null,
        R.string.info_size to fmtSize(item.size),
        R.string.info_saved to fmtDate(item.savedAt),
        if (item.platform.isNotBlank()) R.string.info_platform to item.platform else null,
        if (item.username.isNotBlank()) R.string.info_username to item.username else null,
        if (tagNames.isNotBlank()) R.string.tags to tagNames else null,
        if (item.sourceUrl.isNotBlank()) R.string.info_source to item.sourceUrl else null,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.details)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    rows.forEach { (label, value) ->
                        Column {
                            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = {
            if (item.sourceUrl.isNotBlank()) TextButton(onClick = { openUrl(ctx, item.sourceUrl) }) { Text(stringResource(R.string.open_source)) }
        },
    )
}

@Composable
private fun ZoomableImage(file: File, description: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = file, contentDescription = description, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        // One finger on an unzoomed photo is left to the pager (swipe to next).
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            if (scale > 1f) {
                                val max = Offset(size.width * (scale - 1) / 2f, size.height * (scale - 1) / 2f)
                                val next = offset + event.calculatePan()
                                offset = Offset(next.x.coerceIn(-max.x, max.x), next.y.coerceIn(-max.y, max.y))
                            } else offset = Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
    )
}

@Composable
private fun VideoPlayer(file: File, active: Boolean) {
    val ctx = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    LaunchedEffect(player, active) { player.playWhenReady = active }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}
