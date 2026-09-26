package io.github.saputratanuwijaya.linodea.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms everything after a reboot.
 *
 * AlarmManager forgets every alarm when the device restarts, and nothing warns
 * anyone — the reminder still shows in the app with its time, and simply never
 * goes off. `phase2_notes.md` calls this out as one of the two mitigations to
 * build in from day one rather than retrofit, so the spike carries it: a test
 * of alarm reliability that cannot survive a restart has not tested the thing
 * people actually do to their phones.
 *
 * Alarms whose moment passed while the phone was off are left alone. Firing
 * them at boot would be a notification for something already missed, and would
 * pollute the drift numbers with values measured against a powered-off device.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val now = System.currentTimeMillis()
        SpikeLog.pending(context)
            .filter { it.dueAtMs > now }
            .forEach { AlarmScheduler.rearmSilently(context, it) }

        // Boot starts the process; left running, it would be frozen before
        // the first of the alarms it just re-armed.
        EndWhenHidden.endAfterBroadcast(this, context)
    }
}
