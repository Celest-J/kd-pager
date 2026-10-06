package com.juxtapo.kdpager

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedDispatcher

// The KD app: KD's own site full screen — login page when logged out, /dashboard/profile as home.
// The pager is one feature on top: a button in KD's nav (after the logo) opens our settings page.
class MainActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_GUILD = "guild"
        // hosts that stay inside the app (KD + its login providers); everything else opens the real browser
        private val INSIDE = listOf("krackeddevs.com", "supabase.co", "github.com", "discord.com")
        // the public landing page is not the app's home: the app lives on the dashboard
        const val HOME = "${Kd.SITE}/dashboard/profile"
        const val LOGIN = "${Kd.SITE}/login?next=%2Fdashboard%2Fprofile"
        private fun isLanding(url: String?) = url != null && url.trimEnd('/') == Kd.SITE
    }

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.init(this)
        Notify.setup(this)
        web = WebView(this)
        // Android 15+ draws apps edge-to-edge: keep KD's page below the status bar and above the nav bar / keyboard.
        // The padding sits on a frame — WebView ignores its own padding.
        val frame = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.kd_black))
            addView(web)
        }
        setContentView(frame)
        frame.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
        }
        web.addJavascriptInterface(Bridge(), "KDApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val host = req.url.host ?: return false
                if (host.endsWith("accounts.google.com")) {
                    Toast.makeText(this@MainActivity, "Google blocks login inside apps — use GitHub, Discord or email (same KD account).", Toast.LENGTH_LONG).show()
                    return true
                }
                if (INSIDE.any { host == it || host.endsWith(".$it") }) return false
                startActivity(Intent(Intent.ACTION_VIEW, req.url))
                return true
            }

            // catches client-side (Next.js router) moves too, which never reach shouldOverrideUrlLoading
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (isLanding(url)) view.loadUrl(HOME) else putPager(view, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                putPager(view, url)
                if (cookieSession() != null) loggedIn()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        web.loadUrl(intent.getStringExtra(EXTRA_URL) ?: if (cookieSession() != null) HOME else LOGIN)
        intent.getStringExtra(EXTRA_GUILD)?.let { PagerService.clear(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_URL)?.let { web.loadUrl(it) }
        intent.getStringExtra(EXTRA_GUILD)?.let { PagerService.clear(it) }
    }

    // The pager button in KD's nav calls this. Exposed to every page in the WebView; it only opens our own screen.
    inner class Bridge {
        @JavascriptInterface
        fun openSettings() = runOnUiThread { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
    }

    private val navJs by lazy { assets.open("pager-nav.js").bufferedReader().use { it.readText() } }

    private fun putPager(view: WebView, url: String?) {
        if (url != null && android.net.Uri.parse(url).host == "krackeddevs.com") view.evaluateJavascript(navJs, null)
    }

    // Unreadable cookie = not logged in: show the login page instead of crashing on every launch.
    private fun cookieSession(): Kd.Session? = runCatching { Kd.sessionFromCookie() }
        .onFailure { Log.w("KdPager", "unreadable KD session cookie, treating as logged out: ${it.message}") }
        .getOrNull()

    @Deprecated("pre-33 back handling")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    // First daily login: notification permission, and offer the pager settings page once if it isn't connected.
    private var seen = false
    private fun loggedIn() {
        if (seen) return
        seen = true
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val p = Kd.prefs(this)
        if (!Hub.connected(this) && !p.getBoolean("offered_pager", false)) {
            p.edit().putBoolean("offered_pager", true).apply()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
