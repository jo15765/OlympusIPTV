package com.olympus.iptv.ui.epg

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.GroupRename
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.NameStrip
import com.olympus.iptv.databinding.ActivitySanitizeBinding
import com.olympus.iptv.playlist.LineupClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SanitizeActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySanitizeBinding
    private lateinit var playlistId: String
    private var config = LineupConfig()
    private val channelKept = ArrayList<String>()
    private val channelStrip = ArrayList(NameStrip.sanitizeStreams)
    private val groupKept = ArrayList<String>()
    private val groupStrip = ArrayList(listOf("USA |"))
    private var channelPick: String? = null
    private var groupPick: String? = null
    private var cleaning = false
    private var flash: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySanitizeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        binding.btnBack.setOnClickListener { if (!cleaning) finish() }
        binding.channelToKept.setOnClickListener { moveChannel(toStrip = false) }
        binding.channelToStrip.setOnClickListener { moveChannel(toStrip = true) }
        binding.groupToKept.setOnClickListener { moveGroup(toStrip = false) }
        binding.groupToStrip.setOnClickListener { moveGroup(toStrip = true) }
        binding.btnCleanup.setOnClickListener { cleanup() }
        paint()
        binding.channelStrip.post {
            (binding.channelStrip.getChildAt(0) as? TextView)?.requestFocus()
        }
        lifecycleScope.launch {
            config = (application as OlympusIptvApp).playlistStore.lineup(playlistId)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (cleaning &&
            (event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
        ) {
            return true
        }
        if (!cleaning && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_B)
        ) {
            finish()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun moveChannel(toStrip: Boolean) {
        val token = channelPick ?: return
        if (toStrip) shift(channelKept, channelStrip, token) else shift(channelStrip, channelKept, token)
        paint()
        focusToken(if (toStrip) binding.channelStrip else binding.channelKept, token)
    }

    private fun moveGroup(toStrip: Boolean) {
        val token = groupPick ?: return
        if (toStrip) shift(groupKept, groupStrip, token) else shift(groupStrip, groupKept, token)
        paint()
        focusToken(if (toStrip) binding.groupStrip else binding.groupKept, token)
    }

    private fun shift(from: MutableList<String>, to: MutableList<String>, token: String) {
        val index = from.indexOfFirst { it.equals(token, ignoreCase = true) }
        if (index < 0) return
        val moved = from.removeAt(index)
        if (to.none { it.equals(moved, ignoreCase = true) }) to.add(moved)
    }

    private fun paint() {
        fill(binding.channelKept, channelKept, true)
        fill(binding.channelStrip, channelStrip, true)
        fill(binding.groupKept, groupKept, false)
        fill(binding.groupStrip, groupStrip, false)
        chain(binding.channelKept, binding.channelToKept.id, binding.channelToStrip.id)
        chain(binding.channelStrip, binding.channelToStrip.id, binding.groupToKept.id)
        chain(binding.groupKept, binding.groupToKept.id, binding.groupToStrip.id)
        chain(binding.groupStrip, binding.groupToStrip.id, binding.groupToStrip.id)
    }

    private fun fill(box: LinearLayout, tokens: List<String>, channel: Boolean) {
        box.removeAllViews()
        if (tokens.isEmpty()) {
            box.addView(TextView(this).apply {
                text = getString(R.string.sanitize_none)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 15f
                setPadding(12, 10, 12, 10)
            })
            return
        }
        val gap = (4 * resources.displayMetrics.density).toInt()
        for (token in tokens) {
            val row = TextView(this).apply {
                id = View.generateViewId()
                text = token
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                isFocusable = true
                isClickable = true
                background = getDrawable(R.drawable.bg_focusable)
                setPadding(12, 10, 12, 10)
                setOnFocusChangeListener { view, focused ->
                    if (!focused) return@setOnFocusChangeListener
                    if (channel) channelPick = token else groupPick = token
                    val rect = Rect(0, 0, view.width, view.height)
                    view.requestRectangleOnScreen(rect, false)
                }
            }
            row.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = gap }
            box.addView(row)
        }
    }

    private fun chain(box: LinearLayout, leftId: Int, rightId: Int) {
        val rows = (0 until box.childCount).map { box.getChildAt(it) }.filter { it.isFocusable }
        rows.forEachIndexed { index, row ->
            row.nextFocusUpId = if (index == 0) R.id.btnBack else rows[index - 1].id
            row.nextFocusDownId = if (index == rows.lastIndex) R.id.btnCleanup else rows[index + 1].id
            row.nextFocusLeftId = leftId
            row.nextFocusRightId = rightId
        }
    }

    private fun focusToken(box: LinearLayout, token: String) {
        box.post {
            for (index in 0 until box.childCount) {
                val child = box.getChildAt(index) as? TextView ?: continue
                if (child.text?.toString() == token) {
                    child.requestFocus()
                    return@post
                }
            }
        }
    }

    private fun cleanup() {
        if (cleaning) return
        cleaning = true
        binding.cleaning.visibility = View.VISIBLE
        binding.cleaning.requestFocus()
        flash = ObjectAnimator.ofFloat(binding.cleaning, View.ALPHA, 1f, 0.2f).apply {
            duration = 450
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
        val prefixes = withoutHeld(config.namePrefixes, channelKept, channelStrip)
        val suffixes = withoutHeld(config.nameSuffixes, channelKept, channelStrip)
        val groupTokens = groupStrip.toList()
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    app.catalogDb.rewriteNames(playlistId, prefixes, suffixes)
                    for (token in groupTokens) app.catalogDb.replaceGroupLabels(playlistId, token, "")
                }
                val names = config.groupNames.orEmpty().mapNotNull { (id, name) ->
                    var next = name
                    for (token in groupTokens) next = GroupRename.replaceAll(next, token, "").trim()
                    if (next.isEmpty()) null else id to next
                }.toMap()
                val channels = config.channelNames.orEmpty().mapValues { (_, name) ->
                    NameStrip.apply(name, prefixes, suffixes)
                }
                val next = config.copy(
                    namePrefixes = prefixes,
                    nameSuffixes = suffixes,
                    groupNames = names,
                    channelNames = channels,
                    revision = System.currentTimeMillis()
                )
                app.stagedLineup = playlistId to next
                app.appScope.launch {
                    runCatching { app.playlistStore.saveLineup(playlistId, next) }
                    val email = app.playlistStore.session.first()?.email
                    if (!email.isNullOrBlank()) {
                        runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
                    }
                }
                true
            }.getOrDefault(false)
            flash?.cancel()
            if (saved) {
                finish()
            } else {
                cleaning = false
                binding.cleaning.alpha = 1f
                binding.cleaning.visibility = View.GONE
            }
        }
    }

    private fun withoutHeld(current: List<String>?, held: List<String>, strip: List<String>): List<String> {
        val skip = held.map { it.lowercase() }.toSet()
        val base = current.orEmpty().filter { it.lowercase() !in skip }
        return NameStrip.merge(base, strip)
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, SanitizeActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
