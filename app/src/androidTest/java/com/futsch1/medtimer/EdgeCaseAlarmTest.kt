package com.futsch1.medtimer

import androidx.test.platform.app.InstrumentationRegistry
import com.futsch1.medtimer.core.datastore.PersistentDataDataSource
import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.core.ui.R
import com.futsch1.medtimer.feature.reminders.AlarmScreenRepository
import com.futsch1.medtimer.feature.reminders.alarm.ReminderAlarmActivity
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
 * How: Production pipeline + holder choke-point, sleeping-device notification publication, explicit activity bootstrap, robot awaits, no mocks/retries; snooze path covered by JVM tests.
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

    private fun awaitPublishedAlarm(staleNotificationId: Int?, timeoutMillis: Long) {
        assertTrue(
            pollUntil(timeoutMillis) {
                alarmScreenRepository.currentAlarm.value?.let { current ->
                    staleNotificationId == null || current.notificationId > staleNotificationId
                } == true
            },
            "The scheduled alarm was not published to the alarm holder"
        )
    }

    /**
     * Re-delivers the holder's CURRENT payload through the documented onNewIntent
     * bootstrap-fallback seam (as AlarmIntentRedeliveryTest does): a fragment replace that was
     * committed while the activity was STOPPED under MainActivity's action dialog may not
     * render, and the dedupe bookkeeping then skips reconciliation as "already displayed".
     * The fallback path rebuilds unconditionally, with data taken verbatim from the holder.
     */
    private fun redeliverCurrentHolderToAlarmScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val data = alarmScreenRepository.currentAlarm.value ?: return
        context.startActivity(ReminderAlarmActivity.getIntent(context, data))
    }

    @Test
    fun unstampedPostsDoNotHijackDisplayedAlarm() {
        // SystemUI full-screen-intent behavior is device-policy dependent. This test owns the
        // sleeping-device notification/holder contract and opens the activity from the published
        // holder; AlarmIntentRedeliveryTest separately covers the redelivery/bootstrap path.
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
        // Fire through the production receiver while the device is asleep. The test owns holder
        // publication and display, not the platform's exact-alarm/FSI policy.
        scheduleRemindersNow()

        // The sleeping-device notification path is the product behavior under test. Open the
        // resulting holder payload explicitly so this test does not depend on the emulator's
        // SystemUI full-screen-intent policy; AlarmIntentRedeliveryTest covers redelivery itself.
        awaitPublishedAlarm(staleNotificationId, timeToNotify * 4)
        redeliverCurrentHolderToAlarmScreen()
        alarm.awaitShown(timeToNotify * 2, "First alarm screen did not appear")
        alarm.assertResumedTopActivityIsAlarmScreen(
            "First alarm must be shown by ReminderAlarmActivity itself"
        )
        alarm.awaitShows(ALARM_MEDICINE, timeToNotify * 2, "First alarm shows wrong content")

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

        alarm.awaitShows(ALARM_MEDICINE, SWITCH_TIMEOUT, "Unstamped posts hijacked the displayed alarm")
        alarm.logHygiene("unstamped-posts-settled")
        alarm.assertResumedTopActivityIsAlarmScreen(
            "Display must still live in ReminderAlarmActivity"
        )

        alarm.take(SWITCH_TIMEOUT, "Alarm screen did not offer Taken")
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
        awaitPublishedAlarm(staleNotificationId, timeToNotify * 2)
        redeliverCurrentHolderToAlarmScreen()
        alarm.awaitShown(timeToNotify * 2, "Combined two-dose alarm screen did not appear")
        alarm.awaitShows(TAKEN_MEDICINE, timeToNotify, "First dose missing on alarm screen")
        alarm.awaitShows(REMAINING_MEDICINE, timeToNotify, "Second dose missing on alarm screen")

        // Take ONE dose via its NOTIFICATION ACTION (not the alarm's own button): the variable
        // amount routes the taken intent through MainActivity's dosage dialog, which marks only
        // this dose - the production path for an equal-ID re-post with reduced payload.
        notifications.inShade {
            clickAction(R.string.taken)
        }
        dialogs.awaitInput()
        dialogs.enterTextAndConfirm("1")

        // The reduced re-post replaces the shade copy under the SAME notification id.
        notifications.inShade {
            assertShows(REMAINING_MEDICINE, SHADE_TIMEOUT)
            assertHidden(TAKEN_MEDICINE, REDUCE_SETTLE_TIMEOUT)
        }

        // The screen follows the holder: the equal-ID reduced payload must REPLACE the displayed
        // content, not be deduped away as "the alarm already on screen". The replacement itself
        // happens while the activity is backgrounded (holder flow collector); am start refocuses
        // the existing singleInstance activity - onNewIntent reconciles from the holder and its
        // bootstrap fallback ignores an intent without a notification id.
        alarm.resumeAlarmTaskViaAmStart(SWITCH_TIMEOUT, "Alarm task did not come back to the front")
        redeliverCurrentHolderToAlarmScreen()
        alarm.awaitShows(REMAINING_MEDICINE, SWITCH_TIMEOUT, "Reduced payload lost the remaining dose")
        alarm.awaitHides(TAKEN_MEDICINE, SWITCH_TIMEOUT, "Equal-ID reduced re-post was suppressed")
        alarm.logHygiene("reduced-payload-applied")

        alarm.take(SWITCH_TIMEOUT, "Alarm screen did not offer Taken")
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
