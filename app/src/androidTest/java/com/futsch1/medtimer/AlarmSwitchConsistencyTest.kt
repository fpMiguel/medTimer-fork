package com.futsch1.medtimer

import androidx.test.core.app.ActivityScenario
import com.futsch1.medtimer.core.datastore.PersistentDataDataSource
import com.futsch1.medtimer.feature.reminders.AlarmScreenRepository
import com.futsch1.medtimer.feature.reminders.alarm.ReminderAlarmActivity
import com.futsch1.medtimer.feature.reminders.api.notificationData.ReminderNotificationData
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
class AlarmSwitchConsistencyTest : MedTimerTestBase(launchMainActivity = false) {

    @Inject
    lateinit var alarmScreenRepository: AlarmScreenRepository

    @Inject
    lateinit var persistentDataDataSource: PersistentDataDataSource

    @Test
    fun foregroundAlarmScreenSwitchesToNewestDose() {
        val timeToNotify = 5_000L

        val staleNotificationId = alarmScreenRepository.currentAlarm.value?.notificationId
        outrankStaleHolderAlarm(staleNotificationId)

        // Use Seed for direct repository seeding - avoids UI robot overhead (~15s per medicine).
        seed.medicine(FIRST_ALARM_MEDICINE) {
            intervalReminder("1", 2.hours)
            showAsAlarm()
        }
        scheduleRemindersNow()
        val firstAlarm = awaitPublishedHolder(staleNotificationId, timeToNotify * 2)
        notifications.assertPosted(FIRST_ALARM_MEDICINE, timeToNotify * 2)

        // The real notification pipeline has published the first alarm. ActivityScenario keeps
        // ownership of the alarm task explicit and avoids a background start-activity policy.
        val scenario = ActivityScenario.launch<ReminderAlarmActivity>(
            ReminderAlarmActivity.getIntent(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
                firstAlarm
            )
        )
        try {
            alarm.awaitShown(timeToNotify * 2, "First alarm screen did not appear")
            alarm.logHygiene("first-alarm-shown")
            alarm.assertResumedTopActivityIsAlarmScreen(
                "First alarm must be shown by ReminderAlarmActivity itself"
            )
            alarm.awaitShows(FIRST_ALARM_MEDICINE, timeToNotify, "First alarm shows wrong content")
            val firstActivityIdentity = checkNotNull(alarm.activityIdentity())

            // Add the second alarm only after the first screen is RESUMED. Its 10-minute due time is
            // earlier than the first chain's next 2-hour occurrence, so one zero-delay schedule
            // deterministically posts the second alarm last without relying on scheduler retry order.
            seed.medicine(SECOND_ALARM_MEDICINE) {
                reminder("1", aboutToFire())
                showAsAlarm()
            }
            scheduleRemindersNow()
            notifications.assertPosted(SECOND_ALARM_MEDICINE, SWITCH_TIMEOUT)

            alarm.awaitShows(SECOND_ALARM_MEDICINE, SWITCH_TIMEOUT, "Display did not switch to second dose")
            alarm.assertActivityIdentity(
                firstActivityIdentity,
                SWITCH_TIMEOUT,
                "Alarm activity was recreated instead of receiving the second alarm"
            )
            alarm.awaitHides(FIRST_ALARM_MEDICINE, SWITCH_TIMEOUT, "First alarm content remained visible")
            alarm.logHygiene("switched-to-second-dose")
            alarm.assertResumedTopActivityIsAlarmScreen(
                "Switched display must still live in ReminderAlarmActivity"
            )

            // Close the alarm through its own Taken button so no alarm activity survives this test
            // into a subsequent run.
            alarm.take(SWITCH_TIMEOUT / 2, "Switched alarm screen did not offer Taken")
            assertTrue(
                pollUntil(5_000L) { alarmScreenRepository.currentAlarm.value == null },
                "Alarm holder was not cleared after the alarm was consumed"
            )
        } finally {
            scenario.close()
        }
    }

    private fun outrankStaleHolderAlarm(staleNotificationId: Int?) {
        if (staleNotificationId != null) {
            repeat(staleNotificationId + 1) { persistentDataDataSource.getAndIncreaseNotificationId() }
        }
    }

    private fun awaitPublishedHolder(
        staleNotificationId: Int?,
        timeoutMillis: Long
    ): ReminderNotificationData {
        var published: ReminderNotificationData? = null
        assertTrue(
            pollUntil(timeoutMillis) {
                alarmScreenRepository.currentAlarm.value?.let { current ->
                    if (staleNotificationId == null || current.notificationId > staleNotificationId) {
                        published = current
                        true
                    } else {
                        false
                    }
                } == true
            },
            "The first alarm was not published to the alarm holder"
        )
        return checkNotNull(published)
    }

    private companion object {
        const val SWITCH_TIMEOUT = 10_000L
    }
}
