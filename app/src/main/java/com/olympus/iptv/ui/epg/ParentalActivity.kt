package com.olympus.iptv.ui.epg

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.data.Category
import com.olympus.iptv.data.LineupConfig
import com.olympus.iptv.databinding.ActivityParentalBinding
import com.olympus.iptv.databinding.ItemParentalBinding
import com.olympus.iptv.playlist.CatalogDb
import com.olympus.iptv.playlist.GroupLock
import com.olympus.iptv.playlist.LineupApplier
import com.olympus.iptv.playlist.LineupClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ParentalActivity : AppCompatActivity() {
    private lateinit var binding: ActivityParentalBinding
    private lateinit var playlistId: String
    private var config = LineupConfig()
    private val groups = ArrayList<Category>()
    private val selected = LinkedHashSet<String>()
    private var pinDigits = ""
    private var pinTargets = emptyList<String>()
    @Volatile private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentalBinding.inflate(layoutInflater)
        setContentView(binding.root)
        playlistId = intent.getStringExtra(EXTRA_PLAYLIST_ID).orEmpty()
        if (playlistId.isBlank()) {
            finish()
            return
        }
        binding.groupList.layoutManager = GridLayoutManager(this, 3)
        binding.groupList.itemAnimator = null
        binding.groupList.setHasFixedSize(true)
        binding.groupList.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnSetPin.setOnClickListener { beginPin() }
        binding.btnClearPin.setOnClickListener { clearSelected() }
        binding.pinCancel.setOnClickListener { hidePin() }
        binding.pinClearDigit.setOnClickListener { clearLastDigit() }
        wirePad()
        lifecycleScope.launch {
            val app = application as OlympusIptvApp
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val lineup = app.playlistStore.lineup(playlistId)
                    val live = app.catalogDb.readCategoriesOnly(playlistId, CatalogDb.LIVE)
                    val countries = app.catalogDb.dashboardGroups(playlistId)
                        .asSequence()
                        .filter { it.kind == "LIVE" && it.countryId.isNotEmpty() }
                        .associate { it.id to it.countryId }
                    Triple(lineup, live, countries)
                }.getOrElse {
                    if (it is OutOfMemoryError) {
                        runCatching { app.catalogDb.releaseSearchExport() }
                        runCatching { System.gc() }
                    }
                    Triple(LineupConfig(), emptyList(), emptyMap())
                }
            }
            if (isDestroyed) return@launch
            config = loaded.first
            groups.clear()
            groups.addAll(LineupApplier.groups(loaded.second, config, loaded.third))
            adapter.notifyDataSetChanged()
            updateHint()
            binding.groupList.post {
                if (isDestroyed) return@post
                binding.groupList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    ?: binding.btnBack.requestFocus()
            }
        }
    }

    private fun wirePad() {
        val keys = listOf(
            binding.pin0 to '0',
            binding.pin1 to '1',
            binding.pin2 to '2',
            binding.pin3 to '3',
            binding.pin4 to '4',
            binding.pin5 to '5',
            binding.pin6 to '6',
            binding.pin7 to '7',
            binding.pin8 to '8',
            binding.pin9 to '9'
        )
        for ((view, digit) in keys) {
            view.setOnClickListener { appendDigit(digit) }
        }
    }

    private fun beginPin() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.parental_select_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (saving) return
        pinTargets = selected.toList()
        pinDigits = ""
        binding.groupPinTitle.setText(
            if (pinTargets.size == 1) R.string.group_pin_set else R.string.group_pin_set_many
        )
        paintDigits()
        binding.groupPin.visibility = View.VISIBLE
        binding.pin5.requestFocus()
    }

    private fun clearSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.parental_select_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (saving) return
        val ids = selected.toList()
        selected.clear()
        applyPins(ids, null)
        adapter.notifyDataSetChanged()
        updateHint()
    }

    private fun hidePin() {
        pinTargets = emptyList()
        pinDigits = ""
        binding.groupPin.visibility = View.GONE
        adapter.notifyDataSetChanged()
        updateHint()
        binding.groupList.post {
            if (isDestroyed) return@post
            binding.groupList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                ?: binding.btnSetPin.requestFocus()
        }
    }

    private fun paintDigits() {
        binding.groupPinDigits.text = "•".repeat(pinDigits.length).padEnd(4, '–')
    }

    private fun appendDigit(digit: Char) {
        if (!digit.isDigit() || pinDigits.length >= 4 || saving) return
        pinDigits += digit
        paintDigits()
        if (pinDigits.length == 4) {
            val targets = pinTargets
            val code = pinDigits
            selected.clear()
            applyPins(targets, code)
            hidePin()
        }
    }

    private fun clearLastDigit() {
        if (pinDigits.isEmpty() || saving) return
        pinDigits = pinDigits.dropLast(1)
        paintDigits()
    }

    private fun applyPins(ids: Collection<String>, digits: String?) {
        if (ids.isEmpty() || playlistId.isBlank()) return
        if (digits != null && (digits.length != 4 || digits.any { !it.isDigit() })) return
        val pins = config.groupPins.orEmpty().toMutableMap()
        val hashed = digits?.let { GroupLock.code(it) }
        for (id in ids) {
            if (id.isBlank()) continue
            if (hashed == null) pins.remove(id) else pins[id] = hashed
            GroupLock.lock(id)
        }
        val next = config.copy(
            groupPins = pins.ifEmpty { null },
            revision = System.currentTimeMillis()
        )
        config = next
        val app = application as OlympusIptvApp
        app.stagedLineup = playlistId to next
        saving = true
        app.appScope.launch {
            try {
                runCatching { app.playlistStore.saveLineup(playlistId, next) }
                val email = runCatching { app.playlistStore.session.first()?.email }.getOrNull()
                if (!email.isNullOrBlank()) {
                    runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
                }
            } finally {
                saving = false
            }
        }
    }

    private fun updateHint() {
        binding.parentalHint.text = if (selected.isEmpty()) {
            getString(R.string.parental_hint)
        } else {
            getString(R.string.parental_selected_count, selected.size)
        }
    }

    private fun toggleSelect(position: Int) {
        if (position !in groups.indices) return
        val category = groups[position]
        if (!selected.add(category.id)) selected.remove(category.id)
        adapter.notifyItemChanged(position)
        updateHint()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (binding.groupPin.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            val digit = when (event.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (event.keyCode - KeyEvent.KEYCODE_0))
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
                    ('0' + (event.keyCode - KeyEvent.KEYCODE_NUMPAD_0))
                else -> null
            }
            if (digit != null) {
                appendDigit(digit)
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
                clearLastDigit()
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
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = groups[position].id.hashCode().toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val item = ItemParentalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(item)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            if (position !in groups.indices) return
            val category = groups[position]
            val locked = !config.groupPins.orEmpty()[category.id].isNullOrBlank()
            val checked = category.id in selected
            holder.binding.name.text = category.name
            holder.binding.detail.text = getString(
                if (locked) R.string.parental_locked else R.string.parental_unlocked
            )
            holder.binding.lock.visibility = if (locked) View.VISIBLE else View.GONE
            holder.binding.selectedMark.visibility = if (checked) View.VISIBLE else View.GONE
            holder.binding.root.isSelected = checked
            holder.binding.root.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) toggleSelect(index)
            }
        }

        override fun getItemCount(): Int = groups.size
    }

    private class Holder(val binding: ItemParentalBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun intent(context: Context, playlistId: String): Intent =
            Intent(context, ParentalActivity::class.java).putExtra(EXTRA_PLAYLIST_ID, playlistId)
    }
}
