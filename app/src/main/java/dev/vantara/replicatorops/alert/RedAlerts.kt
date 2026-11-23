package dev.vantara.replicatorops.alert

import dev.vantara.replicatorops.data.PrinterState
import dev.vantara.replicatorops.ui.lcars.formatDuration

/** What a Red Alert notification says. */
data class Alert(val title: String, val text: String, val severe: Boolean)

/** What the ongoing (watch) notification shows. */
data class Watch(val title: String, val text: String, val percent: Int?, val paused: Boolean, val printing: Boolean)

/** Pure logic for Red Alert, kept apart from Android so it can be unit tested. */
object RedAlerts {

    private val BUSY = setOf("printing", "paused")

    /**
     * Alert for a change of print state, or null. Only fires when the previous state is
     * known and the printer was busy (or, for pauses, printing), so connecting doesn't alert.
     */
    fun transition(prev: PrinterState?, now: PrinterState): Alert? {
        if (prev == null || !prev.ready || !now.ready) return null
        val from = prev.printState
        val to = now.printState
        if (from == to) return null
        val name = (now.filename ?: prev.filename)?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Print"
        return when {
            from == "printing" && to == "paused" -> Alert(
                "Red Alert: replication paused",
                (now.message?.let { "$it · " } ?: "") + "$name needs attention (filament runout or a pause).",
                severe = true,
            )
            from in BUSY && to == "complete" -> Alert(
                "Replication complete",
                "$name is ready · ${formatDuration(now.printDuration.takeIf { it > 0 } ?: prev.printDuration)}",
                severe = false,
            )
            from in BUSY && to == "error" -> Alert(
                "Red Alert: replication failed",
                (now.message ?: "Klipper reported an error") + " · $name",
                severe = true,
            )
            from in BUSY && to == "cancelled" -> Alert(
                "Replication cancelled",
                "$name was cancelled.",
                severe = false,
            )
            else -> null
        }
    }

    /** Text for the ongoing notification. */
    fun watch(p: PrinterState, host: String): Watch {
        val name = p.filename?.substringAfterLast('/')?.substringBeforeLast('.')
        return when {
            !p.online -> Watch("Subspace link lost", "Trying to reach $host", null, false, false)
            p.klippy != "ready" -> Watch("Klipper ${p.klippy}", host, null, false, false)
            p.isBusy -> {
                val pct = (p.progress * 100).toInt().coerceIn(0, 100)
                val left = p.remainingSeconds?.let { " · ${formatDuration(it)} left" } ?: ""
                val verb = if (p.isPaused) "Paused" else "Replicating"
                Watch("$verb $pct%$left", name ?: host, pct, p.isPaused, p.isPrinting)
            }
            else -> Watch("Standing by", "Red Alert is watching $host", null, false, false)
        }
    }
}
