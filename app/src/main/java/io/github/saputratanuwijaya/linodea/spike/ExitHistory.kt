package io.github.saputratanuwijaya.linodea.spike

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * How this app's process has died recently, as the system recorded it.
 *
 * Separates the two ways an OEM can stop an alarm. A **freeze** leaves the
 * process alive and leaves no record here -- the alarm arrives late into a
 * warm process. A **kill** ends the process and is recorded, with a reason:
 * "force-stopped or swiped" also wipes every alarm the app had armed, so the
 * alarm never arrives at all. Without this, both look like "it didn't ring".
 *
 * Android 11+; older versions keep no such history.
 */
object ExitHistory {

    data class Exit(val atMs: Long, val reason: String, val description: String?)

    /** Null when the platform cannot say, which is different from "none". */
    fun recent(context: Context, max: Int = 16): List<Exit>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            manager.getHistoricalProcessExitReasons(context.packageName, 0, max).map {
                Exit(it.timestamp, reasonName(it.reason), it.description)
            }
        }.getOrNull()
    }

    /** Deaths while an alarm was waiting: after it was armed, up to its delivery (or now). */
    fun between(exits: List<Exit>, fromMs: Long, toMs: Long): List<Exit> =
        exits.filter { it.atMs in fromMs..toMs }.sortedBy { it.atMs }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission changed"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "force-stopped or swiped (alarms wiped)"
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
