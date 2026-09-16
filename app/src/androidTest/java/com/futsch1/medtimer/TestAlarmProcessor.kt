package com.futsch1.medtimer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.feature.reminders.AlarmProcessor
import java.time.Instant
import javax.inject.Inject

/**
 * Test-only AlarmProcessor that uses setAlarmClock() (doze-exempt) for future alarms.
 * Falls back to super's exact-alarm logic for immediate work. Lives only in androidTest.
 */
class TestAlarmProcessor @Inject constructor(
    context: Context,
    alarmManager: AlarmManager,
    timeAccess: TimeAccess,
    preferencesDataSource: PreferencesDataSource,
) : AlarmProcessor(context, alarmManager, timeAccess, preferencesDataSource) {

    override fun scheduleAlarm(instant: Instant, pendingIntent: PendingIntent) {
        Log.d("TestAlarmProcessor", "setAlarmClock doze-exempt for $instant")
        // Doze-exempt path for sleeping-device FSI; super would use setExactAndAllowWhileIdle.
        // Keep the same PendingIntent so the OS-reentry path is identical.
        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(instant.toEpochMilli(), pendingIntent),
                pendingIntent,
            )
        } catch (e: SecurityException) {
            // Fallback to production path if setAlarmClock is denied (e.g. no permission).
            super.scheduleAlarm(instant, pendingIntent)
        }
    }
}
