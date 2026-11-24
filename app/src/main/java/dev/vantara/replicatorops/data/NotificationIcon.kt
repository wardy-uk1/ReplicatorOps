package dev.vantara.replicatorops.data

import androidx.annotation.DrawableRes
import dev.vantara.replicatorops.R

/** The symbols you can pick for the app's notifications (Config → LCARS System). */
enum class NotificationIcon(val id: String, val label: String, @DrawableRes val res: Int) {
    REPLICATOR("replicator", "Replicator", R.drawable.ic_stat_replicator),
    GEAR("gear", "Engineering", R.drawable.ic_stat_gear),
    ELBOW("elbow", "LCARS", R.drawable.ic_stat_elbow),
    CHIP("chip", "Isolinear", R.drawable.ic_stat_chip);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: REPLICATOR
    }
}
