package com.juxtapo.kdpager

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// KD's backend, as captured in kd-app/CHAT-WIRE.md. The anon key is public: it ships in krackeddevs.com's own bundle.
object Kd {
    const val SITE = "https://krackeddevs.com"
    const val SB = "https://nxukkhyjasusqbzhkqdv.supabase.co"
    const val ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im54dWtraHlqYXN1c3FiemhrcWR2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjUxNTk3NTAsImV4cCI6MjA4MDczNTc1MH0.I9OAWVdUOQ-uwiK0debm8KfW-dvZINRF7felzhnG7ig"
    const val REALTIME = "wss://nxukkhyjasusqbzhkqdv.supabase.co/realtime/v1/websocket?apikey=$ANON&vsn=1.0.0"
    private const val COOKIE = "sb-nxukkhyjasusqbzhkqdv-auth-token"
    private const val CHUNK = 3180 // @supabase/ssr chunk size

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS) // a silently dead socket fails within ~20s instead of lingering
        .build()

    data class Session(val access: String, val refresh: String, val expiresAt: Long, val userId: String, val json: JSONObject)

    data class Guild(val id: String, val name: String, val slug: String)

    class KdError(msg: String) : Exception(msg)

    // ── session: the site cookie is the shared source of truth; whichever copy expires later wins ──

    fun sessionFromCookie(): Session? {
        val all = CookieManager.getInstance().getCookie(SITE) ?: return null
        val parts = all.split(";").map { it.trim() }
            .mapNotNull { kv -> kv.indexOf('=').takeIf { it > 0 }?.let { kv.substring(0, it) to kv.substring(it + 1) } }
            .filter { it.first == COOKIE || it.first.startsWith("$COOKIE.") }
            .sortedBy { it.first.substringAfterLast('.', "0").toIntOrNull() ?: 0 }
        if (parts.isEmpty()) return null
        var raw = parts.joinToString("") { it.second }
        raw = java.net.URLDecoder.decode(raw, "UTF-8")
        if (raw.startsWith("base64-")) raw = String(b64decode(raw.removePrefix("base64-")))
        // a sign-out (on this phone or anywhere: KD's logout is global) can leave the cookie present but empty
        if (raw.isBlank()) return null
        return parse(JSONObject(raw))
    }

    fun sessionFromPrefs(ctx: Context): Session? =
        prefs(ctx).getString("session", null)?.let { parse(JSONObject(it)) }

    fun current(ctx: Context): Session? {
        val c = runCatching { sessionFromCookie() }.getOrNull()
        val p = sessionFromPrefs(ctx)
        val best = listOfNotNull(c, p).maxByOrNull { it.expiresAt } ?: return null
        if (best !== p) save(ctx, best)
        return best
    }

    private fun parse(o: JSONObject): Session {
        val user = o.optJSONObject("user") ?: throw KdError("session has no user object")
        return Session(o.getString("access_token"), o.getString("refresh_token"), o.getLong("expires_at"), user.getString("id"), o)
    }

    fun save(ctx: Context, s: Session) {
        // provider_token is the upstream Google/GitHub OAuth token — never kept
        val o = JSONObject(s.json.toString()).apply { remove("provider_token"); remove("provider_refresh_token") }
        prefs(ctx).edit().putString("session", o.toString()).apply()
    }

    fun forget(ctx: Context) = prefs(ctx).edit().remove("session").apply()

    // Refresh with the refresh token, then write the new session back into the WebView cookie,
    // so the site's own client never sees an expired token and never refreshes on its own.
    fun refresh(ctx: Context, s: Session): Session {
        val body = JSONObject().put("refresh_token", s.refresh).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$SB/auth/v1/token?grant_type=refresh_token")
            .header("apikey", ANON).post(body).build()
        http.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw KdError("refresh failed: HTTP ${r.code} ${text.take(200)}")
            val fresh = parse(JSONObject(text))
            save(ctx, fresh)
            writeCookie(fresh)
            return fresh
        }
    }

    private fun writeCookie(s: Session) {
        val o = JSONObject(s.json.toString()).apply { remove("provider_token"); remove("provider_refresh_token") }
        val value = "base64-" + Base64.encodeToString(o.toString().toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val chunks = value.chunked(CHUNK)
        val cm = CookieManager.getInstance()
        val attrs = "; Path=/; Max-Age=34560000; SameSite=Lax"
        if (chunks.size == 1) cm.setCookie(SITE, "$COOKIE=${chunks[0]}$attrs")
        else chunks.forEachIndexed { i, c -> cm.setCookie(SITE, "$COOKIE.$i=$c$attrs") }
        // drop leftovers from a longer previous session
        for (i in chunks.size until chunks.size + 3) cm.setCookie(SITE, "$COOKIE.$i=; Path=/; Max-Age=0")
        if (chunks.size > 1) cm.setCookie(SITE, "$COOKIE=; Path=/; Max-Age=0")
        cm.flush()
    }

    private fun b64decode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return Base64.decode(t, Base64.DEFAULT)
    }

    // ── REST ──

    private fun get(s: Session, path: String): String {
        val req = Request.Builder().url(SB + path).header("apikey", ANON).header("Authorization", "Bearer ${s.access}").build()
        http.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw KdError("GET $path → HTTP ${r.code} ${text.take(200)}")
            return text
        }
    }

    fun myGuilds(s: Session): List<Guild> {
        val a = JSONArray(get(s, "/rest/v1/guild_members?select=guild_id,guilds(name,slug)&user_id=eq.${s.userId}"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            val g = it.optJSONObject("guilds") ?: throw KdError("guild ${it.getString("guild_id")} has no guilds row")
            Guild(it.getString("guild_id"), g.getString("name"), g.getString("slug"))
        }
    }

    data class Row(val id: String, val guildId: String, val userId: String, val content: String, val createdAt: String)

    // messages newer than `after` (ISO timestamp from KD), oldest first; after == null → just the latest one (sets the mark)
    fun messagesSince(s: Session, guildId: String, after: String?): List<Row> {
        val q = if (after == null) "&order=created_at.desc&limit=1"
                else "&created_at=gt.${java.net.URLEncoder.encode(after, "UTF-8")}&order=created_at.asc&limit=50"
        val a = JSONArray(get(s, "/rest/v1/guild_messages?select=id,guild_id,user_id,content,created_at&guild_id=eq.$guildId$q"))
        return (0 until a.length()).map { a.getJSONObject(it) }.map { row(it) }
    }

    fun row(o: JSONObject) = Row(o.getString("id"), o.getString("guild_id"), o.getString("user_id"), o.optString("content"), o.getString("created_at"))

    // KD's RLS lets an account read only its OWN profile row, so other members' names come from the guild's
    // Members page (React Server Component payload, fetched with the site cookie): {"profile":{"username":…},"user_id":…}
    fun memberNames(slug: String): Map<String, String> {
        val cookie = CookieManager.getInstance().getCookie(SITE) ?: throw KdError("no KD cookie for the members page")
        val req = Request.Builder().url("$SITE/guilds/$slug/members").header("RSC", "1").header("Cookie", cookie).build()
        http.newCall(req).execute().use { r ->
            val text = r.body.string().replace("\\\"", "\"")
            if (!r.isSuccessful) throw KdError("members page $slug → HTTP ${r.code}")
            val re = Regex("\"profile\":\\{\"username\":\"([^\"]+)\"[^}]*\\},\"user_id\":\"([0-9a-f-]{36})\"")
            val names = re.findAll(text).associate { it.groupValues[2] to it.groupValues[1] }
            if (names.isEmpty()) throw KdError("members page $slug: no usernames found (KD changed the page?)")
            return names
        }
    }

    // null = KD returned no visible profile for this user (seen live: hidden/RLS) — caller says so plainly
    fun username(s: Session, userId: String): String? {
        val a = JSONArray(get(s, "/rest/v1/profiles?select=username&id=eq.$userId"))
        return if (a.length() == 0) null else a.getJSONObject(0).optString("username").ifEmpty { null }
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences("kdpager", Context.MODE_PRIVATE)
}
