package com.olympus.iptv.ui.epg

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.data.NameStrip
import com.olympus.iptv.databinding.ActivityStripNamesBinding
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.ui.exitToolHost
import com.olympus.iptv.ui.lineup.RenameDialog
import com.olympus.iptv.ui.viewActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StripNamesFragment : Fragment() {
    private var _binding: ActivityStripNamesBinding? = null
    private val binding get() = _binding!!
    private val playlistId: String
        get() = arguments?.getString(EXTRA_PLAYLIST_ID).orEmpty()
    private var config = LineupConfig()
    private var prefixText = ""
    private var suffixText = ""
    private var applying = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityStripNamesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (playlistId.isBlank()) {
            exitToolHost()
            return
        }
        binding.btnBack.setOnClickListener { exitToolHost() }
        binding.btnEditPrefix.setOnClickListener {
            RenameDialog.show(requireActivity(), prefixText) { entered ->
                prefixText = entered
                paint()
            }
        }
        binding.btnEditSuffix.setOnClickListener {
            RenameDialog.show(requireActivity(), suffixText) { entered ->
                suffixText = entered
                paint()
            }
        }
        binding.btnApply.setOnClickListener { apply() }
        binding.btnEditPrefix.requestFocus()
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as OlympusIptvApp
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

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
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
        val app = requireActivity().application as OlympusIptvApp
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
        viewLifecycleOwner.lifecycleScope.launch {
            val changed = save.await()
            applying = false
            if (!viewActive()) return@launch
            binding.btnApply.isEnabled = true
            binding.stripStatus.text = if (prefixes.isEmpty() && suffixes.isEmpty()) {
                getString(R.string.strip_cleared)
            } else {
                getString(R.string.strip_done, changed)
            }
        }
    }

    companion object {
        const val EXTRA_PLAYLIST_ID = StripNamesActivity.EXTRA_PLAYLIST_ID

        fun newInstance(playlistId: String): StripNamesFragment =
            StripNamesFragment().apply {
                arguments = Bundle().apply { putString(EXTRA_PLAYLIST_ID, playlistId) }
            }
    }
}