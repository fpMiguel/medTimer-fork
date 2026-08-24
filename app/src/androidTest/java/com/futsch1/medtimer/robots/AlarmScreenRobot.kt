package com.futsch1.medtimer.robots

import android.app.Activity
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
import androidx.test.uiautomator.UiSelector
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
     * Asserts the RESUMED activity IS [ReminderAlarmActivity] by component class - MainActivity
     * is also singleInstance, so only the explicit class check disambiguates what is under test.
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
        val keyguard = runCatching {
            device.executeShellCommand("dumpsys window policy")
                .lineSequence()
                .firstOrNull { it.contains("mKeyguardOccluded") }?.trim() ?: "unknown"
        }.getOrDefault("unavailable")
        val screenOn = runCatching { device.isScreenOn }.getOrDefault(false)
        val line = "screenOn=$screenOn keyguard=$keyguard " +
            "resumedTop=${alarmActivity()?.componentName?.flattenToString() ?: "none"}"
        Log.i(HYGIENE_TAG, "[$step] $line")
        println("$HYGIENE_TAG [$step] $line")
    }

    /**
     * Presses Home, waits for the alarm to drop out of RESUMED, then brings THE ALARM TASK back
     * to the front through the system task-stack path (`am stack movetask` - the primitive the
     * recents UI performs on tap). Deliberately NOT the launcher: a launcher tap opens
     * MainActivity, while ReminderAlarmActivity is non-launcher and lives in its own task.
     * The task is resolved by its top activity COMPONENT, so the right task is disambiguated.
     */
    fun pressHomeAndResumeAlarmTask(timeoutMillis: Long, message: String) {
        device.pressHome()
        assertTrue(
            pollUntil(timeoutMillis) { alarmActivity() == null },
            "ReminderAlarmActivity still resumed after pressing Home"
        )
        logHygiene("after-home-before-task-resume")
        wakeDevice()
        val resumed = pollUntil(timeoutMillis) {
            val task = findAlarmTask()
            if (task == null) {
                Log.i(HYGIENE_TAG, "[recents-resume] alarm task not listed; stack=${stackListSummary()}")
                return@pollUntil false
            }
            device.executeShellCommand("am stack move-task ${task.taskId} ${task.stackId} true")
            logHygiene("recents-resume-attempt")
            alarmActivity() != null
        }
        assertTrue(resumed, message)
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

    /** Finds the recents entry whose top activity is the alarm screen; null while not yet listed. */
    private fun findAlarmTask(): TaskRef? {
        val stackList = runCatching { device.executeShellCommand("am stack list") }
            .getOrDefault("")
        var stackId: String? = null
        var taskId: String? = null
        for (line in stackList.lineSequence()) {
            // API 28: "Stack id=0 ..."; other builds: "Tasks in Stack id=0 ..."
            Regex("Stack id=(\\d+)").find(line)?.let { stackId = it.groupValues[1] }
            Regex("taskId=(\\d+)").find(line)?.let { taskId = it.groupValues[1] }
            if (line.contains("topActivity=") &&
                line.contains(ReminderAlarmActivity::class.java.name) &&
                stackId != null && taskId != null
            ) {
                return TaskRef(stackId!!, taskId!!)
            }
        }
        return null
    }

    private fun stackListSummary(): String = runCatching {
        device.executeShellCommand("am stack list")
            .lineSequence()
            .filter { it.contains("taskId=") }
            .map { it.trim().take(120) }
            .joinToString(" || ")
    }.getOrDefault("unavailable")

    /** Taps Taken until the alarm closes: it can resume after the first tap lands. */
    fun take(timeoutMillis: Long, message: String) {
        awaitShown(timeoutMillis, message)
        lastClickError = null
        val closed = pollUntil(CLOSE_TIMEOUT) {
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

    private data class TaskRef(val stackId: String, val taskId: String)

    private companion object {
        const val CLOSE_TIMEOUT = 10_000L
        const val HYGIENE_TAG = "AlarmSwitchConsistency"
    }
}
