package com.olympus.iptv.remind

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper

internal object ReminderClock {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { posted?.let { fire(it) } }
    private var posted: Context? = null
    private var started = false
    private val clock = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            arm(context.applicationContext)
        }
    }

    fun start(app: Application) {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(clock, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(clock, filter)
        }
        arm(app)
    }

    fun arm(context: Context) {
        val app = context.applicationContext
        posted = app
        handler.removeCallbacks(tick)
        if (ReminderActivity.active) return
        val now = System.currentTimeMillis()
        val due = ReminderStore.due(app, now)
        if (due.isNotEmpty()) {
            if (QuietHours.active(app, now)) {
                val wake = QuietHours.endAt(app, now)
                val delay = (wake - now).coerceAtLeast(500L)
                handler.postDelayed(tick, delay)
                scheduleAlarm(app, wake)
                return
            }
            open(app, due.first())
            return
        }
        val next = ReminderStore.nextFire(app, now) ?: run {
            cancelAlarm(app)
            return
        }
        val wake = if (QuietHours.active(app, next)) QuietHours.endAt(app, next) else next
        val delay = (wake - now).coerceAtLeast(500L)
        handler.postDelayed(tick, delay)
        scheduleAlarm(app, wake)
    }

    fun onClosed(context: Context) {
        handler.postDelayed({ arm(context.applicationContext) }, 300)
    }

    private fun fire(context: Context) {
        arm(context)
    }

    private fun open(context: Context, item: ShowReminder) {
        val id = item.id ?: return
        val intent = Intent(context, ReminderActivity::class.java)
            .putExtra(ReminderActivity.EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            handler.postDelayed({ arm(context) }, 15_000)
        }
    }

    private fun scheduleAlarm(context: Context, at: Long) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pending(context)
        val trigger = at.coerceAtLeast(System.currentTimeMillis() + 500)
        val exact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        runCatching {
            if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }.onFailure {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
        }
    }

    private fun cancelAlarm(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, ReminderAlarm::class.java)
        return PendingIntent.getBroadcast(
            context,
            61,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
