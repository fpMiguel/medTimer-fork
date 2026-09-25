package com.futsch1.medtimer.processortests

import android.app.Notification
import android.app.NotificationManager
import com.futsch1.medtimer.core.datastore.PersistentDataDataSource
import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.core.domain.model.Medicine
import com.futsch1.medtimer.core.domain.model.Reminder
import com.futsch1.medtimer.core.domain.model.ReminderEvent
import com.futsch1.medtimer.core.domain.model.UserPreferences
import com.futsch1.medtimer.feature.reminders.AlarmScreenRepository
import com.futsch1.medtimer.feature.reminders.NotificationSoundManager
import com.futsch1.medtimer.feature.reminders.Notifications
import com.futsch1.medtimer.feature.reminders.api.notificationData.ReminderNotificationData
import com.futsch1.medtimer.feature.reminders.notificationData.ReminderNotification
import com.futsch1.medtimer.feature.reminders.notificationData.ReminderNotificationPart
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant

class NotificationsChokePointTest {

    private fun createNotifications(
        alarmScreenRepository: AlarmScreenRepository = mock(),
        notificationManager: NotificationManager = mock(),
        notificationSoundManager: NotificationSoundManager = mock(),
        persistentDataDataSource: PersistentDataDataSource = mock(),
        preferencesDataSource: PreferencesDataSource = mock()
    ): Triple<Notifications, AlarmScreenRepository, NotificationManager> {
        // Preferences: bigNotifications = false so simple factory is chosen
        val prefs = UserPreferences.default().copy(bigNotifications = false)
        whenever(preferencesDataSource.preferences).thenReturn(MutableStateFlow(prefs))
        whenever(persistentDataDataSource.getAndIncreaseNotificationId()).thenReturn(100)
        whenever(alarmScreenRepository.currentAlarm).thenReturn(MutableStateFlow(null))
        whenever(alarmScreenRepository.publish(any())).thenReturn(true)

        val mockNotification = mock<Notification>()

        val simpleFactory = mock<com.futsch1.medtimer.feature.reminders.notificationFactory.SimpleReminderNotificationFactory.Factory>()
        val simpleMock = mock<com.futsch1.medtimer.feature.reminders.notificationFactory.SimpleReminderNotificationFactory>()
        whenever(simpleMock.create()).thenReturn(mockNotification)
        whenever(simpleFactory.create(any())).thenReturn(simpleMock)

        val bigFactory = mock<com.futsch1.medtimer.feature.reminders.notificationFactory.BigReminderNotificationFactory.Factory>()
        val bigMock = mock<com.futsch1.medtimer.feature.reminders.notificationFactory.BigReminderNotificationFactory>()
        whenever(bigMock.create()).thenReturn(mockNotification)
        whenever(bigFactory.create(any())).thenReturn(bigMock)

        val outOfStockFactory = mock<com.futsch1.medtimer.feature.reminders.notificationFactory.OutOfStockNotificationFactory.Factory>()
        val oosMock = mock<com.futsch1.medtimer.feature.reminders.notificationFactory.OutOfStockNotificationFactory>()
        whenever(oosMock.create()).thenReturn(mockNotification)
        whenever(outOfStockFactory.create(any())).thenReturn(oosMock)

        val expirationFactory = mock<com.futsch1.medtimer.feature.reminders.notificationFactory.ExpirationDateNotificationFactory.Factory>()
        val expMock = mock<com.futsch1.medtimer.feature.reminders.notificationFactory.ExpirationDateNotificationFactory>()
        whenever(expMock.create()).thenReturn(mockNotification)
        whenever(expirationFactory.create(any())).thenReturn(expMock)

        val notifications = Notifications(
            notificationSoundManager = notificationSoundManager,
            notificationManager = notificationManager,
            simpleReminderNotificationFactory = simpleFactory,
            bigReminderNotificationFactory = bigFactory,
            outOfStockNotificationFactory = outOfStockFactory,
            expirationDateNotificationFactory = expirationFactory,
            preferencesDataSource = preferencesDataSource,
            persistentDataDataSource = persistentDataDataSource,
            alarmScreenRepository = alarmScreenRepository
        )
        return Triple(notifications, alarmScreenRepository, notificationManager)
    }

    private fun stampedData(id: Int = 1): ReminderNotificationData {
        return ReminderNotificationData(
            remindInstant = Instant.now(),
            reminderIds = listOf(1),
            reminderEventIds = listOf(10),
            notificationId = -1,
            showAsAlarm = true
        )
    }

    private fun stampedNotification(data: ReminderNotificationData): ReminderNotification =
        ReminderNotification(
            listOf(
                ReminderNotificationPart(
                    reminder = Reminder.default().copy(
                        notificationImportance = Reminder.NotificationImportance.HIGH_AND_ALARM
                    ),
                    reminderEvent = ReminderEvent.default(),
                    medicine = Medicine.default()
                )
            ),
            data
        )

    private fun normalNotification(data: ReminderNotificationData): ReminderNotification =
        ReminderNotification(
            listOf(
                ReminderNotificationPart(
                    reminder = Reminder.default(),
                    reminderEvent = ReminderEvent.default(),
                    medicine = Medicine.default()
                )
            ),
            data
        )

    private fun unstampedData(id: Int = 1): ReminderNotificationData {
        return ReminderNotificationData(
            remindInstant = Instant.now(),
            reminderIds = listOf(2),
            reminderEventIds = listOf(20),
            notificationId = -1,
            showAsAlarm = false
        )
    }

