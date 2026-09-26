package io.github.saputratanuwijaya.linodea.spike

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Arms one alarm, by one of the two APIs under test.
 *
 * The question the spike exists to answer is whether either of these survives
 * Doze and an OEM battery manager on a real Infinix, so both are reachable from
 * the UI and every firing records which one delivered it.
 */
object AlarmScheduler {

    /** The two candidates, compared rather than assumed. */
    enum class Api(val label: String) {
        /**
         * The one `phase2_notes.md` already settled on. Android treats it as a
         * clock alarm: highest priority, leaves Doze shortly before firing, and
         * shows the alarm icon in the status bar. Expected to win.
         */
        ALARM_CLOCK("setAlarmClock"),

        /**
         * Permitted in Doze, but believed to be rate-limited to roughly one
         * firing per app per nine minutes while idle. If that holds it is
         * disqualifying on its own, because a prealert and its T-due can
         * legitimately land minutes apart — Linodea sends up to four alarms for
         * a single reminder. Included so the limit is measured, not trusted.
         */
        EXACT_WHILE_IDLE("setExactAndAllowWhileIdle"),
    }

    /**
     * Whether the system will honour an exact alarm at all.
     *
     * On 12 and 13 this can be refused, and a refusal is silent — the alarm is
     * simply downgraded. Checking lets the UI say so rather than letting the
     * test produce a false negative that looks like a Doze failure.
     */
    fun canScheduleExact(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun arm(context: Context, id: Int, api: Api, dueAtMs: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val pending = firePendingIntent(context, id, api, dueAtMs)

        when (api) {
            Api.ALARM_CLOCK -> {
                // The second PendingIntent is what the user taps on the status
                // bar's alarm icon. It is required, and pointing it at our own
                // launcher is the honest answer to "what set this alarm".
                val show = PendingIntent.getActivity(
                    context,
                    id,
                    Intent(context, io.github.saputratanuwijaya.linodea.MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(dueAtMs, show), pending)
            }

            Api.EXACT_WHILE_IDLE ->
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAtMs, pending)
        }

        // Recorded with the alarm so a result carries its own conditions: a
        // run armed while charging, or with keep-alive off, reads as such
        // later without anyone having to remember.
        SpikeLog.armed(context, id, api.label, dueAtMs, DeviceState.snapshot(context))
    }

    /**
     * Re-arm without logging a new entry — for `BOOT_COMPLETED`, where the
     * alarms already exist in the log but Android has forgotten them.
     */
    fun rearmSilently(context: Context, entry: SpikeLog.Entry) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val api = Api.entries.firstOrNull { it.label == entry.api } ?: Api.ALARM_CLOCK
        val pending = firePendingIntent(context, entry.id, api, entry.dueAtMs)
        when (api) {
            Api.ALARM_CLOCK -> {
                val show = PendingIntent.getActivity(
                    context,
                    entry.id,
                    Intent(context, io.github.saputratanuwijaya.linodea.MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(entry.dueAtMs, show), pending)
            }

            Api.EXACT_WHILE_IDLE ->
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.dueAtMs, pending)
        }
    }

    private fun firePendingIntent(
        context: Context,
        id: Int,
        api: Api,
        dueAtMs: Long,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            // A distinct action per id: PendingIntent equality ignores extras,
            // so without it a second alarm would silently replace the first and
            // the test would quietly measure one alarm instead of two.
            action = "${AlarmReceiver.ACTION_FIRE}.$id"
            putExtra(AlarmReceiver.EXTRA_ID, id)
            putExtra(AlarmReceiver.EXTRA_API, api.label)
            putExtra(AlarmReceiver.EXTRA_DUE_AT, dueAtMs)
        }
        return PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
