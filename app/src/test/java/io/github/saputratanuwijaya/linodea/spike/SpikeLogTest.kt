package io.github.saputratanuwijaya.linodea.spike

import io.github.saputratanuwijaya.linodea.spike.SpikeLog.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drift is the number this spike produces, and "it fired 30 seconds late"
 * and "it fired 40 minutes late" are different answers about whether the
 * architecture works. Worth the ten lines.
 *
 * The verdict tests pin the lesson of the first three runs on a real phone:
 * drift alone reported "106s late" for an alarm that was actually held until
 * someone looked. Every combination of screen and lateness has to land on a
 * different word, or the instrument can repeat that mistake.
 */
class SpikeLogTest {

    private fun state(screenOn: Boolean? = false, plugged: Boolean? = false) = SpikeLog.Snapshot(
        screenOn = screenOn,
        locked = true,
        plugged = plugged,
        keepAlive = true,
        batteryExempt = true,
    )

    private fun entry(
        dueAtMs: Long,
        firedAtMs: Long?,
        armed: SpikeLog.Snapshot? = null,
        atFire: SpikeLog.Snapshot? = null,
    ) = SpikeLog.Entry(
        id = 1,
        api = "setAlarmClock",
        armedAtMs = 0L,
        dueAtMs = dueAtMs,
        firedAtMs = firedAtMs,
        coldStart = false,
        armed = armed,
        atFire = atFire,
    )

    @Test
    fun `an alarm that has not fired has no drift rather than a drift of zero`() {
        // Zero would read as "fired exactly on time", which is the opposite of
        // what a pending alarm means.
        assertNull(entry(dueAtMs = 1_000_000, firedAtMs = null).driftSeconds)
    }

    @Test
    fun `drift is how late the alarm was, in seconds`() {
        assertEquals(0L, entry(1_000_000, 1_000_000).driftSeconds)
        assertEquals(42L, entry(1_000_000, 1_042_000).driftSeconds)
        // The case that decides whether Doze is a problem: nearly nine minutes
        // late is a pass by "did it fire" and a failure by any useful standard.
        assertEquals(530L, entry(1_000_000, 1_530_000).driftSeconds)
    }

    @Test
    fun `an alarm delivered early reports negative drift rather than wrapping`() {
        assertEquals(-5L, entry(1_000_000, 995_000).driftSeconds)
    }

    @Test
    fun `on time with the screen off is the only pass`() {
        val e = entry(1_000_000, 1_002_000, atFire = state(screenOn = false))
        assertEquals(Verdict.RANG_IN_DARK, e.verdict)
    }

    @Test
    fun `late with the screen on is a held alarm, not a late one`() {
        // Run 3 exactly: 187 seconds, delivered when the phone was unlocked.
        val e = entry(1_000_000, 1_187_000, atFire = state(screenOn = true))
        assertEquals(Verdict.HELD_UNTIL_SCREEN_ON, e.verdict)
    }

    @Test
    fun `late with the screen off is deferred, which is a different failure from held`() {
        // Nobody woke the phone and it still came late: Doze or a rate limit,
        // not a freeze. Conflating the two would point the fix at the wrong layer.
        val e = entry(1_000_000, 1_530_000, atFire = state(screenOn = false))
        assertEquals(Verdict.LATE_IN_DARK, e.verdict)
    }

    @Test
    fun `on time with the screen already on proves nothing`() {
        val e = entry(1_000_000, 1_003_000, atFire = state(screenOn = true))
        assertEquals(Verdict.SCREEN_WAS_ON, e.verdict)
    }

    @Test
    fun `the on-time window is inclusive at its edge and closed after it`() {
        val edge = 1_000_000 + SpikeLog.ON_TIME_SECONDS * 1000
        assertEquals(Verdict.RANG_IN_DARK, entry(1_000_000, edge, atFire = state()).verdict)
        assertEquals(Verdict.LATE_IN_DARK, entry(1_000_000, edge + 1000, atFire = state()).verdict)
    }

    @Test
    fun `results from the old build are not given a verdict they have no evidence for`() {
        assertEquals(Verdict.UNRECORDED, entry(1_000_000, 1_106_000).verdict)
        val unreadable = state(screenOn = null)
        assertEquals(Verdict.UNRECORDED, entry(1_000_000, 1_002_000, atFire = unreadable).verdict)
    }

    @Test
    fun `a pending alarm is waiting until its window passes, then reads as not delivered`() {
        val e = entry(dueAtMs = 1_000_000, firedAtMs = null)
        assertEquals(Verdict.WAITING, e.verdict)
        assertEquals("waiting", e.headline(nowMs = 1_030_000))
        assertTrue(e.headline(nowMs = 1_600_000).startsWith("NOT DELIVERED"))
    }

    @Test
    fun `charging voids a pass`() {
        assertFalse(entry(1_000_000, 1_002_000, armed = state(), atFire = state()).voidedByCharging)
        // Armed on the charger: nothing about the run is clean.
        assertTrue(
            entry(1_000_000, 1_002_000, armed = state(plugged = true), atFire = state())
                .voidedByCharging
        )
        // Rang on the charger: Doze never had a chance to hold it.
        assertTrue(
            entry(1_000_000, 1_002_000, armed = state(), atFire = state(plugged = true))
                .voidedByCharging
        )
        // Unknown is not plugged: an old entry is not voided by missing data.
        assertFalse(entry(1, 2).voidedByCharging)
    }

