package dev.vantara.replicatorops.data

import android.content.Context

/** One release in the Captain's Log (CHANGELOG.md in the app's assets). */
data class LogEntry(val version: String, val stardate: String?, val items: List<String>)

object CaptainsLog {
    private val HEADING = Regex("""^##\s+v?([0-9][0-9A-Za-z.\-]*)\s*(?:[·\-–]\s*Stardate\s+([0-9.]+))?""", RegexOption.IGNORE_CASE)

    fun load(context: Context): List<LogEntry> =
        runCatching { context.assets.open("CHANGELOG.md").bufferedReader().use { parse(it.readText()) } }
            .getOrDefault(emptyList())

    fun parse(text: String): List<LogEntry> {
        val out = mutableListOf<LogEntry>()
        var version: String? = null
        var stardate: String? = null
        val items = mutableListOf<String>()
        fun flush() {
            version?.let { out += LogEntry(it, stardate, items.toList()) }
            items.clear()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            val m = HEADING.find(line)
            when {
                m != null -> {
                    flush()
                    version = m.groupValues[1]
                    stardate = m.groupValues[2].ifBlank { null }
                }
                version != null && (line.startsWith("- ") || line.startsWith("* ")) -> items += line.drop(2).trim()
            }
        }
        flush()
        return out
    }

    /** Entries newer than [lastSeen] (all of them if never seen), newest first, at most [max]. */
    fun since(entries: List<LogEntry>, lastSeen: String?, max: Int = 3): List<LogEntry> {
        val newer = if (lastSeen.isNullOrBlank()) entries.take(1)
        else entries.filter { StarbaseUplink.compareVersions(it.version, lastSeen) > 0 }
        return newer.take(max)
    }
}
