package io.github.saputratanuwijaya.linodea.spike

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
 * Two exits, and both are needed: when the last screen leaves, and shortly
 * after a receiver has done its work. Either one missing leaves a cached
 * process sitting there for XOS to freeze before the next alarm.
 *
 * Costs the user nothing visible, which is the whole attraction. What it
 * costs the code: the app can never do background work beyond the few
 * hundred milliseconds of a receiver, so everything has to be in storage
 * before the process goes.
 */
object EndWhenHidden {
    private const val PREFS = "linodea.alarm.spike.end"
    private const val KEY = "enabled"

    /** Screens currently started. The process ends only when this is zero. */
    @Volatile
    var visibleScreens: Int = 0

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

    /** Called when a screen stops. */
    fun endIfHidden(context: Context) {
        if (visibleScreens == 0 && isEnabled(context)) exitProcess(0)
    }

    /**
     * For receivers: finish the broadcast properly, then end. The second of
     * grace lets a full-screen intent bring the app forward first, in which
     * case the process stays until that screen leaves.
     */
    fun endAfterBroadcast(receiver: BroadcastReceiver, context: Context) {
        if (!isEnabled(context)) return
        val pending = receiver.goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            pending.finish()
            endIfHidden(context)
        }, 1_000)
    }
}
