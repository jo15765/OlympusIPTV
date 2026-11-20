package com.olympus.iptv.ui.browse

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.R
import com.olympus.iptv.ui.ChannelNumberJump
import com.olympus.iptv.data.Category
import com.olympus.iptv.playlist.GroupLock
import com.olympus.iptv.playlist.LineupClient
import com.olympus.iptv.playlist.PinAlert
import com.olympus.iptv.playlist.PlaylistLoader
import com.olympus.iptv.ui.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun BrowseActivity.wireGuidePinPad() {
        if (guidePinWired) return
        guidePinWired = true
        val digits = listOf(
            binding.guidePin0 to '0',
            binding.guidePin1 to '1',
            binding.guidePin2 to '2',
            binding.guidePin3 to '3',
            binding.guidePin4 to '4',
            binding.guidePin5 to '5',
            binding.guidePin6 to '6',
            binding.guidePin7 to '7',
            binding.guidePin8 to '8',
            binding.guidePin9 to '9'
        )
        for ((view, digit) in digits) {
            view.setOnClickListener { appendGuidePinDigit(digit) }
        }
        binding.guidePinClearDigit.setOnClickListener { clearGuidePinDigit() }
        binding.groupPinCancel.setOnClickListener { cancelGroupPinToGroups() }
        binding.groupPinWrongOk.setOnClickListener { dismissWrongPinToGroups() }
        binding.groupPinScrim.setOnClickListener { /* absorb */ }
        for ((index, key) in guidePinKeys.withIndex()) {
            val col = index % 3
            val row = index / 3
            key.nextFocusLeftId = guidePinKeys[row * 3 + (col - 1).coerceAtLeast(0)].id
            key.nextFocusRightId = guidePinKeys[row * 3 + (col + 1).coerceAtMost(2)].id
            key.nextFocusUpId = guidePinKeys[(row - 1).coerceAtLeast(0) * 3 + col].id
            key.nextFocusDownId = guidePinKeys[(row + 1).coerceAtMost(3) * 3 + col].id
            key.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) pinKeyIndex = index
            }
        }
        binding.groupPinCancel.nextFocusDownId = binding.groupPinClear.id
        binding.guidePin0.nextFocusDownId = binding.groupPinClear.id
        binding.guidePinClearDigit.nextFocusDownId = binding.groupPinClear.id
        binding.groupPinClear.nextFocusUpId = binding.groupPinCancel.id
    }

internal fun BrowseActivity.showGroupPin(category: Category, open: Boolean) {
        wireGuidePinPad()
        pinCategory = category
        pinOpens = open
        pinClearing = false
        pinDigits = ""
        pinKeyIndex = 4
        binding.groupPinTitle.setText(if (open) R.string.group_pin_title else R.string.group_pin_set)
        binding.groupPinWrong.visibility = View.GONE
        paintGroupPin()
        paintGuidePinClear(category.id)
        binding.groupPinClear.setOnClickListener { beginGuidePinClear() }
        binding.guideShell.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (touchPrimary) {
            val params = binding.groupPin.layoutParams as? FrameLayout.LayoutParams
            if (params != null) {
                val margin = (16 * resources.displayMetrics.density).toInt()
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.marginStart = margin
                params.marginEnd = margin
                binding.groupPin.layoutParams = params
            }
            binding.mobileTopBar.visibility = View.VISIBLE
            binding.mobileBack.visibility = View.VISIBLE
        }
        binding.groupPinScrim.visibility = View.VISIBLE
        binding.groupPin.visibility = View.VISIBLE
        binding.groupPinWrong.visibility = View.GONE
        binding.groupPin.post { reclaimGuidePinFocus() }
    }

internal fun BrowseActivity.paintGuidePinClear(groupId: String = pinCategory?.id.orEmpty()) {
        val hasPin = !activeConfig.groupPins.orEmpty()[groupId].isNullOrBlank()
        binding.groupPinClear.visibility =
            if (hasPin && !pinClearing) View.VISIBLE else View.GONE
    }

internal fun BrowseActivity.beginGuidePinClear() {
        val category = pinCategory ?: return
        if (activeConfig.groupPins.orEmpty()[category.id].isNullOrBlank()) return
        pinClearing = true
        pinOpens = false
        pinDigits = ""
        pinKeyIndex = 4
        binding.groupPinTitle.setText(R.string.parental_clear_need_pin_one)
        paintGroupPin()
        paintGuidePinClear(category.id)
        binding.groupPinWrong.visibility = View.GONE
        reclaimGuidePinFocus()
    }

