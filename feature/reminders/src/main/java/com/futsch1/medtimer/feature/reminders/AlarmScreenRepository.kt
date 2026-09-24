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
        if (shouldReplaceAlarm(_currentAlarm.value?.notificationId, candidate.notificationId)) {
            _currentAlarm.value = candidate
            return true
        }
        return false
    }
}
