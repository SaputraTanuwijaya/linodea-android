package io.github.saputratanuwijaya.linodea.spike

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.system.exitProcess

/**
 * Ends the process whenever nothing is on screen, so there is nothing to freeze.
 *
 * The opposite of `KeepAliveService`, and it exists because that one failed.
 * Observed on a real Infinix (XOS): a live process -- even one holding a
 * running foreground service -- was frozen, and its alarm held 3m 52s until
 * the screen came on. A dead one was not: swiped from Recents before its due
 * time, the alarm made Android start a fresh process, and it rang 0s late with
 * the screen off. So instead of staying alive, the app stops existing between
 * alarms and lets AlarmManager build it from nothing each time.
 *
 * Three exits, and all are needed: when the last screen leaves, shortly after
 * a receiver has done its work, and shortly after a start that nothing
 * followed. The third was added after the 8-hour run: the phone started the
 * app by itself hours before anything was due, and that process sat there
 * until it was reclaimed for memory. Any one missing leaves a cached process
 * for XOS to freeze before the next alarm.
 *
 * **An exit must never throw away a delivery that is on its way.** Android
 * hands a process one broadcast at a time, and the moment one is handed back
 * it sends the next. The first version of this handed the broadcast back and
 * then ended -- so when two reminders were due in the same second, the second
 * was sent to a process that was already dying and was lost for good. Seen on
 * the emulator: three paths due together, the first to arrive ended the
 * process, and `setAlarmClock` never arrived at all. So a receiver now ends
 * the process while it is *still holding* its broadcast (Android then starts a
 * fresh process for the next one), and the other two exits wait if something
 * of ours is due this very moment.
 *
 * Costs the user nothing visible, which is the whole attraction. What it
 * costs the code: the app can never do background work beyond the few
 * hundred milliseconds of a receiver, so everything has to be in storage
 * before the process goes.
 */
object EndWhenHidden {
    private const val PREFS = "linodea.alarm.spike.end"
    private const val KEY = "enabled"

    /**
     * How long a process that nothing has used may live. Long enough that a
     * slow cold launch has shown its screen, short enough to be gone before
     * anything decides to freeze it.
     */
    private const val UNUSED_START_MS = 15_000L

    /** A receiver's time to finish what it started before the process goes. */
    private const val RECEIVER_GRACE_MS = 1_000L

    /**
     * An exit that is not inside a receiver holds no broadcast, so nothing
     * stops Android sending one as the process dies. If something of ours is
     * due within this long either side of now, the exit waits and looks again.
     */
    private const val DUE_GUARD_MS = 5_000L
    private const val LOOK_AGAIN_MS = 3_000L

    /** Screens currently started. The process ends only when this is zero. */
    @Volatile
    var visibleScreens: Int = 0

    /**
     * Receivers still inside their second of grace. Two alarms due together
     * arrive a few hundred milliseconds apart, and the first one's exit must
     * not cut the second one short. Main thread only, like every receiver.
     */
    private var inFlight: Int = 0

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Persisted, because the receivers that need it run in a process that was just born. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY, on).commit()
        // A sticky foreground service makes Android restart the process it
        // lives in, which would undo every exit. The two cannot both be on.
        if (on) KeepAliveService.stop(context)
    }

    private fun hidden(context: Context): Boolean =
        visibleScreens == 0 && inFlight == 0 && isEnabled(context)

    private fun endNow(context: Context, why: String): Nothing {
        Journal.note(context, "ended itself: $why")
        exitProcess(0)
    }

    /**
     * Ends the process if nothing is on screen and no receiver is mid-flight.
     * For the exits that happen outside a receiver: a screen leaving, or a
     * start nothing used.
     */
    fun endIfHidden(context: Context, why: String) {
        if (!hidden(context)) return
        val app = context.applicationContext
        if (SpikeLog.anythingDueAround(app, System.currentTimeMillis(), DUE_GUARD_MS)) {
            // Its broadcast may already be addressed to this process. Stay
            // to receive it; that receiver's own exit will end the process.
            Handler(Looper.getMainLooper()).postDelayed({ endIfHidden(app, why) }, LOOK_AGAIN_MS)
            return
        }
        endNow(app, why)
    }

    /**
     * The same, for the start-that-nothing-used timer. A timer can come due
     * while the app is in the middle of opening, and closing it then would be
     * closing it in the user's face, so it does nothing while Android says
     * this app is in front.
     */
    private fun endIfHiddenAndNotOpening(context: Context, why: String) {
        if (isInFront()) return
        endIfHidden(context, why)
    }

    /** True while a screen of ours is in front, or is being brought there. */
    private fun isInFront(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    /**
     * For receivers: keep hold of the broadcast for a second, then end the
     * process *without handing it back*.
     *
     * Handing it back first is what lost a delivery (see the class note):
     * Android answers by sending the next broadcast at once, to a process
     * with a millisecond to live. Ending while still holding it means the
     * only broadcast that dies with the process is the one already dealt
     * with, and Android starts a fresh process for whatever is next.
     *
     * If the process is not going to end -- a screen is up, or Android says
     * one is on its way, which on Android 15 happens when a just-launched app
     * is sent its restart broadcast before its screen has started -- the
     * broadcast is handed back as normal.
     */
    fun endAfterBroadcast(receiver: BroadcastReceiver, context: Context) {
        if (!isEnabled(context)) return
        val app = context.applicationContext
        val pending: BroadcastReceiver.PendingResult? = receiver.goAsync()
        inFlight += 1
        Handler(Looper.getMainLooper()).postDelayed({
            inFlight -= 1
            if (hidden(app) && !isInFront()) endNow(app, "after a receiver")
            pending?.finish()
        }, RECEIVER_GRACE_MS)
    }

    /**
     * Called once per process. If, a little later, no screen is up and no
     * receiver is working, then whatever started this process wanted nothing
     * from it -- and a process nobody wants is the one that gets frozen.
     */
    fun endIfNothingUsesThisStart(app: Application) {
        Handler(Looper.getMainLooper()).postDelayed({
            endIfHiddenAndNotOpening(app, "started and nothing used it")
        }, UNUSED_START_MS)
    }
}
