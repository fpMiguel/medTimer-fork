package com.futsch1.medtimer.feature.reminders.notificationData

/**
 * Pure decision policy for which alarm the full-screen alarm display should show.
 *
 * Notification IDs are allocated by
 * [com.futsch1.medtimer.core.datastore.PersistentDataDataSource.getAndIncreaseNotificationId].
 * The allocator uses a process-wide lock, but its persisted Int counter can wrap. The comparison
 * below therefore uses unsigned serial-number arithmetic: a value is newer when it is ahead by
 * less than half of the Int range. This keeps the normal 0, 1, ... ordering and handles the single
 * MAX_VALUE -> MIN_VALUE wrap without treating the next post as stale.
 *
 * Equal IDs are accepted on purpose: after a take-action, notifications are re-posted with the
 * SAME ID but a REDUCED payload. The newest post of that ID must win. A timestamp is deliberately
 * not used as the ordering key because snoozing changes the scheduled time while the notification
 * is still the alarm that is currently ringing.
 *
 * Truth table for ordinary, non-wrapped values:
 * - current = null, candidate = any  -> true
 * - current = 5, candidate = 4      -> false
 * - current = 5, candidate = 5      -> true
 * - current = 5, candidate = 6      -> true
 */
fun shouldReplaceAlarm(currentNotificationId: Int?, candidateNotificationId: Int): Boolean {
    if (currentNotificationId == null || candidateNotificationId == currentNotificationId) {
        return true
    }

    val modulus = 1L shl Int.SIZE_BITS
    val halfRange = modulus / 2
    val unsignedCandidate = candidateNotificationId.toLong() and 0xFFFFFFFFL
    val unsignedCurrent = currentNotificationId.toLong() and 0xFFFFFFFFL
    val distance = (unsignedCandidate - unsignedCurrent + modulus) % modulus
    return distance in 1L until halfRange
}
