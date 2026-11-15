package org.salvami.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** A site whose private content (stories, private profiles, age-gated posts) needs a signed-in session. */
enum class Site(val label: String, val loginUrl: String, val domain: String, val sessionCookie: String, val aliases: List<String> = emptyList()) {
    INSTAGRAM("Instagram", "https://www.instagram.com/accounts/login/", "instagram.com", "sessionid"),
    FACEBOOK("Facebook", "https://m.facebook.com/login/", "facebook.com", "c_user", listOf("fb.watch")),
    X("X", "https://x.com/i/flow/login", "x.com", "auth_token", listOf("twitter.com")),
    TIKTOK("TikTok", "https://www.tiktok.com/login", "tiktok.com", "sessionid");

    fun matches(host: String) = (aliases + domain).any { host == it || host.endsWith(".$it") }
}

/**
 * Sessions captured from the login WebView, kept as Netscape cookie files for yt-dlp's --cookies.
 * They live in noBackupFilesDir: never in Android backups nor in Salvami's backup zip, since they
 * grant full access to the account.
 */
object Accounts {
    private lateinit var dir: File
    private val _signedIn = MutableStateFlow<Set<Site>>(emptySet())
    val signedIn: StateFlow<Set<Site>> = _signedIn

    fun init(ctx: Context) {
        dir = File(ctx.noBackupFilesDir, "cookies").apply { mkdirs() }
        refresh()
    }

    private fun refresh() {
        _signedIn.value = Site.entries.filter { file(it).isFile }.toSet()
    }

    private fun file(site: Site) = File(dir, "${site.name.lowercase()}.txt")

    fun siteFor(url: String): Site? {
        val host = runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: return null
        return Site.entries.firstOrNull { it.matches(host) }
    }

    /** The cookie file to hand yt-dlp for [url], if the user signed in to that site. */
    fun cookieFile(url: String): File? = siteFor(url)?.let(::file)?.takeIf { it.isFile }

    /** Saves the WebView session of [site]; false while the user has not signed in yet. */
    fun capture(site: Site): Boolean {
        val raw = CookieManager.getInstance().getCookie("https://www.${site.domain}/") ?: return false
        val cookies = raw.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }
        if (cookies.none { (name, value) -> name == site.sessionCookie && value.isNotEmpty() }) return false
        // The WebView hides expiry dates; yt-dlp refreshes the file with the real ones after each run.
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        file(site).writeText(buildString {
            append("# Netscape HTTP Cookie File\n")
            cookies.forEach { (name, value) -> append(".${site.domain}\tTRUE\t/\tTRUE\t$expiry\t$name\t$value\n") }
        })
        refresh()
        return true
    }

    fun signOut(site: Site) {
        file(site).delete()
        val cm = CookieManager.getInstance()
        val url = "https://www.${site.domain}/"
        cm.getCookie(url)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
            cm.setCookie(url, "$name=; Max-Age=0; Domain=.${site.domain}; Path=/")
        }
        cm.flush()
        refresh()
    }
}
