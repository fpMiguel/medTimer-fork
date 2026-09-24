package com.futsch1.medtimer

import com.futsch1.medtimer.core.datastore.PersistentDataDataSource
import com.futsch1.medtimer.feature.reminders.AlarmScreenRepository
import com.futsch1.medtimer.feature.reminders.alarm.ReminderAlarmActivity
import com.futsch1.medtimer.utilities.pollUntil
import com.futsch1.medtimer.utilities.scheduleRemindersNow
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

private const val FIRST_ALARM_MEDICINE = "Soon med"
private const val SECOND_ALARM_MEDICINE = "Later med"

/**
 * Why: A second alarm while RESUMED must switch to the newest dose (regression #1494).
 * How: The real notification pipeline publishes to the application-scoped holder; the test opens
 * the alarm activity explicitly so this test owns the display contract, while EdgeCaseAlarmTest
 * owns the separate sleeping-device notification-delivery contract.
 */
@HiltAndroidTest
class AlarmSwitchConsistencyTest : MedTimerTestBase() {

    @Inject
    lateinit var alarmScreenRepository: AlarmScreenRepository

    @Inject
    lateinit var persistentDataDataSource: PersistentDataDataSource

    @Test
    fun foregroundAlarmScreenSwitchesToNewestDose() {
        val timeToNotify = 5_000L
        alarm.wakeDevice()

        val staleNotificationId = alarmScreenRepository.currentAlarm.value?.notificationId
        outrankStaleHolderAlarm(staleNotificationId)

        // Use Seed for direct repository seeding - avoids UI robot overhead (~15s per medicine).
        seed.medicine(FIRST_ALARM_MEDICINE) {
            intervalReminder("1", 2.hours)
            showAsAlarm()
        }
        scheduleRemindersNow()
        val firstAlarm = awaitPublishedAlarm(staleNotificationId, timeToNotify * 2)

        // The real notification pipeline has published the first alarm. Open the same activity
        // that the platform's full-screen intent would open, without making this display test
        // depend on emulator keyguard/FSI policy.
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(ReminderAlarmActivity.getIntent(context, firstAlarm))
        alarm.awaitShown(timeToNotify * 2, "First alarm screen did not appear")
        alarm.logHygiene("first-alarm-shown")
        alarm.assertResumedTopActivityIsAlarmScreen(
            "First alarm must be shown by ReminderAlarmActivity itself"
        )
        alarm.awaitShows(FIRST_ALARM_MEDICINE, timeToNotify, "First alarm shows wrong content")

        // Add the second alarm only after the first screen is RESUMED. Its 10-minute due time is
        // earlier than the first chain's next 2-hour occurrence, so one zero-delay schedule
        // deterministically posts the second alarm last without relying on scheduler retry order.
        seed.medicine(SECOND_ALARM_MEDICINE) {
            reminder("1", aboutToFire())
            showAsAlarm()
        }
        scheduleRemindersNow()

        alarm.awaitShows(SECOND_ALARM_MEDICINE, SWITCH_TIMEOUT, "Display did not switch to second dose")
        alarm.logHygiene("switched-to-second-dose")
        alarm.assertResumedTopActivityIsAlarmScreen(
            "Switched display must still live in ReminderAlarmActivity"
        )

        // Close the alarm through its own Taken button so no alarm activity survives this test
        // into a subsequent run.
        alarm.take(SWITCH_TIMEOUT / 2, "Switched alarm screen did not offer Taken")
    }

    private fun outrankStaleHolderAlarm(staleNotificationId: Int?) {
        if (staleNotificationId != null) {
            repeat(staleNotificationId + 1) { persistentDataDataSource.getAndIncreaseNotificationId() }
        }
    }

    private fun awaitPublishedAlarm(staleNotificationId: Int?, timeoutMillis: Long) =
        assertTrue(
            pollUntil(timeoutMillis) {
                alarmScreenRepository.currentAlarm.value?.let { current ->
                    staleNotificationId == null || current.notificationId > staleNotificationId
                } == true
            },
            "The first alarm was not published to the alarm holder"
        ).let {
            checkNotNull(alarmScreenRepository.currentAlarm.value)
        }

    private companion object {
        const val SWITCH_TIMEOUT = 10_000L
    }
}
