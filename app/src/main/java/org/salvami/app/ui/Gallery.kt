package org.salvami.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.salvami.app.DownloadService
import org.salvami.app.Downloader
import org.salvami.app.LibState
import org.salvami.app.Library
import org.salvami.app.MediaItem
import org.salvami.app.R

private enum class Sort(val label: Int) {
    NEWEST(R.string.sort_newest), OLDEST(R.string.sort_oldest), USERNAME(R.string.sort_username), SIZE(R.string.sort_size)
}

private enum class Kind(val label: Int) {
    ALL(R.string.filter_all), FAVORITES(R.string.filter_favorites), VIDEOS(R.string.filter_videos),
    PHOTOS(R.string.filter_photos), MEMOS(R.string.filter_memos)
}

/** One grid cell: a single item, or every item of a post downloaded together. */
private class Tile(val items: List<MediaItem>) {
    val cover get() = items.first()
    val key get() = cover.group.ifEmpty { cover.id }
}

@Composable
fun GalleryScreen(state: LibState, onOpen: (ids: List<String>, index: Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val prefs = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }

    var columns by remember { mutableIntStateOf(prefs.getInt("columns", 3).coerceIn(2, 6)) }
    var grouped by remember { mutableStateOf(prefs.getBoolean("grouped", true)) }
    var sort by remember { mutableStateOf(Sort.entries[prefs.getInt("sort", 0).coerceIn(0, Sort.entries.size - 1)]) }
    var kind by rememberSaveable { mutableStateOf(Kind.ALL) }
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf<String?>(null) }
    var tagDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }

    val tagNames = remember(state.tags) { state.tags.associate { it.id to it.name } }
    val shown = remember(state.items, tagNames, kind, tagFilter, query, sort) {
        val q = query.trim().lowercase()
        state.items.asSequence()
            .filter {
                when (kind) {
                    Kind.ALL -> true
                    Kind.FAVORITES -> it.favorite
                    Kind.VIDEOS -> it.video
                    Kind.PHOTOS -> !it.video
                    Kind.MEMOS -> it.memo.isNotBlank()
                }
            }
            .filter { item -> val t = tagFilter; t == null || item.tags.contains(t) }
            .filter { item ->
                q.isEmpty() || listOf(item.title, item.username, item.platform, item.memo, item.sourceUrl).any { it.lowercase().contains(q) } ||
                    item.tags.any { tagNames[it]?.lowercase()?.contains(q) == true }
            }
            .toList()
            .let { list ->
                when (sort) {
                    Sort.NEWEST -> list.sortedByDescending { it.savedAt }
                    Sort.OLDEST -> list.sortedBy { it.savedAt }
                    Sort.USERNAME -> list.sortedWith(compareBy<MediaItem> { it.username.lowercase() }.thenByDescending { it.savedAt })
                    Sort.SIZE -> list.sortedByDescending { it.size }
                }
            }
    }
    // A post shows up where its first matching item would, and always opens whole.
    val tiles = remember(shown, state.items, grouped) {
        if (!grouped) return@remember shown.map { Tile(listOf(it)) }
        val posts = state.items.filter { it.group.isNotEmpty() }.groupBy { it.group }.mapValues { (_, v) -> v.sortedBy { it.position } }
        val seen = HashSet<String>()
        shown.mapNotNull { item ->
            when {
                item.group.isEmpty() -> Tile(listOf(item))
                seen.add(item.group) -> Tile(posts.getValue(item.group))
                else -> null
            }
        }
    }
    val visible = remember(tiles) { tiles.flatMap { it.items } }
    // Drop selections of items that no longer exist or are filtered out.
    val shownIds = remember(visible) { visible.map { it.id } }
    val selection = selected.intersect(shownIds.toSet())
    val selectedItems = visible.filter { it.id in selection }

    val importMsg = stringResource(R.string.imported_n)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val n = withContext(Dispatchers.IO) { Downloader.importLocal(ctx, uris) }
            toast(ctx, importMsg.format(n))
        }
    }

    BackHandler(enabled = selection.isNotEmpty() || searching) {
        if (selection.isNotEmpty()) selected = emptySet() else { searching = false; query = "" }
    }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, stringResource(R.string.cancel)) } },
                    title = { Text(selection.size.toString()) },
                    actions = {
                        IconButton(onClick = { selected = shownIds.toSet() }) { Icon(Icons.Default.SelectAll, stringResource(R.string.select_all)) }
                        val allFav = selectedItems.all { it.favorite }
                        IconButton(onClick = { Library.setFavorite(selection, !allFav) }) {
                            Icon(if (allFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(R.string.favorite))
                        }
                        IconButton(onClick = { tagDialog = true }) { Icon(Icons.AutoMirrored.Filled.Label, stringResource(R.string.tags)) }
                        IconButton(onClick = { shareItems(ctx, selectedItems) }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                        IconButton(onClick = { deleteDialog = true }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        if (searching) {
                            TextField(
                                value = query, onValueChange = { query = it }, singleLine = true,
                                placeholder = { Text(stringResource(R.string.search)) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else Text(stringResource(R.string.app_name))
                    },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(if (searching) Icons.Default.Close else Icons.Default.Search, stringResource(R.string.search))
                        }
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            Sort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text((if (s == sort) "✓ " else "") + stringResource(s.label)) },
                                    onClick = { sort = s; prefs.edit().putInt("sort", s.ordinal).apply(); menuOpen = false },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bigger_tiles)) }, enabled = columns > 2,
                                onClick = { columns--; prefs.edit().putInt("columns", columns).apply() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.smaller_tiles)) }, enabled = columns < 6,
                                onClick = { columns++; prefs.edit().putInt("columns", columns).apply() },
                            )
                            DropdownMenuItem(
                                text = { Text((if (grouped) "✓ " else "") + stringResource(R.string.group_posts)) },
                                onClick = { grouped = !grouped; prefs.edit().putBoolean("grouped", grouped).apply(); menuOpen = false },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_local)) },
                                onClick = {
                                    menuOpen = false
                                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                },
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (selection.isEmpty()) ExtendedFloatingActionButton(
                onClick = { pasteDialog = Downloader.firstUrl(clipboard.getText()?.text) ?: "" },
                icon = { Icon(Icons.Default.ContentPaste, null) },
                text = { Text(stringResource(R.string.paste_link)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Kind.entries) { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(stringResource(k.label)) })
                }
                items(state.tags, key = { it.id }) { tag ->
                    FilterChip(
                        selected = tagFilter == tag.id,
                        onClick = { tagFilter = if (tagFilter == tag.id) null else tag.id },
                        label = { Text(tag.name) },
                        leadingIcon = { TagDot(tag) },
                    )
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (state.items.isEmpty()) R.string.empty_library else R.string.no_results),
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(tiles, key = { it.key }) { tile ->
                        val item = tile.cover
                        val ids = tile.items.map { it.id }
                        val isSelected = ids.all { it in selection }
                        Box(
                            Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(
                                    onClick = {
                                        when {
                                            selection.isNotEmpty() -> selected = if (isSelected) selection - ids.toSet() else selection + ids
                                            tile.items.size > 1 -> onOpen(ids, 0)
                                            else -> onOpen(shownIds, shownIds.indexOf(item.id))
                                        }
                                    },
                                    onLongClick = { selected = selection + ids },
                                )
                        ) {
                            AsyncImage(
                                model = Library.fileOf(item), contentDescription = item.title,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                            )
                            if (item.video) Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
                            if (tile.items.any { it.favorite }) Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(16.dp))
                            if (tile.items.size > 1) {
                                Row(
                                    Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Collections, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text(tile.items.size.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (isSelected) {
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)))
                                Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    pasteDialog?.let { initial ->
        val bad = stringResource(R.string.invalid_link)
        val queued = stringResource(R.string.queued)
        TextInputDialog(
            title = stringResource(R.string.paste_link), initial = initial, singleLine = false,
            confirm = stringResource(R.string.download),
            onConfirm = { text ->
                val url = Downloader.firstUrl(text)
                if (url == null) toast(ctx, bad) else { DownloadService.enqueue(ctx, url); toast(ctx, queued); pasteDialog = null }
            },
            onDismiss = { pasteDialog = null },
        )
    }
    if (tagDialog) {
        TagPickerDialog(
            tags = state.tags,
            // A tag is ticked when every selected item has it.
            active = state.tags.map { it.id }.filter { id -> selectedItems.isNotEmpty() && selectedItems.all { id in it.tags } }.toSet(),
            onToggle = { id, on -> Library.setTag(selection, id, on) },
            onCreate = { name -> Library.setTag(selection, Library.createTag(name).id, true) },
            onDismiss = { tagDialog = false },
        )
    }
    if (deleteDialog) {
        ConfirmDialog(
            text = stringResource(R.string.delete_confirm_n).format(selection.size), confirm = stringResource(R.string.delete),
            onConfirm = { Library.deleteItems(selection); selected = emptySet(); deleteDialog = false },
            onDismiss = { deleteDialog = false },
        )
    }
}
