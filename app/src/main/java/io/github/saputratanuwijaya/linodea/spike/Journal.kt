package io.github.saputratanuwijaya.linodea.spike

import android.content.Context
import java.io.File

/**
 * Every time this process comes into being, does something, or ends itself.
 *
 * The 8-hour run that failed showed a Linodea process being reclaimed for
 * memory at 17:46, ninety minutes after the app had ended itself and hours
 * before anything was due. Something had started it, and nothing could say
 * what: the system's own start history came back empty on that phone. So the
 * app keeps its own. A line reading "process created" with no receiver or
 * screen after it is the phone starting the app for reasons of its own.
 *
 * A plain appended file, because it is written from the first line of
 * `Application.onCreate` and from receivers about to exit: one write, no
 * parsing, nothing queued.
 */
object Journal {
    private const val FILE = "journal.tsv"
    private const val MAX_BYTES = 48 * 1024
    private const val KEEP_LINES = 250

    data class Line(val atMs: Long, val text: String)

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    fun note(context: Context, text: String) {
        runCatching {
            val file = file(context)
            if (file.length() > MAX_BYTES) {
                file.writeText(file.readLines().takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
            }
            file.appendText("${System.currentTimeMillis()}\t${text.replace('\n', ' ')}\n")
        }
    }

    fun recent(context: Context, max: Int = KEEP_LINES): List<Line> = runCatching {
        file(context).readLines().takeLast(max).mapNotNull(::parse)
    }.getOrDefault(emptyList())

    fun parse(line: String): Line? {
        val tab = line.indexOf('\t')
        if (tab <= 0) return null
        val at = line.substring(0, tab).toLongOrNull() ?: return null
        return Line(at, line.substring(tab + 1))
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
