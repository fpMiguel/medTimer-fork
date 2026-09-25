package com.futsch1.medtimer.feature.reminders

import com.futsch1.medtimer.feature.reminders.api.notificationData.ReminderNotificationData
import com.futsch1.medtimer.feature.reminders.notificationData.shouldReplaceAlarm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmScreenRepository @Inject constructor() {
    private val _currentAlarm = MutableStateFlow<ReminderNotificationData?>(null)
    val currentAlarm: StateFlow<ReminderNotificationData?> = _currentAlarm.asStateFlow()

    /**
     * Publishes the latest alarm only if its notification post is at least as recent as the
     * currently displayed post. The check and update must be atomic because notifications can be
     * posted concurrently by reminder workers.
     */
    @Synchronized
    fun publish(candidate: ReminderNotificationData): Boolean {
        val snapshot = candidate.snapshot()
        if (shouldReplaceAlarm(_currentAlarm.value?.notificationId, snapshot.notificationId)) {
            _currentAlarm.value = snapshot
            return true
        }
        return false
    }

    /** Clears the holder only when [candidate] is still the payload that is displayed. */
    @Synchronized
    fun clearIfCurrent(candidate: ReminderNotificationData): Boolean {
        val current = _currentAlarm.value ?: return false
        if (!samePayload(current, candidate)) {
            return false
        }
        _currentAlarm.value = null
        return true
    }

    /** Clears a holder identified only by notification ID. */
    @Synchronized
    fun clear(notificationId: Int): Boolean {
        val current = _currentAlarm.value ?: return false
        if (current.notificationId != notificationId) {
            return false
        }
        _currentAlarm.value = null
        return true
    }

    // The scheduled instant may change during snooze/repeat processing; event identity is the
    // terminal-display identity.
    private fun samePayload(
        first: ReminderNotificationData,
        second: ReminderNotificationData
    ): Boolean =
        first.notificationId == second.notificationId &&
            first.reminderIds == second.reminderIds &&
            first.reminderEventIds == second.reminderEventIds
}
