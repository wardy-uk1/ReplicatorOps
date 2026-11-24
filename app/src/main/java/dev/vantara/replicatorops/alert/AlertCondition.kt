package dev.vantara.replicatorops.alert

import dev.vantara.replicatorops.data.PrinterState
import kotlin.math.roundToInt

/** Ship's alert condition, from calm to critical. */
enum class Condition(val label: String) {
    GREEN("Condition green"),
    YELLOW("Yellow alert"),
    RED("Red alert"),
    DOUBLE_RED("Double red alert"),
}

data class AlertStatus(val condition: Condition, val reason: String? = null) {
    val active: Boolean get() = condition != Condition.GREEN
}

/**
 * Works out the alert condition from the printer's state as it changes (pure logic,
 * unit tested). Feed it every state with the time; [latch] adds one-off events such as
 * a QIDI error message, which stay until [clearLatched] (acknowledged) or 15 minutes pass.
 *
 * - Double red: Klipper has shut down or failed (e.g. a heater fault / thermal runaway),
 *   or contact with the printer was lost during a print for over 30 seconds.
 * - Red: the print failed, or paused without you asking (filament runout and the like),
 *   or a printer error message.
 * - Yellow: you paused the print, a heater is struggling to reach its temperature, or the
 *   QIDI Box is damp.
 */
class AlertMonitor {
    /** Set when the pause came from this app (yellow rather than red). */
    var userPaused = false

    private var busySeen = false
    private var lostSince: Long? = null
    private val heatingSince = HashMap<String, Pair<Double, Long>>()
    private data class Latched(val condition: Condition, val reason: String, val at: Long)
    private val latched = ArrayList<Latched>()

    fun latch(condition: Condition, reason: String, now: Long) {
        latched.removeAll { it.reason == reason }
        latched += Latched(condition, reason, now)
    }

    fun clearLatched() = latched.clear()

    fun update(p: PrinterState, now: Long): AlertStatus {
        latched.removeAll { now - it.at > 15 * 60_000 }

        // Contact lost during a print.
        if (p.online) {
            lostSince = null
            busySeen = p.ready && p.isBusy
        } else if (busySeen) {
            if (lostSince == null) lostSince = now
        }
        if (!p.isPaused) userPaused = false

        // Heaters that aren't getting there: far below target for too long since it was set.
        val slow = ArrayList<String>()
        for (h in p.heaters) {
            val target = h.target ?: 0.0
            if (!p.ready || target < 40) { heatingSince.remove(h.objectName); continue }
            val (t0, since) = heatingSince[h.objectName]?.takeIf { it.first == target } ?: (target to now).also { heatingSince[h.objectName] = it }
            if (h.temperature >= t0 - 10) { heatingSince[h.objectName] = target to now; continue }
            val limit = if (h.objectName == "extruder") 8 * 60_000 else 15 * 60_000
            if (now - since > limit) slow += "${h.label} heating slowly (${h.temperature.roundToInt()}° of ${target.roundToInt()}°)"
        }

        val name = p.filename?.substringAfterLast('/')?.substringBeforeLast('.')
        return when {
            p.online && (p.klippy == "shutdown" || p.klippy == "error") ->
                AlertStatus(Condition.DOUBLE_RED, p.klippyMessage?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: "Klipper ${p.klippy}")
            lostSince != null && now - lostSince!! > 30_000 ->
                AlertStatus(Condition.DOUBLE_RED, "Lost contact with the printer during a print")
            p.ready && p.printState == "error" ->
                AlertStatus(Condition.RED, "Replication failed" + (p.message?.let { ": $it" } ?: ""))
            p.ready && p.isPaused && !userPaused ->
                AlertStatus(Condition.RED, (name?.let { "$it paused" } ?: "Replication paused") + (p.message?.let { ": $it" } ?: " and needs attention"))
            latched.any { it.condition == Condition.RED } ->
                AlertStatus(Condition.RED, latched.last { it.condition == Condition.RED }.reason)
            p.ready && p.isPaused ->
                AlertStatus(Condition.YELLOW, (name?.let { "$it paused" } ?: "Replication paused"))
            slow.isNotEmpty() -> AlertStatus(Condition.YELLOW, slow.first())
            (p.box?.humidity ?: 0.0) > 65.0 ->
                AlertStatus(Condition.YELLOW, "QIDI Box humidity ${p.box!!.humidity!!.roundToInt()}%: dry the filament")
            latched.any { it.condition == Condition.YELLOW } ->
                AlertStatus(Condition.YELLOW, latched.last { it.condition == Condition.YELLOW }.reason)
            else -> AlertStatus(Condition.GREEN)
        }
    }
}
