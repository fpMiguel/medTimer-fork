package com.futsch1.medtimer.feature.reminders.alarm

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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.futsch1.medtimer.core.common.ActivityCodes
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
    lateinit var vibrator: Vibrator

    @Inject
    lateinit var audioManager: AudioManager

    private var mediaPlayer: MediaPlayer? = null

    // Alarm currently requested to be displayed. Written and read on the main thread only;
    // used to dedupe holder emissions against what is already on screen.
    private var displayedAlarm: ReminderNotificationData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val windowInsetsController =
            WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())

        setContentView(R.layout.activity_alarm)

        // Cold-start / recreation fallback: display whatever the launching intent carries.
        addAlarmFragment(intent)

        // Follow the app-wide latest-alarm holder (replay=1 StateFlow). The initial emission
        // replays the retained alarm after process death/recreation; later emissions push
        // newly posted alarms while the screen is up (Main is lifecycleScope's default).
        lifecycleScope.launch {
            alarmScreenRepository.currentAlarm.collect { candidate ->
                if (candidate == null) {
                    // Null holder value: initial StateFlow emission or process-death state.
                    // Never clear or rebuild the screen from null.
                    return@collect
                }
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

    /**
     * Why: Cold-start/recreation fallback and onNewIntent bootstrap when holder stale.
     * How: Parse intent extras and funnel through shared fragment replace with dedupe bookkeeping.
     */
    private fun addAlarmFragment(intent: Intent?) {
        val extras = intent?.extras ?: return
        addAlarmFragment(extras.toReminderNotificationData())
    }

    /**
     * Why: Single funnel for all display paths to keep dedupe consistent.
     * How: Replace fragment, record displayedAlarm; uses commitAllowingStateLoss (stopped paths).
     */
    private fun addAlarmFragment(data: ReminderNotificationData) {
        Log.d(LogTags.ALARM, "Adding alarm fragment")
        displayedAlarm = data
        // commitAllowingStateLoss instead of commit(): display paths can run while the
        // activity is stopped (holder collector / onNewIntent under a covering dialog or
        // the shade), where a plain commit() throws IllegalStateException checkStateLoss.
        // Fragment state loss here is acceptable - every display path re-syncs from the
        // holder re-sync only when holder != null (cold-start fallback is intent, not holder), so no saved transaction state is ever needed.
        supportFragmentManager.beginTransaction()
            .replace(R.id.alarmFragmentContainer, AlarmFragment::class.java, buildArguments(data))
            .commitAllowingStateLoss()
    }

    /**
     * Why: Dedupe holder emissions vs displayed alarm without redundant replaces.
     * How: Compare notificationId + order-sensitive reminderEventIds; not a data class, no full-field compare.
     */
    private fun isCurrentlyDisplayed(candidate: ReminderNotificationData): Boolean {
        val displayed = displayedAlarm ?: return false
        return displayed.notificationId == candidate.notificationId &&
            displayed.reminderEventIds == candidate.reminderEventIds
    }

    /**
     * Why: SINGLE_TOP delivers second alarm without recreation; must not drop events (issue #1494).
     * How: Trigger reconcileFromHolder first (holder swapped before notify, always ahead); fallback to intent only if holder null/older (same >= id rule, supports synthetic AlarmIntentRedeliveryTest).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        // TRIGGER: reconcile the screen with the holder's current value.
        reconcileFromHolder()

        // BOOTSTRAP-FALLBACK guard: fall back to the intent payload only when the holder value
        // is null or older than the intent's payload (same >= notificationId rule).
        if (!shouldReplaceAlarm(
                alarmScreenRepository.currentAlarm.value?.notificationId,
                intent.getIntExtra(ActivityCodes.EXTRA_NOTIFICATION_ID, -1)
            )
        ) {
            // Holder already carries a more recent alarm than the delivered intent; the
            // reconciliation above owns the screen.
            return
        }
        addAlarmFragment(intent)
    }

    /**
     * Why: Sync trigger for onResume/onNewIntent when collector not visible.
     * How: Skip null or already-displayed; else replace fragment.
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
