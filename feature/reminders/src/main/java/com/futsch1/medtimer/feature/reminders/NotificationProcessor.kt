package com.futsch1.medtimer.feature.reminders

import android.app.NotificationManager
import android.util.Log
import com.futsch1.medtimer.core.common.LogTags
import com.futsch1.medtimer.core.common.helpers.MedicineHelper
import com.futsch1.medtimer.core.common.time.TimeAccess
import com.futsch1.medtimer.core.datastore.PersistentDataDataSource
import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.core.domain.model.ReminderEvent
import com.futsch1.medtimer.core.domain.model.ReminderType
import com.futsch1.medtimer.core.domain.repository.ReminderEventRepository
import com.futsch1.medtimer.core.domain.repository.ReminderRepository
import com.futsch1.medtimer.feature.reminders.api.notificationData.ReminderNotificationData
import com.futsch1.medtimer.feature.reminders.notificationData.ReminderNotificationFactory
import com.futsch1.medtimer.feature.reminders.api.notificationData.toReminderNotificationData
import java.time.Instant
import javax.inject.Inject

/**
 * Processes actions related to medicine reminder notifications, such as marking medications
 * as taken or skipped, updating the database, and managing notification states.
 *
 * This class handles:
 * - Updating [ReminderEvent] statuses and processing stock management.
 * - Modifying or canceling active notifications when reminder events are processed.
 * - Triggering UI for variable dosage amounts.
 * - Scheduling follow-up notifications or rescheduling after status changes.

 */
