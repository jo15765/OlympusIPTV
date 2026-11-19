package com.olympus.iptv.record

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.olympus.iptv.databinding.ActivityRecordBinding

class RecordActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityRecordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val playlistId = intent.getStringExtra(EXTRA_PLAYLIST).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.cardView.setOnClickListener {
            startActivity(RecordingsActivity.intent(this, playlistId))
        }
        binding.cardSchedule.setOnClickListener {
            startActivity(ScheduleActivity.intent(this, playlistId))
        }
        binding.cardShare.setOnClickListener {
            startActivity(SmbActivity.intent(this, playlistId))
        }
        binding.cardLocal.setOnClickListener {
            startActivity(RecordingsActivity.intent(this, playlistId))
        }
        binding.cardView.requestFocus()
        RecordingService.start(this)
    }

    companion object {
        private const val EXTRA_PLAYLIST = "playlist_id"
        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, RecordActivity::class.java).putExtra(EXTRA_PLAYLIST, playlistId)
    }
}
