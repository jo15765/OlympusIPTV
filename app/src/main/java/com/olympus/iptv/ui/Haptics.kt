package com.olympus.iptv.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

object Haptics {
    fun tick(view: View?) {
        view ?: return
        if (!DeviceForm.isTouchPrimary(view.context)) return
        val feedback = if (Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(feedback)
    }

    fun reject(view: View?) {
        view ?: return
        if (!DeviceForm.isTouchPrimary(view.context)) return
        val feedback = if (Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(feedback)
    }

    /** 10% brightness or volume step on a phone. */
    fun clock(view: View?) {
        view ?: return
        if (DeviceForm.isTelevision(view.context)) return
        view.performHapticFeedback(
            HapticFeedbackConstants.CLOCK_TICK,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
    }

    /** Double-tap seek. */
    fun confirm(view: View?) {
        view ?: return
        if (DeviceForm.isTelevision(view.context)) return
        val feedback = if (Build.VERSION.SDK_INT >= 30) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(feedback, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }
}
