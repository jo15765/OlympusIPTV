package com.olympus.iptv.ui.epg

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.ui.browse.ToolHost
import com.olympus.iptv.ui.countries.CountriesFragment
import com.olympus.iptv.ui.exitToolHost
import com.olympus.iptv.ui.viewActive
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.ContentKind
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.NameImages
import com.olympus.iptv.data.PlayerDecoder
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.data.ScrobblerIds
import com.olympus.iptv.databinding.ActivityEpgEditsBinding
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.ui.lineup.LineupActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EpgEditsFragment : Fragment() {
    private var _binding: ActivityEpgEditsBinding? = null
    private val binding get() = _binding!!
    private val playlistId: String
        get() = arguments?.getString(EXTRA_PLAYLIST_ID).orEmpty()
    private val kind: ContentKind
        get() = if (arguments?.getString(EXTRA_KIND) == ContentKind.VOD.name) ContentKind.VOD else ContentKind.LIVE
    private val includeVod: Boolean
        get() = arguments?.getBoolean(EXTRA_INCLUDE_VOD, false) == true
    private var config = LineupConfig()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityEpgEditsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (playlistId.isBlank()) {
            closeEdits()
            return
        }

        val tabs = listOf(
            binding.tabPlayback to binding.panelPlayback,
            binding.tabCuration to binding.panelCuration,
            binding.tabSecurity to binding.panelSecurity,
            binding.tabServices to binding.panelServices
        )

        fun selectTab(selectedTab: View) {
            tabs.forEach { (tab, panel) ->
                val active = tab === selectedTab
                tab.isSelected = active
                tab.setBackgroundResource(if (active) R.drawable.bg_button else R.drawable.bg_nav)
                tab.setTextColor(if (active) requireContext().getColor(R.color.bg) else requireContext().getColor(R.color.text_primary))
                panel.visibility = if (active) View.VISIBLE else View.GONE
            }
        }

        tabs.forEach { (tab, _) ->
            tab.setOnClickListener { selectTab(tab) }
            tab.setOnFocusChangeListener { _, focused -> if (focused) selectTab(tab) }
        }

        selectTab(binding.tabPlayback)

        binding.cardCountries.setOnClickListener { openCountries() }
        binding.cardLineup.setOnClickListener { openLineup() }
        binding.cardReset.setOnClickListener { confirmReset() }
        binding.cardStrip.setOnClickListener { openStrip() }
        binding.cardGroups.setOnClickListener { openGroupRename() }
        binding.cardSanitize.setOnClickListener { openSanitize() }
        binding.cardParse.setOnClickListener { confirmParse() }
        binding.cardImages.setOnClickListener { confirmStripImages() }
        binding.cardParental.setOnClickListener { openParental() }
        binding.cardAutoClean.setOnClickListener { toggleAutoClean() }
        binding.cardHideEmpty.setOnClickListener { toggleHideEmpty() }
        binding.cardDensity.setOnClickListener { toggleDensity() }
        binding.cardPinTimeout.setOnClickListener { cyclePinTimeout() }
        binding.cardDecoder.setOnClickListener { cycleDecoder() }
        binding.cardTmdb.setOnClickListener { cycleTmdbLimit() }
        binding.cardScrobbler.setOnClickListener { cycleScrobbler() }
        binding.cardPip.setOnClickListener { togglePip() }
        binding.btnBack.setOnClickListener { closeEdits() }

        binding.tabPlayback.requestFocus()
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closeEdits()
                }
            }
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val store = (requireActivity().application as OlympusIptvApp).playlistStore
            config = store.lineup(playlistId)
            showAutoClean(config.autoCleanNames != false)
            showHideEmpty(store.hideEmptyGroups())
            showDensity(store.guideDensity())
            showPinTimeout(store.parentalTimeoutMinutes())
            showDecoder(store.videoDecoder())
            showTmdb(store.tmdbItemsLimit())
            showScrobbler(store.scrobblerProvider())
            showPip(store.pipSwipeHome())
        }
    }

    private fun toggleAutoClean() {
        val nextEnabled = config.autoCleanNames == false
        val next = config.copy(autoCleanNames = nextEnabled, revision = System.currentTimeMillis())
        config = next
        showAutoClean(nextEnabled)
        val app = requireActivity().application as OlympusIptvApp
        app.stagedLineup = playlistId to next
        app.appScope.launch {
            runCatching { app.playlistStore.saveLineup(playlistId, next) }
            val email = app.playlistStore.session.first()?.email
            if (!email.isNullOrBlank()) {
                runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
            }
        }
        Toast.makeText(
            requireContext(),
            if (nextEnabled) R.string.epg_auto_clean_on else R.string.epg_auto_clean_off,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showAutoClean(enabled: Boolean) {
        binding.autoCleanState.setText(
            if (enabled) R.string.epg_short_hide_empty_on else R.string.epg_short_hide_empty_off
        )
    }

    private fun toggleHideEmpty() {
        viewLifecycleOwner.lifecycleScope.launch {
            val store = (requireActivity().application as OlympusIptvApp).playlistStore
            val next = !store.hideEmptyGroups()
            store.saveHideEmptyGroups(next)
            showHideEmpty(next)
            Toast.makeText(
                requireContext(),
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
        viewLifecycleOwner.lifecycleScope.launch {
            val store = (requireActivity().application as OlympusIptvApp).playlistStore
            val current = store.guideDensity()
            val next = if (current == PlaylistStore.GUIDE_COMPACT) {
                PlaylistStore.GUIDE_COMFORTABLE
            } else {
                PlaylistStore.GUIDE_COMPACT
            }
            store.saveGuideDensity(next)
            showDensity(next)
            Toast.makeText(
                requireContext(),
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
        viewLifecycleOwner.lifecycleScope.launch {
            val store = (requireActivity().application as OlympusIptvApp).playlistStore
            val current = store.parentalTimeoutMinutes()
            val options = PlaylistStore.PARENTAL_TIMEOUTS
            val index = options.indexOf(current).let { if (it < 0) 0 else (it + 1) % options.size }
            val next = options[index]
            store.saveParentalTimeoutMinutes(next)
            showPinTimeout(next)
            Toast.makeText(
                requireContext(),
                getString(R.string.epg_pin_timeout_set, next),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showPinTimeout(minutes: Int) {
        binding.pinTimeoutState.text = getString(R.string.epg_short_pin_timeout_value, minutes)
    }

    private fun cycleDecoder() {
        viewLifecycleOwner.lifecycleScope.launch {
            val store = (requireActivity().application as OlympusIptvApp).playlistStore
            val options = PlayerDecoder.OPTIONS
            val current = store.videoDecoder()
            val index = options.indexOf(current).let { if (it < 0) 0 else (it + 1) % options.size }
            val next = options[index]
            store.saveVideoDecoder(next)
            showDecoder(next)
            Toast.makeText(requireContext(), decoderLabel(next), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDecoder(type: String) {
        binding.decoderState.text = decoderLabel(type)
    }

    private fun decoderLabel(type: String): String = when (PlayerDecoder.normalize(type)) {
        PlayerDecoder.HARDWARE_ASYNC -> getString(R.string.decoder_hardware_async)
        PlayerDecoder.HARDWARE_FAILSAFE -> getString(R.string.decoder_hardware_failsafe)
        PlayerDecoder.SOFTWARE -> getString(R.string.decoder_software)
        else -> getString(R.string.decoder_hardware)
    }

    private fun cycleTmdbLimit() {
        viewLifecycleOwner.lifecycleScope.launch {
            val store = (requireActivity().application as OlympusIptvApp).playlistStore
            val options = PlaylistStore.TMDB_ITEM_OPTIONS
            val current = store.tmdbItemsLimit()
            val index = options.indexOf(current).let { if (it < 0) 0 else (it + 1) % options.size }
            val next = options[index]
            store.saveTmdbItemsLimit(next)
            showTmdb(next)
            Toast.makeText(
                requireContext(),
                getString(R.string.epg_tmdb_set, next),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showTmdb(limit: Int) {
        binding.tmdbState.text = getString(R.string.epg_short_tmdb, limit)
    }

    private fun cycleScrobbler() {
        viewLifecycleOwner.lifecycleScope.launch {
            val store = (requireActivity().application as OlympusIptvApp).playlistStore
            val current = store.scrobblerProvider()
            when (current) {
                ScrobblerIds.NONE -> promptScrobbler(ScrobblerIds.SIMKL)
                ScrobblerIds.SIMKL -> promptScrobbler(ScrobblerIds.TRAKT)
                else -> {
                    store.clearScrobbler()
                    showScrobbler(ScrobblerIds.NONE)
                    Toast.makeText(requireContext(), R.string.scrobbler_cleared, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun promptScrobbler(provider: String) {
        val label = if (provider == ScrobblerIds.SIMKL) {
            getString(R.string.scrobbler_simkl)
        } else {
            getString(R.string.scrobbler_trakt)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val token = EditText(requireContext()).apply {
            hint = getString(R.string.scrobbler_token_hint)
            setSingleLine()
        }
        val client = EditText(requireContext()).apply {
            hint = getString(R.string.scrobbler_client_hint)
            setSingleLine()
        }
        column.addView(token)
        column.addView(client)
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.scrobbler_token_title, label))
            .setView(column)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val store = (requireActivity().application as OlympusIptvApp).playlistStore
                    val tokenValue = token.text?.toString().orEmpty()
                    val clientValue = client.text?.toString().orEmpty()
                    if (tokenValue.isBlank()) {
                        store.clearScrobbler()
                        showScrobbler(ScrobblerIds.NONE)
                        Toast.makeText(requireContext(), R.string.scrobbler_cleared, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    if (provider == ScrobblerIds.SIMKL) {
                        store.saveSimklCredentials(tokenValue, clientValue)
                    } else {
                        store.saveTraktCredentials(tokenValue, clientValue)
                    }
                    showScrobbler(provider)
                    Toast.makeText(requireContext(), R.string.scrobbler_saved, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showScrobbler(provider: String) {
        binding.scrobblerState.text = when (provider) {
            ScrobblerIds.SIMKL -> getString(R.string.scrobbler_simkl)
            ScrobblerIds.TRAKT -> getString(R.string.scrobbler_trakt)
            else -> getString(R.string.scrobbler_none)
        }
    }

    private fun togglePip() {
        viewLifecycleOwner.lifecycleScope.launch {
            val store = (requireActivity().application as OlympusIptvApp).playlistStore
            val next = !store.pipSwipeHome()
            store.savePipSwipeHome(next)
            showPip(next)
            Toast.makeText(
                requireContext(),
                if (next) R.string.epg_short_hide_empty_on else R.string.epg_short_hide_empty_off,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showPip(enabled: Boolean) {
        binding.pipState.setText(
            if (enabled) R.string.epg_short_hide_empty_on else R.string.epg_short_hide_empty_off
        )
    }

    private fun openCountries() {
        parentFragmentManager.beginTransaction()
            .replace((requireView().parent as View).id, CountriesFragment.newInstance(playlistId))
            .addToBackStack("countries")
            .commit()
    }

    private fun openLineup() {
        startActivity(LineupActivity.intent(requireContext(), playlistId, kind, includeVod))
    }

    private fun openStrip() {
        parentFragmentManager.beginTransaction()
            .replace(
                (requireView().parent as View).id,
                StripNamesFragment.newInstance(playlistId)
            )
            .addToBackStack("strip")
            .commit()
    }

    private fun openGroupRename() {
        parentFragmentManager.beginTransaction()
            .replace(
                (requireView().parent as View).id,
                GroupRenameFragment.newInstance(playlistId)
            )
            .addToBackStack("groups")
            .commit()
    }

    private fun openSanitize() {
        parentFragmentManager.beginTransaction()
            .replace(
                (requireView().parent as View).id,
                SanitizeFragment.newInstance(playlistId)
            )
            .addToBackStack("sanitize")
            .commit()
    }

    private fun openParental() {
        parentFragmentManager.beginTransaction()
            .replace((requireView().parent as View).id, ParentalFragment.newInstance(playlistId))
            .addToBackStack("parental")
            .commit()
    }

    private fun confirmReset() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.epg_reset_title)
            .setMessage(R.string.epg_reset_message)
            .setPositiveButton(R.string.epg_reset_confirm) { _, _ ->
                val app = requireActivity().application as OlympusIptvApp
                viewLifecycleOwner.lifecycleScope.launch {
                    val store = app.playlistStore
                    val blank = LineupConfig(revision = System.currentTimeMillis())
                    config = blank
                    app.stagedLineup = playlistId to blank
                    withContext(Dispatchers.IO) {
                        store.saveLineup(playlistId, blank)
                        val email = store.session.first()?.email
                        if (!email.isNullOrBlank()) {
                            runCatching { LineupClient(app).push(email, store.deviceId(), blank) }
                        }
                    }
                    showAutoClean(true)
                    Toast.makeText(requireContext(), R.string.epg_reset_done, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmParse() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.epg_parse_title)
            .setMessage(R.string.epg_parse_message)
            .setPositiveButton(R.string.epg_parse_confirm) { _, _ ->
                val app = requireActivity().application as OlympusIptvApp
                viewLifecycleOwner.lifecycleScope.launch {
                    val parsed = withContext(Dispatchers.IO) {
                        app.catalogDb.parseVisibleNames(playlistId, config)
                    }
                    app.epgRepository.installNameEpg(playlistId, parsed.programs)
                    val next = config.copy(nameEpg = true, revision = System.currentTimeMillis())
                    config = next
                    app.stagedLineup = playlistId to next
                    withContext(Dispatchers.IO) {
                        app.playlistStore.saveLineup(playlistId, next)
                        val email = app.playlistStore.session.first()?.email
                        if (!email.isNullOrBlank()) {
                            runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
                        }
                    }
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.epg_parse_done, parsed.programs.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmStripImages() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.epg_images_title)
            .setMessage(R.string.epg_images_message)
            .setPositiveButton(R.string.epg_images_confirm) { _, _ ->
                val app = requireActivity().application as OlympusIptvApp
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        app.catalogDb.stripImageNames(playlistId)
                    }
                    val next = config.copy(
                        channelNames = config.channelNames.orEmpty().mapValues { NameImages.strip(it.value) },
                        groupNames = config.groupNames.orEmpty().mapValues { NameImages.strip(it.value) },
                        revision = System.currentTimeMillis()
                    )
                    config = next
                    app.stagedLineup = playlistId to next
                    withContext(Dispatchers.IO) {
                        app.playlistStore.saveLineup(playlistId, next)
                        val email = app.playlistStore.session.first()?.email
                        if (!email.isNullOrBlank()) {
                            runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
                        }
                    }
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.epg_images_done, result.first),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun closeFromBrowseBack() = closeEdits()

    private fun closeEdits() {
        if (!viewActive()) return
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle())
        val host = activity as? ToolHost
        if (host != null) {
            exitToolHost()
            return
        }
        if (parentFragment != null) {
            parentFragmentManager.popBackStack()
        } else {
            requireActivity().finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val RESULT_KEY = "epg_edits"
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_INCLUDE_VOD = "include_vod"

        fun newInstance(
            playlistId: String,
            kind: ContentKind = ContentKind.LIVE,
            includeVod: Boolean = false
        ): EpgEditsFragment {
            val fragment = EpgEditsFragment()
            fragment.arguments = Bundle().apply {
                putString(EXTRA_PLAYLIST_ID, playlistId)
                putString(EXTRA_KIND, kind.name)
                putBoolean(EXTRA_INCLUDE_VOD, includeVod)
            }
            return fragment
        }
    }
}
