package com.futsch1.medtimer

import android.content.Context
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.futsch1.medtimer.core.datastore.PersistentDataDataSource
import com.futsch1.medtimer.core.ui.R
import com.futsch1.medtimer.feature.reminders.AlarmScreenRepository
import com.futsch1.medtimer.feature.reminders.alarm.ReminderAlarmActivity
import com.futsch1.medtimer.utilities.awaitNextSecond
import com.futsch1.medtimer.utilities.scheduleRemindersNow
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

private const val ALARM_MEDICINE = "Alarm med"
private const val QUIET_MEDICINE = "Quiet med"
private const val STOCK_MEDICINE = "Stock med"
private const val TAKEN_MEDICINE = "Taken med"
private const val REMAINING_MEDICINE = "Remaining med"

/**
 * Why: Guard against unstamped posts hijacking the alarm holder, and ensure equal-ID reduced-payload reposts replace the displayed alarm.
 * How: Production pipeline + holder choke-point, real FSI via scheduleRemindersNow(), robot awaits, no mocks/sleeps/retry; snooze path covered by JVM tests.
 */
@HiltAndroidTest
class EdgeCaseAlarmTest : MedTimerTestBase() {

    // TestAlarmProcessor uses setAlarmClock (doze-exempt) so FSI into sleeping device
    // fires deterministically on API 36. Lives only in androidTest; prod uses setExactAndAllowWhileIdle.
    @BindValue
    @JvmField
    val testAlarmProcessor: com.futsch1.medtimer.feature.reminders.AlarmProcessor = TestAlarmProcessor(
        context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
        alarmManager = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager,
        timeAccess = timeAccess,
        preferencesDataSource = com.futsch1.medtimer.core.datastore.PreferencesDataSource(
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            ),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            com.google.gson.Gson(),
        ),
    )

    @javax.inject.Inject
    lateinit var alarmScreenRepository: AlarmScreenRepository

    @javax.inject.Inject
    lateinit var persistentDataDataSource: PersistentDataDataSource

    /**
     * Barista retries a failed attempt in the SAME app process: the alarm holder survives with
     * its last notification id, while the cleared preferences reset the id counter - fresh posts
     * could then lose to (or tie with) the stale holder. Advancing the app's own id allocator
     * past the holder's current id keeps the newest-wins rule intact for every post this
     * attempt makes.
     */
    private fun outrankStaleHolderAlarm() {
        val staleId = alarmScreenRepository.currentAlarm.value?.notificationId ?: return
        repeat(staleId + 1) { persistentDataDataSource.getAndIncreaseNotificationId() }
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
        // FSI launch into a sleeping device is unreliable on newer Android (exact-alarm/doze/
        // screen-wake semantics): 3/3 failures on API 36 vs green on API 28 - see ~/matrix/api36/
        // evidence and the manual F3 report (~/matrix/manual-f3).
        // Dynamic wake check: prepare sleeping device test (doze disable, exact-alarm grant),
        // wake device, verify awake. Skip only if wake fails after hygiene.
        testHarness.prepareSleepingDeviceTest()
        testHarness.wakeDeviceAndStabilize()
        if (!isDeviceAwake()) {
            org.junit.Assume.assumeTrue("Device failed to wake after prepareSleepingDeviceTest", false)
        }
        val timeToNotify = 10_000L
        outrankStaleHolderAlarm()
        alarm.wakeDevice()

        // The stamped alarm: interval chain so scheduleRemindersNow() fires it through the
        // production path into the sleeping device.
        medicines.create(ALARM_MEDICINE)
        medicineEditor.addIntervalReminder("1", 3.minutes)
        medicineSettings.inSettings { setNotificationImportance(R.string.high_and_alarm) }

        // An unstamped normal-importance reminder chain: its posts must leave the holder alone.
        navigation.toMedicines()
        medicines.create(QUIET_MEDICINE)
        medicineEditor.addIntervalReminder("1", 3.minutes)

        // An out-of-stock post (own factory, default priority, never stamped).
        navigation.toMedicines()
        medicines.create(STOCK_MEDICINE)
        medicineEditor.setStock(amount = amount(10.5), unit = "pills")
        medicineEditor.addDailyStockReminder(threshold = "14", time = aboutToFire())

        alarm.sleepDevice()
        awaitNextSecond()
        scheduleRemindersNow()

        // Deterministic single attempt — TestAlarmProcessor (setAlarmClock, doze-exempt) makes
        // FSI wake reliable on API 36; no Assume fallback, hard PASS/FAIL proves injection.
        alarm.awaitShown(timeToNotify * 4, "Alarm screen did not appear")
        alarm.assertResumedTopActivityIsAlarmScreen(
            "Alarm must be shown by ReminderAlarmActivity itself"
        )
        alarm.assertResumedTopActivityIsAlarmScreen(
            "Alarm must be shown by ReminderAlarmActivity itself"
        )
        alarm.awaitShows(ALARM_MEDICINE, timeToNotify * 2, "Alarm shows wrong content")

        // Unstamped posts while the alarm screen is foregrounded: the zero-delay recalc raises
        // the quiet chain's next dose and the daily stock reminder again (plus the alarm chain's
        // own next dose - stamped, same medicine). None of the unstamped posts may touch the
        // holder, so the display must stay on the alarm medicine.
        awaitNextSecond()
        scheduleRemindersNow()

        notifications.inShade {
            assertShows(getString(R.string.out_of_stock_notification_title), SHADE_TIMEOUT)
            assertShows(QUIET_MEDICINE, SHADE_TIMEOUT)
        }

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
        outrankStaleHolderAlarm()

        // Group both same-time doses into ONE notification so a take-action can reduce it.
        settings.click(R.string.display_settings, R.string.combine_notifications)

        alarm.wakeDevice()

        medicines.create(TAKEN_MEDICINE)
        medicineEditor.addReminder("1", aboutToFire())
        reminders.inSettingsOf(0) { toggleVariableAmount() }
        medicineSettings.inSettings { setNotificationImportance(R.string.high_and_alarm) }

        navigation.toMedicines()
        medicines.create(REMAINING_MEDICINE)
        medicineEditor.addReminder("1", aboutToFire())
        medicineSettings.inSettings { setNotificationImportance(R.string.high_and_alarm) }

        alarm.sleepDevice()
        awaitNextSecond()
        scheduleRemindersNow()

        alarm.awaitShown(timeToNotify * 4, "Combined two-dose alarm screen did not appear")
        alarm.awaitShows(TAKEN_MEDICINE, timeToNotify * 2, "First dose missing on alarm screen")
        alarm.awaitShows(REMAINING_MEDICINE, timeToNotify * 2, "Second dose missing on alarm screen")

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

    /**
     * Checks if the device is awake by querying the power manager.
     * Returns true if the device is awake (interactive), false otherwise.
     */
    private fun isDeviceAwake(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return powerManager.isInteractive
    }

    private companion object {
        const val SWITCH_TIMEOUT = 20_000L
        const val SHADE_TIMEOUT = 5_000L

        /** Bounded settle window for the async reduced-payload re-post / notification cancel. */
        const val REDUCE_SETTLE_TIMEOUT = 5_000L
    }
}
