package com.juxtapo.kdpager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent

// Every kind of ping is its own Android channel: each one switches on/off in Settings → Apps → KD Pager → Notifications.
object Notify {
    const val CONNECTED = "connected"
    const val MENTIONS = "mentions"
    const val ALERTS = "alerts"
    const val FG_ID = 1

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun guildChannel(id: String) = "guild_$id"

    fun setup(ctx: Context) {
        val nm = nm(ctx)
        nm.createNotificationChannel(NotificationChannel(CONNECTED, "KD connected (silent)", NotificationManager.IMPORTANCE_MIN).apply {
            description = "The permanent line that keeps the listener alive. Never buzzes."
        })
        nm.createNotificationChannel(NotificationChannel(MENTIONS, "Mentions @you", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Someone wrote your @username in a guild chat."
        })
        RaidSound.ensureChannel(ctx)
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Pager problems", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Login expired or the listener stopped. Loud on purpose."
        })
    }

    fun ensureGuild(ctx: Context, g: Kd.Guild) {
        nm(ctx).createNotificationChannel(
            NotificationChannel(guildChannel(g.id), "Guild chat · ${g.name}", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages in ${g.name}."
            })
    }

    private fun open(ctx: Context, url: String, req: Int, guild: String? = null): PendingIntent =
        PendingIntent.getActivity(ctx, req,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_URL, url).putExtra(MainActivity.EXTRA_GUILD, guild)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun connected(ctx: Context, text: String): Notification =
        Notification.Builder(ctx, CONNECTED)
            .setSmallIcon(R.drawable.ic_stat_pager)
            .setContentTitle("KD Pager")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open(ctx, MainActivity.HOME, 0))
            .build()

    fun updateConnected(ctx: Context, text: String) = nm(ctx).notify(FG_ID, connected(ctx, text))

    data class Msg(val who: String, val text: String, val at: Long, val uid: String? = null, val icon: android.graphics.drawable.Icon? = null)

    // One stacked MessagingStyle notification per guild (WhatsApp-like). Mentions go to their own loud channel.
    fun message(ctx: Context, g: Kd.Guild, history: List<Msg>, mention: Boolean) {
        val me = Person.Builder().setName("You").build()
        // XOS's collapsed view shows only the conversation title + last text, so the latest sender goes in the title
        val last = history.last()
        val style = Notification.MessagingStyle(me).setConversationTitle("@${last.who} · ${g.name}").setGroupConversation(true)
        history.takeLast(6).forEach {
            style.addMessage(it.text, it.at, Person.Builder().setName("@${it.who}").setKey(it.uid).setIcon(it.icon).build())
        }
        val n = Notification.Builder(ctx, if (mention) MENTIONS else guildChannel(g.id))
            .setSmallIcon(R.drawable.ic_stat_pager)
            .setStyle(style)
            .setLargeIcon(last.icon)
            .setAutoCancel(true)
            .setContentIntent(open(ctx, "${Kd.SITE}/guilds/${g.slug}/chat", g.id.hashCode(), g.id))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        nm(ctx).notify(g.id.hashCode(), n)
    }

    // kind: "signup" (sign-up just opened) or "ready" (T-5 min). Tap / [Join raid] opens /raid in the app.
    // With boss art: boss face as the big icon, boss banner + tagline when expanded.
    fun raid(ctx: Context, kind: String, raidId: String, title: String, startsAt: Long, boss: BossArt.Spec?, art: BossArt.Art?) {
        val start = java.time.Instant.ofEpochSecond(startsAt).atZone(java.time.ZoneId.systemDefault())
        val at = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").format(start)
        val (head, body) = when (kind) {
            "signup" -> "Raid sign-up open" to "$title · starts $at"
            else -> "Get ready: raid in 5 min" to "$title · $at"
        }
        val id = "raid:$raidId:$kind".hashCode()
        val join = open(ctx, "${Kd.SITE}/raid", 3)
        val skip = PendingIntent.getBroadcast(ctx, id, Intent(ctx, DismissReceiver::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, RaidSound.ensureChannel(ctx))
            .setSmallIcon(R.drawable.ic_stat_pager)
            .setContentTitle(head)
            .setContentText(body)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT)
            .setContentIntent(join)
            .addAction(Notification.Action.Builder(null, "Join raid", join).build())
            .addAction(Notification.Action.Builder(null, "Not this time", skip).build())
        if (art != null) {
            b.setLargeIcon(art.face)
            b.setStyle(Notification.BigPictureStyle()
                .bigPicture(art.banner)
                .bigLargeIcon(null as android.graphics.drawable.Icon?)
                .setSummaryText(listOfNotNull(boss?.name, boss?.note?.takeIf { it.isNotBlank() }).joinToString(" · ")))
        }
        nm(ctx).notify(id, b.build())
    }

    fun alert(ctx: Context, title: String, text: String) {
        nm(ctx).notify(2, Notification.Builder(ctx, ALERTS)
            .setSmallIcon(R.drawable.ic_stat_pager)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(ctx, MainActivity.LOGIN, 2))
            .build())
    }
}
