package com.futsch1.medtimer

import com.futsch1.medtimer.core.ui.R
import com.futsch1.medtimer.utilities.awaitNextSecond
import com.futsch1.medtimer.utilities.scheduleRemindersNow
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

private const val FIRST_ALARM_MEDICINE = "Soon med"
private const val SECOND_ALARM_MEDICINE = "Later med"

/**
 * Why: Second alarm while RESUMED must switch to newest dose (regression #1494).
 * How: Two 3-min interval chains back-to-back; fires via production scheduler + holder choke-point; zero debug delay inline recalc; robot awaits, no mocks/sleeps/retries.
 */
@HiltAndroidTest
class AlarmSwitchConsistencyTest : MedTimerTestBase() {

    @Test
    fun foregroundAlarmScreenSwitchesToNewestDose() {
        val timeToNotify = 5_000L
        alarm.wakeDevice()

        // Use Seed for direct repository seeding - avoids UI robot overhead (~15s per medicine)
        val firstId = seed.medicine(FIRST_ALARM_MEDICINE) {
            intervalReminder("1", 3.minutes)
            // High importance = high_and_alarm (cannotBeSkipped maps to this)
            cannotBeSkipped()
        }
        val secondId = seed.medicine(SECOND_ALARM_MEDICINE) {
            intervalReminder("1", 3.minutes)
            cannotBeSkipped()
        }

        // Fire 1 via the production path: the first-created chain's next occurrence is the
        // earliest pending alarm; the zero-delay schedule makes the app's own recalc show it
        // immediately (inline - nothing for a background reschedule to overwrite). The device
        // is already asleep, so the full-screen intent launches over keyguard.
        alarm.sleepDevice()
        awaitNextSecond()
        scheduleRemindersNow()

        alarm.awaitShown(timeToNotify * 2, "First alarm screen did not appear")
        alarm.logHygiene("first-alarm-shown")
        alarm.assertResumedTopActivityIsAlarmScreen(
            "First alarm must be shown by ReminderAlarmActivity itself"
        )
        alarm.awaitShows(FIRST_ALARM_MEDICINE, timeToNotify, "First alarm shows wrong content")

        // Fire 2 through the production path while the screen is foregrounded+RESUMED: no
        // lifecycle trigger delivers it - the second-created chain's pending occurrence fires
        // immediately and only the holder follows it.
        awaitNextSecond()
        scheduleRemindersNow()

        alarm.awaitShows(SECOND_ALARM_MEDICINE, SWITCH_TIMEOUT / 2, "Display did not switch to second dose")
        alarm.logHygiene("switched-to-second-dose")
        alarm.assertResumedTopActivityIsAlarmScreen(
            "Switched display must still live in ReminderAlarmActivity"
        )

        // Close the alarm through its own Taken button so no alarm activity survives this test
        // into a subsequent run (a leftover screen would poison the next attempt's assertions).
        // Home/Recents retention of the stopped-but-alive screen is verified in F3's manual pass
        // (plan gate G2 default: stopped-but-alive automation deferred).
        alarm.take(SWITCH_TIMEOUT / 2, "Switched alarm screen did not offer Taken")
    }

    private companion object {
        const val SWITCH_TIMEOUT = 10_000L
        const val timeToNotify = 5_000L
    }
}
