package com.olympus.iptv.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.SavedPlaylist
import com.olympus.iptv.databinding.ActivityHomeBinding
import com.olympus.iptv.playlist.CatalogIngest
import com.olympus.iptv.ui.add.AddPlaylistActivity
import com.olympus.iptv.ui.browse.BrowseActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHomeBinding
    private lateinit var adapter: PlaylistAdapter

    private val store get() = (application as OlympusIptvApp).playlistStore
    private val catalogDb get() = (application as OlympusIptvApp).catalogDb

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = PlaylistAdapter(
            onOpen = { openPlaylist(it) },
            onDelete = { deletePlaylist(it) },
            onMove = { item, direction -> movePlaylist(item, direction) },
            onSync = { syncPlaylist(it) }
        )
        binding.playlistList.layoutManager = LinearLayoutManager(this)
        binding.playlistList.adapter = adapter

        binding.btnAdd.setOnClickListener {
            startActivity(Intent(this, AddPlaylistActivity::class.java))
        }
        binding.btnAdd.requestFocus()
        paintProfiles()
        maybeAutoResumeHome()

        lifecycleScope.launch {
            store.playlists.collectLatest { list ->
                paintCards(list)
            }
        }
    }

    private fun paintCards(list: List<SavedPlaylist>) {
        lifecycleScope.launch {
            val cards = withContext(Dispatchers.IO) {
                list.map { playlist ->
                    val counts = runCatching { catalogDb.bucketCounts(playlist.id) }
                        .getOrDefault(intArrayOf(0, 0, 0))
                    val synced = runCatching { store.lastRefreshAt(playlist.id) }.getOrDefault(0L)
                    PlaylistCard(
                        playlist = playlist,
                        live = counts.getOrElse(0) { 0 },
                        movies = counts.getOrElse(1) { 0 },
                        shows = counts.getOrElse(2) { 0 },
                        lastSyncAt = synced
                    )
                }
            }
            if (isDestroyed) return@launch
            adapter.submit(cards)
            binding.emptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            binding.playlistList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun maybeAutoResumeHome() {
        val app = application as OlympusIptvApp
        if (app.autoResumeUsed) return
        lifecycleScope.launch {
            val enabled = withContext(Dispatchers.IO) { store.autoResume() }
            if (!enabled || app.autoResumeUsed || isDestroyed) return@launch
            val list = store.playlists.first()
            val playlist = list.firstOrNull() ?: return@launch
            val last = withContext(Dispatchers.IO) { store.lastChannelId(playlist.id) } ?: return@launch
            if (last.isBlank() || app.autoResumeUsed || isDestroyed) return@launch
            openPlaylist(playlist)
        }
    }

    private fun openPlaylist(playlist: SavedPlaylist) {
        startActivity(
            Intent(this, BrowseActivity::class.java)
                .putExtra(BrowseActivity.EXTRA_PLAYLIST_ID, playlist.id)
        )
    }

    private fun syncPlaylist(playlist: SavedPlaylist) {
        Toast.makeText(this, R.string.playlist_sync_now, Toast.LENGTH_SHORT).show()
        CatalogIngest.start(application as OlympusIptvApp, playlist)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2_000)
            paintCards(store.playlists.first())
        }
    }

    private fun deletePlaylist(playlist: SavedPlaylist) {
        lifecycleScope.launch {
            store.remove(playlist.id)
            Toast.makeText(this@HomeActivity, "Removed ${playlist.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun movePlaylist(playlist: SavedPlaylist, direction: Int) {
        lifecycleScope.launch {
            if (store.movePlaylist(playlist.id, direction)) {
                Toast.makeText(
                    this@HomeActivity,
                    if (direction < 0) R.string.playlist_moved_up else R.string.playlist_moved_down,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun paintProfiles() {
        val names = listOf("Main", "Kids", "Sports")
        lifecycleScope.launch {
            val active = withContext(Dispatchers.IO) { store.activeProfile() }
            binding.profileChips.removeAllViews()
            names.forEach { name ->
                val chip = TextView(this@HomeActivity)
                chip.text = name
                chip.textSize = 16f
                chip.setPadding(28, 16, 28, 16)
                chip.isFocusable = true
                chip.setTextColor(if (name == active) 0xFF101018.toInt() else 0xFFE8E4DC.toInt())
                chip.setBackgroundColor(if (name == active) 0xFFE0B15A.toInt() else 0xFF2A2A34.toInt())
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.marginEnd = 16
                chip.layoutParams = params
                chip.setOnClickListener { chooseProfile(name) }
                chip.setOnLongClickListener {
                    setProfilePin(name)
                    true
                }
                binding.profileChips.addView(chip)
            }
        }
    }

    private fun setProfilePin(name: String) {
        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_pin_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.saveProfilePin(name, input.text?.toString()?.ifBlank { null }) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseProfile(name: String) {
        lifecycleScope.launch {
            val pin = withContext(Dispatchers.IO) { store.profilePin(name) }
            if (pin.isNullOrBlank()) {
                withContext(Dispatchers.IO) { store.saveActiveProfile(name) }
                paintProfiles()
                return@launch
            }
            val input = android.widget.EditText(this@HomeActivity)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            AlertDialog.Builder(this@HomeActivity)
                .setTitle(R.string.profile_pin_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (input.text?.toString() == pin) {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { store.saveActiveProfile(name) }
                            paintProfiles()
                        }
                    } else {
                        Toast.makeText(this@HomeActivity, R.string.profile_locked, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNeutralButton("Set PIN") { _, _ ->
                    val next = input.text?.toString().orEmpty()
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            store.saveProfilePin(name, next.ifBlank { null })
                            store.saveActiveProfile(name)
                        }
                        paintProfiles()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
}
