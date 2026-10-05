package com.juxtapo.kdpager

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Logcat on this phone (Infinix/MTK) is tiny and chatty — lines rotate out in minutes.
// Everything also goes to files/pager.log (read: adb shell run-as com.juxtapo.kdpager cat files/pager.log).
object L {
    private const val TAG = "KDPager"
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) { file = File(ctx.filesDir, "pager.log") }

    @Synchronized private fun write(level: String, msg: String, t: Throwable? = null) {
        val f = file ?: return
        if (f.length() > 512 * 1024) f.renameTo(File(f.parentFile, "pager.old.log"))
        f.appendText("${fmt.format(Date())} $level [${Thread.currentThread().name}] $msg${t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""}\n")
    }

    fun d(msg: String) { Log.d(TAG, msg); write("D", msg) }
    fun i(msg: String) { Log.i(TAG, msg); write("I", msg) }
    fun w(msg: String) { Log.w(TAG, msg); write("W", msg) }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t); write("E", msg, t) }
}
