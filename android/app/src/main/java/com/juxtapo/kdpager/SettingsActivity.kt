package com.juxtapo.kdpager

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

// Our own page, in KD's look (palette + shapes read from krackeddevs.com): dark card, mono uppercase labels, green.
class SettingsActivity : Activity() {

    companion object {
        private const val K_RAID = "raid_alerts"
        fun raidAlerts(ctx: android.content.Context) = Kd.prefs(ctx).getBoolean(K_RAID, true)
    }

    private val dp get() = resources.displayMetrics.density
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var action: TextView

    private fun text(size: Float, color: Int, mono: Boolean = false, bold: Boolean = false) = TextView(this).apply {
        textSize = size; setTextColor(getColor(color))
        // KD's labels are JetBrains Mono (bundled, OFL)
        typeface = Typeface.create(if (mono) resources.getFont(R.font.jetbrains_mono) else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun label(s: String) = text(11f, R.color.kd_grey, mono = true).apply { text = s.uppercase(); letterSpacing = 0.08f }

    private fun shape(fill: Int, stroke: Int?, radius: Float) = GradientDrawable().apply {
        setColor(getColor(fill)); stroke?.let { setStroke((1 * dp).toInt(), getColor(it)) }; cornerRadius = radius * dp
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * dp).toInt()
        status = text(18f, R.color.kd_white, bold = true)
        detail = text(13f, R.color.kd_grey, mono = true).apply { setPadding(0, (6 * dp).toInt(), 0, pad) }
        action = text(15f, R.color.kd_white, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(pad, (14 * dp).toInt(), pad, (14 * dp).toInt())
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(R.color.kd_card, R.color.kd_border, 16f)
            setPadding(pad, pad, pad, pad)
            addView(label("Pager").apply { setPadding(0, 0, 0, (12 * dp).toInt()) })
            addView(status); addView(detail)
            addView(action, LinearLayout.LayoutParams(-1, -2))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(label("Settings").apply { setPadding(0, 0, 0, (4 * dp).toInt()) })
            addView(text(13f, R.color.kd_grey, mono = true).apply { text = "Phone buzzes when your guilds chat."; setPadding(0, 0, 0, pad) })
            addView(card)
            addView(raidCard(pad), LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * dp).toInt() })
        }
        val root = ScrollView(this).apply { setBackgroundColor(getColor(R.color.kd_black)); addView(col) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        setContentView(root)
    }

    private val REQ_PHONE = 10
    private val REQ_FILE = 11
    private lateinit var soundRows: LinearLayout

