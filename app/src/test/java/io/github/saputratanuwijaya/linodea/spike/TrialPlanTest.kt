package io.github.saputratanuwijaya.linodea.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A test run is read the morning after by someone deciding whether to build
 * the companion at all. The arithmetic behind "when was it due" and "when did
 * the hold start" has to be right before the phone is involved.
 */
class TrialPlanTest {
    private val minute = 60_000L

    private fun beat(dueAtMs: Long, firedAtMs: Long?, screenOn: Boolean = false) = SpikeLog.Entry(
        id = 1,
        api = SpikeLog.API_HEARTBEAT,
        armedAtMs = 0L,
        dueAtMs = dueAtMs,
        firedAtMs = firedAtMs,
        coldStart = true,
        atFire = firedAtMs?.let {
            SpikeLog.Snapshot(
                screenOn = screenOn, locked = true, plugged = false, keepAlive = false, batteryExempt = true,
            )
        },
    )

    private val clock: (Long) -> String = { "t${it / minute}" }

    @Test
    fun `a test is due on a whole minute and never sooner than asked`() {
        // 27 seconds into a minute, asking for 6: the next whole minute at or after +6m.
        val now = 100 * minute + 27_000
        val due = TrialPlan.dueAt(now, 6)
        assertEquals(0L, due % minute)
        assertTrue(due - now >= 6 * minute)
        assertTrue(due - now < 7 * minute)
    }

    @Test
    fun `a test started exactly on the minute is not pushed a minute later`() {
        assertEquals(106 * minute, TrialPlan.dueAt(100 * minute, 6))
    }

    @Test
    fun `heartbeats count back from the due time and never land on it`() {
        val now = 100 * minute
        val due = 106 * minute
        // Every 2 minutes before 106: 104 and 102. Not 106 (that moment belongs
        // to the paths under test) and not 100 (already now).
        assertEquals(listOf(102 * minute, 104 * minute), TrialPlan.beatTimes(now, due, 2 * minute))
    }

    @Test
    fun `a heartbeat too close to now is dropped rather than armed in the past`() {
        val now = 100 * minute + 30_000
        // 101:00 is 30 seconds away: the app would still be on screen.
        assertEquals(listOf(103 * minute), TrialPlan.beatTimes(now, 105 * minute, 2 * minute))
    }

    @Test
    fun `eight hours of half-hour heartbeats is fifteen`() {
        val now = 1_000 * minute
        assertEquals(15, TrialPlan.beatTimes(now, now + 480 * minute, 30 * minute).size)
    }

    @Test
    fun `no interval means no heartbeats, not an endless loop`() {
        assertEquals(emptyList<Long>(), TrialPlan.beatTimes(0, 480 * minute, 0))
    }

    @Test
    fun `a ladder with every beat on time is unbroken`() {
        val beats = listOf(beat(10 * minute, 10 * minute + 1_000), beat(20 * minute, 20 * minute + 2_000))
        val ladder = TrialPlan.ladder(beats, nowMs = 30 * minute)
        assertEquals(2, ladder.onTime)
        assertTrue(ladder.unbroken)
        assertNull(ladder.firstMissAtMs)
        assertTrue(TrialPlan.ladderSentence(ladder, clock).startsWith("all 2 arrived on time"))
    }

    @Test
    fun `the first miss is where the hold started`() {
        // On time at 10 and 20; 30 arrived an hour late; 40 never came. This
        // is the shape both 8-hour runs are expected to have, and the point
        // of the ladder is the time it names.
        val beats = listOf(
            beat(10 * minute, 10 * minute + 1_000),
            beat(20 * minute, 20 * minute + 1_000),
            beat(30 * minute, 90 * minute, screenOn = true),
            beat(40 * minute, null),
        )
        val ladder = TrialPlan.ladder(beats, nowMs = 100 * minute)
        assertEquals(2, ladder.onTime)
        assertEquals(1, ladder.late)
        assertEquals(1, ladder.missing)
        assertEquals(20 * minute, ladder.lastOnTimeAtMs)
        assertEquals(30 * minute, ladder.firstMissAtMs)
        assertFalse(ladder.unbroken)
        assertFalse(ladder.recovered)
        val sentence = TrialPlan.ladderSentence(ladder, clock)
        assertTrue(sentence, sentence.contains("on time until t20"))
        assertTrue(sentence, sentence.contains("THE HOLD STARTED BY t30"))
    }

    @Test
    fun `a beat on time after a miss means the hold lifted by itself`() {
        // Different from "held until the charger": something let go. The two
        // must not read the same.
        val beats = listOf(
            beat(10 * minute, 10 * minute + 1_000),
            beat(20 * minute, null),
            beat(30 * minute, 30 * minute + 1_000),
        )
        val ladder = TrialPlan.ladder(beats, nowMs = 100 * minute)
        assertTrue(ladder.recovered)
        assertTrue(TrialPlan.ladderSentence(ladder, clock).contains("the hold lifted by itself"))
    }

    @Test
    fun `a beat that is not due yet is neither on time nor missing`() {
        val beats = listOf(beat(10 * minute, 10 * minute + 1_000), beat(50 * minute, null))
        val ladder = TrialPlan.ladder(beats, nowMs = 20 * minute)
        assertEquals(1, ladder.notDueYet)
        assertEquals(0, ladder.missing)
        assertTrue(ladder.unbroken)
        assertEquals("1 of 2 on time so far, none missed", TrialPlan.ladderSentence(ladder, clock))
    }

    @Test
    fun `a ladder where nothing arrived says the hold began at the first beat`() {
        val beats = listOf(beat(10 * minute, null), beat(20 * minute, null))
        val sentence = TrialPlan.ladderSentence(TrialPlan.ladder(beats, nowMs = 100 * minute), clock)
        assertTrue(sentence, sentence.startsWith("NONE arrived on time"))
        assertTrue(sentence, sentence.contains("t10"))
    }

    @Test
    fun `an on-time beat counts whatever the screen was doing`() {
        // During normal evening use the screen is often on. That says nothing
        // against the beat: the question the ladder asks is only "did the
        // phone still deliver to us at this hour".
        val ladder = TrialPlan.ladder(listOf(beat(10 * minute, 10 * minute + 500, screenOn = true)), 20 * minute)
        assertEquals(1, ladder.onTime)
    }
}