    @Test
    fun stampedNotification_callsSwapBeforeNotify() {
        val alarmRepo: AlarmScreenRepository = mock()
        val notifManager: NotificationManager = mock()
        val (notifications, _, _) = createNotifications(alarmScreenRepository = alarmRepo, notificationManager = notifManager)

        val data = stampedData()
        val reminderNotification = stampedNotification(data)

        notifications.showNotification(reminderNotification)

        // must publish the exact same data instance (with notificationId now assigned)
        verify(alarmRepo).publish(data)
        // and still post the notification
        verify(notifManager).notify(eq(100), any())
    }

    @Test
    fun unstampedNotification_neverTouchesHolder() {
        val alarmRepo: AlarmScreenRepository = mock()
        val notifManager: NotificationManager = mock()
        val (notifications, _, _) = createNotifications(alarmScreenRepository = alarmRepo, notificationManager = notifManager)

        val data = unstampedData()
        val reminderNotification = ReminderNotification(emptyList(), data)

        notifications.showNotification(reminderNotification)

        verify(alarmRepo, never()).publish(any())
        verify(notifManager).notify(eq(100), any())
    }

    @Test
    fun chokePoint_isSingleWriter_publishOrderIsBeforeNotify() {
        val alarmRepo: AlarmScreenRepository = mock()
        val notifManager: NotificationManager = mock()
        val (notifications, _, _) = createNotifications(alarmScreenRepository = alarmRepo, notificationManager = notifManager)

        val data = stampedData()
        val rn = stampedNotification(data)

        notifications.showNotification(rn)

        // InOrder verification: publish happens before notify
        val inOrder = org.mockito.Mockito.inOrder(alarmRepo, notifManager)
        inOrder.verify(alarmRepo).publish(data)
        inOrder.verify(notifManager).notify(eq(100), any())
    }

    @Test
    fun multiplePosts_onlyStampedEnterHolder() {
        val alarmRepo: AlarmScreenRepository = mock()
        val (notifications, _, _) = createNotifications(alarmScreenRepository = alarmRepo)

        val stamped = stampedNotification(stampedData())
        val unstamped = ReminderNotification(emptyList(), unstampedData())
        val stamped2 = stampedNotification(stampedData())

        notifications.showNotification(stamped)
        notifications.showNotification(unstamped)
        notifications.showNotification(stamped2)

        // exactly 2 publishs, for the 2 stamped posts
        verify(alarmRepo, org.mockito.kotlin.times(2)).publish(any())
        verify(alarmRepo).publish(stamped.reminderNotificationData)
        verify(alarmRepo).publish(stamped2.reminderNotificationData)
        verify(alarmRepo, never()).publish(unstamped.reminderNotificationData)
    }

    @Test
    fun serializedAlarmStamp_isReplacedByCurrentEffectiveSetting() {
        val alarmRepo: AlarmScreenRepository = mock()
        val notifManager: NotificationManager = mock()
        val (notifications, _, _) = createNotifications(
            alarmScreenRepository = alarmRepo,
            notificationManager = notifManager
        )
        val data = stampedData()

        notifications.showNotification(normalNotification(data))

        assertFalse(data.showAsAlarm)
        verify(alarmRepo, never()).publish(any())
        verify(notifManager).notify(eq(100), any())
    }

    @Test
    fun staleStampedPost_isCancelled_withoutUpdatingNotificationManager() {
        val alarmRepo: AlarmScreenRepository = mock()
        val notifManager: NotificationManager = mock()
        val (notifications, _, _) = createNotifications(
            alarmScreenRepository = alarmRepo,
            notificationManager = notifManager
        )
        whenever(alarmRepo.publish(any())).thenReturn(false)

        val data = stampedData()
        notifications.showNotification(stampedNotification(data))

        verify(alarmRepo).publish(data)
        verify(notifManager).cancel(100)
        verify(notifManager, never()).notify(any(), any())
    }

    @Test
    fun staleExplicitNormalPost_isCancelledAfterNewerAlarmWins() {
        val alarmRepo: AlarmScreenRepository = mock()
        val notifManager: NotificationManager = mock()
        val (notifications, _, _) = createNotifications(
            alarmScreenRepository = alarmRepo,
            notificationManager = notifManager
        )
        whenever(alarmRepo.currentAlarm).thenReturn(
            MutableStateFlow(
                ReminderNotificationData(
                    remindInstant = Instant.now(),
                    reminderIds = listOf(3),
                    reminderEventIds = listOf(30),
                    notificationId = 200,
                    showAsAlarm = true
                )
            )
        )

        notifications.showNotification(normalNotification(unstampedData()), notificationId = 100)

        verify(notifManager).cancel(100)
        verify(notifManager, never()).notify(any(), any())
    }

    @Test
    fun publishUsesAssignedNotificationId() {
        val alarmRepo: AlarmScreenRepository = mock()
        val persistent: PersistentDataDataSource = mock()
        whenever(persistent.getAndIncreaseNotificationId()).thenReturn(77)
        val prefs: PreferencesDataSource = mock()
        whenever(prefs.preferences).thenReturn(MutableStateFlow(UserPreferences.default().copy(bigNotifications = false)))

        val (notifications, _, _) = createNotifications(
            alarmScreenRepository = alarmRepo,
            persistentDataDataSource = persistent,
            preferencesDataSource = prefs
        )
        // re-stub after helper's default stub (100) to enforce 77 for this test
        whenever(persistent.getAndIncreaseNotificationId()).thenReturn(77)

        val data = stampedData()
        // before post, notificationId is -1
        assert(data.notificationId == -1)
        notifications.showNotification(stampedNotification(data))
        // after post, the same data object has been assigned the generated id
        verify(alarmRepo).publish(org.mockito.kotlin.check { publishped ->
            assert(publishped.notificationId == 77)
        })
    }
}
