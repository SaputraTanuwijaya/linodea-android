package io.github.saputratanuwijaya.linodea.spike

/**
 * Everything the app knows, as text that can be pasted somewhere.
 *
 * Until now a result left the phone as screenshots, read back by eye. That
 * loses most of what was recorded and makes every run depend on which cards
 * happened to be on screen. One button copies all of it.
 *
 * Also the shape of what ships: users' phones are the only way the companion
 * learns about brands nobody here owns, and they can only teach it anything
 * if the app can say what happened in a form someone can send.
 *
 * Pure -- it is handed everything it prints -- so it can be tested.
 */
object Report {

    data class Input(
        val build: String,
        val device: String,
        val nowMs: Long,
        val now: SpikeLog.Snapshot,
        /** One line each: "exact alarms: allowed". */
        val setup: List<String>,
        val calendarApps: List<String>,
        val trials: List<Trial.Info>,
        val entries: List<SpikeLog.Entry>,
        /** Calendar event id -> the calendar's own record of it. */
        val calendarRows: Map<Long, List<CalendarRoute.AlertRow>?>,
        val exits: List<ExitHistory.Exit>?,
        val starts: List<StartHistory.Start>?,
        val journal: List<Journal.Line>,
        val crash: String?,
        /** Why the calendar's record could not be read, when it could not. */
        val calendarProblem: String? = null,
    )

    private const val MAX_JOURNAL_LINES = 160

    fun build(input: Input, clock: (Long) -> String): String = buildString {
        appendLine("LINODEA PHONE TEST REPORT")
        appendLine("build: ${input.build}")
        appendLine("device: ${input.device}")
        appendLine("now: ${clock(input.nowMs)} - ${Describe.moment(input.now)}")
        input.setup.forEach { appendLine("setup: $it") }
        appendLine(
            "apps told about calendar reminders: " +
                input.calendarApps.joinToString(", ").ifEmpty { "none found" }
        )
        appendLine("system's next clock alarm: " + nextClock(input.now, clock))
        input.calendarProblem?.let { appendLine("calendar record could not be read: $it") }

        input.trials.sortedByDescending { it.id }.forEach { trial ->
            appendLine()
            appendTrial(trial, input, clock)
        }

        val loose = input.entries.filter { it.trial == null }
        if (loose.isNotEmpty()) {
            appendLine()
            appendLine("== ALARMS OUTSIDE A TEST ==")
            loose.sortedByDescending { it.dueAtMs }.forEach { appendEntry(it, input, clock) }
        }

        appendLine()
        appendLine("== PROCESS, AS THE SYSTEM RECORDED IT ==")
        appendProcess(input, clock)

        appendLine()
        appendLine("== JOURNAL, AS THE APP RECORDED IT ==")
        val since = input.trials.maxByOrNull { it.id }?.startedAtMs ?: 0L
        val lines = input.journal.filter { it.atMs >= since }.takeLast(MAX_JOURNAL_LINES)
        if (lines.isEmpty()) appendLine("(nothing)")
        lines.forEach { appendLine("${clock(it.atMs)} ${it.text}") }

        input.crash?.let {
            appendLine()
            appendLine("== LAST CRASH ==")
            appendLine(it.take(3000))
        }
    }.trimEnd()

    private fun nextClock(s: SpikeLog.Snapshot, clock: (Long) -> String): String =
        when (val at = s.nextAlarmClockAtMs) {
            null -> "unknown"
            0L -> "none"
            else -> clock(at)
        }

    private fun StringBuilder.appendTrial(trial: Trial.Info, input: Input, clock: (Long) -> String) {
        val arms = input.entries.filter { it.trial == trial.id }
        appendLine(
            "== TEST #${trial.id} (${trial.label}) armed ${clock(trial.startedAtMs)}, " +
                "due ${clock(trial.dueAtMs)} =="
        )
        appendLine("calendar path: ${trial.calendar}")

        arms.filterNot { it.isHeartbeat }.sortedBy { it.id }.forEach { appendEntry(it, input, clock) }

        val beats = arms.filter { it.isHeartbeat }.sortedBy { it.dueAtMs }
        val ladder = TrialPlan.ladder(beats, input.nowMs)
        appendLine("heartbeats: ${TrialPlan.ladderSentence(ladder, clock)}")
        beats.forEach { beat ->
            val at = beat.atFire
            appendLine(
                "    ${clock(beat.dueAtMs)}  ${Describe.beat(beat, input.nowMs)}" +
                    (at?.let { "  [${Describe.moment(it)}]" } ?: "") +
                    if (beat.firedAtMs != null) (if (beat.coldStart) "  cold" else "  warm") else ""
            )
        }
    }

