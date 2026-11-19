package com.olympus.iptv.record

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.SmbSettings
import com.olympus.iptv.databinding.ActivitySmbBinding
import com.olympus.iptv.ui.SoftKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmbActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySmbBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        val app = application as OlympusIptvApp
        SoftKeyboard.keepOffUntilAsked(this, binding.host)
        binding.btnBack.setOnClickListener { finish() }
        lifecycleScope.launch {
            val smb = withContext(Dispatchers.IO) { app.playlistStore.recordingBook(playlistId).smbOrEmpty() }
            binding.host.setText(smb.host.orEmpty())
            binding.share.setText(smb.share.orEmpty())
            binding.folder.setText(smb.folderName())
            binding.username.setText(smb.username.orEmpty())
        }
        binding.save.setOnClickListener {
            val host = binding.host.text?.toString()?.trim().orEmpty()
            val share = binding.share.text?.toString()?.trim().orEmpty()
            if (host.isBlank() || share.isBlank()) {
                Toast.makeText(this, R.string.record_share_needed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val typedPassword = binding.password.text?.toString().orEmpty()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val book = app.playlistStore.recordingBook(playlistId)
                    val password = typedPassword.ifBlank { book.smbOrEmpty().password.orEmpty() }
                    val next = book.copy(
                        smb = SmbSettings(
                            host = host,
                            share = share,
                            folder = binding.folder.text?.toString()?.trim().orEmpty().ifBlank { "Olympus-IPTV" },
                            username = binding.username.text?.toString()?.trim().orEmpty(),
                            password = password
                        ),
                        revision = System.currentTimeMillis()
                    )
                    app.playlistStore.saveRecordingBook(playlistId, next)
                    RecordPrompt.push(app, playlistId, next)
                }
                Toast.makeText(this@SmbActivity, R.string.record_share_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_PLAYLIST = "playlist_id"
        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, SmbActivity::class.java).putExtra(EXTRA_PLAYLIST, playlistId)
    }
}
