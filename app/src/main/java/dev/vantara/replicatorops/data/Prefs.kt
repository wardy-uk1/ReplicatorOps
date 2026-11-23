package dev.vantara.replicatorops.data

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("replicatorops", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v).apply()

    /** "24" (TNG/Voyager) or "25" (Picard). */
    var style: String
        get() = sp.getString("style", "24") ?: "24"
        set(v) = sp.edit().putString("style", v).apply()

    var chirps: Boolean
        get() = sp.getBoolean("chirps", true)
        set(v) = sp.edit().putBoolean("chirps", v).apply()

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

    fun endpoint(): Endpoint? = Endpoint.parse(host, apiKey)
}
