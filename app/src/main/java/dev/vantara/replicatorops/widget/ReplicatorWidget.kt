package dev.vantara.replicatorops.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import dev.vantara.replicatorops.MainActivity
import dev.vantara.replicatorops.R
import dev.vantara.replicatorops.data.PrinterState
import dev.vantara.replicatorops.data.Prefs
import dev.vantara.replicatorops.ui.lcars.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** What the home screen widget shows. */
data class WidgetData(
    val state: String,
    val percent: Int?,
    val left: String?,
    val file: String?,
    val alert: Boolean,
) {
    companion object {
        fun of(printState: String?, online: Boolean, progress: Double, duration: Double, filename: String?): WidgetData {
            if (!online) return WidgetData("Offline", null, null, null, alert = true)
            val busy = printState == "printing" || printState == "paused"
            val label = when (printState) {
                "printing" -> "Replicating"
                "paused" -> "Paused"
                "complete" -> "Complete"
                "cancelled" -> "Cancelled"
                "error" -> "Error"
                else -> "Standing by"
            }
            val pct = if (busy || printState == "complete") (progress * 100).toInt().coerceIn(0, 100) else null
            val left = if (busy && progress > 0.01 && duration > 0) formatDuration(duration / progress - duration) + " left" else null
            val name = filename?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { busy || printState == "complete" }
            return WidgetData(label, pct, left, name, alert = printState == "error" || printState == "paused")
        }

        fun of(p: PrinterState): WidgetData =
            of(if (p.ready) p.printState else null, p.ready, p.progress, p.printDuration, p.filename)
    }
}

/**
 * Resizable LCARS home screen widget: status, progress bar, percent, time left and file.
 * Updated live by Red Alert and by the open app, and every 30 minutes on its own.
 */
class ReplicatorWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                // Ask the printer; only fall back to the last pushed state if it can't be reached.
                val data = fetch(context)?.also { last = it } ?: last ?: WidgetData("Offline", null, null, null, true)
                ids.forEach { render(context, manager, it, data) }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        render(context, manager, id, last ?: WidgetData("Standing by", null, null, null, false))
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var last: WidgetData? = null

        /** Push new data to every widget (no-op if nothing changed or no widgets). */
        fun push(context: Context, data: WidgetData) {
            if (data == last) return
            last = data
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ReplicatorWidget::class.java))
            ids.forEach { render(context, manager, it, data) }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int, d: WidgetData) {
            val options = manager.getAppWidgetOptions(id)
            val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
            val roomy = height == 0 || height >= 90
            val v = RemoteViews(context.packageName, R.layout.widget_replicator)
            v.setTextViewText(R.id.widget_state, d.state)
            v.setTextColor(R.id.widget_state, if (d.alert) 0xFFDD4444.toInt() else 0xFFFFCC66.toInt())
            v.setTextViewText(R.id.widget_percent, d.percent?.let { "$it%" } ?: "")
            if (d.percent != null) {
                v.setViewVisibility(R.id.widget_progress, View.VISIBLE)
                v.setProgressBar(R.id.widget_progress, 100, d.percent, false)
            } else {
                v.setViewVisibility(R.id.widget_progress, View.GONE)
            }
            val detail = listOfNotNull(d.left, d.file).joinToString(" · ")
            v.setTextViewText(R.id.widget_detail, detail.ifEmpty { "Replicator Ops" })
            v.setViewVisibility(R.id.widget_detail, if (roomy || d.percent == null) View.VISIBLE else View.GONE)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(id, v)
        }

        /** One-off query for the periodic update; null if the printer can't be reached. */
        private fun fetch(context: Context): WidgetData? {
            val ep = Prefs(context).endpoint() ?: return WidgetData("No printer set", null, null, null, false)
            val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
            val req = Request.Builder()
                .url(ep.resolve("/printer/objects/query?print_stats&display_status"))
                .apply { ep.apiKey?.let { header("X-Api-Key", it) } }
                .build()
            return runCatching {
                http.newCall(req).execute().use { r ->
                    val status = Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["result"]
                        ?.jsonObject?.get("status")?.jsonObject
                    val ps = status?.get("print_stats") as? JsonObject
                    val ds = status?.get("display_status") as? JsonObject
                    fun JsonObject?.str(k: String) = (this?.get(k) as? JsonPrimitive)?.contentOrNull
                    fun JsonObject?.num(k: String) = (this?.get(k) as? JsonPrimitive)?.doubleOrNull
                    WidgetData.of(ps.str("state"), r.isSuccessful && ps != null, ds.num("progress") ?: 0.0, ps.num("print_duration") ?: 0.0, ps.str("filename"))
                }
            }.getOrNull()
        }
    }
}
