package dev.vantara.replicatorops.ui

import dev.vantara.replicatorops.data.PrinterState
import dev.vantara.replicatorops.ui.lcars.formatDuration

/** Things you can say after tapping the combadge ("Computer, …"). */
enum class VoiceCommand {
    STATUS, TEMPERATURES, TIME_LEFT, PAUSE, RESUME, ABORT,
    LIGHTS_ON, LIGHTS_OFF, COOL_DOWN, HOME, NEXT_IN_QUEUE, UNKNOWN,
}

object Voice {

    /** Turns what was heard into a command. "Computer" at the start is optional. */
    fun parse(heard: String): VoiceCommand {
        val t = heard.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("computer").trim()
        fun has(vararg words: String) = words.any { Regex("\\b$it\\b").containsMatchIn(t) }
        return when {
            has("abort", "cancel", "stop replication", "stop print", "stop the print") -> VoiceCommand.ABORT
            has("resume", "continue", "carry on", "unpause") -> VoiceCommand.RESUME
            has("pause", "hold") -> VoiceCommand.PAUSE
            has("lights on", "light on", "illumination on", "lights up") -> VoiceCommand.LIGHTS_ON
            has("lights off", "light off", "illumination off", "lights down") -> VoiceCommand.LIGHTS_OFF
            has("cool down", "cooldown", "cool", "heaters off") -> VoiceCommand.COOL_DOWN
            has("home", "homing") -> VoiceCommand.HOME
            has("next", "queue", "engage next") -> VoiceCommand.NEXT_IN_QUEUE
            has("temperature", "temperatures", "temps", "temp", "heat") -> VoiceCommand.TEMPERATURES
            has("time", "how long", "remaining", "eta", "left") -> VoiceCommand.TIME_LEFT
            has("status", "report", "progress", "how is", "how's") -> VoiceCommand.STATUS
            else -> VoiceCommand.UNKNOWN
        }
    }

    /** "Replicating, 42 percent complete, 51 minutes remaining. Nozzle 220 degrees, bed 60." */
    fun statusReport(p: PrinterState): String = when {
        !p.online -> "Unable to comply. Subspace link to the replicator is offline."
        p.klippy != "ready" -> "Replicator systems are not ready. Klipper reports ${p.klippy}."
        p.isBusy -> {
            val pct = (p.progress * 100).toInt()
            val verb = if (p.isPaused) "Replication paused" else "Replicating"
            "$verb, $pct percent complete. ${timeLeft(p)} ${temperatures(p)}"
        }
        p.printState == "complete" -> "Replication complete. ${temperatures(p)}"
        p.printState == "error" -> "Warning. The last replication failed. ${p.message ?: ""}".trim()
        else -> "Replicator standing by. ${temperatures(p)}"
    }

    fun timeLeft(p: PrinterState): String {
        val left = p.remainingSeconds ?: return if (p.isBusy) "Time remaining is not yet known." else "No replication in progress."
        return "${spokenDuration(left)} remaining."
    }

    fun temperatures(p: PrinterState): String {
        val noz = p.heaters.firstOrNull { it.objectName == "extruder" }?.temperature
        val bed = p.heaters.firstOrNull { it.objectName == "heater_bed" }?.temperature
        val parts = listOfNotNull(
            noz?.let { "Nozzle ${it.toInt()} degrees" },
            bed?.let { "bed ${it.toInt()}" },
        )
        return if (parts.isEmpty()) "" else parts.joinToString(", ") + "."
    }

    private fun spokenDuration(seconds: Double): String {
        val m = (seconds / 60).toInt()
        val h = m / 60
        val mm = m % 60
        return when {
            h > 0 && mm > 0 -> "$h hour${if (h > 1) "s" else ""} $mm minute${if (mm != 1) "s" else ""}"
            h > 0 -> "$h hour${if (h > 1) "s" else ""}"
            m > 0 -> "$m minute${if (m != 1) "s" else ""}"
            else -> "Less than a minute"
        }.ifEmpty { formatDuration(seconds) }
    }
}
