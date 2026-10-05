package com.juxtapo.kdpager

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// The pager hub: holds the Connect Pager session, listens to KD, and pages this phone through FCM.
object Hub {
    const val BASE = "https://pager.jux-lab.com"
    private const val K_CONNECTED = "hub_connected"
    private const val K_KEY = "hub_device_key"

    class HubError(msg: String) : Exception(msg)

    fun prefs(ctx: Context) = Kd.prefs(ctx)
    // connected without a device key = registered before keys existed: shown as not connected so the user re-runs Connect
    fun connected(ctx: Context) = prefs(ctx).getBoolean(K_CONNECTED, false) && prefs(ctx).getString(K_KEY, null) != null
    fun guildCount(ctx: Context) = prefs(ctx).getInt("guild_count", -1).takeIf { it >= 0 }

    // The Connect Pager session lives only on the hub: this copy is kept in memory until the POST lands, never on disk.
    private fun sessionJson(s: Kd.Session): JSONObject = JSONObject()
        .put("access_token", s.access).put("refresh_token", s.refresh).put("expires_at", s.expiresAt)
        .put("user_id", s.userId)

    // Blocking: call off the main thread. Throws HubError with the exact reason, never returns a guess.
    fun fcmToken(): String {
        val t = com.google.android.gms.tasks.Tasks.await(FirebaseMessaging.getInstance().token)
        if (t.isNullOrEmpty()) throw HubError("FCM returned an empty token")
        return t
    }

    private fun post(path: String, body: JSONObject): String {
        val req = Request.Builder().url("$BASE$path")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        Kd.http.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw HubError("POST $path -> HTTP ${r.code} ${text.take(200)}")
            return text
        }
    }

    fun register(ctx: Context, session: Kd.Session) {
        val token = fcmToken()
        val res = JSONObject(post("/register", JSONObject().put("fcm_token", token).put("kd_session", sessionJson(session))))
        // the hub's proof that this phone owns the registration; rotation + disconnect must send it back
        val key = res.optString("device_key").ifEmpty { throw HubError("hub reply has no device_key: ${res.toString().take(200)}") }
        // a failed guild lookup leaves the count unknown (shown as absent), never a made-up number
        val n = runCatching { Kd.myGuilds(session).size }.onFailure { L.w("guild count unavailable: ${it.message}") }.getOrNull()
        prefs(ctx).edit().putBoolean(K_CONNECTED, true).putString("hub_token", token).putString(K_KEY, key).apply {
            if (n != null) putInt("guild_count", n) else remove("guild_count")
        }.apply()
        L.i("hub: registered fcm token ${token.take(8)}...")
    }

    fun unregister(ctx: Context) {
        val token = prefs(ctx).getString("hub_token", null) ?: fcmToken()
        val key = prefs(ctx).getString(K_KEY, null)
            ?: throw HubError("no device key stored (connected before keys existed): Connect again, then Disconnect")
        post("/unregister", JSONObject().put("fcm_token", token).put("device_key", key))
        prefs(ctx).edit().putBoolean(K_CONNECTED, false).remove("hub_token").remove(K_KEY).remove("guild_count").apply()
        L.i("hub: unregistered")
    }

    // FCM rotated the token. The hub already holds the session, so the hub must be told which new token replaces the old one.
    // /register with {fcm_token, old_fcm_token, device_key} and no kd_session.
    fun tokenRotated(ctx: Context, newToken: String) {
        val old = prefs(ctx).getString("hub_token", null)
        val key = prefs(ctx).getString(K_KEY, null)
        if (!connected(ctx) || old == null || key == null) {
            L.w("hub: new FCM token while not connected — Connect Pager to register it")
            return
        }
        post("/register", JSONObject().put("fcm_token", newToken).put("old_fcm_token", old).put("device_key", key))
        prefs(ctx).edit().putString("hub_token", newToken).apply()
        L.i("hub: token rotated ${newToken.take(8)}...")
    }
}
