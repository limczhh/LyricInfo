package com.lidesheng.lyricinfo.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.lidesheng.lyricinfo.R
import com.lidesheng.lyricinfo.service.LyricMediaListenerService
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : Activity(), MediaSessionTracker.Listener {

    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvFormat: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvCurrentLine: TextView
    private lateinit var tvCurrentTranslation: TextView
    private lateinit var tvCurrentRomaji: TextView
    private lateinit var permissionCard: View
    private lateinit var listLyrics: ListView
    private lateinit var btnPermission: Button
    private lateinit var btnRefresh: Button

    private var tracker: MediaSessionTracker? = null
    private var lines: List<LyricLine> = emptyList()
    private var currentIndex: Int = -1
    private var lastSongKey: String = ""
    private lateinit var adapter: LyricAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvTitle = findViewById(R.id.tvTitle)
        tvArtist = findViewById(R.id.tvArtist)
        tvSource = findViewById(R.id.tvSource)
        tvFormat = findViewById(R.id.tvFormat)
        tvPosition = findViewById(R.id.tvPosition)
        tvCurrentLine = findViewById(R.id.tvCurrentLine)
        tvCurrentTranslation = findViewById(R.id.tvCurrentTranslation)
        tvCurrentRomaji = findViewById(R.id.tvCurrentRomaji)
        permissionCard = findViewById(R.id.permissionCard)
        listLyrics = findViewById(R.id.listLyrics)
        btnPermission = findViewById(R.id.btnPermission)
        btnRefresh = findViewById(R.id.btnRefresh)

        adapter = LyricAdapter()
        listLyrics.adapter = adapter

        btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btnRefresh.setOnClickListener {
            if (LyricMediaListenerService.isEnabled(this)) {
                ensureTracker()
                tracker?.refreshSessions()
            } else {
                updatePermissionUi(false)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = LyricMediaListenerService.isEnabled(this)
        updatePermissionUi(enabled)
        if (enabled) {
            ensureTracker()
            tracker?.refreshSessions()
        } else {
            tracker?.stop()
            tracker = null
        }
    }

    override fun onPause() {
        tracker?.stop()
        tracker = null
        super.onPause()
    }

    private fun ensureTracker() {
        if (tracker != null) return
        tracker = MediaSessionTracker(
            context = this,
            listenerComponent = LyricMediaListenerService.component(this),
            listener = this,
        ).also { it.start() }
    }

    private fun updatePermissionUi(enabled: Boolean) {
        permissionCard.visibility = if (enabled) View.GONE else View.VISIBLE
        if (!enabled) {
            setStatus(getString(R.string.ui_permission_needed), STATUS_WARN)
            tvCurrentLine.text = getString(R.string.ui_permission_needed)
            hideSecondaryCurrent()
        }
    }

    override fun onSessionUpdate(snapshot: MediaSessionTracker.SessionSnapshot) {
        val songKey =
            "${snapshot.packageName}|${snapshot.title}|${snapshot.artist}|${snapshot.durationMs}|${snapshot.lyricInfoRaw?.hashCode()}"
        val songChanged = songKey != lastSongKey
        if (songChanged) {
            lastSongKey = songKey
            lines = snapshot.lines
            adapter.setLines(lines)
            currentIndex = -1
        }

        tvTitle.text = snapshot.title.ifBlank { getString(R.string.ui_no_title) }
        tvArtist.text = snapshot.artist.ifBlank { getString(R.string.ui_no_artist) }
        tvSource.text = getString(R.string.ui_source, snapshot.packageName.ifBlank { "—" })
        val formatParts = buildList {
            add(snapshot.payload?.format?.ifBlank { "—" } ?: "—")
            if (!snapshot.payload?.translation.isNullOrBlank()) add("+译")
            if (!snapshot.payload?.romaji.isNullOrBlank()) add("+罗马音")
        }
        tvFormat.text = getString(R.string.ui_format, formatParts.joinToString(""))
        tvPosition.text = getString(
            R.string.ui_position,
            formatTime(snapshot.positionMs),
            formatTime(snapshot.durationMs),
        )

        when {
            snapshot.lyricInfoRaw.isNullOrBlank() -> {
                setStatus(getString(R.string.ui_waiting_lyric), STATUS_WARN)
                if (songChanged || currentIndex < 0) {
                    tvCurrentLine.text = getString(R.string.ui_waiting_lyric)
                    hideSecondaryCurrent()
                }
            }
            lines.isEmpty() -> {
                setStatus(getString(R.string.ui_receiving), STATUS_OK)
                tvCurrentLine.text = getString(R.string.ui_empty_lyrics)
                hideSecondaryCurrent()
            }
            else -> {
                setStatus(getString(R.string.ui_receiving), STATUS_OK)
                val idx = LyricInfoJson.indexAt(lines, snapshot.positionMs)
                if (idx != currentIndex) {
                    currentIndex = idx
                    adapter.setCurrentIndex(idx)
                    bindCurrentLine(lines[idx])
                    val target = (idx - 2).coerceAtLeast(0)
                    listLyrics.smoothScrollToPosition(target)
                }
            }
        }
    }

    private fun bindCurrentLine(line: LyricLine) {
        // 翻译 → 原文 → 罗马音
        if (!line.translation.isNullOrBlank()) {
            tvCurrentTranslation.visibility = View.VISIBLE
            tvCurrentTranslation.text = line.translation
        } else {
            tvCurrentTranslation.visibility = View.GONE
        }
        tvCurrentLine.text = line.text
        if (!line.romaji.isNullOrBlank()) {
            tvCurrentRomaji.visibility = View.VISIBLE
            tvCurrentRomaji.text = line.romaji
        } else {
            tvCurrentRomaji.visibility = View.GONE
        }
    }

    private fun hideSecondaryCurrent() {
        tvCurrentTranslation.visibility = View.GONE
        tvCurrentRomaji.visibility = View.GONE
    }

    override fun onNoSession() {
        lastSongKey = ""
        lines = emptyList()
        currentIndex = -1
        adapter.setLines(emptyList())
        tvTitle.text = getString(R.string.ui_no_title)
        tvArtist.text = getString(R.string.ui_no_artist)
        tvSource.text = getString(R.string.ui_source, "—")
        tvFormat.text = getString(R.string.ui_format, "—")
        tvPosition.text = getString(R.string.ui_position, "0:00", "0:00")
        tvCurrentLine.text = getString(R.string.ui_waiting_session)
        hideSecondaryCurrent()
        if (LyricMediaListenerService.isEnabled(this)) {
            setStatus(getString(R.string.ui_waiting_session), STATUS_WARN)
        }
    }

    override fun onError(message: String) {
        setStatus(message, STATUS_ERR)
    }

    private fun setStatus(text: String, kind: Int) {
        tvStatus.text = text
        val color = when (kind) {
            STATUS_OK -> getColor(R.color.status_ok)
            STATUS_ERR -> getColor(R.color.status_err)
            else -> getColor(R.color.status_warn)
        }
        tvStatus.setTextColor(color)
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    private inner class LyricAdapter : ArrayAdapter<LyricLine>(this, 0, mutableListOf()) {
        private var highlight = -1

        fun setLines(newLines: List<LyricLine>) {
            clear()
            addAll(newLines)
            highlight = -1
            notifyDataSetChanged()
        }

        fun setCurrentIndex(index: Int) {
            if (highlight == index) return
            highlight = index
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val box = (convertView as? android.widget.LinearLayout)
                ?: android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setPadding(8, 10, 8, 10)
                    // 0: translation, 1: original, 2: romaji
                    repeat(3) {
                        addView(TextView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            gravity = Gravity.CENTER
                        })
                    }
                }

            val trans = box.getChildAt(0) as TextView
            val main = box.getChildAt(1) as TextView
            val roma = box.getChildAt(2) as TextView

            val line = getItem(position)!!
            val active = position == highlight

            // 翻译
            if (!line.translation.isNullOrBlank()) {
                trans.visibility = View.VISIBLE
                trans.text = line.translation
                trans.textSize = if (active) 13f else 12f
                trans.setTextColor(
                    if (active) getColor(R.color.translation) else getColor(R.color.text_muted)
                )
            } else {
                trans.visibility = View.GONE
            }

            // 原文
            main.visibility = View.VISIBLE
            main.text = line.text
            main.textSize = if (active) 17f else 14f
            main.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            main.setTextColor(
                if (active) getColor(R.color.current_line) else getColor(R.color.other_line)
            )

            // 罗马音
            if (!line.romaji.isNullOrBlank()) {
                roma.visibility = View.VISIBLE
                roma.text = line.romaji
                roma.textSize = if (active) 13f else 12f
                roma.setTextColor(
                    if (active) getColor(R.color.romaji) else getColor(R.color.text_muted)
                )
            } else {
                roma.visibility = View.GONE
            }

            box.setBackgroundColor(
                if (active) getColor(R.color.accent_soft) else 0x00000000
            )
            return box
        }
    }

    companion object {
        private const val STATUS_OK = 0
        private const val STATUS_WARN = 1
        private const val STATUS_ERR = 2
    }
}
