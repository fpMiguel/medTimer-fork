package com.futsch1.medtimer.processortests

import android.app.Notification
import android.app.NotificationManager
import com.futsch1.medtimer.core.datastore.PersistentDataDataSource
import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.core.domain.model.UserPreferences
import com.futsch1.medtimer.feature.reminders.AlarmScreenRepository
import com.futsch1.medtimer.feature.reminders.NotificationSoundManager
import com.futsch1.medtimer.feature.reminders.Notifications
import com.futsch1.medtimer.feature.reminders.api.notificationData.ReminderNotificationData
import com.futsch1.medtimer.feature.reminders.notificationData.ReminderNotification
import kotlinx.coroutines.flow.MutableStateFlow
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
        val reminderNotification = ReminderNotification(emptyList(), data)

        notifications.showNotification(reminderNotification)

        // must swap the exact same data instance (with notificationId now assigned)
        verify(alarmRepo).swap(data)
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

        verify(alarmRepo, never()).swap(any())
        verify(notifManager).notify(eq(100), any())
    }

    @Test
    fun chokePoint_isSingleWriter_swapOrderIsBeforeNotify() {
        val alarmRepo: AlarmScreenRepository = mock()
        val notifManager: NotificationManager = mock()
        val (notifications, _, _) = createNotifications(alarmScreenRepository = alarmRepo, notificationManager = notifManager)

        val data = stampedData()
        val rn = ReminderNotification(emptyList(), data)

        notifications.showNotification(rn)

        // InOrder verification: swap happens before notify
        val inOrder = org.mockito.Mockito.inOrder(alarmRepo, notifManager)
        inOrder.verify(alarmRepo).swap(data)
        inOrder.verify(notifManager).notify(eq(100), any())
    }

    @Test
    fun multiplePosts_onlyStampedEnterHolder() {
        val alarmRepo: AlarmScreenRepository = mock()
        val (notifications, _, _) = createNotifications(alarmScreenRepository = alarmRepo)

        val stamped = ReminderNotification(emptyList(), stampedData())
        val unstamped = ReminderNotification(emptyList(), unstampedData())
        val stamped2 = ReminderNotification(emptyList(), stampedData())

        notifications.showNotification(stamped)
        notifications.showNotification(unstamped)
        notifications.showNotification(stamped2)

        // exactly 2 swaps, for the 2 stamped posts
        verify(alarmRepo, org.mockito.kotlin.times(2)).swap(any())
        verify(alarmRepo).swap(stamped.reminderNotificationData)
        verify(alarmRepo).swap(stamped2.reminderNotificationData)
        verify(alarmRepo, never()).swap(unstamped.reminderNotificationData)
    }

    @Test
    fun swapUsesAssignedNotificationId() {
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
        notifications.showNotification(ReminderNotification(emptyList(), data))
        // after post, the same data object has been assigned the generated id
        verify(alarmRepo).swap(org.mockito.kotlin.check { swapped ->
            assert(swapped.notificationId == 77)
        })
    }
}
