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
 * Alarms whose moment passed while the phone was off are not re-armed -- that
 * would record a firing measured against a powered-off device. They are
 * announced as overdue instead, which is what they are.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        // Android 15 also sends this to an app the first time it is opened
        // after being force-stopped, because a force-stop cancels its alarms
        // just as a restart does. Either way the work is the same.
        Journal.note(context, "receiver: restart (the phone, or this app after a force-stop)")

        val now = System.currentTimeMillis()
        SpikeLog.pending(context)
            .filter { it.dueAtMs > now }
            .forEach { AlarmScheduler.rearmSilently(context, it) }

        Delivery.catchUp(context, by = "a restart of the phone or the app")

        // Boot starts the process; left running, it would be frozen before
        // the first of the alarms it just re-armed.
        EndWhenHidden.endAfterBroadcast(this, context)
    }
}
