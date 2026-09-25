package com.futsch1.medtimer

import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.futsch1.medtimer.core.domain.model.Medicine
import com.futsch1.medtimer.core.domain.model.Reminder
import com.futsch1.medtimer.core.domain.model.ReminderEvent
import com.futsch1.medtimer.feature.reminders.AlarmScreenRepository
import com.futsch1.medtimer.feature.reminders.alarm.ReminderAlarmActivity
import com.futsch1.medtimer.feature.reminders.api.notificationData.ReminderNotificationData
import com.futsch1.medtimer.harness.RepositoryEntryPoint
import com.futsch1.medtimer.utilities.pollUntil
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.time.Instant
import javax.inject.Inject
import kotlin.test.assertTrue

/**
 * Regression tests for https://github.com/Futsch1/medTimer/issues/1494
 * ("Alarm can display previous events instead of current ones").
 */
@HiltAndroidTest
class AlarmIntentRedeliveryTest : MedTimerTestBase(launchMainActivity = false) {

    @Inject
    lateinit var alarmScreenRepository: AlarmScreenRepository

    @Before
    fun clearRetainedHolder() {
        alarmScreenRepository.currentAlarm.value?.let(alarmScreenRepository::clearIfCurrent)
    }

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val entryPoint get() = EntryPointAccessors.fromApplication(
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
        RepositoryEntryPoint::class.java
    )

    private fun createAlarmData(medicineName: String, notificationId: Int = -1): ReminderNotificationData = runBlocking {
        val medicineId = entryPoint.medicineRepository().create(Medicine.default().copy(name = medicineName))
        val reminderId = entryPoint.reminderRepository().create(
            Reminder.default().copy(medicineRelId = medicineId)
        )
        val event = entryPoint.reminderEventRepository().create(
            ReminderEvent.default().copy(reminderId = reminderId, medicineName = medicineName)
        )
        ReminderNotificationData.fromArrays(
            listOf(reminderId),
            listOf(event.reminderEventId),
            Instant.now(),
            notificationId
        )
    }

    @Test
    fun secondAlarmIntentReplacesDisplayedEvents() {
        val dataA = createAlarmData("Meds A")
        val dataB = createAlarmData("Meds B")
        val scenario = androidx.test.core.app.ActivityScenario.launch<ReminderAlarmActivity>(
            ReminderAlarmActivity.getIntent(targetContext, dataA)
        )

        try {
            assertTrue(
                pollUntil(10_000) { notificationTitleText()?.contains("Meds A") == true },
                "Alarm screen should show the first alarm's medicine (Meds A), got: ${notificationTitleText()}"
            )

            scenario.onActivity { activity ->
                InstrumentationRegistry.getInstrumentation()
                    .callActivityOnNewIntent(activity, ReminderAlarmActivity.getIntent(targetContext, dataB))
            }

            assertTrue(
                pollUntil(10_000) {
                    val title = notificationTitleText()
                    title?.contains("Meds B") == true && title.contains("Meds A") == false
                },
                "Alarm screen should replace Meds A with Meds B, got: ${notificationTitleText()}"
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun equalIdIntentCannotReplaceTheCurrentHolder() {
        val current = createAlarmData("Current med", notificationId = 42)
        val stale = createAlarmData("Stale med", notificationId = 42)
        alarmScreenRepository.publish(current)
        val scenario = androidx.test.core.app.ActivityScenario.launch<ReminderAlarmActivity>(
            ReminderAlarmActivity.getIntent(targetContext, current)
        )

        try {
            assertTrue(
                pollUntil(10_000) { notificationTitleText()?.contains("Current med") == true },
                "The current holder should be visible before redelivery"
            )

            scenario.onActivity { activity ->
                InstrumentationRegistry.getInstrumentation()
                    .callActivityOnNewIntent(activity, ReminderAlarmActivity.getIntent(targetContext, stale))
            }

            assertTrue(
                pollUntil(10_000) {
                    val title = notificationTitleText()
                    title?.contains("Current med") == true && title.contains("Stale med") == false
                },
                "An equal-ID stale intent replaced the current holder, got: ${notificationTitleText()}"
            )
        } finally {
            scenario.close()
        }
    }

    private fun notificationTitleText(): String? {
        var text: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = (ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { it is ReminderAlarmActivity })
                ?.findViewById<TextView>(com.futsch1.medtimer.feature.reminders.R.id.notificationTitle)
                ?.text
                ?.toString()
        }
        return text
    }
}
