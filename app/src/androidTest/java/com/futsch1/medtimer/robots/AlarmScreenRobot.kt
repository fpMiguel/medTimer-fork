package com.futsch1.medtimer.robots

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import com.futsch1.medtimer.feature.reminders.alarm.ReminderAlarmActivity
import com.futsch1.medtimer.utilities.pollUntil
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.containsString
import kotlin.test.assertTrue

/** The full-screen alarm. It is MedTimer's own activity, so Espresso drives it once it has resumed. */
class AlarmScreenRobot {

    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private var lastClickError: Throwable? = null

    /** The alarm only proves itself by waking a sleeping device, so the test drives the screen too. */
    fun wakeDevice() = device.wakeUp()

    fun sleepDevice() = device.sleep()

    fun awaitShown(timeoutMillis: Long, message: String) {
        assertTrue(pollUntil(timeoutMillis) { alarmActivity() != null }, message)
    }

    /**
     * Bounded wait for the alarm screen to display content containing [text] (e.g. the newest
     * dose's medicine name on [com.futsch1.medtimer.feature.reminders.R.id.notificationTitle]).
     */
    fun awaitShows(text: String, timeoutMillis: Long, message: String) {
        assertTrue(pollUntil(timeoutMillis) { displays(text) }, "$message (never displayed \"$text\")")
    }

    /**
     * Bounded wait for the alarm screen to have REPLACED content containing [text] while staying
     * up - e.g. a taken dose's line gone after an equal-ID reduced-payload re-post.
     */
    fun awaitHides(text: String, timeoutMillis: Long, message: String) {
        assertTrue(
            pollUntil(timeoutMillis) { alarmActivity() != null && !displays(text) },
            "$message (\"$text\" still displayed)"
        )
    }

    /** Stable identity for the resumed alarm activity across a replacement assertion. */
    fun activityIdentity(): Int? = alarmActivity()?.let { System.identityHashCode(it) }

    fun assertActivityIdentity(expected: Int, timeoutMillis: Long, message: String) {
        assertTrue(
            pollUntil(timeoutMillis) { activityIdentity() == expected },
            message
        )
    }

    /**
     * Why: MainActivity is also singleInstance, so RESUMED top must be explicitly validated.
     * How: Assert component className == ReminderAlarmActivity; log resumed component.
     */
    fun assertResumedTopActivityIsAlarmScreen(message: String) {
        val activity = alarmActivity()
        assertTrue(
            activity != null && activity.componentName.className == ReminderAlarmActivity::class.java.name,
            "$message (resumed activity: ${activity?.componentName ?: "none"})"
        )
        Log.i(HYGIENE_TAG, "resumedTop=${activity.componentName.flattenToString()}")
    }

    /** Matrix hygiene probe: screen state, keyguard state, observed component - per attempt. */
    fun logHygiene(step: String) {
        val screenOn = runCatching { device.isScreenOn }.getOrDefault(false)
        val resumedActivity = alarmActivity()
        val keyguard = if (screenOn && resumedActivity != null) {
            "not-sampled-green-state"
        } else {
            runCatching {
                device.executeShellCommand("dumpsys window policy")
                    .lineSequence()
                    .firstOrNull { it.contains("mKeyguardOccluded") }?.trim() ?: "unknown"
            }.getOrDefault("unavailable")
        }
        val line = "screenOn=$screenOn keyguard=$keyguard " +
            "resumedTop=${resumedActivity?.componentName?.flattenToString() ?: "none"}"
        Log.i(HYGIENE_TAG, "[$step] $line")
        println("$HYGIENE_TAG [$step] $line")
    }

    private fun displays(text: String): Boolean {
        val activity = alarmActivity() ?: return false
        return try {
            onView(withId(com.futsch1.medtimer.feature.reminders.R.id.notificationTitle))
                .inRoot(RootMatchers.withDecorView(`is`(activity.window.decorView)))
                .check(matches(withText(containsString(text))))
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Brings the alarm task back to the front via the app context on the singleInstance alarm
     * activity. The extras-less intent only triggers [ReminderAlarmActivity.onNewIntent]'s holder
     * reconciliation; it never supplies a second payload.
     */
    fun resumeAlarmTaskViaAmStart(timeoutMillis: Long, message: String) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        // Started from the app's OWN context: the activity is not exported, so a shell `am start`
        // (uid 2000) is denied. An extras-less intent hits [ReminderAlarmActivity.onNewIntent],
        // which reconciles from the holder and whose bootstrap fallback ignores an intent
        // without a notification id - so what is displayed is purely the holder's payload.
        assertTrue(
            pollUntil(timeoutMillis) {
                runCatching {
                    targetContext.startActivity(
                        Intent(targetContext, ReminderAlarmActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                }
                alarmActivity() != null
            },
            message
        )
        logHygiene("resumed-via-am-start")
    }

    /** Taps Taken until the alarm closes: it can resume after the first tap lands. */
    fun take(timeoutMillis: Long, message: String) {
        awaitShown(timeoutMillis, message)
        lastClickError = null
        val closed = pollUntil(minOf(CLOSE_TIMEOUT, timeoutMillis)) {
            clickTaken()
            alarmActivity() == null
        }
        assertTrue(closed, "Alarm screen did not close" + (lastClickError?.let { ": $it" } ?: ""))
    }

    private fun clickTaken() {
        val activity = alarmActivity() ?: return
        try {
            onView(withId(com.futsch1.medtimer.feature.reminders.R.id.takenButton))
                .inRoot(RootMatchers.withDecorView(`is`(activity.window.decorView)))
                .perform(click())
        } catch (e: Throwable) {
            lastClickError = e
        }
    }

    private fun alarmActivity(): Activity? {
        var activity: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .firstOrNull { it is ReminderAlarmActivity }
        }
        return activity
    }

    private companion object {
        const val CLOSE_TIMEOUT = 10_000L
        const val HYGIENE_TAG = "AlarmSwitchConsistency"
    }
}