class NotificationProcessor @Inject constructor(
    private val alarmProcessor: AlarmProcessor,
    private val notifications: Notifications,
    private val alarmScreenRepository: AlarmScreenRepository,
    private val stockHandlingProcessor: StockHandlingProcessor,
    private val repeatProcessor: RepeatProcessor,
    private val notificationManager: NotificationManager,
    private val reminderNotificationFactory: ReminderNotificationFactory,
    private val reminderRepository: ReminderRepository,
    private val reminderEventRepository: ReminderEventRepository,
    private val preferencesDataSource: PreferencesDataSource,
    private val persistentDataDataSource: PersistentDataDataSource,
    private val timeAccess: TimeAccess
) {
    suspend fun processReminderEventsInNotification(reminderEventIds: List<Int>, status: ReminderEvent.ReminderStatus) {
        Log.d(LogTags.REMINDER, "Process reminder events in notification $reminderEventIds")
        val reminderEventsToUpdate = mutableListOf<ReminderEvent>()
        for (reminderEventId in reminderEventIds) {
            val reminderEvent = reminderEventRepository.fetch(reminderEventId)

            if (reminderEvent != null) {
                reminderEventsToUpdate.add(reminderEvent)
            } else {
                Log.e(LogTags.REMINDER, "Could not find reminder event reID $reminderEventId in database")
            }
        }

        setReminderEventStatus(status, reminderEventsToUpdate)

    }

    fun cancelNotification(notificationId: Int) {
        synchronized(NotificationTransactionLock.lock) {
            Log.d(LogTags.REMINDER, "Cancel notification nID $notificationId")
            notificationManager.cancel(notificationId)
            alarmScreenRepository.clear(notificationId)
        }
    }

    fun cancelNotification(reminderNotificationData: ReminderNotificationData) {
        synchronized(NotificationTransactionLock.lock) {
            Log.d(LogTags.REMINDER, "Cancel notification nID ${reminderNotificationData.notificationId}")
            notificationManager.cancel(reminderNotificationData.notificationId)
            alarmScreenRepository.clearIfCurrent(reminderNotificationData)
        }
    }

    suspend fun removeRemindersFromNotification(reminderEvents: List<ReminderEvent>) {
        val notificationId = reminderEvents.firstOrNull()?.notificationId ?: return
        if (notificationId != -1) {
            removeRemindersFromNotification(notificationId, reminderEvents.map { it.reminderEventId })
        }
    }

    suspend fun removeRemindersFromNotification(notificationId: Int, reminderEventIds: List<Int>) {
        val expectedData = synchronized(NotificationTransactionLock.lock) {
            Log.d(LogTags.REMINDER, "Remove reminders from notification nID $notificationId")
            notificationManager.activeNotifications
                .firstOrNull { it.id == notificationId }
                ?.notification
                ?.extras
                ?.toReminderNotificationData()
                ?.apply { this.notificationId = notificationId }
        } ?: return

        Log.d(LogTags.REMINDER, "Remove reIDs $reminderEventIds from notification nID $notificationId")
        updateNotification(expectedData, reminderEventIds)
    }

    private suspend fun updateNotification(
        expectedData: ReminderNotificationData,
        reminderEventIds: List<Int>
    ) {
        val newReminderNotificationData = expectedData.removeReminderEventIds(reminderEventIds)
        val reminderNotification = reminderNotificationFactory.create(newReminderNotificationData)
        var postedData: ReminderNotificationData? = null

        synchronized(NotificationTransactionLock.lock) {
            val currentData = notificationManager.activeNotifications
                .firstOrNull { it.id == expectedData.notificationId }
                ?.notification
                ?.extras
                ?.toReminderNotificationData()
                ?.apply { this.notificationId = expectedData.notificationId }
            if (currentData == null || !sameNotificationPayload(currentData, expectedData)) {
                return@synchronized
            }

            if (reminderNotification != null) {
                if (!reminderNotification.reminderNotificationData.showAsAlarm) {
                    // A same-ID reduction can turn the remaining notification into a normal post.
                    // Clear only the payload we read; a newer holder must win.
                    alarmScreenRepository.clearIfCurrent(expectedData)
                }
                notifications.showNotification(reminderNotification, expectedData.notificationId)
                postedData = newReminderNotificationData
            } else {
                cancelNotificationLocked(expectedData)
            }
        }

        postedData?.let { rescheduleRepeat(it) }
    }

    private fun cancelNotificationLocked(reminderNotificationData: ReminderNotificationData) {
        notificationManager.cancel(reminderNotificationData.notificationId)
        alarmScreenRepository.clearIfCurrent(reminderNotificationData)
    }

    private fun sameNotificationPayload(
        first: ReminderNotificationData,
        second: ReminderNotificationData
    ): Boolean =
        first.notificationId == second.notificationId &&
            first.reminderIds == second.reminderIds &&
            first.reminderEventIds == second.reminderEventIds &&
            first.remindInstant == second.remindInstant

    private suspend fun rescheduleRepeat(reminderNotificationData: ReminderNotificationData) {
        val preferences = preferencesDataSource.preferences.value
        if (!preferences.repeatReminders) {
            return
        }

        val remainingRepeats = reminderEventRepository.fetch(reminderNotificationData.reminderEventIds[0])
            ?.remainingRepeats ?: return

        if (remainingRepeats != 0) {
            repeatProcessor.processRepeat(
                reminderNotificationData,
                preferences.repeatDelay
            )
        }
    }

    suspend fun setReminderEventStatus(status: ReminderEvent.ReminderStatus, reminderEvents: List<ReminderEvent>) {
        val processedTime = timeAccess.now()

        val reminderEvents = reminderEvents.map { reminderEvent ->
            val status =
                if (reminderEvent.reminderType == ReminderType.OUT_OF_STOCK || reminderEvent.reminderType == ReminderType.EXPIRATION_DATE) {
                    ReminderEvent.ReminderStatus.ACKNOWLEDGED
                } else {
                    status
                }
            Log.i(
                LogTags.REMINDER, String.format(
                    "%s reminder reID %d for %s (%s)",
                    status.toString(),
                    reminderEvent.reminderEventId,
                    reminderEvent.medicineName,
                    reminderEvent.amount
                )
            )
            alarmProcessor.cancelPendingReminderNotifications(reminderEvent.reminderEventId)
            val stockResult = doStockHandling(status, reminderEvent, processedTime)
            reminderEvent.copy(
                status = status,
                processedTimestamp = processedTime,
                stockHandled = stockResult.stockHandled,
                stockAfter = when {
                    stockResult.stockChange != null && reminderEvent.stockBefore >= 0 -> stockResult.stockChange.after
                    status == ReminderEvent.ReminderStatus.SKIPPED -> reminderEvent.stockBefore
                    else -> reminderEvent.stockAfter
                }
            )
        }

        persistentDataDataSource.removePendingLocationSnoozesForReminderEventIds(reminderEvents.map { it.reminderEventId })
        reminderEventRepository.updateAll(reminderEvents)
        removeRemindersFromNotification(reminderEvents)
    }

    private data class StockHandlingResult(val stockHandled: Boolean, val stockChange: StockChange?)

    private suspend fun doStockHandling(status: ReminderEvent.ReminderStatus, reminderEvent: ReminderEvent, processedTime: Instant): StockHandlingResult {
        if (!reminderEvent.stockHandled && status == ReminderEvent.ReminderStatus.TAKEN ||
            reminderEvent.stockHandled && status == ReminderEvent.ReminderStatus.SKIPPED
        ) {
            val reminder = reminderRepository.fetch(reminderEvent.reminderId) ?: return StockHandlingResult(false, null)
            var amount = MedicineHelper.parseAmount(reminderEvent.amount) ?: return StockHandlingResult(false, null)
            if (status == ReminderEvent.ReminderStatus.SKIPPED) {
                amount = -amount
            }
            val stockChange = stockHandlingProcessor.processStock(amount, reminder.medicineRelId, processedTime)
            return StockHandlingResult(status == ReminderEvent.ReminderStatus.TAKEN, stockChange)
        }
        return StockHandlingResult(status == ReminderEvent.ReminderStatus.TAKEN, null)
    }
}
