package io.github.saputratanuwijaya.linodea.spike

/**
 * The arithmetic of a test run, kept apart from the phone so it can be tested.
 */
object TrialPlan {
    private const val MINUTE_MS = 60_000L

    /** A heartbeat closer than this to "now" would be due before the app has left the screen. */
    private const val MIN_LEAD_MS = 45_000L

    /**
     * When a test is due: at least `minutes` away, and on a whole minute.
     *
     * A whole minute because the calendar and the phone's Clock app both work
     * in minutes, and the notification shade shows times to the minute. Every
     * path being due at exactly 06:30:00 makes "which arrived when" readable
     * at a glance.
     */
    fun dueAt(nowMs: Long, minutes: Int): Long {
        val earliest = nowMs + minutes * MINUTE_MS
        val past = earliest % MINUTE_MS
        return if (past == 0L) earliest else earliest - past + MINUTE_MS
    }

    /**
     * When the heartbeats fall: counted back from the due time, so they land
     * on the same grid whatever second the test was started, and never at the
     * due time itself -- that moment belongs to the paths being compared.
     */
    fun beatTimes(nowMs: Long, dueAtMs: Long, everyMs: Long): List<Long> {
        if (everyMs <= 0) return emptyList()
        val times = mutableListOf<Long>()
        var at = dueAtMs - everyMs
        while (at > nowMs + MIN_LEAD_MS) {
            times += at
            at -= everyMs
        }
        return times.reversed()
    }

    /** What a run's heartbeats add up to. */
    data class Ladder(
        val total: Int,
        val onTime: Int,
        val late: Int,
        val missing: Int,
        val notDueYet: Int,
        /** Due time of the last heartbeat that arrived on time. */
        val lastOnTimeAtMs: Long?,
        /** Due time of the first heartbeat that was late or never came. */
        val firstMissAtMs: Long?,
    ) {
        /** Every heartbeat that has come due arrived on time. */
        val unbroken: Boolean get() = late == 0 && missing == 0

        /**
         * The hold lifted by itself: something arrived on time *after* the
         * first miss. That would mean the phone holds for a while and lets go,
         * rather than holding until a charger.
         */
        val recovered: Boolean
            get() = firstMissAtMs != null && lastOnTimeAtMs != null && lastOnTimeAtMs > firstMissAtMs
    }

    fun ladder(beats: List<SpikeLog.Entry>, nowMs: Long): Ladder {
        val onTime = beats.filter { it.arrivedOnTime }
        val late = beats.filter { it.firedAtMs != null && !it.arrivedOnTime }
        val missing = beats.filter {
            it.firedAtMs == null && nowMs - it.dueAtMs > SpikeLog.ON_TIME_SECONDS * 1000
        }
        return Ladder(
            total = beats.size,
            onTime = onTime.size,
            late = late.size,
            missing = missing.size,
            notDueYet = beats.size - onTime.size - late.size - missing.size,
            lastOnTimeAtMs = onTime.maxOfOrNull { it.dueAtMs },
            firstMissAtMs = (late + missing).minOfOrNull { it.dueAtMs },
        )
    }

    /** The ladder as one sentence, which is the finding. */
    fun ladderSentence(ladder: Ladder, clock: (Long) -> String): String = when {
        ladder.total == 0 -> "no heartbeats in this test"
        ladder.onTime + ladder.late + ladder.missing == 0 -> "${ladder.total} planned, none due yet"
        ladder.unbroken && ladder.notDueYet == 0 ->
            "all ${ladder.total} arrived on time - the phone kept waking the app the whole way"
        ladder.unbroken -> "${ladder.onTime} of ${ladder.total} on time so far, none missed"
        ladder.onTime == 0 ->
            "NONE arrived on time (${ladder.late} late, ${ladder.missing} missing) - " +
                "held from the first one, ${clock(ladder.firstMissAtMs!!)}"
        ladder.recovered ->
            "${ladder.onTime} on time, ${ladder.late} late, ${ladder.missing} missing - " +
                "first miss ${clock(ladder.firstMissAtMs!!)}, but on time again by " +
                "${clock(ladder.lastOnTimeAtMs!!)}: the hold lifted by itself"
        else ->
            "${ladder.onTime} on time, ${ladder.late} late, ${ladder.missing} missing - " +
                "on time until ${clock(ladder.lastOnTimeAtMs!!)}, " +
                "THE HOLD STARTED BY ${clock(ladder.firstMissAtMs!!)}"
    }
}
