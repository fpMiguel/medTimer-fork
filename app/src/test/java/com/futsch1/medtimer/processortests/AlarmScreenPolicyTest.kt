package com.futsch1.medtimer.processortests

import com.futsch1.medtimer.feature.reminders.AlarmScreenRepository
import com.futsch1.medtimer.feature.reminders.api.notificationData.ReminderNotificationData
import com.futsch1.medtimer.feature.reminders.notificationData.shouldReplaceAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AlarmScreenPolicyTest {

    private fun data(id: Int, instant: Instant = Instant.ofEpochSecond(0), reminderId: Int = 1, eventId: Int = 1): ReminderNotificationData {
        return ReminderNotificationData(
            remindInstant = instant,
            reminderIds = listOf(reminderId),
            reminderEventIds = listOf(eventId),
            notificationId = id,
            showAsAlarm = true
        )
    }

    // --- shouldReplaceAlarm truth table ---

    @Test
    fun shouldReplaceAlarm_nullCurrent_acceptsAny() {
        assertTrue(shouldReplaceAlarm(null, 0))
        assertTrue(shouldReplaceAlarm(null, 5))
        assertTrue(shouldReplaceAlarm(null, -1))
        assertTrue(shouldReplaceAlarm(null, Int.MAX_VALUE))
    }

    @Test
    fun shouldReplaceAlarm_newer_accepts() {
        assertTrue(shouldReplaceAlarm(5, 6))
        assertTrue(shouldReplaceAlarm(0, 1))
        assertTrue(shouldReplaceAlarm(5, 100))
    }

    @Test
    fun shouldReplaceAlarm_equalId_accepts_sameIdRepostRule() {
        assertTrue(shouldReplaceAlarm(5, 5))
        assertTrue(shouldReplaceAlarm(0, 0))
        assertTrue(shouldReplaceAlarm(7, 7))
    }

    @Test
    fun shouldReplaceAlarm_older_rejects() {
        assertFalse(shouldReplaceAlarm(5, 4))
        assertFalse(shouldReplaceAlarm(7, 5))
        assertFalse(shouldReplaceAlarm(1, 0))
        assertFalse(shouldReplaceAlarm(100, 99))
    }

    @Test
    fun shouldReplaceAlarm_remindInstant_irrelevant_olderInstantNewerId_accepted() {
        // older instant but newer ID -> must be accepted (ID wins, instant irrelevant)
        val current = data(id = 5, instant = Instant.ofEpochSecond(1000))
        val candidate = data(id = 7, instant = Instant.ofEpochSecond(0)) // older instant, newer id
        assertTrue(shouldReplaceAlarm(current.notificationId, candidate.notificationId))
        // Also via ReminderNotificationData objects: construct and check directly
        assertTrue(candidate.remindInstant.isBefore(current.remindInstant))
        assertTrue(candidate.notificationId > current.notificationId)
    }

    @Test
    fun shouldReplaceAlarm_remindInstant_irrelevant_newerInstantOlderId_rejected() {
        // newer instant but older ID -> must be rejected (ID wins, instant irrelevant)
        val current = data(id = 7, instant = Instant.ofEpochSecond(0))
        val candidate = data(id = 5, instant = Instant.ofEpochSecond(1000)) // newer instant, older id
        assertFalse(shouldReplaceAlarm(current.notificationId, candidate.notificationId))
        assertTrue(candidate.remindInstant.isAfter(current.remindInstant))
        assertTrue(candidate.notificationId < current.notificationId)
    }

    // --- AlarmScreenRepository.swap sequences ---

    @Test
    fun swap_doubleDeliveryInterleaving_staysOnNewest() {
        val repo = AlarmScreenRepository()
        val first = data(id = 7, reminderId = 1, eventId = 1)
        val second = data(id = 5, reminderId = 2, eventId = 2)

        assertTrue(repo.swap(first))
        assertEquals(7, repo.currentAlarm.value?.notificationId)

        // older ID must not replace newer
        assertFalse(repo.swap(second))
        assertEquals(7, repo.currentAlarm.value?.notificationId)
        // payload must still be first
        assertEquals(listOf(1), repo.currentAlarm.value?.reminderIds)
    }

    @Test
    fun swap_equalId_differentPayload_replacement_secondWins() {
        val repo = AlarmScreenRepository()
        val first = data(id = 5, reminderId = 1, eventId = 10)
        val second = data(id = 5, reminderId = 2, eventId = 20)

        assertTrue(repo.swap(first))
        assertEquals(listOf(1), repo.currentAlarm.value?.reminderIds)

        // equal ID with different payload must replace
        assertTrue(repo.swap(second))
        assertEquals(5, repo.currentAlarm.value?.notificationId)
        assertEquals(listOf(2), repo.currentAlarm.value?.reminderIds)
        assertEquals(listOf(20), repo.currentAlarm.value?.reminderEventIds)
    }

    @Test
    fun swap_retention_valuePresentWithNoCollector() {
        val repo = AlarmScreenRepository()
        val d = data(id = 3, reminderId = 9, eventId = 9)

        assertTrue(repo.swap(d))

        // No collector subscribed - value must still be present via direct access
        assertNotNull(repo.currentAlarm.value)
        assertEquals(3, repo.currentAlarm.value?.notificationId)
        assertEquals(listOf(9), repo.currentAlarm.value?.reminderIds)

        // Re-read without any flow collection - still retained
        assertEquals(3, repo.currentAlarm.value?.notificationId)
    }

    @Test
    fun swap_sequence_newerThenOlderThenEqual() {
        val repo = AlarmScreenRepository()
        assertTrue(repo.swap(data(id = 5)))
        assertTrue(repo.swap(data(id = 7)))
        assertEquals(7, repo.currentAlarm.value?.notificationId)
        assertFalse(repo.swap(data(id = 5)))
        assertEquals(7, repo.currentAlarm.value?.notificationId)
        // equal to current (7) with new payload should replace
        val equalPayload = data(id = 7, reminderId = 99, eventId = 99)
        assertTrue(repo.swap(equalPayload))
        assertEquals(listOf(99), repo.currentAlarm.value?.reminderIds)
    }
}
