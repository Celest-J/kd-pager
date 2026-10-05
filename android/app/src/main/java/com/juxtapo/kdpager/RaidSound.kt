package com.juxtapo.kdpager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The raid alert sound. Android locks a channel's sound when the channel is created, so every choice gets
// its own channel: switching = delete the old raid channel, create one for the new sound.
// Choice keys: "old_vibe" / "moonlight" / "night_rider" (bundled), "uri:<content uri>" (phone sound or own file).
object RaidSound {
    private const val K_SOUND = "raid_sound"
    private const val K_LABEL = "raid_sound_label"
    private val PREFERRED = "old_vibe"
    private const val TRIM_SECS = 25.0
    private const val FADE_SECS = 1.5

    private val ALL = linkedMapOf(
        "old_vibe" to Pair("Old Vibe", "Juxtapo's pick"),
        "moonlight" to Pair("Moonlight Raid", "Celeste's pick"),
        "night_rider" to Pair("Night Rider", "Celeste's synth"),
    )

    private fun bundled(ctx: Context, key: String) =
        ctx.resources.getIdentifier("raid_$key", "raw", ctx.packageName) != 0

    // only the sounds this APK actually carries
    fun builtIn(ctx: Context): Map<String, Pair<String, String>> = ALL.filterKeys { bundled(ctx, it) }

    private fun default(ctx: Context) = if (bundled(ctx, PREFERRED)) PREFERRED else builtIn(ctx).keys.first()

    fun key(ctx: Context): String {
        val k = Kd.prefs(ctx).getString(K_SOUND, null) ?: return default(ctx)
        // a saved built-in this build doesn't carry (e.g. a sound a from-source build doesn't carry) -> the default
        return if (k.startsWith("uri:") || k == "silent" || bundled(ctx, k)) k else default(ctx)
    }
    fun label(ctx: Context): String? = Kd.prefs(ctx).getString(K_LABEL, null)

    fun uri(ctx: Context, key: String): Uri? = when {
        key.startsWith("uri:") -> Uri.parse(key.removePrefix("uri:"))
        key == "silent" -> null
        else -> Uri.parse("android.resource://${ctx.packageName}/raw/raid_$key")
    }

    fun channelId(key: String) = "raid_" + Integer.toHexString(key.hashCode())

    // Creates (or keeps) the channel for the current sound and removes every other raid channel.
    fun ensureChannel(ctx: Context): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val key = key(ctx)
        val id = channelId(key)
        nm.notificationChannels.filter { it.id.startsWith("raid_") && it.id != id }.forEach { nm.deleteNotificationChannel(it.id) }
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, "Raid alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Raid sign-up opens, and 5 minutes before the raid starts."
                setSound(uri(ctx, key), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            })
        }
        return id
    }

    fun choose(ctx: Context, key: String, label: String?) {
        Kd.prefs(ctx).edit().putString(K_SOUND, key).putString(K_LABEL, label).apply()
        ensureChannel(ctx)
        L.i("raid sound -> $key ($label)")
    }

    // Preview: play the choice once through the notification stream.
    private var playing: android.media.Ringtone? = null
    fun preview(ctx: Context, key: String) {
        playing?.stop()
        val u = uri(ctx, key) ?: return
        playing = RingtoneManager.getRingtone(ctx, u)?.apply { play() }
    }
    fun stopPreview() { playing?.stop(); playing = null }

    // "My files…": the system can't read a file the user picked for us alone, so decode it, keep the first
    // 25 s with a fade-out, and save it as a WAV in the phone's shared Notifications folder. Blocking.
    fun importOwn(ctx: Context, src: Uri, name: String): Uri {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Own sounds need Android 10 or newer")
        val (pcm, rate, channels) = decode(ctx, src)
        fade(pcm, rate, channels)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "KD raid - ${name.substringBeforeLast('.')}.wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Notifications/KD Pager/")
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val out = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("MediaStore refused the new sound file")
        cr.openOutputStream(out)!!.use { it.write(wav(pcm, rate, channels)) }
        cr.update(out, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        return out
    }

    private data class Pcm(val data: ShortArray, val rate: Int, val channels: Int)

    private fun decode(ctx: Context, src: Uri): Pcm {
        val ex = MediaExtractor()
        ctx.contentResolver.openFileDescriptor(src, "r").use { fd ->
            ex.setDataSource(fd!!.fileDescriptor)
        }
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IllegalStateException("No audio track in that file")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var limit = Long.MAX_VALUE
        try {
            while (true) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (n < 0 || ex.sampleTime > TRIM_SECS * 1_000_000) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    limit = (TRIM_SECS * rate).toLong() * channels * 2
                    val b = codec.getOutputBuffer(o)!!
                    val chunk = ByteArray(info.size); b.position(info.offset); b.get(chunk)
                    out.write(chunk)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || out.size() >= limit) break
                }
            }
        } finally { codec.stop(); codec.release(); ex.release() }
        val bytes = out.toByteArray().let { if (it.size > limit) it.copyOf(limit.toInt()) else it }
        if (bytes.isEmpty()) throw IllegalStateException("Decoded no audio from that file")
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return Pcm(ShortArray(sb.remaining()).also { sb.get(it) }, rate, channels)
    }

    private fun fade(d: ShortArray, rate: Int, channels: Int) {
        val n = (FADE_SECS * rate).toInt() * channels
        val start = maxOf(0, d.size - n)
        for (i in start until d.size) d[i] = (d[i] * (d.size - i).toDouble() / (d.size - start)).toInt().toShort()
    }

    private fun wav(pcm: ShortArray, rate: Int, channels: Int): ByteArray {
        val data = pcm.size * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }
}