    @Test
    fun `charging does not void a failure that happened unplugged`() {
        // The overnight run exactly: armed unplugged, held 58 minutes, released
        // a minute after a low battery went on the charger. It first read
        // "DOES NOT COUNT", which hid the failure behind its own release.
        val overnight = entry(
            dueAtMs = 1_000_000,
            firedAtMs = 1_000_000 + 3_509_000,
            armed = state(),
            atFire = state(screenOn = true, plugged = true),
        )
        assertEquals(Verdict.HELD_UNTIL_SCREEN_ON, overnight.verdict)
        assertFalse(overnight.voidedByCharging)
        assertTrue(overnight.releasedOnCharger)
    }

    @Test
    fun `an unlogged delivery still gets a verdict from its own due time`() {
        // The 12:03 phantom: armed before a Clear, rang with no entry. Its due
        // time travels in the alarm's intent, so it can still be judged.
        val phantom = SpikeLog.Entry(
            id = 3,
            api = "setAlarmClock",
            armedAtMs = 1_000_000,
            dueAtMs = 1_000_000,
            firedAtMs = 1_001_000,
            coldStart = true,
            atFire = state(screenOn = false),
            unlogged = true,
        )
        assertEquals(Verdict.RANG_IN_DARK, phantom.verdict)
        assertTrue(phantom.unlogged)
    }

    @Test
    fun `a calendar reminder for someone else's event is not ours arriving`() {
        // The broadcast goes out for every calendar on the phone. A meeting
        // at 09:00 must not be recorded as the 06:30 test being delivered.
        val ours = entry(dueAtMs = 1_000_000, firedAtMs = null).copy(api = SpikeLog.API_CALENDAR)
        assertTrue(SpikeLog.calendarMatches(ours, alarmAtMs = 1_000_000, nowMs = 1_000_500))
        assertFalse(SpikeLog.calendarMatches(ours, alarmAtMs = 9_000_000, nowMs = 9_000_500))
        // No time on the broadcast: fall back to "is ours due yet".
        assertTrue(SpikeLog.calendarMatches(ours, alarmAtMs = null, nowMs = 1_000_500))
        assertFalse(SpikeLog.calendarMatches(ours, alarmAtMs = null, nowMs = 999_000))
        // An alarm of ours is never delivered by a calendar broadcast, and a
        // calendar entry is never delivered twice.
        assertFalse(SpikeLog.calendarMatches(entry(1_000_000, null), 1_000_000, 1_000_500))
        assertFalse(SpikeLog.calendarMatches(ours.copy(firedAtMs = 1_000_100), 1_000_000, 1_000_500))
    }

    @Test
    fun `only something overdue and unannounced is caught up, and never a heartbeat`() {
        val due = 1_000_000L
        val late = due + SpikeLog.OVERDUE_AFTER_MS + 1
        assertTrue(SpikeLog.needsNoticing(entry(due, null), late))
        // Mid-delivery: the alarm is a second old. Announcing it now would
        // race the alarm itself and ring twice.
        assertFalse(SpikeLog.needsNoticing(entry(due, null), due + 1_000))
        // Already delivered, or already announced once.
        assertFalse(SpikeLog.needsNoticing(entry(due, due + 500), late))
        assertFalse(SpikeLog.needsNoticing(entry(due, null).copy(noticedAtMs = late), late + 5_000))
        // A missing heartbeat is a result, not a reminder someone is owed.
        assertFalse(SpikeLog.needsNoticing(entry(due, null).copy(api = SpikeLog.API_HEARTBEAT), late))
    }

    @Test
    fun `an exit waits only for a delivery that is due this very moment`() {
        // The emulator lost a delivery because the process ended as it was
        // being sent. An exit outside a receiver now waits if something is
        // due within a few seconds either side -- and only then, or a held
        // alarm hours overdue would keep the process alive for ever, which is
        // the frozen-process failure all over again.
        val due = 1_000_000L
        val pending = entry(due, null)
        assertTrue(SpikeLog.isDueAround(pending, due - 3_000, withinMs = 5_000))
        assertTrue(SpikeLog.isDueAround(pending, due + 4_000, withinMs = 5_000))
        assertFalse(SpikeLog.isDueAround(pending, due - 60_000, withinMs = 5_000))
        assertFalse(SpikeLog.isDueAround(pending, due + 6 * 3_600_000L, withinMs = 5_000))
        // Already arrived: nothing to wait for.
        assertFalse(SpikeLog.isDueAround(entry(due, due + 500), due + 1_000, withinMs = 5_000))
    }

    @Test
    fun `a catch-up says how late it was and what was running`() {
        val e = entry(1_000_000, null).copy(noticedAtMs = 1_000_000 + 344 * 60_000L, noticedBy = "the app being opened")
        assertEquals("the app announced it itself 5h 44m after due (the app being opened)", e.noticedLine())
        assertNull(entry(1_000_000, null).noticedLine())
        // Announced by the app, still not delivered by the phone: the headline
        // must keep saying so.
        assertTrue(e.headline(nowMs = 1_000_000 + 400 * 60_000L).startsWith("NOT DELIVERED"))
    }

    @Test
    fun `durations read as a person would say them`() {
        assertEquals("2s", SpikeLog.formatDuration(2))
        assertEquals("3m 07s", SpikeLog.formatDuration(187))
        assertEquals("1h 04m", SpikeLog.formatDuration(3_840))
        assertEquals("-5s", SpikeLog.formatDuration(-5))
    }

    @Test
    fun `only deaths while an alarm was waiting are charged to it`() {
        val exits = listOf(
            ExitHistory.Exit(atMs = 50, reason = "before arming", description = null),
            ExitHistory.Exit(atMs = 300, reason = "force-stopped", description = null),
            ExitHistory.Exit(atMs = 200, reason = "freezer", description = null),
            ExitHistory.Exit(atMs = 900, reason = "after delivery", description = null),
        )
        val during = ExitHistory.between(exits, fromMs = 100, toMs = 500)
        assertEquals(listOf("freezer", "force-stopped"), during.map { it.reason })
    }
}
