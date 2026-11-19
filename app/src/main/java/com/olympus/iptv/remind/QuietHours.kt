package com.olympus.iptv.remind

import android.content.Context
import com.olympus.iptv.OlympusIptvApp
import com.olympus.iptv.data.QuietHoursBridge
import java.util.Calendar
import kotlinx.coroutines.runBlocking

internal object QuietHours {
    fun active(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val (enabled, start, end) = settings(context)
        if (!enabled) return false
        val minute = Calendar.getInstance().apply { timeInMillis = now }.let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }
        return if (start == end) false
        else if (start < end) minute in start until end
        else minute >= start || minute < end
    }

    fun endAt(context: Context, now: Long = System.currentTimeMillis()): Long {
        val endMinute = settings(context).third
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val current = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, endMinute / 60)
        cal.set(Calendar.MINUTE, endMinute % 60)
        if (endMinute <= current) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun settings(context: Context): Triple<Boolean, Int, Int> {
        QuietHoursBridge.snapshot()?.let { return it }
        val app = context.applicationContext as? OlympusIptvApp
            ?: return Triple(false, 0, 0)
        return runBlocking {
            val store = app.playlistStore
            Triple(store.quietHours(), store.quietStartMinute(), store.quietEndMinute())
        }.also { QuietHoursBridge.remember(it.first, it.second, it.third) }
    }
}