internal fun BrowseActivity.hideGroupPin() {
        pinCategory = null
        pinDigits = ""
        pinClearing = false
        binding.groupPin.visibility = View.GONE
        binding.groupPinScrim.visibility = View.GONE
        binding.groupPinWrong.visibility = View.GONE
        binding.guideShell.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

internal fun BrowseActivity.cancelGroupPinToGroups() {
        hideGroupPin()
        showMenu(BrowseActivity.GuideMenu.GROUPS)
        armGroupFocus()
        focusGroups()
    }

internal fun BrowseActivity.dismissWrongPinToGroups() {
        binding.groupPinWrong.visibility = View.GONE
        hideGroupPin()
        showMenu(BrowseActivity.GuideMenu.GROUPS)
        armGroupFocus()
        focusGroups()
    }

internal fun BrowseActivity.showWrongPinModal() {
        pinFailStreak += 1
        if (pinFailStreak >= 2) {
            val email = playlist?.email?.trim().orEmpty()
            if (email.isNotBlank() && PinAlert.configured()) {
                val app = application as OlympusIptvApp
                app.appScope.launch {
                    val deviceId = runCatching { app.playlistStore.deviceId() }.getOrDefault("")
                    PinAlert.notifyFailedUnlock(app, email, deviceId)
                }
            }
            pinFailStreak = 0
        }
        pinDigits = ""
        paintGroupPin()
        binding.groupPin.visibility = View.GONE
        binding.groupPinWrong.visibility = View.VISIBLE
        binding.groupPinWrongOk.post { binding.groupPinWrongOk.requestFocus() }
    }

internal fun BrowseActivity.paintGroupPin() {
        binding.groupPinDigits.text = "•".repeat(pinDigits.length).padEnd(4, '–')
    }

internal fun BrowseActivity.appendGuidePinDigit(digit: Char) {
        if (!digit.isDigit() || pinDigits.length >= 4) return
        pinDigits += digit
        paintGroupPin()
        if (pinDigits.length == 4) submitGroupPin()
    }

internal fun BrowseActivity.clearGuidePinDigit() {
        if (pinDigits.isEmpty()) return
        pinDigits = pinDigits.dropLast(1)
        paintGroupPin()
    }

internal fun BrowseActivity.reclaimGuidePinFocus() {
        if (binding.groupPin.visibility != View.VISIBLE) return
        val index = pinKeyIndex.coerceIn(0, guidePinKeys.lastIndex)
        if (!guidePinKeys[index].requestFocus()) {
            binding.guidePin5.requestFocus()
        }
    }

internal fun BrowseActivity.moveGuidePinFocus(dx: Int, dy: Int): Boolean {
        if (binding.groupPin.visibility != View.VISIBLE) return false
        val index = guidePinKeys.indexOfFirst { it === currentFocus }.let {
            if (it >= 0) it else pinKeyIndex
        }.coerceIn(0, guidePinKeys.lastIndex)
        val col = index % 3
        val row = index / 3
        val next = (row + dy).coerceIn(0, 3) * 3 + (col + dx).coerceIn(0, 2)
        pinKeyIndex = next
        guidePinKeys[next].requestFocus()
        return true
    }

internal fun BrowseActivity.focusInGuidePin(): Boolean {
        var view = currentFocus ?: return false
        while (true) {
            if (view === binding.groupPin || view === binding.groupPinWrong) return true
            view = view.parent as? View ?: return false
        }
    }

internal fun BrowseActivity.submitGroupPin() {
        val category = pinCategory ?: return
        if (pinClearing) {
            if (GroupLock.accepts(activeConfig, category.id, pinDigits)) {
                pinFailStreak = 0
                Haptics.tick(binding.groupPin)
                saveGroupPin(category.id, null)
                hideGroupPin()
                categoryAdapter.refreshDetails()
                paintParentalChip()
                Toast.makeText(this, R.string.pin_cleared_ok, Toast.LENGTH_SHORT).show()
                focusGroups()
            } else {
                Haptics.reject(binding.groupPin)
                showWrongPinModal()
            }
            return
        }
        if (!pinOpens) {
            saveGroupPin(category.id, pinDigits)
            hideGroupPin()
            categoryAdapter.refreshDetails()
            paintParentalChip()
            Haptics.tick(binding.groupPin)
            Toast.makeText(this, R.string.pin_set_ok, Toast.LENGTH_SHORT).show()
            focusGroups()
            return
        }
        if (GroupLock.accepts(activeConfig, category.id, pinDigits)) {
            pinFailStreak = 0
            Haptics.tick(binding.groupPin)
            lifecycleScope.launch {
                val minutes = withContext(Dispatchers.IO) {
                    (application as OlympusIptvApp).playlistStore.parentalTimeoutMinutes()
                }
                GroupLock.unlock(category.id, minutes)
                hideGroupPin()
                selectedCategoryId = category.id
                showChannels()
                openFullGuide()
            }
            return
        }
        Haptics.reject(binding.groupPin)
        showWrongPinModal()
    }

internal fun BrowseActivity.saveGroupPin(groupId: String, digits: String?) {
        val saved = playlist ?: return
        val pins = activeConfig.groupPins.orEmpty().toMutableMap()
        if (digits == null) {
            pins.remove(groupId)
            GroupLock.lock(groupId)
        } else {
            pins[groupId] = GroupLock.code(digits)
            GroupLock.lock(groupId)
        }
        val next = activeConfig.copy(
            groupPins = pins.ifEmpty { null },
            revision = System.currentTimeMillis()
        )
        activeConfig = next
        val app = application as OlympusIptvApp
        app.stagedLineup = saved.id to next
        app.appScope.launch {
            runCatching { app.playlistStore.saveLineup(saved.id, next) }
            val email = saved.email
            if (!email.isNullOrBlank()) {
                runCatching { LineupClient(app).push(email, app.playlistStore.deviceId(), next) }
            }
        }
    }


internal fun BrowseActivity.offerGroupLock(category: Category) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - pinAt < 500L) return
        pinAt = now
        if (category.id == PlaylistLoader.ALL_ID ||
            category.id == PlaylistLoader.FAVORITES_ID ||
            category.id == PlaylistLoader.RECENT_ID ||
            category.id == PlaylistLoader.CONTINUE_ID ||
            category.id == PlaylistLoader.TOP_WATCHED_ID
        ) return
        showGroupPin(category, open = false)
    }

