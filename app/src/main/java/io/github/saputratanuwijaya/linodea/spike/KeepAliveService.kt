package io.github.saputratanuwijaya.linodea.spike

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.saputratanuwijaya.linodea.MainActivity
import io.github.saputratanuwijaya.linodea.R

/**
 * Keeps the process out of the freezer.
 *
 * The last lever, and it is not a nice one.
 *
 * Observed on a real Infinix running XOS, with battery optimisation disabled,
 * "sleep standby optimisation" off, and autostart already granted: alarms still
 * did not arrive until the app itself was opened. The process is frozen, the
 * broadcast queues behind the freeze, and none of the three settings prevents
 * it. Everything a manifest or an intent can do had been done.
 *
 * A foreground service is what remains. An app holding one is not a cached app,
 * so there is nothing to freeze, and OEM cleaners treat it far more carefully —
 * it is the mechanism behind every alarm clock and fitness tracker that keeps
 * working on these devices.
 *
 * **The cost is a permanent notification in the shade**, which is a real
 * intrusion for an app whose whole pitch is staying out of the way. It is here
 * to find out whether it works at all; whether it ships is a product decision,
 * not this file's.
 *
 * Deliberately does no work. It exists to hold the process, and the alarms
 * still come from `AlarmManager` — a service that tried to keep time itself
 * would be a fire-time daemon on the phone, which is a different design and a
 * worse one.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, ongoing())
        // START_STICKY: if the system kills us anyway, come back. On a device
        // that freezes apps this aggressively, being restarted is the point.
        return START_STICKY
    }

    private fun ongoing(): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Keeping alarms alive",
                // LOW: no sound and no heads-up. It has to exist, it does not
                // have to be noticed -- the alarm channel is the loud one.
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pending = SpikeLog.pending(this).size
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Linodea alarms armed")
            .setContentText(
                if (pending == 0) "Waiting" else "$pending waiting"
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val CHANNEL = "keep-alive"
        private const val NOTIFICATION_ID = 9001

        /**
         * Best-effort: on Android 12+ a background start throws, and there is
         * no useful recovery — the app simply does not get the protection and
         * the results say so.
         */
        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }

        fun isRunning(context: Context): Boolean {
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            @Suppress("DEPRECATION")
            return manager.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == KeepAliveService::class.java.name
            }
        }
    }
}
