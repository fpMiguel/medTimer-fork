package com.futsch1.medtimer.processortests

import com.futsch1.medtimer.core.datastore.PersistentDataDataSource
import com.futsch1.medtimer.core.location.GeofenceRegistrar
import com.futsch1.medtimer.feature.reminders.AlarmProcessor
import com.futsch1.medtimer.feature.reminders.NotificationProcessor
import com.futsch1.medtimer.feature.reminders.SnoozeProcessor
import com.futsch1.medtimer.feature.reminders.api.notificationData.ReminderNotificationData
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class SnoozeProcessorTest {

    private val alarmProcessor: AlarmProcessor = mock()
    private val notificationProcessor: NotificationProcessor = mock()
    private val persistentDataDataSource: PersistentDataDataSource = mock()
    private val geofenceRegistrar: GeofenceRegistrar = mock()

    private val snoozeProcessor = SnoozeProcessor(
        alarmProcessor,
        notificationProcessor,
        persistentDataDataSource,
        geofenceRegistrar
    )

    private fun sampleData(
        reminderIds: List<Int> = listOf(1),
        reminderEventIds: List<Int> = listOf(10),
        notificationId: Int = 42
    ): ReminderNotificationData {
        return ReminderNotificationData.fromArrays(
            reminderIds,
            reminderEventIds,
            Instant.now(),
            notificationId
        )
    }

    @Test
    fun processSnooze_setsRemindInstantToFutureAndSchedulesSecondaryAlarm() {
        val data = sampleData()
        val before = Instant.now()
        val snooze = 5.minutes

        snoozeProcessor.processSnooze(data, snooze)

        val after = Instant.now()

        // remindInstant must be before+5min .. after+5min
        val expectedLow = before.plusSeconds(snooze.inWholeSeconds)
        val expectedHigh = after.plusSeconds(snooze.inWholeSeconds)
        assertTrue(!data.remindInstant.isBefore(expectedLow), "remindInstant $data too early, expected >= $expectedLow")
        assertTrue(!data.remindInstant.isAfter(expectedHigh), "remindInstant $data too late, expected <= $expectedHigh")

        // delegates to AlarmProcessor and cancels original notification
        verify(alarmProcessor).setSecondaryAlarm(data)
        verify(notificationProcessor).cancelNotification(42)
    }

    @Test
    fun processSnooze_withCustomOneMinute_reschedulesCorrectly() {
        val data = sampleData()
        val before = Instant.now()

        snoozeProcessor.processSnooze(data, 1.minutes)

        val after = Instant.now()
        val low = before.plusSeconds(60)
        val high = after.plusSeconds(60)
        assertTrue(data.remindInstant.epochSecond in low.epochSecond..high.epochSecond)
        verify(alarmProcessor).setSecondaryAlarm(data)
        verify(notificationProcessor).cancelNotification(any())
    }

    @Test
    fun processSnooze_preservesNotificationIdAndEventIds() {
        val data = sampleData(reminderIds = listOf(1, 2), reminderEventIds = listOf(10, 20), notificationId = 7)
        snoozeProcessor.processSnooze(data, 2.minutes)

        // ids must be untouched, only remindInstant changes
        assertEquals(listOf(1, 2), data.reminderIds.toList())
        assertEquals(listOf(10, 20), data.reminderEventIds.toList())
        assertEquals(7, data.notificationId)

        val captor = argumentCaptor<ReminderNotificationData>()
        verify(alarmProcessor).setSecondaryAlarm(captor.capture())
        assertEquals(7, captor.firstValue.notificationId)
    }

    @Test
    fun processSnooze_doesNotDependOnWallClockWait() {
        val data = sampleData()
        val start = System.nanoTime()
        snoozeProcessor.processSnooze(data, 10.seconds)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // must be fast — no sleep, no wait
        assertTrue(elapsedMs < 500, "processSnooze took $elapsedMs ms, should be <500 ms (no wall-clock wait)")
        verify(alarmProcessor).setSecondaryAlarm(any())
    }

    @Test
    fun processLocationSnooze_addsPendingSnoozeAndCancels() {
        val data = sampleData(reminderIds = listOf(5), reminderEventIds = listOf(50), notificationId = 99)
        // set a stable remindInstant so toPendingSnooze is deterministic
        data.remindInstant = Instant.ofEpochSecond(1_000_000)

        snoozeProcessor.processLocationSnooze(data)

        // cancels the pending alarm for the event
        verify(alarmProcessor).cancelPendingReminderNotifications(data)
        // persists as pending location snooze
        verify(persistentDataDataSource).addPendingLocationSnooze(any())
        // registers geofence and cancels notification
        verify(geofenceRegistrar).registerHomeGeofence()
        verify(notificationProcessor).cancelNotification(99)
    }

    @Test
    fun processLocationSnooze_persistsCorrectMapping() {
        val data = sampleData(reminderIds = listOf(1), reminderEventIds = listOf(10), notificationId = 5)
        data.remindInstant = Instant.parse("2026-08-31T10:00:00Z")

        snoozeProcessor.processLocationSnooze(data)

        val captor = argumentCaptor<com.futsch1.medtimer.core.domain.model.PendingSnooze>()
        verify(persistentDataDataSource).addPendingLocationSnooze(captor.capture())
        val snooze = captor.firstValue
        assertEquals(listOf(1), snooze.reminderIds)
        assertEquals(listOf(10), snooze.reminderEventIds)
        assertEquals(5, snooze.notificationId)
        assertEquals(Instant.parse("2026-08-31T10:00:00Z"), snooze.remindInstant)
    }
}
