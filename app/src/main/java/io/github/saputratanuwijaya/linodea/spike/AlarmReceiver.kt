package io.github.saputratanuwijaya.linodea.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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

        // Read before anything else, and before the notification below can
        // light the screen: this is the evidence of whether the phone was
        // woken by a person (a held alarm) or by the alarm itself.
        val state = DeviceState.snapshot(context)

        // `ProcessState.wasWarm` is set by the Application object only when the
        // process was already alive. A cold delivery means Android had to
        // rebuild us to get here, which is what an OEM kill looks like when it
        // does not simply swallow the alarm.
        val coldStart = !ProcessState.wasWarm
        Journal.note(context, "receiver: $api #$id")

        // An id the log does not know still gets a record: the overnight run
        // rang a cleared alarm at 12:03 that nothing could see afterwards, and
        // a phantom nobody can read is how the run was misread.
        val entry = SpikeLog.fired(context, id, coldStart, state)
            ?: SpikeLog.unlogged(
                context, id, api, intent.getLongExtra(EXTRA_DUE_AT, 0L), coldStart, state,
            )

        // A heartbeat announces nothing. Arriving is its whole result.
        if (!entry.isHeartbeat) {
            Notify.arrived(context, entry, coldStart, quiet = entry.noticedAtMs != null)
            entry.trial?.let { Notify.cancelUpcoming(context, it) }
        }

        // This process is running, so anything else that is overdue gets
        // announced now rather than whenever the phone releases it.
        Delivery.catchUp(
            context,
            by = if (entry.isHeartbeat) "a heartbeat" else "another alarm arriving",
            state = state,
        )

        // Last, after the record and the notification are both out of this
        // process: the sound is played by the system, so it outlives us.
        EndWhenHidden.endAfterBroadcast(this, context)
    }

    companion object {
        const val ACTION_FIRE = "io.github.saputratanuwijaya.linodea.spike.FIRE"
        const val EXTRA_ID = "id"
        const val EXTRA_API = "api"
        const val EXTRA_DUE_AT = "dueAt"
    }
}
