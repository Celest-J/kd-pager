package com.juxtapo.kdpager

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.time.Instant

// The hub's data-only push: {type, guild_id, guild_name, guild_slug, msg_id, user_id, username, content, created_at, mention}
// plus optional avatar_url (absent = sender has no picture or is unknown).
class PagerMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(m: RemoteMessage) {
        L.init(this)
        Notify.setup(this)
        val d = m.data
        when (d["type"]) {
            "chat" -> chat(d)
            "raid" -> raid(d)
            "alert" -> Notify.alert(this, "KD Pager", d["message"] ?: "Hub alert without a message")
            else -> {
                L.e("fcm: unknown push type '${d["type"]}': $d")
                Notify.alert(this, "Pager payload error", "Unknown push type '${d["type"]}' — app older than hub?")
            }
        }
    }

    // {type=raid, kind=signup|ready, raid_id, title, starts_at (unix s), optional boss_name/note/sprite/sheet_w/frame/x/hue}. Dropped when Raid alerts is off in settings.
    private fun raid(d: Map<String, String>) {
        if (!SettingsActivity.raidAlerts(this)) { L.d("fcm: raid ${d["kind"]} dropped, raid alerts off"); return }
        val missing = listOf("kind", "raid_id", "title", "starts_at").filter { d[it].isNullOrEmpty() }
        val starts = d["starts_at"]?.toLongOrNull()
        if (missing.isNotEmpty() || starts == null) {
            L.e("fcm: raid payload bad (missing $missing, starts_at '${d["starts_at"]}'): $d")
            Notify.alert(this, "Pager payload error", "Raid push missing fields $missing")
            return
        }
        // boss_* fields are optional: absent = /raid showed no art; the alert still goes out, face-less
        val boss = BossArt.spec(d)
        Notify.raid(this, d["kind"]!!, d["raid_id"]!!, d["title"]!!, starts, boss, boss?.let { BossArt.build(this, it) })
    }

    private fun chat(d: Map<String, String>) {
        val missing = listOf("guild_id", "guild_name", "guild_slug", "username", "content", "created_at").filter { d[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) {
            L.e("fcm: payload missing $missing: $d")
            Notify.alert(this, "Pager payload error", "Hub push missing fields $missing")
            return
        }
        val at = runCatching { Instant.parse(d["created_at"]).toEpochMilli() }.getOrElse {
            L.e("fcm: bad created_at '${d["created_at"]}'", it)
            return
        }
        val g = Kd.Guild(d["guild_id"]!!, d["guild_name"]!!, d["guild_slug"]!!)
        Notify.ensureGuild(this, g)
        val h = PagerService.history.getOrPut(g.id) { java.util.Collections.synchronizedList(mutableListOf()) }
        h.add(Notify.Msg(d["username"]!!, d["content"]!!, at, d["user_id"], Avatars.icon(this, d["avatar_url"])))
        Notify.message(this, g, h.toList(), d["mention"] == "1")
        L.d("fcm: ${g.slug} msg ${d["msg_id"]}")
    }

    override fun onNewToken(token: String) {
        L.init(this)
        L.i("fcm: new token ${token.take(8)}...")
        Thread {
            runCatching { Hub.tokenRotated(this, token) }.onFailure { L.e("hub: token re-register failed", it) }
        }.start()
    }
}
