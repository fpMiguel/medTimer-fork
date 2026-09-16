package com.futsch1.medtimer.feature.reminders.widgets

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.futsch1.medtimer.core.common.LogTags
import com.futsch1.medtimer.core.common.di.ApplicationScope
import com.futsch1.medtimer.core.common.di.Dispatcher
import com.futsch1.medtimer.core.common.di.MedTimerDispatchers
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class WidgetUpdateReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetUpdateReceiverEntryPoint {
        fun nextRemindersWidgetProvider(): NextRemindersWidgetProvider
        fun latestRemindersWidgetProvider(): LatestRemindersWidgetProvider
        @ApplicationScope fun applicationScope(): CoroutineScope
        @Dispatcher(MedTimerDispatchers.IO) fun ioDispatcher(): CoroutineDispatcher
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context?.applicationContext ?: context
        if (appContext == null) {
            Log.w(LogTags.WIDGET, "WidgetUpdateReceiver: null context, skipping update")
            pendingResult.finish()
            return
        }
        val entryPoint: WidgetUpdateReceiverEntryPoint = try {
            EntryPointAccessors.fromApplication(appContext, WidgetUpdateReceiverEntryPoint::class.java)
        } catch (e: IllegalStateException) {
            Log.w(LogTags.WIDGET, "WidgetUpdateReceiver: Hilt component not ready, skipping widget update", e)
            pendingResult.finish()
            return
        } catch (e: Exception) {
            Log.w(LogTags.WIDGET, "WidgetUpdateReceiver: unexpected entry point failure, skipping", e)
            pendingResult.finish()
            return
        }
        val nextRemindersWidgetProvider = entryPoint.nextRemindersWidgetProvider()
        val latestRemindersWidgetProvider = entryPoint.latestRemindersWidgetProvider()
        val applicationScope = entryPoint.applicationScope()
        val ioDispatcher = entryPoint.ioDispatcher()
        applicationScope.launch(ioDispatcher) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)

                val appWidgetIdsNextReminders = appWidgetManager.getAppWidgetIds(
                    ComponentName(
                        context!!,
                        NextRemindersWidgetProvider::class.java
                    )
                )
                performWidgetUpdate(
                    nextRemindersWidgetProvider.getWidgetImpl(context), appWidgetIdsNextReminders, appWidgetManager
                )

                val appWidgetIdsLatestReminders = appWidgetManager.getAppWidgetIds(
                    ComponentName(
                        context,
                        LatestRemindersWidgetProvider::class.java
                    )
                )
                performWidgetUpdate(
                    latestRemindersWidgetProvider.getWidgetImpl(context), appWidgetIdsLatestReminders, appWidgetManager
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}