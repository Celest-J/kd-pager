package com.juxtapo.kdpager

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// The listener: one Supabase Realtime websocket, one postgres_changes channel per guild, no presence
// (the pager must not show the user "online" 24/7).
// Three threads, so a slow network call can never starve the heartbeat:
//   sock — websocket frames and connection state
//   io   — every REST call: boot, catch-up, sender names, notifications, token refresh (in order)
//   beat — heartbeat + watchdog, touches nothing but the socket and a timestamp
class PagerService : Service() {

    companion object {
        private const val REFRESH_MARGIN_S = 5 * 60L
        private const val HEARTBEAT_S = 25L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, PagerService::class.java))
        }

        // per-guild stack shown in the notification; opening that guild's chat clears it
        val history = ConcurrentHashMap<String, MutableList<Notify.Msg>>()
        fun clear(guildId: String) { history.remove(guildId) }
    }

    private val sock = Executors.newSingleThreadScheduledExecutor { Thread(it, "sock") }
    private val io = Executors.newSingleThreadScheduledExecutor { Thread(it, "io") }
    private val beat = Executors.newSingleThreadScheduledExecutor { Thread(it, "beat") }

    // ScheduledExecutorService swallows task exceptions — every task goes through here so errors reach the log and status line
    private fun ScheduledExecutorService.run(where: String, block: () -> Unit) = execute(guard(where, block))
    private fun ScheduledExecutorService.later(where: String, delayS: Long, block: () -> Unit): ScheduledFuture<*> =
        schedule(guard(where, block), delayS, TimeUnit.SECONDS)
    private fun guard(where: String, block: () -> Unit) = Runnable {
        try { block() } catch (e: Throwable) {
            L.e("error in $where", e)
            runCatching { status("Error in $where: ${e.message}") }
        }
    }

    @Volatile private var session: Kd.Session? = null
    @Volatile private var guilds: Map<String, Kd.Guild> = emptyMap()
    @Volatile private var myName: String? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var lastFrameAt = 0L
    private var heartbeat: ScheduledFuture<*>? = null   // sock thread
    private var refreshTask: ScheduledFuture<*>? = null // io thread
    private var backoffS = 5L                           // sock thread
    @Volatile private var ref = 0
    // io thread only: catch-up marks (newest created_at per guild) and recent ids so a row seen live and in catch-up pings once
    private val lastSeen = HashMap<String, String>()
    private val seenIds = LinkedHashSet<String>()
    private val names = HashMap<String, String>() // user_id → username, from guild Members pages

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        L.init(this)
        Notify.setup(this)
        val n = Notify.connected(this, "Starting…")
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notify.FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(Notify.FG_ID, n)
        L.i("service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (session == null) io.run("boot") { boot() }
        return START_STICKY
    }

    override fun onDestroy() {
        L.w("service destroyed")
        ws?.close(1000, "service stopped")
        sock.shutdownNow(); io.shutdownNow(); beat.shutdownNow()
        super.onDestroy()
    }

    private fun status(text: String) = Notify.updateConnected(this, text)

    // ── io: boot ──

    private var bootBackoffS = 5L
    private fun boot() {
        try {
            var s = Kd.current(this) ?: return fail("Not logged in", "Open KD Pager and log in with GitHub, Discord or email.")
            if (s.expiresAt - now() < REFRESH_MARGIN_S) s = Kd.refresh(this, s)
            session = s
            val list = Kd.myGuilds(s)
            if (list.isEmpty()) return fail("No guilds", "Your KD account is in no guild — nothing to listen to.")
            list.forEach { Notify.ensureGuild(this, it) }
            guilds = list.associateBy { it.id }
            myName = Kd.username(s, s.userId)
            for (g in list) if (lastSeen[g.id] == null) Kd.messagesSince(s, g.id, null).firstOrNull()?.let { lastSeen[g.id] = it.createdAt; seenIds += it.id }
            L.i("boot ok as @$myName, guilds=${list.joinToString { it.name }}, marks=$lastSeen")
            scheduleRefresh()
            sock.run("connect") { connect() }
        } catch (e: Exception) {
            L.e("boot", e)
            status("Error: ${e.message} — retrying in ${bootBackoffS}s")
            io.later("boot retry", bootBackoffS) { boot() }
            bootBackoffS = (bootBackoffS * 2).coerceAtMost(60)
        }
    }

    // ── sock: connection ──

    private fun connect() {
        ws?.cancel()
        status("Connecting…")
        L.i("connecting")
        ws = Kd.http.newWebSocket(Request.Builder().url(Kd.REALTIME).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = sock.run("open") { onOpen(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { lastFrameAt = now(); sock.run("frame") { onFrame(text) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = sock.run("failure") { onDrop(webSocket, "failure: ${t.message}") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = sock.run("closed") { onDrop(webSocket, "closed $code $reason") }
        })
    }

    private fun topic(g: Kd.Guild) = "realtime:kdpager:${g.id}"

    private fun send(socket: WebSocket, topic: String, event: String, payload: JSONObject) {
        socket.send(JSONObject().put("topic", topic).put("event", event).put("payload", payload).put("ref", (++ref).toString()).toString())
    }

    private fun onOpen(socket: WebSocket) {
        val s = session ?: return
        for (g in guilds.values) {
            val change = JSONObject().put("event", "INSERT").put("schema", "public").put("table", "guild_messages").put("filter", "guild_id=eq.${g.id}")
            val config = JSONObject()
                .put("broadcast", JSONObject().put("self", false))
                .put("presence", JSONObject().put("key", "").put("enabled", false))
                .put("postgres_changes", JSONArray().put(change))
            send(socket, topic(g), "phx_join", JSONObject().put("config", config).put("access_token", s.access))
        }
        lastFrameAt = now()
        heartbeat?.cancel(false)
        heartbeat = beat.scheduleWithFixedDelay(guard("heartbeat") { tick() }, HEARTBEAT_S, HEARTBEAT_S, TimeUnit.SECONDS)
        backoffS = 5
        L.i("open, joined ${guilds.size} guilds")
        status("Listening to ${guilds.values.joinToString(" · ") { it.name }}")
    }

    // beat thread: XOS can cut the app's network while the phone idles — the socket goes quiet without closing
    private fun tick() {
        val socket = ws ?: return
        val quiet = now() - lastFrameAt
        if (quiet > HEARTBEAT_S * 2 + 5) {
            L.w("no frame for ${quiet}s — reconnecting")
            socket.cancel()
            return
        }
        send(socket, "phoenix", "heartbeat", JSONObject())
        L.d("heartbeat sent (last frame ${quiet}s ago)")
    }

    private fun onFrame(text: String) {
        val f = JSONObject(text)
        val payload = f.optJSONObject("payload") ?: return
        when (f.optString("event")) {
            "phx_reply" -> if (payload.optString("status") != "ok") {
                L.e("join refused: $text")
                status("KD refused a channel: ${payload.optJSONObject("response")?.optString("reason") ?: text.take(120)}")
            } else if (f.optString("topic") != "phoenix") L.d("joined ${f.optString("topic")}")
            "system" -> if (payload.optString("status") != "ok") {
                L.e("system: $text")
                status("KD realtime: ${payload.optString("message").take(120)}")
            } else if (payload.optString("extension") == "postgres_changes") {
                val g = guilds[f.optString("topic").substringAfterLast(':')]
                L.i("subscribed ${g?.name}")
                if (g != null) io.run("catch-up") { catchUp(g) }
            }
            "postgres_changes" -> {
                val rec = payload.optJSONObject("data")?.optJSONObject("record") ?: return L.w("postgres_changes without record: ${text.take(200)}")
                val row = Kd.row(rec)
                L.i("live row ${row.id} guild=${row.guildId} user=${row.userId}")
                io.run("row") { onRow(row) }
            }
        }
    }

    private fun onDrop(socket: WebSocket, why: String) {
        if (socket !== ws) return // an old socket we already replaced
        heartbeat?.cancel(false)
        L.w("socket $why — reconnect in ${backoffS}s")
        status("Disconnected ($why) — reconnecting in ${backoffS}s")
        sock.later("reconnect", backoffS) { connect() }
        backoffS = (backoffS * 2).coerceAtMost(60) // network comes back when XOS wakes the app — retry often
    }

    // ── io: messages ──

    // Every (re)subscribe: whatever was posted while the socket was dark comes in here
    private fun catchUp(g: Kd.Guild) {
        val s = session ?: return
        val missed = Kd.messagesSince(s, g.id, lastSeen[g.id])
        L.i("catch-up ${g.name} after ${lastSeen[g.id]}: ${missed.size} missed")
        missed.forEach { onRow(it) }
    }

    private fun onRow(r: Kd.Row) {
        if (!seenIds.add(r.id)) return L.d("dup ${r.id}")
        if (seenIds.size > 500) seenIds.remove(seenIds.first())
        if ((lastSeen[r.guildId] ?: "") < r.createdAt) lastSeen[r.guildId] = r.createdAt
        val s = session ?: return
        if (r.userId == s.userId) return L.d("own message ${r.id}, no ping")
        val g = guilds[r.guildId] ?: return L.w("row for unknown guild ${r.guildId}")
        val who = nameOf(g, r.userId)
        val mention = myName?.let { r.content.contains("@$it", ignoreCase = true) } ?: false
        val h = history.getOrPut(g.id) { java.util.Collections.synchronizedList(mutableListOf()) }
        h += Notify.Msg(who, r.content, System.currentTimeMillis())
        if (h.size > 20) h.removeAt(0)
        Notify.message(this, g, h, mention)
        L.i("NOTIFIED ${g.name} from @$who mention=$mention")
    }

    private fun nameOf(g: Kd.Guild, uid: String): String {
        names[uid]?.let { return it }
        // new face: re-read that guild's member list once
        runCatching { names += Kd.memberNames(g.slug) }.onFailure { L.e("member names ${g.slug}", it) }
        return names[uid] ?: "unknown member".also { L.w("no name for $uid in ${g.slug}") }
    }

    // ── io: session ──

    private fun scheduleRefresh() {
        val s = session ?: return
        refreshTask?.cancel(false)
        val inS = (s.expiresAt - now() - REFRESH_MARGIN_S).coerceAtLeast(5)
        L.i("token refresh in ${inS}s")
        refreshTask = io.later("refresh", inS) { doRefresh() }
    }

    private fun doRefresh() {
        try {
            val cur = Kd.current(this) ?: return fail("Logged out", "KD session is gone. Open KD Pager and log in again.")
            // the WebView may have refreshed already — then adopt its session instead of rotating the token twice
            val s = if (cur.expiresAt - now() > REFRESH_MARGIN_S) cur else Kd.refresh(this, cur)
            session = s
            ws?.let { socket -> guilds.values.forEach { send(socket, topic(it), "access_token", JSONObject().put("access_token", s.access)) } }
            L.i("token refreshed, expires ${s.expiresAt}")
            scheduleRefresh()
        } catch (e: Kd.KdError) {
            L.e("refresh", e)
            // a rotated-away refresh token: one more look at the cookie, else the login is really dead
            val again = runCatching { Kd.sessionFromCookie() }.getOrNull()
            if (again != null && again.expiresAt - now() > REFRESH_MARGIN_S) { Kd.save(this, again); session = again; scheduleRefresh() }
            else fail("Login expired", "KD Pager could not renew your session (${e.message}). Open the app and log in again.")
        } catch (e: Exception) {
            L.e("refresh (network)", e)
            status("Session renew failed: ${e.message} — retrying in 30s")
            refreshTask = io.later("refresh retry", 30) { doRefresh() }
        }
    }

    private fun fail(title: String, text: String) {
        L.e("FAIL $title: $text")
        Notify.alert(this, title, text)
        stopSelf()
    }

    private fun now() = System.currentTimeMillis() / 1000
}
