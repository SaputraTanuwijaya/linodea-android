package io.github.saputratanuwijaya.linodea.spike

/**
 * Words for what a snapshot recorded.
 *
 * Shared by the screen and the copyable report, so the two cannot describe
 * the same reading differently. Pure: no Android, so it can be tested.
 */
object Describe {

    fun yesNo(value: Boolean?, yes: String, no: String): String? = when (value) {
        true -> yes
        false -> no
        null -> null
    }

    fun battery(s: SpikeLog.Snapshot): String? = listOfNotNull(
        s.batteryPercent?.let { "battery $it%" },
        if (s.powerSave == true) "POWER SAVING ON" else null,
    ).joinToString(" ").ifEmpty { null }

    /** What Android itself says about the app at that moment. */
    fun system(s: SpikeLog.Snapshot): String? = listOfNotNull(
        s.bucket?.let { "usage tier $it" },
        if (s.backgroundRestricted == true) "BACKGROUND RESTRICTED" else null,
        s.idle?.takeIf { it != "no" }?.let { "Doze $it" },
    ).joinToString(" - ").ifEmpty { null }

    fun armed(s: SpikeLog.Snapshot): String = listOfNotNull(
        yesNo(s.endWhenHidden, "end-when-hidden ON", "end-when-hidden off"),
        if (s.keepAlive == true) "keep-alive ON" else null,
        yesNo(s.plugged, "PLUGGED IN", "unplugged"),
        yesNo(s.batteryExempt, "battery unrestricted", "battery OPTIMISED"),
        battery(s),
        system(s),
    ).joinToString(" - ")

    /** The phone at the instant something arrived, or was found overdue. */
    fun moment(s: SpikeLog.Snapshot): String = listOfNotNull(
        yesNo(s.screenOn, "screen ON", "screen off"),
        yesNo(s.locked, "locked", "unlocked"),
        yesNo(s.plugged, "PLUGGED IN", "unplugged"),
        if (s.keepAlive == true) "keep-alive running" else null,
        battery(s),
        system(s),
    ).joinToString(" - ")

    /**
     * Whether the system took a `setAlarmClock` alarm as a clock alarm.
     *
     * A clock alarm is the one kind stock Android promises to leave Doze for,
     * and it is the reason that API was chosen. Some manufacturers quietly
     * strip that status from apps that are not their own clock. The system
     * publishes its "next clock alarm", so reading it straight after arming
     * says whether ours was accepted -- no guessing from lateness afterwards.
     */
    fun clockAccepted(dueAtMs: Long, armed: SpikeLog.Snapshot?, clock: (Long) -> String): String? {
        val next = armed?.nextAlarmClockAtMs ?: return null
        return when {
            next == dueAtMs -> "the system lists it as its next clock alarm"
            next == 0L -> "THE SYSTEM LISTS NO CLOCK ALARM - ours was not accepted as one"
            next < dueAtMs -> "an earlier clock alarm (${clock(next)}) is ahead of it, so acceptance cannot be read"
            else -> "THE SYSTEM'S NEXT CLOCK ALARM IS A LATER ONE (${clock(next)}) - ours was not accepted as one"
        }
    }

    /**
     * How much of a wait the phone's processor slept through, from two
     * snapshots and the times they were taken. Null when either is missing.
     */
    fun asleep(from: SpikeLog.Snapshot?, fromAtMs: Long, to: SpikeLog.Snapshot?, toAtMs: Long): String? {
        val before = from?.sleptMs ?: return null
        val after = to?.sleptMs ?: return null
        val span = toAtMs - fromAtMs
        if (span <= 0) return null
        val slept = after - before
        // The count restarts from zero when the phone does.
        if (slept < 0) return "the phone was restarted in between"
        return "the phone slept ${SpikeLog.formatDuration(slept / 1000)} of the " +
            "${SpikeLog.formatDuration(span / 1000)} since arming"
    }

    /** One heartbeat, in a few words. */
    fun beat(entry: SpikeLog.Entry, nowMs: Long): String {
        val drift = entry.driftSeconds
        return when (entry.verdict) {
            SpikeLog.Verdict.WAITING ->
                if (nowMs - entry.dueAtMs > SpikeLog.ON_TIME_SECONDS * 1000) "MISSING" else "not due yet"
            SpikeLog.Verdict.RANG_IN_DARK -> "on time (${SpikeLog.formatDuration(drift!!)})"
            SpikeLog.Verdict.SCREEN_WAS_ON -> "on time (${SpikeLog.formatDuration(drift!!)}), screen was on"
            SpikeLog.Verdict.LATE_IN_DARK -> "LATE ${SpikeLog.formatDuration(drift!!)}, screen off"
            SpikeLog.Verdict.HELD_UNTIL_SCREEN_ON -> "HELD ${SpikeLog.formatDuration(drift!!)}, came with the screen"
            SpikeLog.Verdict.UNRECORDED -> "${SpikeLog.formatDuration(drift!!)} after due"
        }
    }
}
