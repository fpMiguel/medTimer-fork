package com.futsch1.medtimer

import androidx.test.platform.app.InstrumentationRegistry
import com.futsch1.medtimer.core.datastore.PersistentDataDataSource
import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.core.ui.R
import com.futsch1.medtimer.feature.reminders.AlarmScreenRepository
import com.futsch1.medtimer.feature.reminders.alarm.ReminderAlarmActivity
import com.futsch1.medtimer.feature.reminders.api.notificationData.ReminderNotificationData
import com.futsch1.medtimer.utilities.awaitNextSecond
import com.futsch1.medtimer.utilities.pollUntil
import com.futsch1.medtimer.utilities.scheduleRemindersNow
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import kotlin.test.assertTrue

private const val ALARM_MEDICINE = "Alarm med"
private const val QUIET_MEDICINE = "Quiet med"
private const val TAKEN_MEDICINE = "Taken med"
private const val REMAINING_MEDICINE = "Remaining med"

/**
 * Why: Guard against unstamped posts hijacking the alarm holder, and ensure equal-ID reduced-payload reposts replace the displayed alarm.
 * How: Production notification/holder publication + explicit activity bootstrap; this test does
 * not claim to verify platform AlarmManager/Doze/FSI delivery. Snooze behavior is covered by JVM tests.
 */
@HiltAndroidTest
class EdgeCaseAlarmTest : MedTimerTestBase() {

    @javax.inject.Inject
    lateinit var alarmScreenRepository: AlarmScreenRepository

    @javax.inject.Inject
    lateinit var persistentDataDataSource: PersistentDataDataSource

    @javax.inject.Inject
    lateinit var preferencesDataSource: PreferencesDataSource

