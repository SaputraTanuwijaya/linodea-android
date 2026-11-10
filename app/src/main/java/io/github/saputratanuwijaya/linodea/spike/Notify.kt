package io.github.saputratanuwijaya.linodea.spike

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.saputratanuwijaya.linodea.MainActivity
import io.github.saputratanuwijaya.linodea.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the app puts in the notification shade.
 *
 * A notification, not an alarm. Saputra's call (2026-11-10): the phone is an
 * extension of the desktop that has to be *noticed*, not a clock that has to
 * wake someone. So the due notification uses the ordinary notification sound,
 * which silent mode and Do Not Disturb mute like any other, and it no longer
 * takes over the lock screen. The record on the card is the evidence of
 * whether it arrived, not anyone's ears.
 */
object Notify {
    // New ids rather than edits: a channel's sound and importance are fixed
    // when it is created, and the earlier builds created theirs as alarms.
    private const val DUE = "reminder-v3"
    private const val UPCOMING = "upcoming-v1"

    /** Clear of alarm ids, which count up from 1. */
    private const val UPCOMING_ID_BASE = 500_000

    private val TIME = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun open(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        // areNotificationsEnabled guards the Android 13+ case where the runtime
        // permission was refused: without it this throws and the recorded
        // result would be lost to an exception after the fact.
        val compat = NotificationManagerCompat.from(context)
        if (compat.areNotificationsEnabled()) runCatching { compat.notify(id, notification) }
    }

    private fun title(entry: SpikeLog.Entry): String =
        entry.trial?.let { "Linodea test #$it" } ?: "Linodea alarm #${entry.id}"

    private fun dueBuilder(context: Context, entry: SpikeLog.Entry, text: String, quiet: Boolean) =
        NotificationCompat.Builder(context, DUE)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title(entry))
            // The verdict is on the notification itself, so a glance at the
            // lock screen answers the question without opening anything.
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // The shade shows when this was posted, which is a second record
            // of the arrival time that does not depend on the app's own log.
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setSilent(quiet)
            .setContentIntent(open(context, entry.id))

    private fun ensureDueChannel(context: Context) {
        manager(context).createNotificationChannel(
            NotificationChannel(
                DUE,
                "Reminders",
                // HIGH so it makes a sound and shows over other apps. The
                // sound is the channel default: the phone's own notification
                // tone, on the notification stream.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { enableVibration(true) }
        )
    }

    /**
     * Something delivered this entry: an alarm of ours, or the calendar.
     * `quiet` when the app had already announced it as overdue -- the second
     * notice replaces the first with the final verdict, without ringing twice.
     */
    fun arrived(context: Context, entry: SpikeLog.Entry, coldStart: Boolean, quiet: Boolean) {
        ensureDueChannel(context)
        val text = "${entry.api} - ${entry.headline(System.currentTimeMillis())}" +
            (if (entry.unlogged) " - NOT IN THE LOG (armed before a Clear)" else "") +
            if (coldStart) " - cold start" else ""
        post(context, entry.id, dueBuilder(context, entry, text, quiet).build())
    }

    /** Nothing delivered it; the app found it overdue while running for another reason. */
    fun overdue(context: Context, entry: SpikeLog.Entry) {
        ensureDueChannel(context)
        val text = "${entry.api} - LATE: ${entry.noticedLine() ?: "found overdue"}. " +
            "The phone has still not delivered it."
        post(context, entry.id, dueBuilder(context, entry, text, quiet = false).build())
    }

    /**
     * The one thing that needs nothing to run at the due time.
     *
     * Posted when the test is armed, with a countdown the system's own
     * notification shade ticks. If every other path is held, this is still
     * there each time the phone is looked at, and past zero it counts the
     * lateness in the shade itself.
     */
    fun upcoming(context: Context, trial: Int, dueAtMs: Long) {
        manager(context).createNotificationChannel(
            NotificationChannel(
                UPCOMING,
                "Upcoming",
                // LOW: no sound, no heads-up. It is there to be found.
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val notification = NotificationCompat.Builder(context, UPCOMING)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle("Linodea test #$trial - due ${TIME.format(Date(dueAtMs))}")
            .setContentText("Counting down. Still here after zero means nothing arrived.")
            .setWhen(dueAtMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(context, UPCOMING_ID_BASE + trial))
            .build()
        post(context, UPCOMING_ID_BASE + trial, notification)
    }

    /** The countdown has done its job once anything else announces the reminder. */
    fun cancelUpcoming(context: Context, trial: Int) {
        runCatching { manager(context).cancel(UPCOMING_ID_BASE + trial) }
    }

    fun cancelAll(context: Context) {
        runCatching { manager(context).cancelAll() }
    }
}
