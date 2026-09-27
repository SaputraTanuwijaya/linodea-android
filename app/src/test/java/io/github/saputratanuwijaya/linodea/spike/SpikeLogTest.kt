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