    /**
     * Barista retries a failed attempt in the SAME app process: the alarm holder survives with
     * its last notification id, while the cleared preferences reset the id counter - fresh posts
     * could then lose to (or tie with) the stale holder. Advancing the app's own id allocator
     * past the holder's current id keeps the newest-wins rule intact for every post this
     * attempt makes.
     */
    private fun outrankStaleHolderAlarm(
        staleNotificationId: Int? = alarmScreenRepository.currentAlarm.value?.notificationId
    ) {
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
            "The scheduled alarm was not published to the alarm holder"
        )
        return checkNotNull(published)
    }

    /**
     * Re-delivers the holder's CURRENT payload through the documented onNewIntent
     * bootstrap-fallback seam (as AlarmIntentRedeliveryTest does): a fragment replace that was
     * committed while the activity was STOPPED under MainActivity's action dialog may not
     * render, and the dedupe bookkeeping then skips reconciliation as "already displayed".
     * The fallback path rebuilds unconditionally, with data taken verbatim from the holder.
     */
    private fun redeliverCurrentHolderToAlarmScreen(data: ReminderNotificationData) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(ReminderAlarmActivity.getIntent(context, data))
    }

    @Test
    fun unstampedPostsDoNotHijackDisplayedAlarm() {
        // SystemUI full-screen-intent behavior is device-policy dependent. This test owns the
        // notification/holder display contract and opens the activity from the published holder.
        val timeToNotify = 10_000L

        val staleNotificationId = alarmScreenRepository.currentAlarm.value?.notificationId
        outrankStaleHolderAlarm(staleNotificationId)

        // The stamped alarm: this receiver is the production notification path; the activity is
        // bootstrapped from its published holder below, so emulator FSI policy stays out of scope.
        seed.medicine(ALARM_MEDICINE) {
            reminder("1", aboutToFire())
            showAsAlarm()
        }

        // An unstamped normal-importance reminder: its post must leave the holder alone. It is
        // later than the stamped alarm so the first schedule cannot publish both chains together.
        seed.medicine(QUIET_MEDICINE) {
            reminder("1", laterToday())
        }

        alarm.sleepDevice()
        // Fire through the production receiver. The test owns holder publication and display,
        // not AlarmManager/Doze/FSI delivery.
        scheduleRemindersNow()

        // Open the resulting holder payload explicitly. SystemUI/AlarmManager delivery is outside
        // this display-contract test; AlarmIntentRedeliveryTest covers redelivery itself.
        val firstPublishedAlarm = awaitPublishedHolder(staleNotificationId, timeToNotify * 4)
        notifications.assertPosted(ALARM_MEDICINE, timeToNotify * 4)
        redeliverCurrentHolderToAlarmScreen(firstPublishedAlarm)
        alarm.awaitShown(timeToNotify * 2, "First alarm screen did not appear")
        alarm.assertResumedTopActivityIsAlarmScreen(
            "First alarm must be shown by ReminderAlarmActivity itself"
        )
        alarm.awaitShows(ALARM_MEDICINE, timeToNotify * 2, "First alarm shows wrong content")
        val displayedHolder = firstPublishedAlarm

        // Unstamped posts while the alarm screen is foregrounded: the zero-delay recalc raises
        // the quiet reminder. It may leave the post in the shade rather than the holder, so the
        // visible alarm must stay on Alarm med.
        // One recalc raises whatever is currently due and stops at the first future chain, so a
        // single call can leave due chains behind depending on order. Drain until the post is
        // present; the assertShows below still fails loudly if it never appears.
        for (attempt in 1..MAX_DRAIN_ATTEMPTS) {
            scheduleRemindersNow()
            // The Schedule broadcast is handled async on the app side; give the
            // quiet chain's post up to one bounded wait to land before checking.
            if (notifications.isPosted(QUIET_MEDICINE, SHADE_TIMEOUT)) break
        }

        notifications.assertPosted(QUIET_MEDICINE, SHADE_TIMEOUT)
        assertTrue(
            alarmScreenRepository.currentAlarm.value?.notificationId == displayedHolder.notificationId &&
                alarmScreenRepository.currentAlarm.value?.reminderEventIds == displayedHolder.reminderEventIds,
            "A normal notification changed the alarm holder"
        )

        alarm.awaitShows(ALARM_MEDICINE, SWITCH_TIMEOUT, "Unstamped posts hijacked the displayed alarm")
        alarm.logHygiene("unstamped-posts-settled")
        alarm.assertResumedTopActivityIsAlarmScreen(
            "Display must still live in ReminderAlarmActivity"
        )

        alarm.take(SWITCH_TIMEOUT, "Alarm screen did not offer Taken")
        awaitHolderCleared()
    }

    @Test
    fun equalIdReducedPayloadRepostUpdatesDisplayedAlarm() {
        val timeToNotify = 10_000L
        val staleNotificationId = alarmScreenRepository.currentAlarm.value?.notificationId
        outrankStaleHolderAlarm(staleNotificationId)

        // This test owns equal-ID reduced-payload behavior, not the editor or the settings UI.
        // Set the production preference and seed the exact same-time notification shape directly.
        preferencesDataSource.putBoolean(PreferencesDataSource.COMBINE_NOTIFICATIONS, true)
        assertTrue(
            pollUntil(1_000L) { preferencesDataSource.preferences.value.combineNotifications },
            "The production combine-notifications preference did not become active"
        )
        seed.medicine(TAKEN_MEDICINE) {
            reminder("1", aboutToFire(), variableAmount = true)
            showAsAlarm()
        }
        seed.medicine(REMAINING_MEDICINE) {
            reminder("1", aboutToFire())
            showAsAlarm()
        }

        // Repository observers may still be recalculating after the second seed. Let that
        // first async recalc settle before requesting one deterministic combined notification.
        awaitNextSecond()

        scheduleRemindersNow()
        val combinedPublishedAlarm = awaitPublishedHolder(staleNotificationId, timeToNotify * 2)
        notifications.assertPosted(TAKEN_MEDICINE, timeToNotify * 2)
        redeliverCurrentHolderToAlarmScreen(combinedPublishedAlarm)
        alarm.awaitShown(timeToNotify * 2, "Combined two-dose alarm screen did not appear")
        alarm.awaitShows(TAKEN_MEDICINE, timeToNotify, "First dose missing on alarm screen")
        alarm.awaitShows(REMAINING_MEDICINE, timeToNotify, "Second dose missing on alarm screen")
        val originalNotification = notifications.postedData(TAKEN_MEDICINE)
        val originalNotificationId = originalNotification.notificationId

        // Take ONE dose via its NOTIFICATION ACTION (not the alarm's own button): the variable
        // amount routes the taken intent through MainActivity's dosage dialog, which marks only
        // this dose - the production path for an equal-ID re-post with reduced payload.
        notifications.inShade {
            clickAction(R.string.taken)
        }
        dialogs.awaitInput()
        dialogs.enterTextAndConfirm("1")

        // The reduced re-post replaces the shade copy under the SAME notification id.
        val reducedNotification = notifications.postedData(REMAINING_MEDICINE)
        assertTrue(
            reducedNotification.notificationId == originalNotificationId,
            "Reduced notification did not retain the original notification ID"
        )
        assertTrue(
            reducedNotification.reminderEventIds.size == originalNotification.reminderEventIds.size - 1,
            "Reduced notification did not contain exactly the remaining event"
        )
        notifications.inShade {
            assertShows(REMAINING_MEDICINE, SHADE_TIMEOUT)
            assertHidden(TAKEN_MEDICINE, REDUCE_SETTLE_TIMEOUT)
        }

        // The screen follows the holder after the stopped activity is resumed. No second full
        // payload is delivered here, so this verifies the collector/onResume recovery itself.
        alarm.resumeAlarmTaskViaAmStart(SWITCH_TIMEOUT, "Alarm task did not come back to the front")
        alarm.awaitShows(REMAINING_MEDICINE, SWITCH_TIMEOUT, "Reduced payload lost the remaining dose")
        alarm.awaitHides(TAKEN_MEDICINE, SWITCH_TIMEOUT, "Equal-ID reduced re-post was suppressed")
        alarm.logHygiene("reduced-payload-applied")

        alarm.take(SWITCH_TIMEOUT, "Alarm screen did not offer Taken")
        awaitHolderCleared()
    }

    private fun awaitHolderCleared() {
        assertTrue(
            pollUntil(5_000L) { alarmScreenRepository.currentAlarm.value == null },
            "Alarm holder was not cleared after the alarm was consumed"
        )
    }

    private companion object {
        /** Upper bound for draining all due chains; each recalc strictly shrinks the due set. */
        const val MAX_DRAIN_ATTEMPTS = 4
        const val SWITCH_TIMEOUT = 20_000L
        const val SHADE_TIMEOUT = 5_000L

        /** Bounded settle window for the async reduced-payload re-post / notification cancel. */
        const val REDUCE_SETTLE_TIMEOUT = 5_000L
    }
}