    private fun raidCard(pad: Int): LinearLayout {
        val sw = android.widget.Switch(this).apply {
            isChecked = raidAlerts(this@SettingsActivity)
            // KD's toggle: bright green pill, black knob (the stock Switch dims its track)
            fun pill(fill: Int, w: Int, h: Int) = GradientDrawable().apply { setColor(getColor(fill)); cornerRadius = h * dp; setSize((w * dp).toInt(), (h * dp).toInt()) }
            trackDrawable = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), pill(R.color.kd_green, 44, 24))
                addState(intArrayOf(), pill(R.color.kd_border, 44, 24))
            }
            thumbDrawable = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked), pill(R.color.kd_black, 24, 24).apply { setStroke((3 * dp).toInt(), getColor(R.color.kd_green)) })
                addState(intArrayOf(), pill(R.color.kd_grey, 24, 24).apply { setStroke((3 * dp).toInt(), getColor(R.color.kd_card)) })
            }
            setOnCheckedChangeListener { _, on -> Kd.prefs(this@SettingsActivity).edit().putBoolean(K_RAID, on).apply() }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(16f, R.color.kd_white, bold = true).apply { text = "Raid alerts" })
            addView(text(12f, R.color.kd_grey, mono = true).apply {
                text = "SIGN-UP OPEN · 5 MIN BEFORE T-0"; setPadding(0, (4 * dp).toInt(), 0, 0)
            })
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
        soundRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(R.color.kd_card, R.color.kd_border, 16f)
            setPadding(pad, pad, pad, pad)
            addView(head)
            addView(label("Raid sound").apply { setPadding(0, pad, 0, (6 * dp).toInt()) })
            addView(soundRows)
        }
    }

    // One row per choice: ● picked / ○ not. Tapping a built-in picks it and plays a preview.
    private fun renderSounds() {
        soundRows.removeAllViews()
        val cur = RaidSound.key(this)
        fun row(title: String, sub: String, picked: Boolean, onTap: () -> Unit) {
            soundRows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
                addView(text(16f, if (picked) R.color.kd_green else R.color.kd_grey, mono = true).apply {
                    text = if (picked) "●" else "○"; setPadding(0, 0, (12 * dp).toInt(), 0)
                })
                addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(15f, if (picked) R.color.kd_white else R.color.kd_grey, bold = picked).apply { text = title })
                    addView(text(11f, R.color.kd_grey, mono = true).apply { text = sub.uppercase() })
                })
                setOnClickListener { onTap() }
            })
        }
        RaidSound.builtIn(this).forEach { (k, v) ->
            row(v.first, v.second, cur == k) { RaidSound.choose(this, k, v.first); RaidSound.preview(this, k); renderSounds() }
        }
        soundRows.addView(android.view.View(this).apply { setBackgroundColor(getColor(R.color.kd_border)) },
            LinearLayout.LayoutParams(-1, (1 * dp).toInt()).apply { topMargin = (6 * dp).toInt(); bottomMargin = (6 * dp).toInt() })
        val custom = cur.startsWith("uri:")
        val phone = custom && Kd.prefs(this).getString("raid_sound_src", null) == "phone"
        row("Phone sounds…", if (phone) RaidSound.label(this) ?: "picked" else "your phone's alert tones", phone) {
            startActivityForResult(Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                    android.media.RingtoneManager.TYPE_NOTIFICATION or android.media.RingtoneManager.TYPE_RINGTONE or android.media.RingtoneManager.TYPE_ALARM)
                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Raid sound")
            }, REQ_PHONE)
        }
        row("My files…", if (custom && !phone) RaidSound.label(this) ?: "picked" else "mp3 / music · first 25 s", custom && !phone) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"
            }, REQ_FILE)
        }
    }

    @Deprecated("startActivityForResult is enough for two pickers")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        when (req) {
            REQ_PHONE -> {
                val u: android.net.Uri = data.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI) ?: return
                val name = android.media.RingtoneManager.getRingtone(this, u)?.getTitle(this)
                Kd.prefs(this).edit().putString("raid_sound_src", "phone").apply()
                RaidSound.choose(this, "uri:$u", name)
                renderSounds()
            }
            REQ_FILE -> {
                val src = data.data ?: return
                val name = contentResolver.query(src, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "sound"
                android.widget.Toast.makeText(this, "Cutting the first 25 s of $name…", android.widget.Toast.LENGTH_SHORT).show()
                Thread {
                    val r = runCatching { RaidSound.importOwn(this, src, name) }
                    runOnUiThread {
                        r.onSuccess { u ->
                            Kd.prefs(this).edit().putString("raid_sound_src", "file").apply()
                            RaidSound.choose(this, "uri:$u", name.substringBeforeLast('.'))
                            RaidSound.preview(this, "uri:$u")
                            renderSounds()
                        }.onFailure {
                            L.e("own raid sound failed", it)
                            android.widget.Toast.makeText(this, "Could not use $name: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        RaidSound.stopPreview()
    }

    override fun onResume() {
        super.onResume()
        render(null)
        renderSounds()
    }

    // One control: logged in = pager on, Log out = pager off. There is no separate pager login.
    private fun render(error: String?) {
        val loggedIn = runCatching { Kd.sessionFromCookie() }.getOrNull() != null
        val on = Hub.connected(this)
        status.text = when { on -> "Pager on"; loggedIn -> "Pager off"; else -> "Logged out" }
        detail.text = listOfNotNull(
            when {
                on -> Hub.guildCount(this)?.let { "WATCHING $it ${if (it == 1) "GUILD" else "GUILDS"}" } ?: "GUILD COUNT UNKNOWN"
                loggedIn -> "TURNS ON BY ITSELF ON THE NEXT KD PAGE"
                else -> "LOG IN TO KD AND THE PAGER TURNS ON"
            },
            error,
        ).joinToString("\n")
        action.visibility = if (loggedIn) View.VISIBLE else View.GONE
        action.text = "Log out"
        action.setTextColor(getColor(R.color.kd_white))
        action.background = shape(R.color.kd_black, R.color.kd_border, 8f)
        action.setOnClickListener {
            action.isEnabled = false
            Thread {
                val err = if (Hub.connected(this)) runCatching { Hub.unregister(this) }.onFailure { L.e("logout: unregister failed", it) }.exceptionOrNull()?.message else null
                runOnUiThread {
                    // logged out either way; a failed hub notice is retried on the next app open
                    Kd.clearCookie()
                    Kd.forget(this)
                    if (err != null) Toast.makeText(this, "Logged out, but the pager hub could not be told: $err. Retrying next open.", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_URL, MainActivity.LOGIN)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }
            }.start()
        }
    }
}
