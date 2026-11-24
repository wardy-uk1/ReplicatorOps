package dev.vantara.replicatorops.alert

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.vantara.replicatorops.MainActivity
import dev.vantara.replicatorops.R
import dev.vantara.replicatorops.data.MoonrakerClient
import dev.vantara.replicatorops.data.PrinterState
import dev.vantara.replicatorops.data.NotificationIcon
import dev.vantara.replicatorops.data.Prefs
import dev.vantara.replicatorops.data.ReplicationQueue
import dev.vantara.replicatorops.widget.ReplicatorWidget
import dev.vantara.replicatorops.ui.screens.cameraSnapshot
import dev.vantara.replicatorops.widget.WidgetData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Red Alert: a foreground service that stays linked to the printer and posts
 * notifications when a print finishes, fails, is cancelled or pauses, plus an
 * ongoing "Replicating 42% · 51m left" notification.
 */
class RedAlertService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val client = MoonrakerClient(scope, http)
    private var host = ""
    private val queue by lazy { ReplicationQueue.get(this) }
    private var lastWatch: Watch? = null
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = Prefs(this)
        val ep = prefs.endpoint()
        // Must call startForeground promptly, even if we're about to stop.
        startInForeground(watchNotification(lastWatch ?: Watch("Standing by", "Opening subspace link", null, false, false)))
        if (!prefs.redAlert || ep == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> { scope.launch { runCatching { client.call("printer.print.pause") } }; return START_STICKY }
            ACTION_RESUME -> { scope.launch { runCatching { client.call("printer.print.resume") } }; return START_STICKY }
            ACTION_NEXT -> { scope.launch { startNext() }; return START_STICKY }
        }
        if (client.endpoint != ep) {
            host = ep.host
            lastWatch = null
            client.connect(ep)
        }
        if (!watching) {
            scope.launch {
                var last = ""
                var lastAt = 0L
                client.alerts.collect { a ->
                    val now = System.currentTimeMillis()
                    if (a.raw == last && now - lastAt < 30_000) return@collect
                    last = a.raw; lastAt = now
                    postAlert(Alert("Red Alert: printer message", "${a.message} (${a.code})", severe = true))
                }
            }
            watching = true
            scope.launch {
                var prev: PrinterState? = null
                var layerShotTaken = false
                client.state.collect { now ->
                    RedAlerts.transition(prev, now)?.let { alert ->
                        val next = queue.items.value.firstOrNull()
                        if (now.printState == "complete" && next != null) {
                            val label = next.substringAfterLast('/').substringBeforeLast('.')
                            postAlert(alert.copy(text = "${alert.text}. Clear the build plate, then tap Next to start $label."), withNext = true)
                        } else {
                            postAlert(alert)
                        }
                    }
                    // First-layer watch: one camera snapshot per print as layer 2 begins.
                    if (now.ready && !now.isBusy) layerShotTaken = false
                    if (!layerShotTaken && RedAlerts.secondLayerStarted(now) && Prefs(this@RedAlertService).firstLayerWatch) {
                        layerShotTaken = true
                        scope.launch { postFirstLayer(now) }
                    }
                    // Keep the last ready state so a short link drop doesn't hide a transition.
                    if (now.ready || prev == null) prev = now
                    updateWatch(now)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        watching = false
        client.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, WATCH_ID, n, type)
    }

    private fun updateWatch(p: PrinterState) {
        ReplicatorWidget.push(this, WidgetData.of(p))
        val w = RedAlerts.watch(p, host)
        if (w == lastWatch) return
        lastWatch = w
        if (canNotify()) NotificationManagerCompat.from(this).notify(WATCH_ID, watchNotification(w))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun selfAction(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RedAlertService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun watchNotification(w: Watch): Notification =
        NotificationCompat.Builder(this, CHANNEL_WATCH)
            .setSmallIcon(NotificationIcon.of(Prefs(this).notificationIcon).res)
            .setContentTitle(w.title)
            .setContentText(w.text)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                w.percent?.let { setProgress(100, it, false) }
                if (w.printing) addAction(0, "Pause", selfAction(ACTION_PAUSE, 1))
                if (w.paused) addAction(0, "Resume", selfAction(ACTION_RESUME, 2))
            }
            .build()

    /** Replication queue: start the next file (from the notification's Next button). */
    private suspend fun startNext() {
        NotificationManagerCompat.from(this).cancel(ALERT_ID)
        val next = queue.pop() ?: return
        runCatching {
            client.state.value.box?.let { box ->
                val needs = dev.vantara.replicatorops.data.FilamentMapping.needs(runCatching { client.metadata(next) }.getOrNull())
                val map = dev.vantara.replicatorops.data.FilamentMapping.suggest(needs, box.slots, box.toolMap)
                if (map.isNotEmpty()) client.gcode(dev.vantara.replicatorops.data.FilamentMapping.gcode(map))
            }
            client.call("printer.print.start", kotlinx.serialization.json.buildJsonObject {
                put("filename", kotlinx.serialization.json.JsonPrimitive(next))
            })
        }.onFailure {
            queue.pushFront(next)
            postAlert(Alert("Red Alert: couldn't start the next print", it.message ?: "The printer refused the job", severe = true))
        }
    }

    private fun postAlert(a: Alert, withNext: Boolean = false) {
        if (!canNotify()) return
        val n = NotificationCompat.Builder(this, if (a.severe) CHANNEL_RED else CHANNEL_DONE)
            .setSmallIcon(NotificationIcon.of(Prefs(this).notificationIcon).res)
            .setContentTitle(a.title)
            .setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setPriority(if (a.severe) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (a.severe) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setColor(if (a.severe) 0xFFDD4444.toInt() else 0xFFFFCC66.toInt())
            .apply { if (withNext) addAction(0, "Next", selfAction(ACTION_NEXT, 4)) }
            .build()
        NotificationManagerCompat.from(this).notify(ALERT_ID, n)
    }

    private suspend fun postFirstLayer(p: PrinterState) {
        if (!canNotify()) return
        val key = Prefs(this).apiKey.ifBlank { null }
        val url = runCatching { client.webcamSnapshotUrl() }.getOrNull()
        val image = url?.let { cameraSnapshot(http, it, key) }
        val name = p.filename?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Print"
        val text = if (image != null) "Layer 1 is down on $name. Check it looks right." else "Layer 1 is down on $name (no camera image)."
        val n = NotificationCompat.Builder(this, CHANNEL_LAYER)
            .setSmallIcon(NotificationIcon.of(Prefs(this).notificationIcon).res)
            .setContentTitle("First layer complete")
            .setContentText(text)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setColor(0xFFFFCC66.toInt())
            .apply {
                if (image != null) {
                    setLargeIcon(image)
                    setStyle(NotificationCompat.BigPictureStyle().bigPicture(image).bigLargeIcon(null as android.graphics.Bitmap?))
                } else {
                    setStyle(NotificationCompat.BigTextStyle().bigText(text))
                }
                if (p.isPrinting) addAction(0, "Pause", selfAction(ACTION_PAUSE, 3))
            }
            .build()
        NotificationManagerCompat.from(this).notify(LAYER_ID, n)
    }

    private fun canNotify(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_WATCH = "watch"
        const val CHANNEL_RED = "red_alert"
        const val CHANNEL_DONE = "replication"
        const val CHANNEL_LAYER = "first_layer"
        private const val LAYER_ID = 1703
        private const val WATCH_ID = 1701
        private const val ALERT_ID = 1702
        private const val ACTION_PAUSE = "dev.vantara.replicatorops.PAUSE"
        private const val ACTION_RESUME = "dev.vantara.replicatorops.RESUME"
        private const val ACTION_NEXT = "dev.vantara.replicatorops.NEXT"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL_WATCH, "Printer watch", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Ongoing progress while Red Alert keeps the link open"
                        setShowBadge(false)
                    },
                    NotificationChannel(CHANNEL_RED, "Red Alert", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Print failed, or paused (for example filament runout)"
                    },
                    NotificationChannel(CHANNEL_DONE, "Replication complete", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Print finished or cancelled"
                    },
                    NotificationChannel(CHANNEL_LAYER, "First-layer watch", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Camera snapshot when the second layer starts"
                    },
                )
            )
        }

        /** Start (or refresh) the watch if Red Alert is on and a printer is set. */
        fun sync(context: Context) {
            val prefs = Prefs(context)
            val intent = Intent(context, RedAlertService::class.java)
            if (prefs.redAlert && prefs.endpoint() != null) {
                runCatching { ContextCompat.startForegroundService(context, intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
