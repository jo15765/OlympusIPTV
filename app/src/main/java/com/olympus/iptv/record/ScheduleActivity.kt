package com.olympus.iptv.record

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Channel
import com.olympus.iptv.data.EpgProgram
import com.olympus.iptv.databinding.ActivityRecordScheduleBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.ui.SoftKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScheduleActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecordScheduleBinding
    private lateinit var playlistId: String
    private var searchJob: Job? = null
    private var channel: Channel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordScheduleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        binding.results.layoutManager = LinearLayoutManager(this)
        binding.btnBack.setOnClickListener {
            if (channel != null) showChannels(binding.inputSearch.text?.toString().orEmpty()) else finish()
        }
        SoftKeyboard.keepOffUntilAsked(this, binding.inputSearch)
        binding.inputSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (channel == null) showChannels(s?.toString().orEmpty())
            }
        })
        showChannels("")
    }

    private fun showChannels(query: String) {
        channel = null
        binding.heading.text = ""
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            binding.results.adapter = RowAdapter(emptyList()) {}
            return
        }
        val app = application as OlympusIptvApp
        searchJob = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                app.catalogDb.searchChannels(playlistId, CatalogDb.LIVE, trimmed, 40)
            }
            binding.results.adapter = RowAdapter(found.map { it.name }) { index ->
                openChannel(found[index])
            }
        }
    }

    private fun openChannel(selected: Channel) {
        channel = selected
        binding.heading.text = selected.name
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val playlist = withContext(Dispatchers.IO) {
                app.playlistStore.playlists.first().firstOrNull { it.id == playlistId }
            }
            val programs = withContext(Dispatchers.IO) {
                app.epgRepository.upcoming(playlist, selected)
            }
            if (programs.isEmpty()) {
                binding.heading.text = getString(R.string.record_programs_empty)
                binding.results.adapter = RowAdapter(emptyList()) {}
                return@launch
            }
            val now = System.currentTimeMillis()
            val labels = programs.map { program ->
                val whenLabel = if (program.contains(now)) getString(R.string.record_on_now) else getString(R.string.record_upcoming)
                "$whenLabel  ${program.timeRangeLabel()}  ${program.title}"
            }
            binding.results.adapter = RowAdapter(labels) { index ->
                RecordPrompt.askDestination(this@ScheduleActivity, playlistId, selected, programs[index])
            }
        }
    }

    companion object {
        private const val EXTRA_PLAYLIST = "playlist_id"
        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, ScheduleActivity::class.java).putExtra(EXTRA_PLAYLIST, playlistId)
    }
}

private class RowAdapter(
    private val labels: List<String>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<RowAdapter.Holder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_record_row, parent, false) as TextView
        return Holder(view)
    }

    override fun getItemCount(): Int = labels.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.label.text = labels[position]
        holder.label.setOnClickListener { onClick(position) }
    }

    class Holder(val label: TextView) : RecyclerView.ViewHolder(label)
}
