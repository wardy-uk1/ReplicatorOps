package dev.vantara.replicatorops.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One printer in the Fleet registry. */
@Serializable
data class PrinterProfile(
    val id: Long,
    val name: String,
    val host: String,
    val apiKey: String = "",
)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("replicatorops", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // ---- Fleet registry --------------------------------------------------------
    // Older versions kept one printer in "host"/"api_key"; it becomes the first entry.

    var printers: List<PrinterProfile>
        get() {
            val stored = sp.getString("printers", null)
                ?.let { runCatching { json.decodeFromString<List<PrinterProfile>>(it) }.getOrNull() }
            if (stored != null) return stored
            val legacy = sp.getString("host", "").orEmpty()
            if (legacy.isBlank()) return emptyList()
            val first = PrinterProfile(1L, legacy, legacy, sp.getString("api_key", "").orEmpty())
            sp.edit().putString("printers", json.encodeToString(listOf(first))).putLong("active_printer", 1L).apply()
            return listOf(first)
        }
        set(v) = sp.edit().putString("printers", json.encodeToString(v)).apply()

    var activeId: Long
        get() = sp.getLong("active_printer", printers.firstOrNull()?.id ?: 0L)
        set(v) = sp.edit().putLong("active_printer", v).apply()

    val activePrinter: PrinterProfile?
        get() = printers.let { list -> list.firstOrNull { it.id == activeId } ?: list.firstOrNull() }

    /** Saves a printer (new or edited) and returns it. */
    fun savePrinter(p: PrinterProfile): PrinterProfile {
        val list = printers
        printers = if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p
        return p
    }

    fun removePrinter(id: Long) {
        printers = printers.filterNot { it.id == id }
        if (activeId == id) activeId = printers.firstOrNull()?.id ?: 0L
    }

    /** Address of the active printer. Setting it edits the active printer (or creates one). */
    var host: String
        get() = activePrinter?.host.orEmpty()
        set(v) = updateActive { it.copy(host = v, name = if (it.name.isBlank() || it.name == it.host) v else it.name) }

    var apiKey: String
        get() = activePrinter?.apiKey.orEmpty()
        set(v) = updateActive { it.copy(apiKey = v) }

    private fun updateActive(change: (PrinterProfile) -> PrinterProfile) {
        val current = activePrinter ?: PrinterProfile(System.currentTimeMillis(), "", "").also { activeId = it.id }
        savePrinter(change(current))
    }

    /** "24" (TNG/Voyager) or "25" (Picard). */
    var style: String
        get() = sp.getString("style", "24") ?: "24"
        set(v) = sp.edit().putString("style", v).apply()

    var chirps: Boolean
        get() = sp.getBoolean("chirps", true)
        set(v) = sp.edit().putBoolean("chirps", v).apply()

    /** Double red alert sounds on the alarm volume, through Do Not Disturb. */
    var alertThroughDnd: Boolean
        get() = sp.getBoolean("alert_through_dnd", true)
        set(v) = sp.edit().putBoolean("alert_through_dnd", v).apply()

    /** Symbol shown in notifications: replicator, gear, elbow or chip (see NotificationIcon). */
    var notificationIcon: String
        get() = sp.getString("notification_icon", "replicator") ?: "replicator"
        set(v) = sp.edit().putString("notification_icon", v).apply()

    /** Last version whose Captain's Log entry the user has acknowledged. */
    var lastSeenVersion: String
        get() = sp.getString("last_seen_version", "") ?: ""
        set(v) = sp.edit().putString("last_seen_version", v).apply()

    /** Red Alert: keep a background link to the printer and send notifications. */
    var redAlert: Boolean
        get() = sp.getBoolean("red_alert", true)
        set(v) = sp.edit().putBoolean("red_alert", v).apply()

    /** True once we've asked for the notification permission (Android 13+). */
    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notifications", false)
        set(v) = sp.edit().putBoolean("asked_notifications", v).apply()

    /** Macro names starred on the Ship systems screen. */
    /** Saved slicer presets. */
    var slicePresets: List<dev.vantara.replicatorops.slicer.SlicePreset>
        get() = sp.getString("slice_presets", null)
            ?.let { runCatching { json.decodeFromString<List<dev.vantara.replicatorops.slicer.SlicePreset>>(it) }.getOrNull() }
            .orEmpty()
        set(v) = sp.edit().putString("slice_presets", json.encodeToString(v)).apply()

    var favouriteMacros: Set<String>
        get() = sp.getStringSet("fav_macros", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("fav_macros", v).apply()

    /** First-layer watch: send a camera snapshot when layer 2 starts. */
    var firstLayerWatch: Boolean
        get() = sp.getBoolean("first_layer_watch", true)
        set(v) = sp.edit().putBoolean("first_layer_watch", v).apply()

    fun endpoint(): Endpoint? = Endpoint.parse(host, apiKey)
}
