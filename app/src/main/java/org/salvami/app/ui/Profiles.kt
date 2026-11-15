package org.salvami.app.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.salvami.app.LibState
import org.salvami.app.Library
import org.salvami.app.Profile
import org.salvami.app.Profiles
import org.salvami.app.R
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfilesScreen(state: LibState) {
    val ctx = LocalContext.current
    var platform by rememberSaveable { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<Profile?>(null) }
    val platforms = state.profiles.map { it.platform }.distinct().sorted()
    val shown = state.profiles.filter { platform == null || it.platform == platform }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.profiles)) }) }) { padding ->
        if (state.profiles.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_profiles), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (platforms.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FilterChip(selected = platform == null, onClick = { platform = null }, label = { Text(stringResource(R.string.filter_all)) })
                    platforms.forEach { p ->
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = platform == p, onClick = { platform = if (platform == p) null else p }, label = { Text(p) })
                    }
                }
            }
            items(shown, key = { it.id }) { p ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = { openUrl(ctx, p.url) }, onLongClick = { menu = true })
                        .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(p)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name.ifEmpty { "@${p.handle}" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "@${p.handle} · ${p.platform}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.open_profile)) }, onClick = { menu = false; openUrl(ctx, p.url) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = {
                                menu = false
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p.url)
                                runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.refresh)) }, onClick = { menu = false; Profiles.refresh(p.id) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; toDelete = p })
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }

    toDelete?.let { p ->
        ConfirmDialog(
            text = stringResource(R.string.delete_profile_confirm, p.name.ifEmpty { "@${p.handle}" }), confirm = stringResource(R.string.delete),
            onConfirm = { Profiles.delete(setOf(p.id)); toDelete = null },
            onDismiss = { toDelete = null },
        )
    }
}

@Composable
private fun Avatar(p: Profile) {
    val modifier = Modifier.size(48.dp).clip(CircleShape)
    if (p.avatar.isNotEmpty()) {
        AsyncImage(model = File(Library.avatarDir, p.avatar), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(
                (p.name.ifEmpty { p.handle }).take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
