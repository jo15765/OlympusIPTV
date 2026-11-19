package com.olympus.iptv.ui

import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import android.view.WindowManager

object SoftKeyboard {
    fun keepOffUntilAsked(activity: AppCompatActivity, field: EditText) {
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        field.showSoftInputOnFocus = false
    }

    fun showWhenFocused(activity: AppCompatActivity, field: EditText) {
        field.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            view.post {
                val imm = activity.getSystemService(InputMethodManager::class.java)
                @Suppress("DEPRECATION")
                imm?.showSoftInput(view, InputMethodManager.SHOW_FORCED)
                WindowCompat.getInsetsController(activity.window, view)
                    .show(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
        }
    }

    fun hide(activity: AppCompatActivity) {
        val focus = activity.currentFocus
        val imm = activity.getSystemService(InputMethodManager::class.java)
        val token = focus?.windowToken ?: activity.window.decorView.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(
            androidx.core.view.WindowInsetsCompat.Type.ime()
        )
        focus?.clearFocus()
    }
}
