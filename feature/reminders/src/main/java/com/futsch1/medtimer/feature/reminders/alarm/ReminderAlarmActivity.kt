package com.futsch1.medtimer.feature.reminders.alarm

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.futsch1.medtimer.core.common.LogTags
import com.futsch1.medtimer.core.datastore.PreferencesDataSource
import com.futsch1.medtimer.feature.reminders.AlarmScreenRepository
import com.futsch1.medtimer.feature.reminders.R
import com.futsch1.medtimer.feature.reminders.api.notificationData.ReminderNotificationData
import com.futsch1.medtimer.feature.reminders.api.notificationData.toReminderNotificationData
import com.futsch1.medtimer.feature.reminders.api.notificationData.writeTo
import com.futsch1.medtimer.feature.reminders.notificationData.shouldReplaceAlarm
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject


@AndroidEntryPoint
class ReminderAlarmActivity : AppCompatActivity() {

    // Single-threaded executor ensures buildMediaPlayer, startAlarm, and pauseAlarm
    // run sequentially, preventing concurrent MediaPlayer state transitions.
    private val alarmExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Inject
    lateinit var preferencesDataSource: PreferencesDataSource

    @Inject
    lateinit var alarmScreenRepository: AlarmScreenRepository

    @Inject
    lateinit var notificationManager: NotificationManager

    @Inject
    lateinit var vibrator: Vibrator

    @Inject
    lateinit var audioManager: AudioManager

    private var mediaPlayer: MediaPlayer? = null

    // Alarm currently requested to be displayed. Written and read on the main thread only;
    // used to dedupe holder emissions against what is already on screen.
    private var displayedAlarm: ReminderNotificationData? = null
    private var hasSeenPublishedAlarm = false

    private val alarmFragmentLifecycleCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fragmentManager: FragmentManager,
            fragment: Fragment,
            view: View,
            savedInstanceState: Bundle?
        ) {
            if (fragment is AlarmFragment &&
                supportFragmentManager.findFragmentById(R.id.alarmFragmentContainer) === fragment
            ) {
                // The marker means rendered, not merely queued for a stopped activity.
                displayedAlarm = fragment.reminderNotificationData
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val windowInsetsController =
            WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())

        setContentView(R.layout.activity_alarm)
        supportFragmentManager.registerFragmentLifecycleCallbacks(alarmFragmentLifecycleCallbacks, true)

        // Prefer the retained holder during cold start/recreation, unless the launching intent is
        // strictly newer. Equal IDs deliberately keep the holder because a queued equal-ID intent
        // may carry an older reduced/unreduced payload.
        val retainedAlarm = alarmScreenRepository.currentAlarm.value
        val intentAlarm = readAlarmData(intent)?.takeIf { it.valid }
        val publishedAlarm = readPublishedAlarmFromNotifications()
        val fallbackAlarm = listOfNotNull(publishedAlarm, intentAlarm).reduceOrNull { current, candidate ->
            if (candidate.notificationId != current.notificationId &&
                shouldReplaceAlarm(current.notificationId, candidate.notificationId)
            ) {
                candidate
            } else {
                current
            }
        }
        val initialAlarm = when {
            retainedAlarm == null -> fallbackAlarm
            intentAlarm == null -> retainedAlarm
            intentAlarm.notificationId != retainedAlarm.notificationId &&
                shouldReplaceAlarm(retainedAlarm.notificationId, intentAlarm.notificationId) -> {
                alarmScreenRepository.publish(intentAlarm)
                intentAlarm
            }
            else -> retainedAlarm
        }
        if (retainedAlarm == null && fallbackAlarm?.showAsAlarm == true) {
            alarmScreenRepository.publish(fallbackAlarm)
        }
        hasSeenPublishedAlarm = retainedAlarm != null || fallbackAlarm?.showAsAlarm == true
        addAlarmFragment(initialAlarm)

        // Follow the app-wide latest-alarm holder (replay=1 StateFlow). The initial emission
        // replays the retained alarm after process death/recreation; later emissions push
        // newly posted alarms while the screen is up (Main is lifecycleScope's default).
        lifecycleScope.launch {
            alarmScreenRepository.currentAlarm.collect { candidate ->
                if (candidate == null) {
                    // The initial null is a process with no retained alarm. A later null is an
                    // explicit holder invalidation and must close the stale alarm task.
                    if (hasSeenPublishedAlarm && !isFinishing) {
                        finishAndRemoveTask()
                    }
                    return@collect
                }
                hasSeenPublishedAlarm = true
                if (isCurrentlyDisplayed(candidate)) {
                    // Exact alarm already on screen.
                    return@collect
                }
                // Newer/different alarm: replace the displayed fragment with the holder's data.
                addAlarmFragment(candidate)
            }
        }

        lifecycleScope.launch(alarmExecutor) {
            buildMediaPlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasSeenPublishedAlarm && alarmScreenRepository.currentAlarm.value == null) {
            finishAndRemoveTask()
            return
        }
        // Why: Reconcile pending holder swaps that weren't visible while stopped.
        // How: Call reconcileFromHolder to sync displayed alarm from holder.
        reconcileFromHolder()
        lifecycleScope.launch(alarmExecutor) {
            startAlarm()
        }
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch(alarmExecutor) {
            pauseAlarm()
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            displayedAlarm?.let(alarmScreenRepository::clearIfCurrent)
        }
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(alarmFragmentLifecycleCallbacks)
        super.onDestroy()
        (alarmExecutor.executor as ExecutorService).awaitTermination(1, TimeUnit.SECONDS)
        releaseMediaPlayer()
        Log.d(LogTags.ALARM, "Destroyed alarm activity")
    }

    private fun buildMediaPlayer() {
        val audioContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            createAttributionContext("audioPlayback")
        } else {
            this@ReminderAlarmActivity
        }
        val tmpMediaPlayer = MediaPlayer.create(
            audioContext,
            preferencesDataSource.preferences.value.alarmRingtone ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
            null,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            0
        ) ?: MediaPlayer.create(
            audioContext,
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            null,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            0
        )
        if (tmpMediaPlayer != null) {
            tmpMediaPlayer.isLooping = true
            mediaPlayer = tmpMediaPlayer
        } else {
            Log.w(LogTags.ALARM, "Failed to create media player")
        }
    }

    private fun startAlarm() {
        Log.d(LogTags.ALARM, "Executing startAlarm job")

        if (shallPlayAlarm()) {
            playAlarmTone()
        }

        if (shallVibrate()) {
            vibrate()
        }
    }

    private fun pauseAlarm() {
        Log.d(LogTags.ALARM, "Executing pauseAlarm job")

        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (_: IllegalStateException) {
            // MediaPlayer was not initialized or already released; pauseAlarm() is best-effort — safe to ignore
        }

        vibrator.cancel()
    }

    private fun releaseMediaPlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
        Log.d(LogTags.ALARM, "Released media player")
    }

    private fun vibrate() {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(500, 500), 0))
    }

    private fun playAlarmTone() {
        mediaPlayer?.start()
    }

    private fun shallPlayAlarm(): Boolean {
        return combinePreferenceAndRingerMode(preferencesDataSource.preferences.value.noAlarmSoundWhenSilent)
    }

    private fun shallVibrate(): Boolean {
        return combinePreferenceAndRingerMode(preferencesDataSource.preferences.value.noVibrationWhenSilent)
    }

    private fun combinePreferenceAndRingerMode(preferenceValue: Boolean): Boolean {
        if (preferenceValue) {
            // If the silent mode is active, do not ring the alarm
            return audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT
        }
        return true
    }

    private fun readAlarmData(intent: Intent?): ReminderNotificationData? =
        intent?.extras?.let { extras ->
            runCatching { extras.toReminderNotificationData() }.getOrNull()
        }

    /**
     * Reconstructs the newest alarm payload after process death. The repository is intentionally
     * in-memory, while the active notification remains the durable source for a posted alarm.
     */
    private fun readPublishedAlarmFromNotifications(): ReminderNotificationData? {
        var latest: ReminderNotificationData? = null
        notificationManager.activeNotifications.forEach { status ->
            val data = status.notification.extras.toReminderNotificationData().apply {
                notificationId = status.id
            }
            val previous = latest
            if (data.valid && data.showAsAlarm &&
                (previous == null || shouldReplaceAlarm(previous.notificationId, data.notificationId))
            ) {
                latest = data
            }
        }
        return latest
    }

    /**
     * Single funnel for all display paths. The lifecycle callback records the marker only after
     * the fragment view exists, so a transaction queued while STOPPED cannot suppress recovery.
     */
    private fun addAlarmFragment(data: ReminderNotificationData?) {
        if (data == null) {
            return
        }
        Log.d(LogTags.ALARM, "Adding alarm fragment")
        supportFragmentManager.beginTransaction()
            .replace(R.id.alarmFragmentContainer, AlarmFragment::class.java, buildArguments(data))
            .commitAllowingStateLoss()
    }

    /**
     * Dedupe holder emissions against the fragment that has actually rendered.
     */
    private fun isCurrentlyDisplayed(candidate: ReminderNotificationData): Boolean {
        val displayed = displayedAlarm ?: return false
        return displayed.notificationId == candidate.notificationId &&
            displayed.reminderEventIds == candidate.reminderEventIds
    }

    /**
     * SINGLE_TOP delivers a second alarm without recreation. The holder is authoritative; an
     * equal-ID intent is deliberately ignored because equal IDs can carry an older reduced/unreduced
     * payload. A strictly newer intent is adopted into the holder before it is displayed.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        reconcileFromHolder()

        val incoming = readAlarmData(intent)?.takeIf { it.valid } ?: return
        val currentId = alarmScreenRepository.currentAlarm.value?.notificationId
        if (currentId == null && hasSeenPublishedAlarm) {
            // The activity has already displayed an alarm and its holder was explicitly cleared;
            // a queued redelivery must not resurrect the terminal payload.
            return
        }
        if (currentId != null &&
            (incoming.notificationId == currentId || !shouldReplaceAlarm(currentId, incoming.notificationId))
        ) {
            return
        }

        if (currentId == null) {
            if (incoming.showAsAlarm && !alarmScreenRepository.publish(incoming)) {
                return
            }
        } else if (!alarmScreenRepository.publish(incoming)) {
            return
        }

        setIntent(intent)
        addAlarmFragment(incoming)
    }

    /**
     * Sync trigger for onResume/onNewIntent when collector has not rendered yet.
     */
    private fun reconcileFromHolder() {
        val candidate = alarmScreenRepository.currentAlarm.value ?: return
        if (isCurrentlyDisplayed(candidate)) {
            return
        }
        addAlarmFragment(candidate)
    }

    companion object {
        /**
         * Why: Keep intent and holder paths symmetric.
         * How: Shared builder via writeTo for both getIntent and holder replace.
         */
        fun buildArguments(reminderNotificationData: ReminderNotificationData): Bundle =
            Bundle().apply { reminderNotificationData.writeTo(this) }

        fun getIntent(
            context: Context,
            reminderNotificationData: ReminderNotificationData
        ): Intent {
            val intent = Intent(context, ReminderAlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            intent.putExtras(buildArguments(reminderNotificationData))
            return intent
        }
    }
}
