package com.juxtapo.kdpager

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.view.View
import android.widget.TextView

// "Connect Pager": a login of its own, apart from the daily one in MainActivity, so phone and hub never share a refresh token.
// Flow: park the daily KD cookie -> fresh KD login (captcha is the user's) -> read the new cookie -> POST it to the hub
// -> delete it locally (never signOut: that would revoke the hub's token) -> put the daily cookie back.
class ConnectActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private val cm get() = CookieManager.getInstance()
    private var parked: Map<String, String>? = null
    private var busy = false
    private lateinit var spin: ProgressBar
    private lateinit var retry: Button

    private fun isAuth(name: String) = name.startsWith("sb-")

    private fun authCookies(): Map<String, String> =
        (cm.getCookie(Kd.SITE) ?: "").split(";").map { it.trim() }
            .mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it) to kv.substring(it + 1) } }
            .filter { isAuth(it.first) }.toMap()

    private fun wipeAuth() {
        authCookies().keys.forEach { cm.setCookie(Kd.SITE, "$it=; Path=/; Max-Age=0") }
        cm.flush()
    }

    private fun restoreDaily() {
        val p = parked ?: return
        wipeAuth()
        p.forEach { (k, v) -> cm.setCookie(Kd.SITE, "$k=$v; Path=/; Max-Age=34560000; SameSite=Lax") }
        cm.flush()
        parked = null
    }

    private fun say(text: String) = runOnUiThread { status.text = text }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.init(this)
        Notify.setup(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        status = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); setPadding(24, 16, 24, 16); textSize = 14f }
        spin = ProgressBar(this).apply { visibility = View.GONE }
        retry = Button(this).apply { text = "Try again"; visibility = View.GONE
            setOnClickListener { visibility = View.GONE; busy = false; web.visibility = View.VISIBLE; web.loadUrl(MainActivity.LOGIN) } }
        web = WebView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.kd_black))
            addView(status); addView(spin); addView(retry)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom); insets
            }
        }
        setContentView(col)

        cm.apply { setAcceptCookie(true); setAcceptThirdPartyCookies(web, true) }
        web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true }
        parked = authCookies()
        wipeAuth()
        status.text = "Log in to KD to get notified"
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                cm.flush()
                if (busy) return
                val s = try { Kd.sessionFromCookie() } catch (e: Exception) {
                    L.e("connect: cookie parse failed", e); say("Connect error: cookie unreadable: ${e.message}"); return
                } ?: return
                busy = true
                runOnUiThread { web.visibility = View.GONE; spin.visibility = View.VISIBLE; status.text = "Setting up notifications..." }
                Thread { send(s) }.start()
            }
        }
        web.loadUrl(MainActivity.LOGIN)
    }

    private fun send(s: Kd.Session) {
        var err: String? = null
        try {
            Hub.register(this, s)
        } catch (e: Exception) {
            L.e("connect: register failed", e)
            err = e.message ?: e.javaClass.simpleName
        }
        runOnUiThread {
            wipeAuth()      // the hub owns this session now; the app keeps none
            restoreDaily()
            spin.visibility = View.GONE
            if (err == null) {
                status.text = "Connected \u2713"
                status.postDelayed({ finish() }, 1200)
            } else {
                status.text = "Could not set up notifications: $err"
                retry.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroy() {
        if (parked != null) restoreDaily()
        super.onDestroy()
    }
}
