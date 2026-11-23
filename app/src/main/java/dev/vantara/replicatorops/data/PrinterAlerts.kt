package dev.vantara.replicatorops.data

/** A message the printer shows as a pop-up on its own screen (QIDI "Code:QDE_…" errors). */
data class PrinterAlert(val code: String, val message: String, val raw: String)

object PrinterAlerts {
    private val QIDI = Regex("""Code:\s*([A-Z]{2,4}_[0-9_]+)\s*;\s*Message:\s*(.+)""", RegexOption.IGNORE_CASE)

    /** Parses one G-code response line; null if it isn't a printer pop-up. */
    fun parse(line: String): PrinterAlert? {
        val text = line.trim().removePrefix("!!").removePrefix("//").trim()
        val m = QIDI.find(text) ?: return null
        val code = m.groupValues[1].uppercase()
        val message = plain(m.groupValues[2].trim().trimEnd('.').trim()) + "."
        return PrinterAlert(code, message, line.trim())
    }

    /**
     * Makes Box slot names readable: "1D" (Box 1, slot D) -> "Box 1 slot 4",
     * "slot3" (counted from 0) -> "slot 4".
     */
    fun plain(message: String): String = message
        .replace(Regex("""\b([1-4])([A-D])\b""")) { r ->
            "Box ${r.groupValues[1]} slot ${r.groupValues[2][0] - 'A' + 1}"
        }
        .replace(Regex("""\bslot([0-3])\b""", RegexOption.IGNORE_CASE)) { r ->
            "slot ${r.groupValues[1].toInt() + 1}"
        }
}
