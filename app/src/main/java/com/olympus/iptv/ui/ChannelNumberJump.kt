package com.olympus.iptv.ui

import android.view.KeyEvent

object ChannelNumberJump {
    const val DELAY_MS = 1_200L
    const val MAX_DIGITS = 4

    fun digit(keyCode: Int): Int = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> -1
    }

    fun append(current: String, digit: Int): String {
        val base = if (current.length >= MAX_DIGITS) "" else current
        return base + digit.coerceIn(0, 9).toString()
    }

    fun indexFor(numbers: List<Int?>, typed: Int, allowRowFallback: Boolean = true): Int {
        if (typed <= 0) return -1
        val byNumber = numbers.indexOfFirst { it == typed }
        if (byNumber >= 0) return byNumber
        if (!allowRowFallback) return -1
        val row = typed - 1
        return if (row in numbers.indices) row else -1
    }
}
