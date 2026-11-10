package io.github.saputratanuwijaya.linodea.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The report is the only thing that leaves the phone. If it drops a path, or
 * words a path that was never armed like one that failed, the conclusion drawn
 * from a whole night is wrong and nothing on the phone will contradict it.
 */
class ReportTest {
    private val minute = 60_000L
    private val clock: (Long) -> String = { "t${it / minute}" }

    private fun state(
        screenOn: Boolean? = false,
        plugged: Boolean? = false,
        bucket: String? = "ACTIVE",
        nextClock: Long? = null,
        battery: Int? = 80,
    ) = SpikeLog.Snapshot(
        screenOn = screenOn,
        locked = true,
        plugged = plugged,
        keepAlive = false,
        batteryExempt = true,
        endWhenHidden = true,
        batteryPercent = battery,
        powerSave = false,
        bucket = bucket,
        backgroundRestricted = false,
        idle = "no",
        nextAlarmClockAtMs = nextClock,
    )

    private fun input(
        entries: List<SpikeLog.Entry>,
        trials: List<Trial.Info> = listOf(
            Trial.Info(1, "8 hours", 0L, 480 * minute, 30 * minute, "handed to the phone's calendar (event 7)"),
        ),
        rows: Map<Long, List<CalendarRoute.AlertRow>?> = emptyMap(),
        nowMs: Long = 500 * minute,
    ) = Report.Input(
        build = "test build",
        device = "TEST PHONE",
        nowMs = nowMs,
        now = state(screenOn = true),
        setup = listOf("battery: unrestricted"),
        calendarApps = listOf("com.example.calendar"),
        trials = trials,
        entries = entries,
        calendarRows = rows,
        exits = emptyList(),
        starts = null,
        journal = listOf(Journal.Line(1 * minute, "process created")),
        crash = null,
    )

    private fun arm(
        id: Int,
        api: String,
        firedAtMs: Long?,
        atFire: SpikeLog.Snapshot? = firedAtMs?.let { state() },
        armed: SpikeLog.Snapshot? = state(),
        noticedAtMs: Long? = null,
        calendarEventId: Long? = null,
    ) = SpikeLog.Entry(
        id = id,
        api = api,
        armedAtMs = 0L,
        dueAtMs = 480 * minute,
        firedAtMs = firedAtMs,
        coldStart = true,
        armed = armed,
        atFire = atFire,
        trial = 1,
        noticedAtMs = noticedAtMs,
        noticedBy = noticedAtMs?.let { "the app being opened" },
        atNotice = noticedAtMs?.let { state(screenOn = true, bucket = "RARE", battery = 31) },
        calendarEventId = calendarEventId,
    )

    @Test
    fun `every path of a test appears with its own verdict`() {
        val report = Report.build(
            input(
                listOf(
                    arm(1, "setAlarmClock", firedAtMs = null),
                    arm(2, "setExactAndAllowWhileIdle", firedAtMs = 480 * minute + 1_000),
                    arm(3, SpikeLog.API_CALENDAR, firedAtMs = 480 * minute + 2_000, calendarEventId = 7),
                )
            ),
            clock,
        )
        assertTrue(report, report.contains("[setAlarmClock #1] NOT DELIVERED"))
        assertTrue(report, report.contains("[setExactAndAllowWhileIdle #2] PASS"))
        assertTrue(report, report.contains("[calendar reminder #3] PASS"))
        assertTrue(report, report.contains("== TEST #1 (8 hours) armed t0, due t480 =="))
    }

    @Test
    fun `a path that was never armed says not tested, in the trial's own words`() {
        // No calendar permission: there is no calendar entry at all. The
        // report must not be silent about it, or its absence reads as a pass
        // for everything else and nothing for the calendar.
        val trial = Trial.Info(1, "8 hours", 0L, 480 * minute, 0L, "NOT TESTED - calendar permission not granted")
        val report = Report.build(input(listOf(arm(1, "setAlarmClock", null)), trials = listOf(trial)), clock)
        assertTrue(report, report.contains("calendar path: NOT TESTED - calendar permission not granted"))
    }

    @Test
    fun `an overdue path the app announced itself shows the phone as it was while held`() {
        // The reading nobody has had before: what Android said about the app
        // at the moment it was opened with its alarm hours overdue.
        val report = Report.build(
            input(listOf(arm(1, "setAlarmClock", firedAtMs = null, noticedAtMs = 499 * minute))),
            clock,
        )
        assertTrue(report, report.contains("the app announced it itself 19m 00s after due (the app being opened)"))
        assertTrue(report, report.contains("while it was held: screen ON"))
        assertTrue(report, report.contains("usage tier RARE"))
        assertTrue(report, report.contains("battery 31%"))
    }

