package com.futsch1.medtimer.feature.reminders

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.util.Log
import com.futsch1.medtimer.core.common.LogTags
import com.futsch1.medtimer.core.datastore.PersistentDataDataSource
import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.feature.reminders.notificationData.ReminderNotification
import com.futsch1.medtimer.feature.reminders.notificationData.effectiveShowAsAlarm
import com.futsch1.medtimer.feature.reminders.notificationData.shouldReplaceAlarm
import com.futsch1.medtimer.feature.reminders.notificationFactory.BigReminderNotificationFactory
import com.futsch1.medtimer.feature.reminders.notificationFactory.ExpirationDateNotificationFactory
import com.futsch1.medtimer.feature.reminders.notificationFactory.OutOfStockNotificationFactory
import com.futsch1.medtimer.feature.reminders.notificationFactory.SimpleReminderNotificationFactory
import javax.inject.Inject

internal object NotificationTransactionLock {
    val lock = Any()
}

@SuppressLint("DefaultLocale")
class Notifications @Inject constructor(
    private val notificationSoundManager: NotificationSoundManager,
    private val notificationManager: NotificationManager,
    private val simpleReminderNotificationFactory: SimpleReminderNotificationFactory.Factory,
    private val bigReminderNotificationFactory: BigReminderNotificationFactory.Factory,
    private val outOfStockNotificationFactory: OutOfStockNotificationFactory.Factory,
    private val expirationDateNotificationFactory: ExpirationDateNotificationFactory.Factory,
    private val preferencesDataSource: PreferencesDataSource,
    private val persistentDataDataSource: PersistentDataDataSource,
    private val alarmScreenRepository: AlarmScreenRepository
) {
    fun showNotification(reminderNotification: ReminderNotification, notificationId: Int = -1): Int =
        synchronized(NotificationTransactionLock.lock) {
            var actualNotificationId = notificationId
            if (actualNotificationId == -1) {
                actualNotificationId = this.nextNotificationId
            }
            reminderNotification.reminderNotificationData.notificationId = actualNotificationId
            val retainedAlarmId = alarmScreenRepository.currentAlarm.value?.notificationId
            if (notificationId != -1 && retainedAlarmId != null &&
                retainedAlarmId != actualNotificationId &&
                !shouldReplaceAlarm(retainedAlarmId, actualNotificationId)
            ) {
                // A same-ID update raced with a newer alarm. Do not resurrect the old notification
                // under its old ID, even when the reduced payload is now normal-only.
                notificationManager.cancel(actualNotificationId)
                return@synchronized actualNotificationId
            }

            // The factory is also responsible for stamping showAsAlarm, but the holder decision
            // must happen before factory construction because constructing an FSI factory updates
            // the shared PendingIntent. Re-stamp here so a stale serialized true value cannot
            // publish a normal post as an alarm.
            val isAlarmPost = !reminderNotification.isOutOfStockNotification() &&
                !reminderNotification.isExpirationDateNotification() &&
                reminderNotification.reminderNotificationParts.any { it.effectiveShowAsAlarm() }
            reminderNotification.reminderNotificationData.showAsAlarm = isAlarmPost
            if (isAlarmPost && !alarmScreenRepository.publish(reminderNotification.reminderNotificationData)) {
                // A stale explicit same-ID/reduced post must not update NotificationManager or
                // its shared FSI PendingIntent after a newer alarm has won the holder.
                notificationManager.cancel(actualNotificationId)
                return@synchronized actualNotificationId
            }

            val factory = try {
                when {
                    reminderNotification.isOutOfStockNotification() -> outOfStockNotificationFactory.create(reminderNotification)
                    reminderNotification.isExpirationDateNotification() -> expirationDateNotificationFactory.create(reminderNotification)
                    preferencesDataSource.preferences.value.bigNotifications -> bigReminderNotificationFactory.create(reminderNotification)
                    else -> simpleReminderNotificationFactory.create(reminderNotification)
                }
            } catch (error: Exception) {
                if (isAlarmPost) {
                    alarmScreenRepository.clearIfCurrent(reminderNotification.reminderNotificationData)
                }
                throw error
            }

            val notification = try {
                factory.create()
            } catch (error: Exception) {
                if (isAlarmPost) {
                    alarmScreenRepository.clearIfCurrent(reminderNotification.reminderNotificationData)
                }
                throw error
            }
            notify(actualNotificationId, notification)
            Log.d(
                LogTags.REMINDER,
                String.format("Show notification nID %d: %s", actualNotificationId, reminderNotification)
            )

            actualNotificationId
        }

    private val nextNotificationId: Int
        get() = persistentDataDataSource.getAndIncreaseNotificationId()

    private fun notify(notificationId: Int, notification: android.app.Notification) {
        notificationManager.notify(notificationId, notification)

        notificationSoundManager.restore()
    }
}
