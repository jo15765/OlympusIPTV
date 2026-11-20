package com.olympus.iptv.ui.countries

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.databinding.ActivityCountriesBinding
import com.olympus.iptv.playlist.CountryOption
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.ui.SoftKeyboard
import com.olympus.iptv.ui.exitToolHost
import com.olympus.iptv.ui.viewActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CountriesFragment : Fragment() {
    private var _binding: ActivityCountriesBinding? = null
    private val binding get() = _binding!!
    private val playlistId: String
        get() = arguments?.getString(EXTRA_PLAYLIST_ID).orEmpty()
    private var config = LineupConfig()
    private var options = listOf<CountryOption>()
    private val shown = mutableSetOf<String>()
    private var query = ""
    private var listFilter = ListFilter.BOTH
    private var closing = false

    private var ready = false
    private lateinit var adapter: CountryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityCountriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (playlistId.isBlank()) {
            exitToolHost()
            return
        }
        options = emptyList()
        adapter = CountryAdapter { id -> toggleCountry(id) }
        binding.countryList.layoutManager =
            androidx.recyclerview.widget.GridLayoutManager(requireContext(), 4)
        binding.countryList.adapter = adapter
        binding.countryList.setHasFixedSize(true)
        binding.countryList.itemAnimator = null
        binding.countryList.setItemViewCacheSize(8)
        binding.btnAll.setOnClickListener {
            shown.clear()
            shown.addAll(options.map { it.id })
            render()
        }
        binding.btnNone.setOnClickListener {
            shown.clear()
            render()
        }
        binding.btnVisible.setOnClickListener { setFilter(ListFilter.VISIBLE) }
        binding.btnHidden.setOnClickListener { setFilter(ListFilter.HIDDEN) }
        binding.btnBoth.setOnClickListener { setFilter(ListFilter.BOTH) }
        binding.btnHideKeyboard.setOnClickListener { hideKeyboard() }
        binding.btnDone.isEnabled = false
        binding.btnDone.setOnClickListener { closeAndSave() }
        SoftKeyboard.keepOffUntilAsked(requireActivity() as androidx.appcompat.app.AppCompatActivity, binding.search)
        binding.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        binding.search.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        binding.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty().trim()
                render()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as OlympusIptvApp
            options = withContext(Dispatchers.IO) {
                app.catalogDb.dashboardSnapshot(playlistId).countries
            }
            config = app.playlistStore.lineup(playlistId)
            val hidden = config.hiddenCountries.orEmpty().toSet()
            shown.clear()
            if (config.includeInternational == true) {
                shown.addAll(options.map { it.id }.filter { it !in hidden })
            } else {
                shown.addAll(options.map { it.id }.filter { it == "us" && it !in hidden })
            }
            ready = true
            if (!viewActive()) return@launch
            binding.btnDone.isEnabled = true
            render()
            binding.countryList.post {
                binding.countryList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun hideKeyboard() {
        SoftKeyboard.hide(requireActivity() as androidx.appcompat.app.AppCompatActivity)
        binding.countryList.post {
            binding.countryList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                ?: binding.btnHideKeyboard.requestFocus()
        }
    }

    private fun toggleCountry(id: String) {
        val turningOn = id !in shown
        if (turningOn) shown.add(id) else shown.remove(id)
        if (listFilter == ListFilter.BOTH) adapter.setShown(id, turningOn) else render()
    }

    private fun setFilter(next: ListFilter) {
        listFilter = next
        paintFilter()
        render()
    }

    private fun paintFilter() {
        val both = listFilter == ListFilter.BOTH
        val visible = listFilter == ListFilter.VISIBLE
        val hidden = listFilter == ListFilter.HIDDEN
        paintChoice(binding.btnBoth, both)
        paintChoice(binding.btnVisible, visible)
        paintChoice(binding.btnHidden, hidden)
    }

    private fun paintChoice(button: android.widget.Button, selected: Boolean) {
        val ctx = requireContext()
        button.background = ContextCompat.getDrawable(
            ctx,
            if (selected) R.drawable.bg_button else R.drawable.bg_button_secondary
        )
        button.setTextColor(ContextCompat.getColor(ctx, if (selected) R.color.bg else R.color.text_primary))
    }

    private fun render() {
        if (!viewActive()) return
        val visible = options.filter { option ->
            val matchesQuery = query.isBlank() || option.label.contains(query, ignoreCase = true)
            val matchesFilter = when (listFilter) {
                ListFilter.BOTH -> true
                ListFilter.VISIBLE -> option.id in shown
                ListFilter.HIDDEN -> option.id !in shown
            }
            matchesQuery && matchesFilter
        }
        adapter.submit(visible, shown)
    }

    private fun closeAndSave() {
        if (closing) return
        closing = true
        if (!ready || playlistId.isBlank()) {
            exitToolHost()
            return
        }
        val hidden = options.map { it.id }.filter { it !in shown }
        val next = config.copy(
            hiddenCountries = hidden,
            includeInternational = shown.any { it != "us" },
            revision = System.currentTimeMillis()
        )
        val app = requireActivity().application as OlympusIptvApp
        app.stagedLineup = playlistId to next
        app.appScope.launch {
            runCatching { app.playlistStore.saveLineup(playlistId, next) }
            val email = app.playlistStore.session.first()?.email
            if (!email.isNullOrBlank()) {
                runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
            }
        }
        parentFragmentManager.setFragmentResult(REQUEST_SAVED, Bundle.EMPTY)
        exitToolHost()
    }

    private enum class ListFilter { BOTH, VISIBLE, HIDDEN }

    companion object {
        const val EXTRA_PLAYLIST_ID = CountriesActivity.EXTRA_PLAYLIST_ID
        const val REQUEST_SAVED = "countries_saved"

        fun newInstance(playlistId: String): CountriesFragment =
            CountriesFragment().apply {
                arguments = Bundle().apply { putString(EXTRA_PLAYLIST_ID, playlistId) }
            }
    }
}