internal fun BrowseActivity.onGroupPinKey(event: KeyEvent): Boolean {
        if (binding.groupPinWrong.visibility == View.VISIBLE) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_B
            ) {
                dismissWrongPinToGroups()
            }
            return true
        }
        if (binding.groupPin.visibility != View.VISIBLE) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (!focusInGuidePin()) reclaimGuidePinFocus()
            val digit = when (event.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                    ('0' + (event.keyCode - KeyEvent.KEYCODE_0))
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
                    ('0' + (event.keyCode - KeyEvent.KEYCODE_NUMPAD_0))
                else -> null
            }
            if (digit != null) {
                appendGuidePinDigit(digit)
                return true
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                    clearGuidePinDigit()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> {
                    cancelGroupPinToGroups()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> return moveGuidePinFocus(0, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> return moveGuidePinFocus(0, 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> return moveGuidePinFocus(-1, 0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> return moveGuidePinFocus(1, 0)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    (currentFocus ?: guidePinKeys[pinKeyIndex.coerceIn(0, guidePinKeys.lastIndex)])
                        .performClick()
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
        if (!focusInGuidePin()) {
            reclaimGuidePinFocus()
            return true
        }
        return true
    }

internal fun BrowseActivity.onDialKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (currentFocus is android.widget.EditText) return false
        val inGuide = binding.contentColumn.visibility == View.VISIBLE &&
            (focusIsIn(binding.channelList) || focusIsIn(binding.programPane))
        if (!inGuide && dialDigits.isEmpty()) return false
        val digit = ChannelNumberJump.digit(event.keyCode)
        if (digit >= 0 && inGuide) {
            dialDigits = ChannelNumberJump.append(dialDigits, digit)
            binding.channelDialNumber.text = dialDigits
            binding.channelDialNumber.setShadowLayer(24f, 0f, 0f, getColor(R.color.accent))
            binding.channelDialHint.visibility = View.GONE
            binding.channelDial.visibility = View.VISIBLE
            focusHandler.removeCallbacks(dialJump)
            focusHandler.postDelayed(dialJump, ChannelNumberJump.DELAY_MS)
            return true
        }
        if (dialDigits.isEmpty()) return false
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            focusHandler.removeCallbacks(dialJump)
            jumpDial()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK ||
            event.keyCode == KeyEvent.KEYCODE_ESCAPE ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_B
        ) {
            clearDial()
            return true
        }
        return false
    }

internal fun BrowseActivity.jumpDial() {
        val typed = dialDigits
        if (typed.isEmpty()) return
        val onPrograms = focusIsIn(binding.programPane)
        val number = typed.toIntOrNull() ?: 0
        clearDial()
        val index = ChannelNumberJump.indexFor(visibleChannels.map { it.number }, number)
        if (number <= 0 || index !in visibleChannels.indices) {
            binding.channelDialNumber.text = typed
            binding.channelDialHint.text = getString(R.string.channel_missing)
            binding.channelDialHint.visibility = View.VISIBLE
            binding.channelDial.visibility = View.VISIBLE
            focusHandler.postDelayed({ binding.channelDial.visibility = View.GONE }, 900L)
            return
        }
        if (onPrograms) focusProgram(index, currentProgramColumn(index))
        else focusRowAt(binding.channelList, index, View.NO_ID)
    }

internal fun BrowseActivity.clearDial() {
        dialDigits = ""
        focusHandler.removeCallbacks(dialJump)
        binding.channelDial.visibility = View.GONE
        binding.channelDialHint.visibility = View.GONE
    }

