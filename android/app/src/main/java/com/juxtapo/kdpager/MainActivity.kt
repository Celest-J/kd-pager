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
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject
import java.util.concurrent.Executors

// The KD app: KD's own site full screen — login page when logged out, /dashboard/profile as home.
// The pager is one feature on top: a button in KD's nav (after the logo) opens our settings page.
// One login: the first KD login hands its session to the hub (Hub.handover). From then on the hub is the only
// refresher — KD's page refreshes through shouldInterceptRequest below, and the app swaps in the hub's token
// before KD's server can see an expiring one. KD's logout is caught too: it would sign out every device.
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
        // the hub renews 20 min before expiry; the phone swaps its copy once under 15 min, far ahead of KD's own ~90 s
        private const val FRESH_MARGIN_S = 15 * 60L
        private val SB_HOST = android.net.Uri.parse(Kd.SB).host
    }

    private lateinit var web: WebView
    private val bg = Executors.newSingleThreadExecutor { Thread(it, "session") }
    @Volatile private var handing = false
    @Volatile private var handoverFailedAt = 0L

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
                val c = cookieSession() ?: return
                loggedIn()
                if (!Kd.hubHeld(c)) handover(c)
            }

            // runs on a WebView worker thread: blocking calls are allowed here
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (req.url.host != SB_HOST) return null
                val path = req.url.path ?: return null
                val refresh = path.endsWith("/auth/v1/token") && req.url.getQueryParameter("grant_type") == "refresh_token"
                val logout = path.endsWith("/auth/v1/logout")
                if (!refresh && !logout) return null
                if (req.method == "OPTIONS") return cors(req, 204, "")
                if (req.method != "POST") return null
                return if (refresh) refreshFromHub(req) else logoutHere(req)
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        val url = intent.getStringExtra(EXTRA_URL)
        intent.getStringExtra(EXTRA_GUILD)?.let { PagerService.clear(it) }
        // KD's server must never see an expiring hub-held cookie: sync first, then load
        bg.execute {
            val loggedIn = syncSession()
            runOnUiThread { web.loadUrl(url ?: if (loggedIn) HOME else LOGIN) }
        }
    }

    private var created = true
    override fun onResume() {
        super.onResume()
        pagerChanged() // e.g. back from Settings after Log out
        if (created) { created = false; return }
        bg.execute {
            val c = cookieSession() ?: return@execute
            if (!Kd.hubHeld(c) || c.expiresAt - now() > FRESH_MARGIN_S) return@execute
            val loggedIn = syncSession()
            runOnUiThread { if (loggedIn) web.reload() else web.loadUrl(LOGIN) }
        }
    }

    private fun now() = System.currentTimeMillis() / 1000
    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    // Background. Returns whether the user is logged in. Swaps an expiring hub-held cookie for the hub's current
    // session, and finishes a logout whose hub notice failed earlier (logged out = pager off).
    private fun syncSession(): Boolean {
        val c = cookieSession()
        if (c == null) {
            if (Hub.connected(this)) runCatching { Hub.unregister(this) }
                .onFailure { L.e("logged out, hub unregister failed", it); toast("Logged out, but the pager hub could not be told: ${it.message}. Retrying next open.") }
            return false
        }
        if (!Kd.hubHeld(c) || c.expiresAt - now() > FRESH_MARGIN_S) return true
        return try {
            Kd.writeCookie(Hub.session(this))
            true
        } catch (e: Hub.HubGone) {
            L.w("hub no longer holds this session: ${e.message}")
            Kd.clearCookie(); Hub.forgetLocal(this)
            toast("Pager session ended — log in to KD again.")
            false
        } catch (e: Exception) {
            L.e("hub token fetch failed", e)
            toast("Pager hub unreachable: ${e.message}. KD may ask you to log in.")
            true
        }
    }

    // A fresh KD login: hand it to the hub once. Too close to expiry and KD's page would refresh it mid-handover,
    // so wait for KD to renew it first; after a failure wait a minute instead of retrying on every page.
    private fun handover(c: Kd.Session) {
        if (handing || now() - handoverFailedAt < 60) return
        if (c.expiresAt - now() < 600) { L.w("fresh session expires in <10 min, handing over after KD renews it"); return }
        handing = true
        bg.execute {
            try {
                Hub.handover(this, c)
                runOnUiThread { pagerChanged(); explainOnce() }
            } catch (e: Exception) {
                handoverFailedAt = now()
                L.e("handover failed", e)
                toast("Pager not on: ${e.message}. Retrying on the next page.")
            } finally {
                handing = false
            }
        }
    }

    private fun pagerChanged() = web.evaluateJavascript("window.__kdappSetPager && window.__kdappSetPager(${Hub.connected(this)})", null)

    // Nothing turns on behind the user's back: the first handover says what just happened and how to undo it.
    private fun explainOnce() {
        val p = Kd.prefs(this)
        if (p.getBoolean("pager_intro_shown", false)) { toast("Pager on ✓"); return }
        p.edit().putBoolean("pager_intro_shown", true).apply()
        android.app.AlertDialog.Builder(this)
            .setTitle("Pager is on")
            .setMessage("KD Pager uses your KD login — there is no second login.\n\n" +
                "While you are logged in, the pager hub keeps that login so your phone buzzes for guild chat and raids, even with the app closed.\n\n" +
                "Log out (in KD or in Pager settings) turns it off.")
            .setPositiveButton("Got it", null)
            .setNeutralButton("Pager settings") { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
            .show()
    }

    // KD's page wants a refresh: answer with the hub's session (the phone holds no refresh token to send).
    private fun refreshFromHub(req: WebResourceRequest): WebResourceResponse? {
        val c = cookieSession()
        if (c == null || !Kd.hubHeld(c)) return null // not handed over yet: KD refreshes its own session
        return try {
            val s = Hub.session(this)
            cors(req, 200, JSONObject(s.json.toString()).put("expires_in", s.expiresAt - now()).toString())
        } catch (e: Hub.HubGone) {
            L.w("refresh: hub no longer holds this session: ${e.message}")
            Hub.forgetLocal(this)
            runOnUiThread { pagerChanged() }
            toast("Pager session ended — log in to KD again.")
            cors(req, 400, JSONObject().put("error", "invalid_grant").put("error_description", "pager session ended: ${e.message}").toString())
        } catch (e: Exception) {
            L.e("refresh: hub unreachable", e)
            cors(req, 503, JSONObject().put("error", "hub_unreachable").put("error_description", "${e.message}").toString())
        }
    }

    // KD's logout signs out every device, which would also end the hub's session. Here it means: this phone
    // logs out and its pager turns off. KD's page then clears its own cookie and shows the login page.
    private fun logoutHere(req: WebResourceRequest): WebResourceResponse {
        if (Hub.connected(this)) runCatching { Hub.unregister(this) }
            .onFailure { L.e("logout: hub unregister failed", it); toast("Logged out, but the pager hub could not be told: ${it.message}. Retrying next open.") }
        Kd.forget(this)
        runOnUiThread { pagerChanged() }
        L.i("logout: this phone only, pager off")
        return cors(req, 204, "")
    }

    private fun cors(req: WebResourceRequest, code: Int, body: String): WebResourceResponse {
        fun h(name: String) = req.requestHeaders.entries.firstOrNull { it.key.equals(name, true) }?.value
        val headers = mapOf(
            "Access-Control-Allow-Origin" to (h("Origin") ?: Kd.SITE),
            "Access-Control-Allow-Headers" to (h("Access-Control-Request-Headers") ?: "authorization, apikey, content-type, x-client-info, x-supabase-api-version"),
            "Access-Control-Allow-Methods" to "POST, OPTIONS",
            "Access-Control-Allow-Credentials" to "true",
            "Vary" to "Origin",
        )
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; else -> "Service Unavailable" }
        return WebResourceResponse("application/json", "utf-8", code, reason, headers, body.byteInputStream())
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
        @JavascriptInterface
        fun pagerOn(): Boolean = Hub.connected(this@MainActivity)
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

    // First login of this run: notification permission (the pager itself turns on through handover).
    private var seen = false
    private fun loggedIn() {
        if (seen) return
        seen = true
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
