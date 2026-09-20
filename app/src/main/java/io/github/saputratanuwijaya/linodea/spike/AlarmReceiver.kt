package io.github.saputratanuwijaya.linodea.spike

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.saputratanuwijaya.linodea.MainActivity
import io.github.saputratanuwijaya.linodea.R

/**
 * What happens when an alarm actually goes off.
 *
 * Records first, notifies second. The record is the experiment's result and the
 * notification is only how a human notices — if the process is killed halfway
 * through, a written result with no notification is still a data point, while a
 * notification with no record tells the morning nothing.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) return
        val api = intent.getStringExtra(EXTRA_API) ?: "unknown"
        val dueAt = intent.getLongExtra(EXTRA_DUE_AT, 0L)

        // `ProcessState.wasWarm` is set by the Application object only when the
        // process was already alive. A cold delivery means Android had to
        // rebuild us to get here, which is what an OEM kill looks like when it
        // does not simply swallow the alarm.
        val coldStart = !ProcessState.wasWarm
        SpikeLog.fired(context, id, coldStart)

        notify(context, id, api, dueAt, coldStart)
    }

    private fun notify(context: Context, id: Int, api: String, dueAt: Long, coldStart: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Alarm spike",
                // HIGH so it can make a sound and appear over other apps --
                // the point is to be noticed from across a room at 3am.
                NotificationManager.IMPORTANCE_HIGH,
            )
        )

        val driftSeconds = (System.currentTimeMillis() - dueAt) / 1000
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Alarm #$id fired")
            // The drift is on the notification itself, so a glance at the lock
            // screen in the morning answers the question without opening
            // anything: "it fired" and "it fired on time" are different results.
            .setContentText(
                "$api - ${driftSeconds}s late" + if (coldStart) " - cold start" else ""
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        // areNotificationsEnabled guards the Android 13+ case where the runtime
        // permission was refused: without it this throws and the recorded
        // result would be lost to an exception after the fact.
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    companion object {
        const val ACTION_FIRE = "io.github.saputratanuwijaya.linodea.spike.FIRE"
        const val EXTRA_ID = "id"
        const val EXTRA_API = "api"
        const val EXTRA_DUE_AT = "dueAt"
        private const val CHANNEL = "alarm-spike"
    }
}
