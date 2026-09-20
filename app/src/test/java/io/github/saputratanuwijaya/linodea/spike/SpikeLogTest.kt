package io.github.saputratanuwijaya.linodea.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Drift is the only number this spike produces, and "it fired 30 seconds late"
 * and "it fired 40 minutes late" are different answers about whether the
 * architecture works. Worth the ten lines.
 */
class SpikeLogTest {

    private fun entry(dueAtMs: Long, firedAtMs: Long?) = SpikeLog.Entry(
        id = 1,
        api = "setAlarmClock",
        armedAtMs = 0L,
        dueAtMs = dueAtMs,
        firedAtMs = firedAtMs,
        coldStart = false,
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
}
