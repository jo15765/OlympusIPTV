package com.olympus.iptv.ui.epg

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.ui.browse.stableRowId
import com.olympus.iptv.ui.exitToolHost
import com.olympus.iptv.ui.viewActive
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
import com.olympus.iptv.ui.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ParentalFragment : Fragment() {
    private var _binding: ActivityParentalBinding? = null
    private val binding get() = _binding!!
    private val playlistId: String
        get() = arguments?.getString(EXTRA_PLAYLIST_ID).orEmpty()
    private var config = LineupConfig()
    private val groups = ArrayList<Category>()
    private val selected = LinkedHashSet<String>()
    private var pinDigits = ""
    private var pinTargets = emptyList<String>()
    private var pinKeyIndex = 4
    private var pinFocusGuard: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private var screenFocusGuard: ViewTreeObserver.OnGlobalFocusChangeListener? = null
    @Volatile private var saving = false

    private val pinKeys: List<View> by lazy {
        listOf(
            binding.pin1, binding.pin2, binding.pin3,
            binding.pin4, binding.pin5, binding.pin6,
            binding.pin7, binding.pin8, binding.pin9,
            binding.pinClearDigit, binding.pin0, binding.pinCancel
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityParentalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (playlistId.isBlank()) {
            exitToolHost()
            return
        }
        val act = requireActivity()
        if (com.olympus.iptv.ui.DeviceForm.isTouchPrimary(requireContext())) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(act.window, false)
            com.olympus.iptv.ui.InsetsPad.apply(
                binding.parentalContent,
                top = true,
                bottom = true,
                left = true,
                right = true
            )
        }
        binding.groupList.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.groupList.itemAnimator = null
        binding.groupList.setHasFixedSize(true)
        binding.groupList.adapter = adapter
        binding.btnBack.setOnClickListener { exitToolHost() }
        binding.btnSetPin.setOnClickListener { beginPin() }
        binding.btnClearPin.setOnClickListener { clearSelected() }
        binding.pinCancel.setOnClickListener { hidePin() }
        binding.pinClearDigit.setOnClickListener { clearLastDigit() }
        binding.pinOk.setOnClickListener { submitPinModal() }
        binding.pinScrim.setOnClickListener { reclaimPinFocus() }
        binding.groupPin.setOnClickListener { reclaimPinFocus() }
        val paintActionFocus = { view: android.widget.TextView, hasFocus: Boolean ->
            view.setBackgroundResource(if (hasFocus) R.drawable.bg_button else R.drawable.bg_button_secondary)
            view.setTextColor(requireContext().getColor(if (hasFocus) R.color.bg else R.color.text_primary))
        }
        binding.btnSetPin.setOnFocusChangeListener { _, hasFocus -> paintActionFocus(binding.btnSetPin, hasFocus) }
        binding.btnClearPin.setOnFocusChangeListener { _, hasFocus -> paintActionFocus(binding.btnClearPin, hasFocus) }
        wirePad()
        attachScreenFocusGuard()
        binding.btnSetPin.post {
            if (viewActive() && !pinOpen()) binding.btnSetPin.requestFocus()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as OlympusIptvApp
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val lineup = app.playlistStore.lineup(playlistId)
                    val live = app.catalogDb.readCategoriesOnly(playlistId, CatalogDb.LIVE)
                    val countries = app.catalogDb.liveCountryByCategory(playlistId)
                    Triple(lineup, live, countries)
                }.getOrElse {
                    if (it is OutOfMemoryError) {
                        runCatching { app.catalogDb.releaseSearchExport() }
                        runCatching { System.gc() }
                    }
                    Triple(LineupConfig(), emptyList(), emptyMap())
                }
            }
            if (!viewActive()) return@launch
            config = loaded.first
            groups.clear()
            groups.addAll(LineupApplier.groups(loaded.second, config, loaded.third))
            adapter.notifyDataSetChanged()
            updateHint()
            if (!pinOpen()) {
                binding.btnSetPin.requestFocus()
            }
        }
    }

    override fun onDestroyView() {
        detachPinFocusGuard()
        detachScreenFocusGuard()
        _binding = null
        super.onDestroyView()
    }

    private val currentFocus: View?
        get() = activity?.currentFocus

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
        for ((index, key) in pinKeys.withIndex()) {
            val col = index % 3
            val row = index / 3
            key.nextFocusLeftId = pinKeys[row * 3 + (col - 1).coerceAtLeast(0)].id
            key.nextFocusRightId = pinKeys[row * 3 + (col + 1).coerceAtMost(2)].id
            key.nextFocusUpId = pinKeys[(row - 1).coerceAtLeast(0) * 3 + col].id
            key.nextFocusDownId = if (row == 3) binding.pinOk.id else pinKeys[(row + 1) * 3 + col].id
            key.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) pinKeyIndex = index
            }
        }
        binding.pinOk.nextFocusUpId = binding.pin0.id
        binding.pinOk.nextFocusDownId = binding.pinOk.id
        binding.pinOk.nextFocusLeftId = binding.pinClearDigit.id
        binding.pinOk.nextFocusRightId = binding.pinCancel.id
    }

    private fun pinOpen(): Boolean = binding.groupPin.visibility == View.VISIBLE

    private fun beginPin() {
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), R.string.parental_select_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (saving) return
        clearingLocks = false
        pinTargets = selected.toList()
        pinDigits = ""
        pinKeyIndex = 4
        binding.groupPinTitle.setText(
            if (pinTargets.size == 1) R.string.group_pin_set else R.string.group_pin_set_many
        )
        paintDigits()
        setBackgroundFocusEnabled(false)
        binding.pinScrim.visibility = View.VISIBLE
        binding.groupPin.visibility = View.VISIBLE
        attachPinFocusGuard()
        binding.groupPin.post { reclaimPinFocus() }
    }

    private var clearingLocks = false

    private fun clearSelected() {
        if (selected.isEmpty()) {
            Toast.makeText(requireContext(), R.string.parental_select_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (saving) return
        val locked = selected.filter { !config.groupPins.orEmpty()[it].isNullOrBlank() }
        if (locked.isEmpty()) {
            Toast.makeText(requireContext(), R.string.parental_clear_none_locked, Toast.LENGTH_SHORT).show()
            return
        }
        clearingLocks = true
        pinTargets = locked
        pinDigits = ""
        pinKeyIndex = 4
        binding.groupPinTitle.setText(R.string.parental_clear_need_pin)
        paintDigits()
        setBackgroundFocusEnabled(false)
        binding.pinScrim.visibility = View.VISIBLE
        binding.groupPin.visibility = View.VISIBLE
        attachPinFocusGuard()
        binding.groupPin.post { reclaimPinFocus() }
    }

    private fun hidePin() {
        detachPinFocusGuard()
        pinTargets = emptyList()
        pinDigits = ""
        clearingLocks = false
        binding.groupPin.visibility = View.GONE
        binding.pinScrim.visibility = View.GONE
        setBackgroundFocusEnabled(true)
        adapter.notifyDataSetChanged()
        updateHint()
        binding.btnSetPin.post {
            if (!viewActive() || pinOpen()) return@post
            binding.btnSetPin.requestFocus()
        }
    }

    private fun setBackgroundFocusEnabled(enabled: Boolean) {
        binding.parentalContent.descendantFocusability =
            if (enabled) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.btnBack.isFocusable = enabled
        binding.btnSetPin.isFocusable = enabled
        binding.btnClearPin.isFocusable = enabled
        binding.groupList.isFocusable = enabled
        if (!enabled) {
            binding.parentalContent.clearFocus()
            binding.groupList.clearFocus()
        }
    }

    private fun focusInView(container: View): Boolean {
        var view = currentFocus ?: return false
        while (true) {
            if (view === container) return true
            view = view.parent as? View ?: return false
        }
    }

    private fun focusInPin(): Boolean = focusInView(binding.groupPin)

    private fun reclaimPinFocus() {
        if (!viewActive() || !pinOpen()) return
        if (currentFocus === binding.pinOk) return
        val index = pinKeyIndex.coerceIn(0, pinKeys.lastIndex)
        val target = pinKeys[index]
        if (!target.requestFocus()) {
            binding.pin5.requestFocus()
        }
    }

    private fun movePinFocus(dx: Int, dy: Int): Boolean {
        if (!pinOpen()) return false
        if (!focusInPin()) {
            reclaimPinFocus()
            return true
        }
        if (currentFocus === binding.pinOk) {
            when {
                dy < 0 -> pinKeys[pinKeyIndex.coerceIn(9, 11)].requestFocus()
                dx < 0 -> binding.pinClearDigit.requestFocus()
                dx > 0 -> binding.pinCancel.requestFocus()
            }
            return true
        }
        val index = pinKeys.indexOfFirst { it === currentFocus }.let {
            if (it >= 0) it else pinKeyIndex
        }.coerceIn(0, pinKeys.lastIndex)
        val col = index % 3
        val row = index / 3
        if (row == 3 && dy > 0) {
            binding.pinOk.requestFocus()
            return true
        }
        val nextCol = (col + dx).coerceIn(0, 2)
        val nextRow = (row + dy).coerceIn(0, 3)
        val next = nextRow * 3 + nextCol
        pinKeyIndex = next
        pinKeys[next].requestFocus()
        return true
    }

    private fun attachPinFocusGuard() {
        if (pinFocusGuard != null) return
        val guard = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (!pinOpen() || !viewActive()) return@OnGlobalFocusChangeListener
            val inside = newFocus != null && run {
                var view: View? = newFocus
                while (view != null) {
                    if (view === binding.groupPin) return@run true
                    view = view.parent as? View
                }
                false
            }
            if (!inside) {
                binding.groupPin.post {
                    if (pinOpen() && !focusInPin()) reclaimPinFocus()
                }
            }
        }
        pinFocusGuard = guard
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(guard)
    }

    private fun detachPinFocusGuard() {
        val guard = pinFocusGuard ?: return
        pinFocusGuard = null
        val observer = binding.root.viewTreeObserver
        if (observer.isAlive) observer.removeOnGlobalFocusChangeListener(guard)
    }

    private fun attachScreenFocusGuard() {
        if (screenFocusGuard != null) return
        val guard = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (!viewActive()) return@OnGlobalFocusChangeListener
            if (pinOpen()) return@OnGlobalFocusChangeListener
            val inside = newFocus != null && run {
                var view: View? = newFocus
                while (view != null) {
                    if (view === binding.root) return@run true
                    view = view.parent as? View
                }
                false
            }
            if (!inside) {
                binding.btnSetPin.post {
                    if (viewActive() && !pinOpen() && !focusInView(binding.root)) {
                        binding.btnSetPin.requestFocus()
                    }
                }
            }
        }
        screenFocusGuard = guard
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener(guard)
    }

    private fun detachScreenFocusGuard() {
        val guard = screenFocusGuard ?: return
        screenFocusGuard = null
        val observer = binding.root.viewTreeObserver
        if (observer.isAlive) observer.removeOnGlobalFocusChangeListener(guard)
    }

    private fun paintDigits() {
        binding.groupPinDigits.text = "•".repeat(pinDigits.length).padEnd(4, '–')
    }

    private fun appendDigit(digit: Char) {
        if (!digit.isDigit() || pinDigits.length >= 4 || saving) return
        pinDigits += digit
        paintDigits()
        if (pinDigits.length == 4) {
            binding.pinOk.requestFocus()
            submitPinModal()
        }
    }

    private fun submitPinModal() {
        if (saving) return
        if (pinDigits.length < 4) {
            Haptics.reject(binding.groupPin)
            Toast.makeText(requireContext(), R.string.parental_clear_bad_pin, Toast.LENGTH_SHORT).show()
            reclaimPinFocus()
            return
        }
        val targets = pinTargets
        val code = pinDigits
        if (clearingLocks) {
            val match = targets.all { GroupLock.accepts(config, it, code) }
            if (!match) {
                Haptics.reject(binding.groupPin)
                pinDigits = ""
                paintDigits()
                reclaimPinFocus()
                Toast.makeText(requireContext(), R.string.parental_clear_bad_pin, Toast.LENGTH_SHORT).show()
                return
            }
            selected.clear()
            clearingLocks = false
            Haptics.tick(binding.groupPin)
            applyPins(targets, null)
            hidePin()
            adapter.notifyDataSetChanged()
            updateHint()
            Toast.makeText(requireContext(), R.string.pin_cleared_ok, Toast.LENGTH_SHORT).show()
            return
        }
        selected.clear()
        Haptics.tick(binding.groupPin)
        applyPins(targets, code)
        hidePin()
        Toast.makeText(requireContext(), R.string.pin_set_ok, Toast.LENGTH_SHORT).show()
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
        val app = requireActivity().application as OlympusIptvApp
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

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!pinOpen()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val focused = currentFocus
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (focused === binding.btnSetPin || focused === binding.btnClearPin || focused === binding.btnBack) {
                            return true
                        }
                        if (focused != null && focusInView(binding.groupList)) {
                            val holder = binding.groupList.findContainingViewHolder(focused)
                            val pos = holder?.bindingAdapterPosition ?: -1
                            if (pos in 0..2) {
                                binding.btnSetPin.requestFocus()
                                return true
                            }
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (focused === binding.btnSetPin) return true
                        if (focused === binding.btnClearPin) {
                            binding.btnSetPin.requestFocus()
                            return true
                        }
                        if (focused === binding.btnBack) {
                            binding.btnClearPin.requestFocus()
                            return true
                        }
                        if (focused != null && focusInView(binding.groupList)) {
                            val holder = binding.groupList.findContainingViewHolder(focused)
                            val pos = holder?.bindingAdapterPosition ?: -1
                            if (pos >= 0 && pos % 3 == 0) return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (focused === binding.btnSetPin) {
                            binding.btnClearPin.requestFocus()
                            return true
                        }
                        if (focused === binding.btnClearPin) {
                            binding.btnBack.requestFocus()
                            return true
                        }
                        if (focused === binding.btnBack) return true
                        if (focused != null && focusInView(binding.groupList)) {
                            val holder = binding.groupList.findContainingViewHolder(focused)
                            val pos = holder?.bindingAdapterPosition ?: -1
                            if (pos >= 0 && (pos % 3 == 2 || pos == groups.lastIndex)) return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (focused === binding.btnSetPin || focused === binding.btnClearPin) {
                            if (groups.isNotEmpty()) {
                                binding.groupList.scrollToPosition(0)
                                binding.groupList.post {
                                    binding.groupList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                                }
                            }
                            return true
                        }
                        if (focused === binding.btnBack) {
                            binding.btnSetPin.requestFocus()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                        exitToolHost()
                        return true
                    }
                }
            }
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (!focusInPin()) reclaimPinFocus()
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
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                    clearLastDigit()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                    hidePin()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> return movePinFocus(0, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> return movePinFocus(0, 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> return movePinFocus(-1, 0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> return movePinFocus(1, 0)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    val target = if (currentFocus === binding.pinOk) binding.pinOk
                    else (currentFocus?.takeIf { focusInPin() } ?: pinKeys[pinKeyIndex.coerceIn(0, pinKeys.lastIndex)])
                    target.performClick()
                    return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL ->
                    return true
            }
            if (event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
                event.keyCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9
            ) {
                return true
            }
        }
        if (!focusInPin()) {
            reclaimPinFocus()
            return true
        }
        return true
    }

    private val adapter = object : RecyclerView.Adapter<Holder>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = stableRowId(groups.getOrNull(position)?.id)

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
        const val EXTRA_PLAYLIST_ID = "playlist_id"

        fun newInstance(playlistId: String): ParentalFragment =
            ParentalFragment().apply {
                arguments = Bundle().apply { putString(EXTRA_PLAYLIST_ID, playlistId) }
            }
    }
}
