package com.futsch1.medtimer.processortests

import android.content.Intent
import android.os.Bundle
import com.futsch1.medtimer.core.common.ActivityCodes
import com.futsch1.medtimer.feature.reminders.AlarmScreenRepository
import com.futsch1.medtimer.feature.reminders.api.notificationData.ReminderNotificationData
import com.futsch1.medtimer.feature.reminders.api.notificationData.toReminderNotificationData
import com.futsch1.medtimer.feature.reminders.api.notificationData.writeTo
import com.futsch1.medtimer.feature.reminders.notificationData.shouldReplaceAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
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

    // --- Serialization round-trip + legacy-compat pins for showAsAlarm ---

    /* Pure JVM: Bundle/Intent are Mockito mocks backed by an in-memory map (same pattern as
     * TestReminderContext), so these tests are deterministic.
     */

    private fun alarmData(showAsAlarm: Boolean): ReminderNotificationData {
        return ReminderNotificationData(
            remindInstant = Instant.ofEpochSecond(42),
            reminderIds = listOf(3),
            reminderEventIds = listOf(4),
            notificationId = 5,
            showAsAlarm = showAsAlarm
        )
    }

    /** A Bundle mock whose put/get are actually backed by a map, unlike Android's stub jar. */
    private fun mapBackedBundle(): Pair<Bundle, MutableMap<String, Any?>> {
        val store = mutableMapOf<String, Any?>()
        val bundle = mock<Bundle> {
            on { putIntArray(any(), anyOrNull()) }.then { invocation ->
                store[invocation.getArgument(0)] = invocation.getArgument<IntArray?>(1); Unit
            }
            on { getIntArray(any()) }.then { invocation -> store[invocation.getArgument(0)] as IntArray? }
            on { putBoolean(any(), any()) }.then { invocation ->
                store[invocation.getArgument(0)] = invocation.getArgument<Boolean>(1); Unit
            }
            on { getBoolean(any(), any()) }.then { invocation ->
                val key: String = invocation.getArgument(0)
                val default: Boolean = invocation.getArgument(1)
                store[key] as? Boolean ?: default
            }
            on { putLong(any(), any()) }.then { invocation ->
                store[invocation.getArgument(0)] = invocation.getArgument<Long>(1); Unit
            }
            on { getLong(any()) }.then { invocation -> store[invocation.getArgument(0)] as? Long ?: 0L }
            on { putInt(any(), any()) }.then { invocation ->
                store[invocation.getArgument(0)] = invocation.getArgument<Int>(1); Unit
            }
            on { getInt(any(), any()) }.then { invocation ->
                val key: String = invocation.getArgument(0)
                val default: Int = invocation.getArgument(1)
                store[key] as? Int ?: default
            }
        }
        return bundle to store
    }

    private fun mapBackedIntent(extras: Bundle): Intent {
        val intent = mock<Intent>()
        `when`(intent.putExtra(any(), anyOrNull<IntArray>())).then { invocation ->
            extras.putIntArray(invocation.getArgument(0), invocation.getArgument(1)); intent
        }
        `when`(intent.putExtra(any(), any<Long>())).then { invocation ->
            extras.putLong(invocation.getArgument(0), invocation.getArgument(1)); intent
        }
        `when`(intent.putExtra(any(), any<Int>())).then { invocation ->
            extras.putInt(invocation.getArgument(0), invocation.getArgument(1)); intent
        }
        `when`(intent.putExtra(any(), any<Boolean>())).then { invocation ->
            extras.putBoolean(invocation.getArgument(0), invocation.getArgument(1)); intent
        }
        `when`(intent.extras).thenReturn(extras)
        return intent
    }

    // --- Legacy compat: pre-EXTRA_SHOW_AS_ALARM senders must deserialize as NOT alarm ---

    @Test
    fun legacyBundle_withoutShowAsAlarm_deserializesFalse() {
        val (bundle, store) = mapBackedBundle()
        // Simulate a sender from before EXTRA_SHOW_AS_ALARM existed: full payload, no alarm flag.
        store[ActivityCodes.EXTRA_REMINDER_ID_LIST] = intArrayOf(3)
        store[ActivityCodes.EXTRA_REMINDER_EVENT_ID_LIST] = intArrayOf(4)
        store[ActivityCodes.EXTRA_REMIND_INSTANT] = 42L
        store[ActivityCodes.EXTRA_NOTIFICATION_ID] = 5

        val result = bundle.toReminderNotificationData()

        assertFalse(result.showAsAlarm)
    }

    // --- Round-trip preservation ---

    @Test
    fun intentRoundTrip_truePreserved() {
        val original = alarmData(showAsAlarm = true)
        val (bundle, _) = mapBackedBundle()
        val intent = mapBackedIntent(bundle)

        original.writeTo(intent)
        val result = intent.extras!!.toReminderNotificationData()

        assertTrue(result.showAsAlarm)
        assertEquals(listOf(3), result.reminderIds)
        assertEquals(listOf(4), result.reminderEventIds)
        assertEquals(5, result.notificationId)
    }

    @Test
    fun bundleRoundTrip_truePreserved() {
        val original = alarmData(showAsAlarm = true)
        val bundle = mapBackedBundle().first

        original.writeTo(bundle)
        val result = bundle.toReminderNotificationData()

        assertTrue(result.showAsAlarm)
        assertEquals(listOf(3), result.reminderIds)
        assertEquals(listOf(4), result.reminderEventIds)
        assertEquals(5, result.notificationId)
    }

    @Test
    fun bundleRoundTrip_falsePreserved() {
        val original = alarmData(showAsAlarm = false)
        val bundle = mapBackedBundle().first

        original.writeTo(bundle)
        val result = bundle.toReminderNotificationData()

        assertFalse(result.showAsAlarm)
    }

    // --- Legacy re-stamp pinning ---

    /*
     * Source contract (ReminderNotificationFactory): when any part reports effectiveShowAsAlarm(),
     * the init block routes through addFullScreenIntent() which stamps
     * reminderNotificationData.showAsAlarm = true BEFORE the data reaches the codec, so a freshly
     * deserialized/constructed value of `false` cannot survive into an actual full-screen alarm.
     * ReminderNotificationFactory is abstract and depends on Context, PendingIntent and
     * NotificationCompat.Builder, so it cannot be exercised in a pure-JVM unit test; its FSI flow
     * (including this re-stamp) is verified by the instrumented AlarmSwitchConsistencyTest.
     *
     * What IS pinnable on the JVM: every non-factory construction path yields showAsAlarm=false,
     * meaning the factory stamping is the ONLY producer of `true` - if the stamp were removed,
     * no round-trip could ever carry `true`.
     */

    @Test
    fun legacyConstructionPaths_defaultShowAsAlarmFalse_onlyFactoryStampsTrue() {
        // Companion factories used for fresh notifications do not take showAsAlarm.
        val fromArrays = ReminderNotificationData.fromArrays(listOf(1), listOf(2), Instant.ofEpochSecond(7), 9)
        assertFalse(fromArrays.showAsAlarm)

        // Minimal legacy-style extras (only the ID present) also deserialize to false.
        val (bundle, store) = mapBackedBundle()
        store[ActivityCodes.EXTRA_NOTIFICATION_ID] = 11
        assertFalse(bundle.toReminderNotificationData().showAsAlarm)
    }
}
