package org.salvami.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.salvami.app.ui.toast

/**
 * The site's own login page in a WebView: the password goes only to the site. As soon as the
 * session cookie shows up it is saved for yt-dlp and the screen closes.
 */
class LoginActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_SITE = "site"

        fun start(ctx: Context, site: Site) =
            ctx.startActivity(Intent(ctx, LoginActivity::class.java).putExtra(EXTRA_SITE, site.name))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val site = intent.getStringExtra(EXTRA_SITE)?.let { name -> Site.entries.firstOrNull { it.name == name } }
            ?: return finish()

        setContent {
            SalvamiTheme {
                // Logins hop through redirects and client-side navigation: polling is the reliable signal.
                LaunchedEffect(Unit) {
                    while (!Accounts.capture(site)) delay(1000)
                    CookieManager.getInstance().flush()
                    toast(this@LoginActivity, getString(R.string.signed_in_to, site.label))
                    finish()
                }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } },
                            title = { Text(stringResource(R.string.sign_in_to, site.label)) },
                        )
                    },
                ) { padding ->
                    AndroidView(
                        modifier = Modifier.padding(padding).fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                // Without explicit params AndroidView leaves the WebView at WRAP_CONTENT height,
                                // which makes it report 100vh as 0: sites sized on the viewport render empty.
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = WebViewClient()
                                loadUrl(site.loginUrl)
                            }
                        },
                    )
                }
            }
        }
    }
}
