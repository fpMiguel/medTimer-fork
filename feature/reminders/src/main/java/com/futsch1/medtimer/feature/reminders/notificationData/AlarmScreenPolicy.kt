package com.futsch1.medtimer.feature.reminders.notificationData

/**
 * Pure decision policy for which alarm the full-screen alarm display should show.
 *
 * The rule is POST-order last-write-wins: notification IDs are handed out by
 * [com.futsch1.medtimer.core.datastore.PersistentDataDataSource.getAndIncreaseNotificationId]
 * (strictly increasing, `@Synchronized`), so a larger ID always means a LATER post. Later posts
 * proxy interruption recency - the alarm that was posted most recently is the one ringing NOW,
 * so it wins the screen.
 *
 * Equal IDs are accepted on purpose: after a take-action, notifications are re-posted with the
 * SAME ID but a REDUCED payload (remaining reminders removed). The newest post of that ID must
 * win, so an equal-ID re-post replaces what is currently displayed.
 *
 * [ReminderNotificationData.remindInstant] is FORBIDDEN as a sort key: snoozing an old dose
 * assigns it a fresh (larger) notification ID while keeping its OLDER dose time. Keying on dose
 * time would let that stale dose hijack the screen away from a genuinely newer alarm. The screen
 * follows whatever rings NOW, matching the platform's own last-posted behavior.
 *
 * Truth table:
 * - current = null, candidate = any  -> true  (nothing displayed yet; anything may take over)
 * - current = 5, candidate = 4      -> false (older post must not hijack a live alarm)
 * - current = 5, candidate = 5      -> true  (same-ID re-post with reduced payload wins)
 * - current = 5, candidate = 6      -> true  (newer post is more recent)
 */
fun shouldReplaceAlarm(currentNotificationId: Int?, candidateNotificationId: Int): Boolean =
    currentNotificationId == null || candidateNotificationId >= currentNotificationId
