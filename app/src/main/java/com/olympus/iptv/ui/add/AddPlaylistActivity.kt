package com.olympus.iptv.ui.add

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.PlaylistStore
import com.olympus.iptv.data.PlaylistType
import com.olympus.iptv.data.SavedPlaylist
import com.olympus.iptv.databinding.ActivityAddPlaylistBinding
import com.olympus.iptv.playlist.PlaylistLoader
import com.olympus.iptv.playlist.XtreamClient
import kotlinx.coroutines.launch

class AddPlaylistActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAddPlaylistBinding
    private var mode = PlaylistType.M3U
    private val loader = PlaylistLoader()

    private val store get() = (application as OlympusIptvApp).playlistStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tabM3u.setOnClickListener { setMode(PlaylistType.M3U) }
        binding.tabXtream.setOnClickListener { setMode(PlaylistType.XTREAM) }
        binding.btnCancel.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }
        setMode(PlaylistType.M3U)
        binding.inputName.requestFocus()
    }

    private fun setMode(type: PlaylistType) {
        mode = type
        val m3uSelected = type == PlaylistType.M3U
        binding.m3uFields.visibility = if (m3uSelected) View.VISIBLE else View.GONE
        binding.xtreamFields.visibility = if (m3uSelected) View.GONE else View.VISIBLE
        binding.tabM3u.background = ContextCompat.getDrawable(
            this,
            if (m3uSelected) R.drawable.bg_button else R.drawable.bg_button_secondary
        )
        binding.tabXtream.background = ContextCompat.getDrawable(
            this,
            if (m3uSelected) R.drawable.bg_button_secondary else R.drawable.bg_button
        )
        binding.tabM3u.setTextColor(
            ContextCompat.getColor(this, if (m3uSelected) R.color.bg else R.color.text_primary)
        )
        binding.tabXtream.setTextColor(
            ContextCompat.getColor(this, if (m3uSelected) R.color.text_primary else R.color.bg)
        )
    }

    private fun save() {
        binding.errorText.visibility = View.GONE
        val name = binding.inputName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            showError("Enter a display name")
            return
        }

        val playlist = when (mode) {
            PlaylistType.M3U -> {
                val url = binding.inputM3uUrl.text?.toString()?.trim().orEmpty()
                if (!url.startsWith("http")) {
                    showError("Enter a valid M3U URL")
                    return
                }
                SavedPlaylist(
                    id = PlaylistStore.newId(),
                    name = name,
                    type = PlaylistType.M3U,
                    m3uUrl = url
                )
            }
            PlaylistType.XTREAM -> {
                val host = binding.inputHost.text?.toString()?.trim().orEmpty()
                val user = binding.inputUser.text?.toString()?.trim().orEmpty()
                val pass = binding.inputPass.text?.toString()?.trim().orEmpty()
                if (host.isBlank() || user.isBlank() || pass.isBlank()) {
                    showError("Host, username, and password are required")
                    return
                }
                SavedPlaylist(
                    id = PlaylistStore.newId(),
                    name = name,
                    type = PlaylistType.XTREAM,
                    xtreamHost = XtreamClient.normalizeHost(host),
                    xtreamUsername = user,
                    xtreamPassword = pass
                )
            }
        }

        binding.btnSave.isEnabled = false
        binding.btnSave.text = getString(R.string.loading)
        lifecycleScope.launch {
            try {
                val ok = loader.validate(playlist)
                if (!ok) {
                    showError(getString(R.string.error_load))
                    return@launch
                }
                store.add(playlist)
                Toast.makeText(this@AddPlaylistActivity, "Saved $name", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_load))
            } finally {
                binding.btnSave.isEnabled = true
                binding.btnSave.text = getString(R.string.save)
            }
        }
    }

    private fun showError(message: String) {
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = message
    }
}
