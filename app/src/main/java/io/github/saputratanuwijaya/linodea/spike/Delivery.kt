package io.github.saputratanuwijaya.linodea.spike

import android.content.Context

/**
 * The app delivering what the phone did not.
 *
 * In both 8-hour runs the app was opened while its alarm was hours overdue,
 * showed "NOT DELIVERED", and announced nothing: it kept waiting for an alarm
 * the phone was holding, and the reminder only appeared when a charger went
 * in. A reminder the app knows is late and says nothing about is the app's
 * failure, not the phone's.
 *
 * So every time this process runs, for any reason -- opened by hand, woken by
 * a heartbeat, by another alarm, by a calendar reminder, by a restart -- it
 * announces whatever is overdue. That cannot make a held reminder on time. It
 * bounds how late it can be to "the next time anything at all runs this app".
 *
 * The alarm itself stays armed, so the record still shows when the phone
 * finally delivered it and how much earlier the catch-up was.
 */
object Delivery {

    /** Announces everything overdue that nothing has announced. Returns how many. */
    fun catchUp(
        context: Context,
        by: String,
        state: SpikeLog.Snapshot = DeviceState.snapshot(context),
    ): Int {
        val caught = SpikeLog.noticeOverdue(context, by, state)
        caught.forEach { entry ->
            Notify.overdue(context, entry)
            entry.trial?.let { Notify.cancelUpcoming(context, it) }
        }
        if (caught.isNotEmpty()) {
            Journal.note(context, "announced ${caught.size} overdue itself ($by)")
        }
        return caught.size
    }
}