    private fun StringBuilder.appendEntry(entry: SpikeLog.Entry, input: Input, clock: (Long) -> String) {
        appendLine("[${entry.api} #${entry.id}] ${entry.headline(input.nowMs)}")
        appendLine(
            "    due ${clock(entry.dueAtMs)}" +
                (entry.firedAtMs?.let { " - arrived ${clock(it)}" } ?: "") +
                if (entry.unlogged) " - NOT IN THE LOG (armed before a Clear)" else ""
        )
        if (entry.voidedByCharging) {
            appendLine("    DOES NOT COUNT - plugged in, so the phone was never tested asleep")
        } else if (entry.releasedOnCharger) {
            appendLine("    armed unplugged and arrived on the charger - charging may be what released it")
        }
        entry.armed?.let { appendLine("    armed: ${Describe.armed(it)}") }
        if (entry.api == AlarmScheduler.Api.ALARM_CLOCK.label) {
            Describe.clockAccepted(entry.dueAtMs, entry.armed, clock)?.let { appendLine("    clock alarm: $it") }
        }
        entry.atFire?.let {
            appendLine(
                "    arrived: ${Describe.moment(it)}" +
                    if (entry.coldStart) " - cold start" else " - warm process"
            )
        }
        entry.firedAtMs?.let { at ->
            Describe.asleep(entry.armed, entry.armedAtMs, entry.atFire, at)?.let { appendLine("    $it") }
        }
        entry.noticedLine()?.let { appendLine("    $it") }
        entry.atNotice?.let { appendLine("    while it was held: ${Describe.moment(it)}") }
        entry.noticedAtMs?.let { at ->
            Describe.asleep(entry.armed, entry.armedAtMs, entry.atNotice, at)
                ?.let { appendLine("    by then, $it") }
        }
        entry.calendarEventId?.let { eventId ->
            appendLine("    calendar's own record: " + calendarRecord(input.calendarRows[eventId], clock))
        }
    }

    /** The calendar's record of a reminder, in a line. */
    fun calendarRecord(rows: List<CalendarRoute.AlertRow>?, clock: (Long) -> String): String = when {
        rows == null -> "cannot be read"
        rows.isEmpty() -> "none yet (the calendar only writes one for the next reminder due)"
        else -> rows.joinToString("; ") { row ->
            listOfNotNull(
                "${row.state} for ${clock(row.alarmAtMs)}",
                row.notifiedAtMs?.let { "a calendar app showed it ${clock(it)}" },
            ).joinToString(", ")
        }
    }

    private fun StringBuilder.appendProcess(input: Input, clock: (Long) -> String) {
        val lines = mutableListOf<Pair<Long, String>>()
        input.exits?.forEach { exit ->
            val detail = listOfNotNull(
                exit.standing?.let { "was $it" },
                exit.description?.takeIf { it.isNotBlank() }?.let { "\"$it\"" },
            ).joinToString(", ")
            lines += exit.atMs to "died: ${exit.reason}" + if (detail.isEmpty()) "" else " ($detail)"
        }
        input.starts?.forEach { start ->
            lines += start.atMs to "started by ${start.reason} (${start.type})"
        }
        if (input.exits == null) appendLine("deaths unknown (needs Android 11+)")
        if (input.starts == null) appendLine("starts unknown (needs Android 15+)")
        if (lines.isEmpty()) appendLine("(nothing recorded)")
        lines.sortedBy { it.first }.forEach { (at, text) -> appendLine("${clock(at)} $text") }
    }
}
