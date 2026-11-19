package com.olympus.iptv.ui.epg

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Category
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.databinding.ActivityParentalBinding
import com.olympus.iptv.databinding.ItemParentalBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.GroupLock
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.playlist.PlaylistLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ParentalActivity : AppCompatActivity() {
    private lateinit var binding: ActivityParentalBinding
    private lateinit var playlistId: String
    private var config = LineupConfig()
    private val rows = ArrayList<Row>()
    private var pinRow: Row? = null
    private var pinDigits = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        binding.groupList.layoutManager = LinearLayoutManager(this)
        binding.groupList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.groupPinCancel.setOnClickListener { hidePin() }
        binding.groupPinClear.setOnClickListener {
            val row = pinRow ?: return@setOnClickListener
            savePin(row.category.id, null)
            hidePin()
        }
        lifecycleScope.launch {
            val app = application as OlympusIptvApp
            val loaded = withContext(Dispatchers.IO) {
                val lineup = app.playlistStore.lineup(playlistId)
                val live = app.catalogDb.readCategoriesOnly(playlistId, CatalogDb.LIVE)
                val movies = app.catalogDb.readCategoriesOnly(playlistId, CatalogDb.VOD)
                val shows = app.catalogDb.readCategoriesOnly(playlistId, CatalogDb.SERIES)
                Quad(lineup, live, movies, shows)
            }
            config = loaded.lineup
            rows.clear()
            addRows(loaded.live, getString(R.string.parental_live))
            addRows(loaded.movies, getString(R.string.parental_movies))
            addRows(loaded.shows, getString(R.string.parental_shows))
            adapter.notifyDataSetChanged()
            binding.groupList.post {
                binding.groupList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: binding.btnBack.requestFocus()
            }
        }
    }

    private fun addRows(categories: List<Category>, kind: String) {
        for (category in categories) {
            if (category.id == PlaylistLoader.ALL_ID ||
                category.id == PlaylistLoader.FAVORITES_ID ||
                category.id == PlaylistLoader.RECENT_ID ||
                category.id == PlaylistLoader.CONTINUE_ID
            ) continue
            rows.add(Row(category, kind))
        }
    }

    private fun showPin(row: Row) {
        pinRow = row
        pinDigits = ""
        binding.groupPinTitle.text = row.category.name
        binding.groupPinClear.visibility = if (GroupLock.locked(config, row.category.id)) View.VISIBLE else View.GONE
        paintDigits()
        binding.groupPin.visibility = View.VISIBLE
        binding.groupPinCancel.requestFocus()
    }

    private fun hidePin() {
        pinRow = null
        pinDigits = ""
        binding.groupPin.visibility = View.GONE
        adapter.notifyDataSetChanged()
        binding.groupList.post {
            binding.groupList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun paintDigits() {
        binding.groupPinDigits.text = "•".repeat(pinDigits.length).padEnd(4, '–')
    }

    private fun savePin(groupId: String, digits: String?) {
        val pins = config.groupPins.orEmpty().toMutableMap()
        if (digits == null) pins.remove(groupId) else pins[groupId] = GroupLock.code(digits)
        val next = config.copy(
            groupPins = pins.ifEmpty { null },
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
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.groupPin.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            val digit = when (event.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> event.keyCode - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> event.keyCode - KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            if (digit >= 0) {
                if (pinDigits.length < 4) pinDigits += digit.toString()
                paintDigits()
                if (pinDigits.length == 4) {
                    val row = pinRow
                    if (row != null) savePin(row.category.id, pinDigits)
                    hidePin()
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_B
            ) {
                hidePin()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val item = ItemParentalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(item)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            holder.binding.name.text = row.category.name
            holder.binding.kind.text = row.kind
            holder.binding.lock.visibility = if (GroupLock.locked(config, row.category.id)) View.VISIBLE else View.GONE
            holder.binding.root.setOnClickListener { showPin(row) }
        }

        override fun getItemCount(): Int = rows.size
    }

    private class Holder(val binding: ItemParentalBinding) : RecyclerView.ViewHolder(binding.root)

    private data class Row(val category: Category, val kind: String)

    private data class Quad(
        val lineup: LineupConfig,
        val live: List<Category>,
        val movies: List<Category>,
        val shows: List<Category>
    )

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, ParentalActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
