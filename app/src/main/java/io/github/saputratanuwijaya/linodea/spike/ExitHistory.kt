package io.github.saputratanuwijaya.linodea.spike

import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * How this app's process has died recently, as the system recorded it.
 *
 * Separates the two ways an OEM can stop an alarm. A **freeze** leaves the
 * process alive and leaves no record here -- the alarm arrives late into a
 * warm process. A **kill** ends the process and is recorded, with a reason; a
 * force-stop also wipes every alarm the app had armed, so the alarm never
 * arrives at all. Without this, all of them look like "it didn't ring".
 *
 * The reason alone was not enough: the overnight run showed a
 * "force-stopped or swiped" one minute before due, and the reason cannot say
 * whether a person or the OEM's cleaner did it. The system's free-text
 * description and the process's standing at death (on screen, or in the
 * background) can.
 *
 * Android 11+; older versions keep no such history. The system keeps it across
 * app updates, so a newer build can read deaths an older one lived through.
 */
object ExitHistory {

    data class Exit(
        val atMs: Long,
        val reason: String,
        val description: String?,
        /** What the process was doing when it died: "on screen", "background", ... */
        val standing: String? = null,
    )

    /** Null when the platform cannot say, which is different from "none". */
    fun recent(context: Context, max: Int = 16): List<Exit>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, max).map {
                Exit(it.timestamp, reasonName(it.reason), it.description, standingName(it.importance))
            }
        }.getOrNull()
    }

    /** Deaths while an alarm was waiting: after it was armed, up to its delivery (or now). */
    fun between(exits: List<Exit>, fromMs: Long, toMs: Long): List<Exit> =
        exits.filter { it.atMs in fromMs..toMs }.sortedBy { it.atMs }

    /**
     * The process's importance at death, in words. "On screen" at a
     * user-requested kill means a person had the app open; "background" means
     * something else reached in and killed it.
     */
    fun standingName(importance: Int): String = when {
        importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "on screen"
        importance <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground service"
        importance <= RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        importance <= RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        importance <= RunningAppProcessInfo.IMPORTANCE_SERVICE -> "running a service"
        // TOP_SLEEPING: the top app, with the screen off.
        importance <= 325 -> "on top, screen off"
        importance <= RunningAppProcessInfo.IMPORTANCE_CACHED -> "background"
        else -> "gone ($importance)"
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "ended itself"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        // Both land here. Only a force-stop wipes the alarms; a swipe from
        // Recents was observed on XOS to leave them armed (S95, run #1).
        ApplicationExitInfo.REASON_USER_REQUESTED -> "force-stopped or swiped from Recents"
        ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by user"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_OTHER -> "killed by the system"
        // Constants below are newer than R, but are compile-time ints, so
        // naming them is safe on any version that returned them.
        ApplicationExitInfo.REASON_FREEZER -> "killed by the freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state changed"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
        else -> "reason $reason"
    }
}
