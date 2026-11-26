package com.olympus.iptv.ui

import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import android.view.WindowManager

object SoftKeyboard {
    fun keepOffUntilAsked(activity: AppCompatActivity, field: EditText) {
        if (DeviceForm.isTouchPrimary(activity)) {
            show(activity, field)
            return
        }
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        field.showSoftInputOnFocus = false
    }

    fun show(activity: AppCompatActivity, field: EditText) {
        val touch = DeviceForm.isTouchPrimary(activity)
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        field.showSoftInputOnFocus = true
        field.isFocusable = true
        field.isFocusableInTouchMode = true
        field.requestFocus()
        field.post {
            val imm = activity.getSystemService(InputMethodManager::class.java)
            val flag = if (touch) {
                0
            } else {
                @Suppress("DEPRECATION")
                InputMethodManager.SHOW_FORCED
            }
            imm?.showSoftInput(field, flag)
            WindowCompat.getInsetsController(activity.window, field)
                .show(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
    }

    fun showWhenFocused(activity: AppCompatActivity, field: EditText) {
        val touch = DeviceForm.isTouchPrimary(activity)
        field.showSoftInputOnFocus = true
        field.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            view.post {
                val imm = activity.getSystemService(InputMethodManager::class.java)
                val flag = if (touch) {
                    0
                } else {
                    @Suppress("DEPRECATION")
                    InputMethodManager.SHOW_FORCED
                }
                imm?.showSoftInput(view, flag)
                WindowCompat.getInsetsController(activity.window, view)
                    .show(androidx.core.view.WindowInsetsCompat.Type.ime())
                val rect = android.graphics.Rect(0, 0, view.width, view.height + (72 * view.resources.displayMetrics.density).toInt())
                view.requestRectangleOnScreen(rect, true)
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
