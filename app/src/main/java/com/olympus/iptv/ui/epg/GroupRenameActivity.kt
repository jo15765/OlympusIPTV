package com.olympus.iptv.ui.epg

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.GroupRename
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.databinding.ActivityGroupRenameBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.ui.lineup.RenameDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupRenameActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGroupRenameBinding
    private lateinit var playlistId: String
    private var config = LineupConfig()
    private var findText = ""
    private var replaceText = ""
    private var working = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupRenameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnEditFind.setOnClickListener {
            RenameDialog.show(this, findText) { entered ->
                findText = entered.trim()
                paint()
            }
        }
        binding.btnEditReplace.setOnClickListener {
            RenameDialog.show(this, replaceText) { entered ->
                replaceText = entered
                paint()
            }
        }
        binding.btnPreview.setOnClickListener { run(commit = false) }
        binding.btnApply.setOnClickListener { run(commit = true) }
        binding.btnEditFind.requestFocus()
        lifecycleScope.launch {
            val app = application as OlympusIptvApp
            val stored = withContext(Dispatchers.IO) { app.playlistStore.lineup(playlistId) }
            val staged = app.stagedLineup?.takeIf { it.first == playlistId }?.second
            config = if (staged != null && (staged.revision ?: 0L) >= (stored.revision ?: 0L)) staged else stored
            findText = config.groupFind.orEmpty()
            replaceText = config.groupReplace.orEmpty()
            paint()
        }
    }

    private fun paint() {
        binding.findValue.text = findText.ifBlank { getString(R.string.group_rename_none) }
        binding.replaceValue.text = replaceText.ifBlank { getString(R.string.group_rename_none) }
    }

    private fun run(commit: Boolean) {
        if (working) return
        if (findText.isBlank()) {
            binding.renameStatus.setText(R.string.group_rename_need_find)
            return
        }
        working = true
        binding.btnPreview.isEnabled = false
        binding.btnApply.isEnabled = false
        val app = application as OlympusIptvApp
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val groups = listOf(CatalogDb.LIVE, CatalogDb.VOD, CatalogDb.SERIES)
                    .flatMap { bucket -> app.catalogDb.readCategoriesOnly(playlistId, bucket) }
                    .map { it.id to it.name }
                GroupRename.apply(groups, config.groupNames.orEmpty(), findText, replaceText)
            }
            val stored = if (commit) {
                withContext(Dispatchers.IO) { app.catalogDb.replaceGroupLabels(playlistId, findText, replaceText) }
            } else {
                0 to emptyList<GroupRename.Sample>()
            }
            if (commit && (result.changed > 0 || stored.first > 0)) {
                val next = config.copy(
                    groupNames = result.names,
                    groupFind = findText.trim(),
                    groupReplace = replaceText,
                    revision = System.currentTimeMillis()
                )
                config = next
                app.stagedLineup = playlistId to next
                withContext(Dispatchers.IO) {
                    runCatching { app.playlistStore.saveLineup(playlistId, next) }
                    val email = app.playlistStore.session.first()?.email
                    if (!email.isNullOrBlank()) {
                        runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
                    }
                }
            }
            working = false
            binding.btnPreview.isEnabled = true
            binding.btnApply.isEnabled = true
            if (isFinishing) return@launch
            val shown = if (stored.first > result.changed) {
                result.copy(changed = stored.first, examples = stored.second.ifEmpty { result.examples })
            } else {
                result
            }
            showResult(shown, commit && shown.changed > 0)
        }
    }

    private fun showResult(result: GroupRename.Result, applied: Boolean) {
        val title = when {
            result.changed == 0 -> getString(R.string.group_rename_none_matched)
            applied -> getString(R.string.group_rename_done, result.changed)
            else -> getString(R.string.group_rename_preview_title, result.changed)
        }
        binding.renameStatus.text = title
        if (result.examples.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val body = buildString {
            append(getString(R.string.group_rename_examples))
            result.examples.forEach { sample ->
                append("\n\n")
                append(getString(R.string.group_rename_before, sample.before))
                append("\n")
                append(getString(R.string.group_rename_after, sample.after))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, GroupRenameActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
