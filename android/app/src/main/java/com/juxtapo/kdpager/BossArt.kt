package com.juxtapo.kdpager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.sin

// The raid boss portrait, drawn the way KD's /raid page draws it: one frame of a sprite sheet,
// CSS hue-rotate applied, scaled up with hard pixels. Sheet cached on disk by URL.
object BossArt {
    data class Spec(val name: String, val note: String, val sprite: String, val sheetW: Int, val frame: Int, val x: Int, val hue: Int)

    class Art(val face: Bitmap, val banner: Bitmap)

    fun spec(d: Map<String, String>): Spec? {
        val name = d["boss_name"] ?: return null
        return Spec(
            name, d["boss_note"] ?: "",
            d["boss_sprite"] ?: return null,
            d["boss_sheet_w"]?.toIntOrNull() ?: return null,
            d["boss_frame"]?.toIntOrNull() ?: return null,
            d["boss_x"]?.toIntOrNull() ?: return null,
            d["boss_hue"]?.toIntOrNull() ?: 0,
        )
    }

    private fun sheet(ctx: Context, url: String): Bitmap? {
        val h = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(File(ctx.filesDir, "boss").apply { mkdirs() }, "$h.png")
        if (!f.exists()) {
            val bytes = runCatching {
                Kd.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    r.body.bytes()
                }
            }.onFailure { L.w("boss sprite fetch failed $url: ${it.message}") }.getOrNull() ?: return null
            f.writeBytes(bytes)
        }
        return BitmapFactory.decodeFile(f.path) ?: run { L.w("boss sprite unreadable: $url"); f.delete(); null }
    }

    // CSS filter: hue-rotate(deg), same matrix the browser uses
    private fun hue(deg: Int): ColorMatrix {
        val a = Math.toRadians(deg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return ColorMatrix(floatArrayOf(
            0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
            0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
            0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
    }

    // Blocking (FCM thread). null + logged reason when the art can't be built; the alert still goes out.
    fun build(ctx: Context, s: Spec): Art? {
        val sheet = sheet(ctx, s.sprite) ?: return null
        val k = sheet.width.toFloat() / s.sheetW          // page draws the sheet scaled; map page px -> file px
        val fx = (s.x * k).toInt(); val fw = (s.frame * k).toInt()
        if (fw <= 0 || fx + fw > sheet.width || fw > sheet.height) {
            L.w("boss frame out of sheet: x=$fx w=$fw sheet=${sheet.width}x${sheet.height}"); return null
        }
        val raw = Bitmap.createBitmap(sheet, fx, 0, fw, fw)
        val pixel = Paint().apply { isFilterBitmap = false; colorFilter = ColorMatrixColorFilter(hue(s.hue)) }

        val face = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        Canvas(face).apply {
            drawColor(ctx.getColor(R.color.kd_black))
            drawBitmap(raw, null, android.graphics.Rect(16, 16, 240, 240), pixel)
        }
        val banner = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        Canvas(banner).apply {
            drawColor(ctx.getColor(R.color.kd_black))
            // faint green glow behind the boss, like KD's hero blocks
            drawCircle(512f, 256f, 230f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(512f, 256f, 230f, 0x3322C55E, 0x0022C55E, android.graphics.Shader.TileMode.CLAMP)
            })
            drawBitmap(raw, null, android.graphics.Rect(512 - 208, 256 - 208, 512 + 208, 256 + 208), pixel)
        }
        return Art(face, banner)
    }
}
