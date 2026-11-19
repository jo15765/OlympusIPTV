package com.olympus.iptv.remind

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderAlarm : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderClock.arm(context.applicationContext)
    }
}
