package io.github.saputratanuwijaya.linodea.spike

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash so it can be read on the next launch.
 *
 * This app is installed by sideload with USB debugging deliberately off, so
 * there is no logcat to look at — a crash is a notification that vanishes in a
 * second and then nothing. The first build crashed on open and the only report
 * available was "it crashed", which is not enough to fix anything.
 *
 * Same principle as the alarm records: an app that cannot be observed through
 * the usual tools has to observe itself.
 */
object CrashLog {
    private const val PREFS = "linodea.alarm.spike.crash"
    private const val KEY = "last"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Chain onto whatever handler already exists rather than replacing it.
     * Dropping the previous one would stop the process dying properly, which
     * leaves the app wedged instead of restarting cleanly.
     */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { save(context, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun save(context: Context, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("EEE HH:mm:ss", Locale.getDefault()).format(Date())
        // commit(), not apply(): the process is already on its way out and a
        // queued write is the one that never lands.
        prefs(context).edit().putString(KEY, "$stamp\n\n$stack").commit()
    }

    fun last(context: Context): String? = prefs(context).getString(KEY, null)

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).commit()
    }
}
