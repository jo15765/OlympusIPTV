package com.olympus.iptv.ui.lineup

import android.app.Dialog
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import com.olympus.iptv.R
import com.olympus.iptv.databinding.DialogRenameBinding

object RenameDialog {
    fun show(activity: android.app.Activity, current: String, onSave: (String) -> Unit) {
        val binding = DialogRenameBinding.inflate(activity.layoutInflater)
        val dialog = Dialog(activity)
        dialog.setContentView(binding.root)
        dialog.window?.apply {
            setBackgroundDrawableResource(R.drawable.bg_login_card)
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.62f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }

        binding.nameField.showSoftInputOnFocus = false
        binding.nameField.setText(current)
        binding.nameField.setSelection(binding.nameField.text?.length ?: 0)

        val letters = mutableListOf<Button>()
        var upper = true
        fun applyCase() {
            letters.forEach { button ->
                val label = button.tag as? String ?: return@forEach
                button.text = if (upper) label.uppercase() else label.lowercase()
            }
        }

        val grid = listOf(
            "ABCDEFGHI",
            "JKLMNOPQR",
            "STUVWXYZ",
            "1234567890"
        )
        grid.forEach { row ->
            binding.letterGrid.addView(rowOf(activity, row.map { ch ->
                keyButton(activity, ch.toString()).also { button ->
                    button.tag = ch.toString()
                    letters.add(button)
                    button.setOnClickListener { insert(binding, button.text.toString()) }
                }
            }))
        }
        applyCase()
        val shift = keyButton(activity, activity.getString(R.string.lineup_shift))
        shift.setOnClickListener {
            upper = !upper
            applyCase()
        }
        val space = keyButton(activity, activity.getString(R.string.lineup_space))
        space.setOnClickListener { insert(binding, " ") }
        val delete = keyButton(activity, activity.getString(R.string.lineup_delete))
        delete.setOnClickListener { backspace(binding) }
        val symbols = listOf("|", "&", "+", "/", ":", ".", "-", "_", ",").map { symbol ->
            keyButton(activity, symbol).also { button ->
                button.setOnClickListener { insert(binding, symbol) }
            }
        }
        binding.letterGrid.addView(rowOf(activity, listOf(shift, space, delete)))
        binding.letterGrid.addView(rowOf(activity, symbols))
        wireGrid(binding.letterGrid, binding.btnSave)

        binding.nameField.setOnKeyListener { _, keyCode, event ->
            val movesCursor = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (!movesCursor) return@setOnKeyListener false
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener true
            val field = binding.nameField
            val cursor = field.selectionStart.coerceAtLeast(0)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (cursor > 0) field.setSelection(cursor - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val length = field.text?.length ?: 0
                    if (cursor < length) field.setSelection(cursor + 1)
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    binding.btnSave.requestFocus()
            }
            true
        }
        binding.btnSave.setOnClickListener {
            dialog.dismiss()
            onSave(binding.nameField.text?.toString()?.trim().orEmpty())
        }
        binding.btnCancel.setOnClickListener { dialog.dismiss() }
        binding.btnSave.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                binding.nameField.requestFocus()
                true
            } else {
                false
            }
        }
        binding.btnCancel.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                binding.nameField.requestFocus()
                true
            } else {
                false
            }
        }

        dialog.setOnShowListener { binding.nameField.requestFocus() }
        dialog.show()
        com.olympus.iptv.ui.DialogChrome.dress(dialog)
    }

    private fun insert(binding: DialogRenameBinding, text: String) {
        val field = binding.nameField
        val start = field.selectionStart.coerceAtLeast(0)
        val end = field.selectionEnd.coerceAtLeast(start)
        field.text?.replace(start, end, text)
        field.setSelection((start + text.length).coerceAtMost(field.text?.length ?: 0))
    }

    private fun backspace(binding: DialogRenameBinding) {
        val field = binding.nameField
        val start = field.selectionStart.coerceAtLeast(0)
        val end = field.selectionEnd.coerceAtLeast(start)
        if (start != end) {
            field.text?.delete(start, end)
            field.setSelection(start)
        } else if (start > 0) {
            field.text?.delete(start - 1, start)
            field.setSelection(start - 1)
        }
    }

    private fun keyButton(activity: android.app.Activity, label: String): Button {
        return Button(activity).apply {
            text = label
            isFocusable = true
            isFocusableInTouchMode = true
            background = activity.getDrawable(R.drawable.bg_button_secondary)
            setTextColor(activity.getColor(R.color.text_primary))
            textSize = 14f
            isAllCaps = false
            maxLines = 1
            isSingleLine = true
            minWidth = 0
            minimumWidth = 0
            val pad = (activity.resources.displayMetrics.density * 8).toInt()
            setPadding(pad, 0, pad, 0)
            layoutParams = LinearLayout.LayoutParams(0, (activity.resources.displayMetrics.density * 40).toInt(), 1f).apply {
                marginEnd = (activity.resources.displayMetrics.density * 4).toInt()
            }
        }
    }

    private fun rowOf(activity: android.app.Activity, buttons: List<Button>): LinearLayout {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (activity.resources.displayMetrics.density * 4).toInt()
            }
            buttons.forEach { addView(it) }
        }
    }

    private fun wireGrid(grid: LinearLayout, above: View) {
        val rows = (0 until grid.childCount).map { grid.getChildAt(it) as LinearLayout }
        rows.forEach { row ->
            for (index in 0 until row.childCount) {
                val button = row.getChildAt(index)
                if (button.id == View.NO_ID) button.id = View.generateViewId()
            }
        }
        rows.forEachIndexed { rowIndex, row ->
            val buttons = (0 until row.childCount).map { row.getChildAt(it) }
            buttons.forEachIndexed { index, button ->
                button.nextFocusLeftId = buttons.getOrNull(index - 1)?.id ?: button.id
                button.nextFocusRightId = buttons.getOrNull(index + 1)?.id ?: button.id
                val up = rows.getOrNull(rowIndex - 1)?.getChildAt(index.coerceAtMost((rows.getOrNull(rowIndex - 1)?.childCount ?: 1) - 1))
                val down = rows.getOrNull(rowIndex + 1)?.getChildAt(index.coerceAtMost((rows.getOrNull(rowIndex + 1)?.childCount ?: 1) - 1))
                button.nextFocusUpId = up?.id ?: above.id
                if (down != null) button.nextFocusDownId = down.id
            }
        }
        val first = rows.firstOrNull()?.getChildAt(0) ?: return
        above.nextFocusDownId = first.id
    }
}
