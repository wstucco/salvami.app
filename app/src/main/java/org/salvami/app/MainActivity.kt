package org.salvami.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.salvami.app.ui.DetailScreen
import org.salvami.app.ui.DownloadsScreen
import org.salvami.app.ui.GalleryScreen
import org.salvami.app.ui.SettingsScreen
import org.salvami.app.ui.toast

class MainActivity : ComponentActivity() {
    /** Tab to show; a shared link jumps to the downloads tab. */
    private val tab = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState == null) handleShare(intent)
        setContent { SalvamiTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val url = Downloader.firstUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        if (url == null) {
            toast(this, getString(R.string.invalid_link))
        } else {
            DownloadService.enqueue(this, url)
            toast(this, getString(R.string.queued))
            tab.intValue = 1
        }
        intent.action = Intent.ACTION_MAIN // don't enqueue again on rotation
    }

    @Composable
    private fun Root() {
        val state by Library.state.collectAsState()
        var detail by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
        var detailStart by rememberSaveable { mutableIntStateOf(0) }

        val open = detail
        if (open != null) {
            DetailScreen(state, open, detailStart) { detail = null }
            return
        }
        val active = state.history.count { it.status == Status.PENDING || it.status == Status.RUNNING }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab.intValue == 0, onClick = { tab.intValue = 0 },
                        icon = { Icon(Icons.Default.PhotoLibrary, null) }, label = { Text(stringResource(R.string.gallery)) },
                    )
                    NavigationBarItem(
                        selected = tab.intValue == 1, onClick = { tab.intValue = 1 },
                        icon = {
                            BadgedBox(badge = { if (active > 0) Badge { Text(active.toString()) } }) { Icon(Icons.Default.Download, null) }
                        },
                        label = { Text(stringResource(R.string.downloads)) },
                    )
                    NavigationBarItem(
                        selected = tab.intValue == 2, onClick = { tab.intValue = 2 },
                        icon = { Icon(Icons.Default.Settings, null) }, label = { Text(stringResource(R.string.settings)) },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                when (tab.intValue) {
                    0 -> GalleryScreen(state) { ids, index -> detailStart = index; detail = ArrayList(ids) }
                    1 -> DownloadsScreen(state)
                    else -> SettingsScreen(state)
                }
            }
        }
    }
}

@Composable
private fun SalvamiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF3ECF98), secondary = Color(0xFF7FD8B8))
        else -> lightColorScheme(primary = Color(0xFF0C7A55), secondary = Color(0xFF2F6B55))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
