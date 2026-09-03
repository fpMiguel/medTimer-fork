package com.futsch1.medtimer.feature.ui.overview.actions

import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.core.datastore.PersistentDataDataSource
import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.core.domain.model.Medicine
import com.futsch1.medtimer.core.domain.model.Reminder
import com.futsch1.medtimer.core.domain.model.ReminderEvent
import com.futsch1.medtimer.core.domain.model.ScheduledReminder
import com.futsch1.medtimer.core.domain.model.SimulatedReminder
import com.futsch1.medtimer.core.domain.model.UserPreferences
import com.futsch1.medtimer.feature.ui.overview.model.PastReminderEvent
import com.futsch1.medtimer.feature.ui.overview.model.SimulatedReminderEvent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression coverage for #1686 ("Skip scheduled dose no longer available"):
 * the per-medicine "cannot be skipped" toggle must control the SKIPPED button
 * in the overview when the global "reminders cannot be skipped" preference is off.
 * The global preference additionally hides SKIPPED everywhere (f6ba5fd0, v1.25.0).
 */
class SkipVisibilityTest {

    private fun preferences(globalCannotSkip: Boolean): PreferencesDataSource {
        val dataSource: PreferencesDataSource = mock()
        whenever(dataSource.preferences).thenReturn(
            MutableStateFlow(UserPreferences.default().copy(cannotSkipReminders = globalCannotSkip))
        )
        return dataSource
    }

    private fun scheduledEvent(medicineSkippable: Boolean, globalCannotSkip: Boolean): SimulatedReminderEvent {
        val medicine = Medicine.default().copy(id = 1, cannotBeSkipped = !medicineSkippable)
        val reminder = Reminder.default().copy(id = 1)
        val scheduled = ScheduledReminder(
            medicine = medicine,
            reminder = reminder,
            timestamp = Instant.ofEpochSecond(1_000)
        )
        return SimulatedReminderEvent(
            preferences(globalCannotSkip),
            SimulatedReminder(scheduled, stockBefore = 0.0, stockAfter = 0.0)
        )
    }

    private fun pastEvent(eventSkippable: Boolean, globalCannotSkip: Boolean): PastReminderEvent {
        val persistent: PersistentDataDataSource = mock()
        whenever(persistent.getPendingLocationSnoozes()).thenReturn(emptyList())
        val timeAccess: TimeAccess = mock()
        whenever(timeAccess.now()).thenReturn(Instant.ofEpochSecond(2_000))
        val event = ReminderEvent.default().copy(
            reminderEventId = 1,
            reminderId = 1,
            remindedTimestamp = Instant.ofEpochSecond(1_000),
            status = ReminderEvent.ReminderStatus.RAISED,
            cannotBeSkipped = !eventSkippable
        )
        return PastReminderEvent(preferences(globalCannotSkip), persistent, timeAccess, event)
    }

    @Test
    fun `scheduled dose shows skipped when medicine allows and global allows`() {
        val buttons = ScheduledReminderActions(scheduledEvent(medicineSkippable = true, globalCannotSkip = false)).visibleButtons

        assertTrue(Button.SKIPPED in buttons, "SKIPPED must show when both toggles are off (#1686)")
    }

    @Test
    fun `scheduled dose hides skipped when medicine forbids`() {
        val buttons = ScheduledReminderActions(scheduledEvent(medicineSkippable = false, globalCannotSkip = false)).visibleButtons

        assertFalse(Button.SKIPPED in buttons, "SKIPPED must hide when medicine cannot be skipped")
    }

    @Test
    fun `scheduled dose hides skipped when global forbids`() {
        val buttons = ScheduledReminderActions(scheduledEvent(medicineSkippable = true, globalCannotSkip = true)).visibleButtons

        assertFalse(Button.SKIPPED in buttons, "global preference hides SKIPPED everywhere (f6ba5fd0)")
    }

    @Test
    fun `past event shows skipped when event allows and global allows`() {
        val buttons = ReminderEventActions(pastEvent(eventSkippable = true, globalCannotSkip = false)).visibleButtons

        assertTrue(Button.SKIPPED in buttons, "SKIPPED must show when both toggles are off (#1686)")
    }

    @Test
    fun `past event hides skipped when event forbids`() {
        val buttons = ReminderEventActions(pastEvent(eventSkippable = false, globalCannotSkip = false)).visibleButtons

        assertFalse(Button.SKIPPED in buttons, "SKIPPED must hide when event cannot be skipped")
    }

    @Test
    fun `past event hides skipped when global forbids`() {
        val buttons = ReminderEventActions(pastEvent(eventSkippable = true, globalCannotSkip = true)).visibleButtons

        assertFalse(Button.SKIPPED in buttons, "global preference hides SKIPPED everywhere (f6ba5fd0)")
    }
}
