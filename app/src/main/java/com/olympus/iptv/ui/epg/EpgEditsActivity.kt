package com.olympus.iptv.ui.epg

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.NameImages
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.databinding.ActivityEpgEditsBinding
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.ui.countries.CountriesActivity
import com.olympus.iptv.ui.lineup.LineupActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EpgEditsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEpgEditsBinding
    private lateinit var playlistId: String
    private var kind = ContentKind.LIVE
    private var includeVod = false
    private var config = LineupConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgEditsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        kind = if (intent.getStringExtra(EXTRA_KIND) == ContentKind.VOD.name) ContentKind.VOD else ContentKind.LIVE
        includeVod = intent.getBooleanExtra(EXTRA_INCLUDE_VOD, false)

        binding.cardCountries.setOnClickListener { openCountries() }
        binding.cardLineup.setOnClickListener { openLineup() }
        binding.cardReset.setOnClickListener { confirmReset() }
        binding.cardStrip.setOnClickListener { openStrip() }
        binding.cardGroups.setOnClickListener { openGroupRename() }
        binding.cardSanitize.setOnClickListener { startActivity(SanitizeActivity.intent(this, playlistId)) }
        binding.cardParse.setOnClickListener { confirmParse() }
        binding.cardImages.setOnClickListener { confirmStripImages() }
        binding.cardParental.setOnClickListener {
            startActivity(ParentalActivity.intent(this, playlistId))
        }
        binding.cardHideEmpty.setOnClickListener { toggleHideEmpty() }
        binding.cardDensity.setOnClickListener { toggleDensity() }
        binding.cardPinTimeout.setOnClickListener { cyclePinTimeout() }
        binding.btnBack.setOnClickListener { finish() }
        val details = listOf(
            binding.cardCountries to R.string.epg_card_countries_detail,
            binding.cardLineup to R.string.epg_card_lineup_detail,
            binding.cardReset to R.string.epg_card_reset_detail,
            binding.cardStrip to R.string.epg_card_strip_detail,
            binding.cardGroups to R.string.epg_card_groups_detail,
            binding.cardSanitize to R.string.epg_card_sanitize_detail,
            binding.cardParse to R.string.epg_card_parse_detail,
            binding.cardImages to R.string.epg_card_images_detail,
            binding.cardParental to R.string.epg_card_parental_detail,
            binding.cardHideEmpty to R.string.epg_card_hide_empty_detail,
            binding.cardDensity to R.string.epg_card_density_detail,
            binding.cardPinTimeout to R.string.epg_card_pin_timeout_detail
        )
        details.forEach { (card, detail) ->
            card.setOnFocusChangeListener { _, focused ->
                if (focused) binding.cardDetail.setText(detail)
            }
        }
        binding.cardCountries.requestFocus()
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            config = store.lineup(playlistId)
            showHideEmpty(store.hideEmptyGroups())
            showDensity(store.guideDensity())
            showPinTimeout(store.parentalTimeoutMinutes())
        }
    }

    private fun toggleHideEmpty() {
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val next = !store.hideEmptyGroups()
            store.saveHideEmptyGroups(next)
            showHideEmpty(next)
            Toast.makeText(
                this@EpgEditsActivity,
                if (next) R.string.epg_hide_empty_on else R.string.epg_hide_empty_off,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showHideEmpty(hide: Boolean) {
        binding.hideEmptyState.setText(
            if (hide) R.string.epg_short_hide_empty_on else R.string.epg_short_hide_empty_off
        )
    }

    private fun toggleDensity() {
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val current = store.guideDensity()
            val next = if (current == PlaylistStore.GUIDE_COMPACT) {
                PlaylistStore.GUIDE_COMFORTABLE
            } else {
                PlaylistStore.GUIDE_COMPACT
            }
            store.saveGuideDensity(next)
            showDensity(next)
            Toast.makeText(
                this@EpgEditsActivity,
                if (next == PlaylistStore.GUIDE_COMPACT) R.string.epg_density_compact
                else R.string.epg_density_comfortable,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showDensity(density: String) {
        binding.densityState.setText(
            if (density == PlaylistStore.GUIDE_COMPACT) R.string.epg_short_density_compact
            else R.string.epg_short_density_comfortable
        )
    }

    private fun cyclePinTimeout() {
        lifecycleScope.launch {
            val store = (application as OlympusIptvApp).playlistStore
            val current = store.parentalTimeoutMinutes()
            val options = PlaylistStore.PARENTAL_TIMEOUTS
            val index = options.indexOf(current).let { if (it < 0) 0 else (it + 1) % options.size }
            val next = options[index]
            store.saveParentalTimeoutMinutes(next)
            showPinTimeout(next)
            Toast.makeText(
                this@EpgEditsActivity,
                getString(R.string.epg_pin_timeout_set, next),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showPinTimeout(minutes: Int) {
        binding.pinTimeoutState.text = getString(R.string.epg_short_pin_timeout_value, minutes)
    }

    private fun openCountries() {
        startActivity(Intent(this, CountriesActivity::class.java).putExtra(CountriesActivity.EXTRA_PLAYLIST_ID, playlistId))
    }

    private fun openLineup() {
        if ((application as OlympusIptvApp).guideSource == null) return
        startActivity(LineupActivity.intent(this, playlistId, kind, includeVod))
    }

    private fun openStrip() {
        startActivity(StripNamesActivity.intent(this, playlistId))
    }

    private fun openGroupRename() {
        startActivity(GroupRenameActivity.intent(this, playlistId))
    }

    private fun confirmParse() {
        AlertDialog.Builder(this)
            .setTitle(R.string.epg_parse_title)
            .setMessage(R.string.epg_parse_message)
            .setPositiveButton(R.string.epg_parse_confirm) { _, _ -> parseVisibleNames() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun parseVisibleNames() {
        if (!binding.cardParse.isEnabled) return
        binding.cardParse.isEnabled = false
        binding.cardDetail.setText(R.string.epg_parse_working)
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                app.catalogDb.parseVisibleNames(playlistId, config)
            }
            val next = config.copy(nameEpg = true, revision = System.currentTimeMillis())
            config = next
            app.stagedLineup = playlistId to next
            app.epgRepository.installNameEpg(playlistId, parsed.programs)
            app.appScope.launch {
                runCatching { app.playlistStore.saveLineup(playlistId, next) }
                val email = app.playlistStore.session.first()?.email
                if (!email.isNullOrBlank()) {
                    runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
                }
            }
            binding.cardParse.isEnabled = true
            val message = getString(R.string.epg_parse_done, parsed.programs.size)
            binding.cardDetail.text = message
            if (!isFinishing) showParseExamples(parsed.programs.size, parsed.examples)
        }
    }

    private fun showParseExamples(count: Int, examples: List<Pair<String, String>>) {
        val body = if (examples.isEmpty()) {
            getString(R.string.epg_parse_same)
        } else {
            buildString {
                append(getString(R.string.epg_parse_examples))
                examples.forEach { (before, after) ->
                    append("\n\n")
                    append(getString(R.string.epg_parse_before, before))
                    append("\n")
                    append(getString(R.string.epg_parse_after, after))
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.epg_parse_done, count))
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmStripImages() {
        AlertDialog.Builder(this)
            .setTitle(R.string.epg_images_title)
            .setMessage(R.string.epg_images_message)
            .setPositiveButton(R.string.epg_images_confirm) { _, _ -> stripImages() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun stripImages() {
        if (!binding.cardImages.isEnabled) return
        binding.cardImages.isEnabled = false
        binding.cardDetail.setText(R.string.epg_images_working)
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val stripped = app.catalogDb.stripImages(playlistId)
                val groupNames = config.groupNames.orEmpty().mapValues { (_, name) -> NameImages.strip(name) }
                val channelNames = config.channelNames.orEmpty().mapValues { (_, name) -> NameImages.strip(name) }
                val overlayChanges = (config.groupNames.orEmpty().count { NameImages.strip(it.value) != it.value }) +
                    config.channelNames.orEmpty().count { NameImages.strip(it.value) != it.value }
                Triple(stripped, groupNames, channelNames) to overlayChanges
            }
            val stripped = result.first.first
            val groupNames = result.first.second
            val channelNames = result.first.third
            val changed = stripped.changed + result.second
            if (changed > 0) {
                val next = config.copy(
                    groupNames = groupNames,
                    channelNames = channelNames,
                    revision = System.currentTimeMillis()
                )
                config = next
                app.stagedLineup = playlistId to next
                app.appScope.launch {
                    runCatching { app.playlistStore.saveLineup(playlistId, next) }
                    val email = app.playlistStore.session.first()?.email
                    if (!email.isNullOrBlank()) {
                        runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
                    }
                }
            }
            binding.cardImages.isEnabled = true
            if (isFinishing) return@launch
            val title = if (changed == 0) {
                getString(R.string.epg_images_none)
            } else {
                getString(R.string.epg_images_done, changed)
            }
            binding.cardDetail.text = title
            val builder = AlertDialog.Builder(this@EpgEditsActivity).setTitle(title)
            if (stripped.examples.isEmpty()) {
                builder.setPositiveButton(android.R.string.ok, null).show()
            } else {
                val body = buildString {
                    append(getString(R.string.epg_images_examples))
                    stripped.examples.forEach { (before, after) ->
                        append("\n\n")
                        append(getString(R.string.group_rename_before, before))
                        append("\n")
                        append(getString(R.string.group_rename_after, after))
                    }
                }
                builder.setMessage(body).setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.epg_reset_title)
            .setMessage(R.string.epg_reset_message)
            .setPositiveButton(R.string.epg_reset_confirm) { _, _ -> resetVisibility() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resetVisibility() {
        val next = config.copy(
            hiddenCountries = emptyList(),
            hiddenGroups = emptyList(),
            hiddenChannels = emptyList(),
            revision = System.currentTimeMillis()
        )
        config = next
        val app = application as OlympusIptvApp
        app.stagedLineup = playlistId to next
        app.appScope.launch {
            runCatching { app.playlistStore.saveLineup(playlistId, next) }
            val email = app.playlistStore.session.first()?.email
            if (!email.isNullOrBlank()) {
                runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
            }
        }
        Toast.makeText(this, R.string.epg_reset_done, Toast.LENGTH_SHORT).show()
    }

    override fun finish() {
        setResult(RESULT_OK)
        super.finish()
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_INCLUDE_VOD = "include_vod"

        fun intent(context: Context, playlistId: String, kind: ContentKind, includeVod: Boolean): Intent =
            Intent(context, EpgEditsActivity::class.java)
                .putExtra(EXTRA_PLAYLIST_ID, playlistId)
                .putExtra(EXTRA_KIND, kind.name)
                .putExtra(EXTRA_INCLUDE_VOD, includeVod)
    }
}
