package com.juxtapo.kdpager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Icon
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

// Sender pictures for chat notifications: KD's public avatar URL -> round 128px icon, cached on disk by URL.
// A new picture on KD has a new URL, so the cache never goes stale. Any error -> null and a logged reason;
// the notification then shows Android's plain person icon.
object Avatars {
    private const val SIZE = 128

    private fun file(ctx: Context, url: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(ctx.filesDir, "avatars").apply { mkdirs() }, "$h.png")
    }

    // Blocking: FCM calls onMessageReceived off the main thread.
    fun icon(ctx: Context, url: String?): Icon? {
        if (url == null) return null
        val f = file(ctx, url)
        if (!f.exists()) {
            val raw = runCatching {
                Kd.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    r.body.bytes()
                }
            }.onFailure { L.w("avatar fetch failed $url: ${it.message}") }.getOrNull() ?: return null
            val src = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: run { L.w("avatar not an image: $url"); return null }
            f.outputStream().use { round(src).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val bmp = BitmapFactory.decodeFile(f.path) ?: run { L.w("avatar cache unreadable: ${f.path}"); return null }
        return Icon.createWithBitmap(bmp)
    }

    private fun round(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(sq, SIZE, SIZE, true)
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(scaled, 0f, 0f, p)
        return out
    }
}