    @Test
    fun `the calendar's own record is read back, including a record that does not exist yet`() {
        assertEquals("cannot be read", Report.calendarRecord(null, clock))
        assertTrue(Report.calendarRecord(emptyList(), clock).startsWith("none yet"))
        val fired = CalendarRoute.AlertRow("fired", 480 * minute, 481 * minute)
        assertEquals(
            "fired for t480, a calendar app showed it t481",
            Report.calendarRecord(listOf(fired), clock),
        )
        val scheduled = CalendarRoute.AlertRow("scheduled", 480 * minute, null)
        assertEquals("scheduled for t480", Report.calendarRecord(listOf(scheduled), clock))
    }

    @Test
    fun `heartbeats are summed up in one line and then listed`() {
        val beats = listOf(
            SpikeLog.Entry(10, SpikeLog.API_HEARTBEAT, 0L, 30 * minute, 30 * minute + 1_000, true, atFire = state(), trial = 1),
            SpikeLog.Entry(11, SpikeLog.API_HEARTBEAT, 0L, 60 * minute, null, false, trial = 1),
        )
        val report = Report.build(input(beats), clock)
        assertTrue(report, report.contains("heartbeats: 1 on time, 0 late, 1 missing"))
        assertTrue(report, report.contains("t30  on time (1s)"))
        assertTrue(report, report.contains("t60  MISSING"))
        // A heartbeat is not a path under test and must not be listed as one.
        assertFalse(report, report.contains("[heartbeat #10]"))
    }

    @Test
    fun `alarms armed by hand are reported apart from any test`() {
        val loose = SpikeLog.Entry(5, "setAlarmClock", 0L, 10 * minute, 10 * minute + 500, true, atFire = state())
        val report = Report.build(input(listOf(loose), trials = emptyList()), clock)
        assertTrue(report, report.contains("== ALARMS OUTSIDE A TEST =="))
        assertTrue(report, report.contains("[setAlarmClock #5] PASS"))
    }

    @Test
    fun `whether the system accepted a clock alarm is stated, not left to be inferred`() {
        val due = 480 * minute
        assertEquals(
            "the system lists it as its next clock alarm",
            Describe.clockAccepted(due, state(nextClock = due), clock),
        )
        // The reading that would explain everything: the phone never treated
        // ours as a clock alarm in the first place.
        assertTrue(Describe.clockAccepted(due, state(nextClock = 0L), clock)!!.startsWith("THE SYSTEM LISTS NO CLOCK ALARM"))
        assertTrue(Describe.clockAccepted(due, state(nextClock = due + minute), clock)!!.contains("LATER ONE"))
        // The user's own earlier alarm hides ours; that proves nothing either way.
        assertTrue(Describe.clockAccepted(due, state(nextClock = due - minute), clock)!!.contains("cannot be read"))
        // An entry from a build that did not record it gets no claim at all.
        assertNull(Describe.clockAccepted(due, state(nextClock = null), clock))
    }

    @Test
    fun `how long the phone slept through a wait is read from two snapshots`() {
        val armed = state().copy(sleptMs = 10 * minute)
        val arrived = state().copy(sleptMs = 370 * minute)
        // Asleep for six of the eight hours: nothing woke it at the due time.
        assertEquals(
            "the phone slept 6h 00m of the 8h 00m since arming",
            Describe.asleep(armed, 0L, arrived, 480 * minute),
        )
        // The count restarts with the phone, so a smaller later reading is a restart.
        assertEquals(
            "the phone was restarted in between",
            Describe.asleep(arrived, 0L, armed, 480 * minute),
        )
        // An entry from a build that did not record it says nothing.
        assertNull(Describe.asleep(state(), 0L, arrived, 480 * minute))
    }

    @Test
    fun `the journal only goes back to the latest test`() {
        val base = input(listOf(arm(1, "setAlarmClock", null)))
        val report = Report.build(
            base.copy(
                trials = listOf(Trial.Info(1, "8 hours", 100 * minute, 480 * minute, 0L, "x")),
                journal = listOf(
                    Journal.Line(50 * minute, "from an older run"),
                    Journal.Line(200 * minute, "process created"),
                ),
            ),
            clock,
        )
        assertFalse(report, report.contains("from an older run"))
        assertTrue(report, report.contains("t200 process created"))
    }
}
