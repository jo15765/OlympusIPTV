package com.olympus.iptv.ui.epg

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.NameStrip
import com.olympus.iptv.databinding.ActivityStripNamesBinding
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.ui.lineup.RenameDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StripNamesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStripNamesBinding
    private lateinit var playlistId: String
    private var config = LineupConfig()
    private var prefixText = ""
    private var suffixText = ""
    private var applying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStripNamesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnEditPrefix.setOnClickListener {
            RenameDialog.show(this, prefixText) { entered ->
                prefixText = entered
                paint()
            }
        }
        binding.btnEditSuffix.setOnClickListener {
            RenameDialog.show(this, suffixText) { entered ->
                suffixText = entered
                paint()
            }
        }
        binding.btnApply.setOnClickListener { apply() }
        binding.btnEditPrefix.requestFocus()
        lifecycleScope.launch {
            val app = application as OlympusIptvApp
            val stored = withContext(Dispatchers.IO) { app.playlistStore.lineup(playlistId) }
            val staged = app.stagedLineup?.takeIf { it.first == playlistId }?.second
            val chosen = if (staged != null && (staged.revision ?: 0L) >= (stored.revision ?: 0L)) staged else stored
            config = chosen
            if (staged != null && chosen === staged && chosen != stored) {
                withContext(Dispatchers.IO) { app.playlistStore.saveLineup(playlistId, chosen) }
            }
            prefixText = NameStrip.tokens(config.namePrefixes).joinToString(", ")
            suffixText = NameStrip.tokens(config.nameSuffixes).joinToString(", ")
            paint()
        }
    }

    private fun paint() {
        binding.prefixValue.text = listed(prefixText)
        binding.suffixValue.text = listed(suffixText)
    }

    private fun listed(raw: String): String {
        val tokens = NameStrip.tokens(raw)
        return if (tokens.isEmpty()) getString(R.string.strip_none) else tokens.joinToString("\n")
    }

    private fun apply() {
        if (applying) return
        applying = true
        binding.btnApply.isEnabled = false
        val prefixes = NameStrip.tokens(prefixText)
        val suffixes = NameStrip.tokens(suffixText)
        prefixText = prefixes.joinToString(", ")
        suffixText = suffixes.joinToString(", ")
        paint()
        val next = config.copy(
            namePrefixes = prefixes,
            nameSuffixes = suffixes,
            channelNames = config.channelNames.orEmpty().mapValues { (_, name) ->
                NameStrip.apply(name, prefixes, suffixes)
            },
            revision = System.currentTimeMillis()
        )
        config = next
        val app = application as OlympusIptvApp
        app.stagedLineup = playlistId to next
        binding.stripStatus.setText(R.string.strip_working)
        val save = app.appScope.async {
            runCatching { app.playlistStore.saveLineup(playlistId, next) }
            val count = if (prefixes.isEmpty() && suffixes.isEmpty()) {
                0
            } else {
                runCatching { app.catalogDb.rewriteNames(playlistId, prefixes, suffixes) }.getOrDefault(0)
            }
            val email = app.playlistStore.session.first()?.email
            if (!email.isNullOrBlank()) {
                runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
            }
            count
        }
        lifecycleScope.launch {
            val changed = save.await()
            applying = false
            binding.btnApply.isEnabled = true
            binding.stripStatus.text = if (prefixes.isEmpty() && suffixes.isEmpty()) {
                getString(R.string.strip_cleared)
            } else {
                getString(R.string.strip_done, changed)
            }
        }
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, StripNamesActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
