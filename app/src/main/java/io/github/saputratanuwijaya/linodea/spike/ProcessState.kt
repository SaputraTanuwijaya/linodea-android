package io.github.saputratanuwijaya.linodea.spike

import android.app.Application

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

        // `wasWarm` is deliberately NOT set here: process creation is exactly
        // the case being detected. MainActivity sets it, so "warm" means "the
        // user has had this app open since the process started".
    }
}
