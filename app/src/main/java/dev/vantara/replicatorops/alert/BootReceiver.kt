package dev.vantara.replicatorops.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts Red Alert after a reboot or after a Starbase Uplink update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> RedAlertService.sync(context)
        }
    }
}
