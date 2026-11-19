package com.olympus.iptv.ui.countries

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.databinding.ActivityCountriesBinding
import com.olympus.iptv.playlist.CountryOption
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.ui.SoftKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CountriesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCountriesBinding
    private lateinit var playlistId: String
    private var config = LineupConfig()
    private var options = listOf<CountryOption>()
    private val shown = mutableSetOf<String>()
    private var query = ""
    private var listFilter = ListFilter.BOTH
    private var closing = false

    private var ready = false
    private lateinit var adapter: CountryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCountriesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        options = emptyList()
        adapter = CountryAdapter { id -> toggleCountry(id) }
        binding.countryList.layoutManager = GridLayoutManager(this, 4)
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
        binding.btnDone.setOnClickListener { finish() }
        SoftKeyboard.keepOffUntilAsked(this, binding.search)
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
        lifecycleScope.launch {
            val app = application as OlympusIptvApp
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
            binding.btnDone.isEnabled = true
            render()
            binding.countryList.post {
                binding.countryList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
            }
        }
    }

    private fun hideKeyboard() {
        SoftKeyboard.hide(this)
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
        button.background = ContextCompat.getDrawable(
            this,
            if (selected) R.drawable.bg_button else R.drawable.bg_button_secondary
        )
        button.setTextColor(ContextCompat.getColor(this, if (selected) R.color.bg else R.color.text_primary))
    }

    private fun render() {
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

    override fun finish() {
        if (closing) return
        closing = true
        if (!ready || !::playlistId.isInitialized || playlistId.isBlank()) {
            super.finish()
            return
        }
        val hidden = options.map { it.id }.filter { it !in shown }
        val next = config.copy(
            hiddenCountries = hidden,
            includeInternational = shown.any { it != "us" },
            revision = System.currentTimeMillis()
        )
        val app = application as OlympusIptvApp
        app.stagedLineup = playlistId to next
        app.appScope.launch {
            runCatching { app.playlistStore.saveLineup(playlistId, next) }
            val email = app.playlistStore.session.first()?.email
            if (!email.isNullOrBlank()) {
                runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
            }
        }
        setResult(RESULT_OK)
        super.finish()
    }

    private enum class ListFilter { BOTH, VISIBLE, HIDDEN }

    companion object {
        const val EXTRA_PLAYLIST_ID = "playlist_id"
    }
}
