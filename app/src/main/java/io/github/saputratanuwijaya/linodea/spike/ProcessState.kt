package io.github.saputratanuwijaya.linodea.spike

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Whether this process was already running when an alarm arrived.
 *
 * The difference matters more than it looks. "The alarm fired" and "the alarm
 * fired, but Android had to rebuild the app from nothing to deliver it" are
 * different results: the second means something killed the process and the
 * alarm survived it anyway, which is precisely the XOS behaviour the spike is
 * trying to observe. Without this, a kill that the alarm survived is
 * indistinguishable from no kill at all.
 */
object ProcessState {
    /**
     * False until the Application object has been alive long enough for the
     * user to have opened the app; a receiver arriving into a fresh process
     * sees false.
     */
    @Volatile
    var wasWarm: Boolean = false
}

class SpikeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Installed here so it covers every entry point, including a receiver
        // waking a cold process at 3am -- the crash least likely to be seen.
        CrashLog.install(this)

        // First line of every process's life. Whatever follows it in the
        // journal -- a receiver, a screen, or nothing -- is what started it.
        Journal.note(this, "process created")

        // `wasWarm` is deliberately NOT set here: process creation is exactly
        // the case being detected. MainActivity sets it, so "warm" means "the
        // user has had this app open since the process started".

        // Counted here rather than in each screen, so the count cannot be
        // forgotten by a screen added later.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                EndWhenHidden.visibleScreens += 1
                Journal.note(this@SpikeApplication, "screen shown")
            }

            override fun onActivityStopped(activity: Activity) {
                EndWhenHidden.visibleScreens -= 1
                // A rotation stops and restarts the screen; ending the process
                // in the gap would look like a crash. Anything else -- Home,
                // Recents, the screen turning off -- is the app leaving.
                if (!activity.isChangingConfigurations) {
                    EndWhenHidden.endIfHidden(this@SpikeApplication, "left the screen")
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        EndWhenHidden.endIfNothingUsesThisStart(this)
    }
}
