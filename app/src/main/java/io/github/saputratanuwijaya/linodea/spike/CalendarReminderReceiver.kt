package io.github.saputratanuwijaya.linodea.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract

/**
 * Hears the system calendar say a reminder is due.
 *
 * The other half of `CalendarRoute`. This broadcast is sent by Android's
 * calendar storage, not by an alarm of ours, and it is one of the few the
 * system still delivers to an app that is not running.
 *
 * It has to be exported, because the sender is another app. That means
 * anything on the phone can send a fake one, so the broadcast is trusted for
 * nothing but its timing: it marks a calendar entry delivered only if that
 * entry's own due time matches, and an early or unrelated one changes nothing.
 *
 * It also fires for every other calendar on the phone -- someone's meeting,
 * a birthday. Those deliver nothing, but each is this process running, so
 * each is a chance to announce anything overdue.
 */
class CalendarReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CalendarContract.ACTION_EVENT_REMINDER) return

        // First, as in AlarmReceiver: the screen's state before anything here
        // can change it is the evidence of what woke the phone.
        val state = DeviceState.snapshot(context)
        val coldStart = !ProcessState.wasWarm

        // The time this reminder was for travels both as an extra and as the
        // last part of the address; read whichever the phone's version sends.
        val alarmAt = intent.getLongExtra(CalendarContract.CalendarAlerts.ALARM_TIME, -1L)
            .takeIf { it > 0 }
            ?: intent.data?.lastPathSegment?.toLongOrNull()?.takeIf { it > 0 }
        Journal.note(context, "receiver: calendar reminder" + (alarmAt?.let { " for $it" } ?: ""))

        val now = System.currentTimeMillis()
        // A reminder cannot be due in the future. One that claims to be is
        // not from the calendar.
        val delivered = if (alarmAt != null && alarmAt > now + EARLY_SLACK_MS) {
            emptyList()
        } else {
            SpikeLog.calendarFired(context, alarmAt, coldStart, state, now)
        }
        delivered.forEach { entry ->
            Notify.arrived(context, entry, coldStart, quiet = entry.noticedAtMs != null)
            entry.trial?.let { Notify.cancelUpcoming(context, it) }
        }

        Delivery.catchUp(
            context,
            by = if (delivered.isEmpty()) "another calendar's reminder" else "the calendar reminder arriving",
            state = state,
        )

        EndWhenHidden.endAfterBroadcast(this, context)
    }

    private companion object {
        const val EARLY_SLACK_MS = 5_000L
    }
}
